package ru.samorez.alreaderxsync.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ru.samorez.alreaderxsync.MainActivity
import ru.samorez.alreaderxsync.R
import ru.samorez.alreaderxsync.data.DeviceRole
import ru.samorez.alreaderxsync.data.SyncDirection
import ru.samorez.alreaderxsync.data.SyncMode
import ru.samorez.alreaderxsync.data.SyncState
import ru.samorez.alreaderxsync.data.TransportConfig
import ru.samorez.alreaderxsync.data.TransportMode
import ru.samorez.alreaderxsync.storage.DirectoryAccessManager
import ru.samorez.alreaderxsync.storage.SettingsRepository
import ru.samorez.alreaderxsync.sync.SyncRepository
import ru.samorez.alreaderxsync.transport.BluetoothPairingHelper
import ru.samorez.alreaderxsync.transport.BluetoothTransport
import ru.samorez.alreaderxsync.transport.SyncTransport
import ru.samorez.alreaderxsync.transport.WifiHelper
import ru.samorez.alreaderxsync.transport.WifiLanTransport
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground-сервис «серверной» стороны синхронизации (Reader).
 *
 * Режим транспорта берётся из [TransportConfig.transportMode]:
 *  - AUTO: TCP ServerSocket + BluetoothServerSocket параллельно.
 *  - BLUETOOTH_ONLY: только BluetoothServerSocket. TCP не
 *    принимается, ServerSocket открывается только для передачи
 *    порта в HandshakeResponse (Phone его игнорирует).
 *
 * Handshake всегда идёт через Bluetooth RFCOMM — это отдельный
 * канал, независимый от передачи данных.
 */
class SyncServerService : Service() {

    companion object {
        const val TAG = "SyncServerService"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "sync_server_channel"

        const val WAKE_LOCK_TIMEOUT_MS = 2 * 60 * 60 * 1000L

        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"

        private const val TRANSPORT_WAIT_TIMEOUT_MS = 30_000L

        private val _statusFlow =
            MutableStateFlow<ServerStatus>(ServerStatus.Stopped)
        val statusFlow: StateFlow<ServerStatus> = _statusFlow.asStateFlow()

        /**
         * DIAGNOSTIC: Log.e — чтобы не терялось в logcat,
         * и было видно, кто и когда меняет статус.
         */
        internal fun updateStatus(status: ServerStatus) {
            Log.e(TAG, "updateStatus: $status")
            _statusFlow.value = status
        }

        fun start(context: Context) {
            val intent = Intent(context, SyncServerService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, SyncServerService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    sealed class ServerStatus {
        object Stopped : ServerStatus()

        data class WaitingForConnection(
            val ip: String,
            val port: Int
        ) : ServerStatus()

        data class Connected(val peerMac: String) : ServerStatus()

        data class Syncing(
            val taskName: String,
            val taskIndex: Int,
            val totalTasks: Int,
            val progress: Int,
            val currentFile: String?,
            val transportName: String?
        ) : ServerStatus()

        data class Complete(
            val transferred: Int,
            val errors: Int
        ) : ServerStatus()

        data class Error(val message: String) : ServerStatus()
    }

    private var wakeLock: PowerManager.WakeLock? = null

    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO
    )
    private val transportScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO
    )

    private var serverSocket: ServerSocket? = null
    private lateinit var directoryAccessManager: DirectoryAccessManager
    private lateinit var settingsRepository: SettingsRepository

    private val isStopping = AtomicBoolean(false)

    /**
     * DIAGNOSTIC: флаг «сервис активен». Помогает отличить
     * нормальный старт/стоп от «сервис убит системой».
     */
    private val isRunning = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        Log.e(TAG, "!!! onCreate CALLED !!!")
        super.onCreate()
        directoryAccessManager = DirectoryAccessManager(this)
        settingsRepository = SettingsRepository(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.e(
            TAG,
            "onStartCommand: action=${intent?.action}, " +
                    "startId=$startId, flags=$flags, " +
                    "isRunning=${isRunning.get()}, " +
                    "isStopping=${isStopping.get()}"
        )

        when (intent?.action) {
            ACTION_START -> {
                isStopping.set(false)
                isRunning.set(true)

                createNotificationChannel()
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(getString(R.string.server_notification_waiting))
                )
                acquireWakeLock()
                serviceScope.launch { runServerLoop() }
            }
            ACTION_STOP -> stopServer()
        }
        return START_STICKY
    }

    private suspend fun runServerLoop() {
        Log.e(TAG, "!!! runServerLoop STARTED !!!")
        try {
            // --- Читаем режим транспорта ---
            val config: TransportConfig =
                settingsRepository.getTransportConfig()
            Log.d(TAG, "TransportMode: ${config.transportMode}")

            // useTcp = true только в AUTO.
            // В BLUETOOTH_ONLY TCP не принимается.
            val useTcp = config.transportMode == TransportMode.AUTO
            val useBluetooth = true

            // ServerSocket открываем ВСЕГДА — он дешёвый,
            // и порт нужен для HandshakeResponse. При BLUETOOTH_ONLY
            // accept() не вызывается (см. awaitTransport(useTcp=false)).
            val socket = ServerSocket(0).apply { reuseAddress = true }
            serverSocket = socket
            val port = socket.localPort
            val ip = WifiHelper(this@SyncServerService).getLocalIpAddress() ?: "—"
            Log.e(TAG, "Server bound to $ip:$port (useTcp=$useTcp)")

            updateStatus(ServerStatus.WaitingForConnection(ip, port))
            updateNotification(
                getString(R.string.server_notification_waiting_address_format, ip, port)
            )

            // --- Handshake (всегда через Bluetooth RFCOMM) ---
            val bluetoothHelper = BluetoothPairingHelper(this@SyncServerService)
            val handshake = bluetoothHelper.performHandshakeAsServer(
                serverPort = port,
                timeoutMs = WAKE_LOCK_TIMEOUT_MS
            )

            if (handshake == null) {
                Log.e(TAG, "Handshake failed or timeout")
                updateStatus(
                    ServerStatus.Error(getString(R.string.server_error_handshake_failed))
                )
                stopServer()
                return
            }

            val peerMac = handshake.peerMac
            val request = handshake.request ?: run {
                Log.e(TAG, "Handshake: no request from peer")
                updateStatus(
                    ServerStatus.Error(getString(R.string.server_error_no_request))
                )
                stopServer()
                return
            }

            Log.e(TAG, "Handshake OK with $peerMac, tasks=${request.tasks.size}")
            updateStatus(ServerStatus.Connected(peerMac))
            updateNotification(
                getString(R.string.server_notification_connected_format, peerMac)
            )

            val mode = SyncMode.valueOf(request.mode)
            val direction = SyncDirection.valueOf(request.direction)
            val repository = SyncRepository(this@SyncServerService)
            var totalTransferred = 0
            var totalErrors = 0

            for ((index, taskSpec) in request.tasks.withIndex()) {
                if (!currentCoroutineContext().isActive) break

                val root = when (taskSpec.name) {
                    "AlReaderX" -> directoryAccessManager
                        .getDocumentFile(DirectoryAccessManager.KEY_ALREADER_DIR)
                    "Books" -> directoryAccessManager
                        .getDocumentFile(DirectoryAccessManager.KEY_BOOKS_DIR)
                    else -> null
                }

                if (root == null) {
                    Log.e(TAG, "Task ${taskSpec.name}: root not configured")
                    continue
                }

                Log.e(
                    TAG,
                    "Waiting for transport for ${taskSpec.name} " +
                            "(useTcp=$useTcp, useBluetooth=$useBluetooth)"
                )

                val transport = awaitTransport(
                    tcpServerSocket = socket,
                    timeoutMs = TRANSPORT_WAIT_TIMEOUT_MS,
                    useTcp = useTcp,
                    useBluetooth = useBluetooth
                )

                if (transport == null) {
                    Log.e(TAG, "No transport accepted for ${taskSpec.name}")
                    totalErrors++
                    continue
                }

                val taskTransportName = transport.name
                Log.e(TAG, "Transport for ${taskSpec.name}: $taskTransportName")

                updateStatus(
                    ServerStatus.Syncing(
                        taskName = taskSpec.name,
                        taskIndex = index + 1,
                        totalTasks = request.tasks.size,
                        progress = 0,
                        currentFile = null,
                        transportName = taskTransportName
                    )
                )
                updateNotification(
                    getString(
                        R.string.server_notification_syncing_format,
                        taskSpec.name,
                        index + 1,
                        request.tasks.size,
                        taskTransportName
                    )
                )
                val result = repository.sync(
                    transport = transport,
                    sourceRoot = root,
                    targetRoot = root,
                    mode = mode,
                    excludedPaths = taskSpec.excludePaths,
                    localRole = DeviceRole.READER,
                    direction = direction,
                    onProgress = { state ->
                        if (state is SyncState.Transferring) {
                            updateStatus(
                                ServerStatus.Syncing(
                                    taskName = taskSpec.name,
                                    taskIndex = index + 1,
                                    totalTasks = request.tasks.size,
                                    progress = state.progress,
                                    currentFile = state.currentFile,
                                    transportName = taskTransportName
                                )
                            )
                        }
                    }
                )

                totalTransferred += result.transferred
                totalErrors += result.errors.size

                Log.e(
                    TAG,
                    "Task ${taskSpec.name} done: " +
                            "transferred=${result.transferred}, " +
                            "errors=${result.errors.size}"
                )

                runCatching { transport.close() }
            }

            updateStatus(ServerStatus.Complete(totalTransferred, totalErrors))
            updateNotification(
                getString(
                    R.string.server_notification_complete_format,
                    totalTransferred
                )
            )

            delay(3000L)
            stopServer()

        } catch (e: CancellationException) {
            Log.e(TAG, "runServerLoop: CancellationException: ${e.message}")
            Log.e(
                TAG,
                "runServerLoop: stackTrace=\n" +
                        e.stackTrace.take(15).joinToString("\n")
            )
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "runServerLoop: Exception: ${e.message}", e)
            updateStatus(
                ServerStatus.Error(
                    e.message ?: getString(R.string.server_error_unknown)
                )
            )
            stopServer()
        } finally {
            Log.e(TAG, "!!! runServerLoop FINISHED !!!")
        }
    }

    /**
     * Параллельно ждёт TCP и/или Bluetooth.
     *
     * @param tcpServerSocket открытый ServerSocket (может быть null,
     *        если useTcp = false, но тогда useBluetooth = true).
     * @param timeoutMs таймаут ожидания.
     * @param useTcp true — ждать TCP-accept.
     * @param useBluetooth true — ждать Bluetooth-accept.
     * @return первый принятый транспорт или null.
     */
    @SuppressLint("MissingPermission")
    private suspend fun awaitTransport(
        tcpServerSocket: ServerSocket?,
        timeoutMs: Long,
        useTcp: Boolean,
        useBluetooth: Boolean
    ): SyncTransport? = withContext(Dispatchers.IO) {

        // Если ни один транспорт не разрешён — выходим сразу.
        if (!useTcp && !useBluetooth) {
            Log.e(TAG, "awaitTransport: neither TCP nor Bluetooth enabled")
            return@withContext null
        }

        // --- Bluetooth-сервер (только если разрешён) ---
        var btServer: BluetoothServerSocket? = null
        if (useBluetooth) {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter != null) {
                try {
                    btServer = adapter.listenUsingRfcommWithServiceRecord(
                        "AlReaderXSync",
                        BluetoothPairingHelper.SERVICE_UUID
                    )
                    Log.e(TAG, "BluetoothServerSocket opened")
                } catch (e: SecurityException) {
                    Log.e(TAG, "BT listen SecurityException: ${e.message}", e)
                } catch (e: Exception) {
                    Log.e(
                        TAG,
                        "Failed to open BluetoothServerSocket: ${e.message}",
                        e
                    )
                }
            } else {
                Log.e(TAG, "BluetoothAdapter is null, skipping BT")
            }
        }

        // --- TCP-accept (только если разрешён и есть сокет) ---
        val tcpDeferred = if (useTcp && tcpServerSocket != null) {
            transportScope.async {
                try {
                    withTimeoutOrNull(timeoutMs) {
                        tcpServerSocket.accept()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "TCP accept stopped: ${e.message}")
                    null
                }
            }
        } else {
            Log.e(TAG, "TCP accept disabled (useTcp=$useTcp)")
            null
        }

        // --- Bluetooth-accept ---
        val btAcceptDeferred = if (btServer != null) {
            transportScope.async {
                try {
                    btServer.accept()
                } catch (e: Exception) {
                    Log.e(TAG, "Bluetooth accept stopped: ${e.message}")
                    null
                }
            }
        } else null

        var selectedTransportName: String? = null

        try {
            val result: Pair<SyncTransport, String>? = when {
                // --- Оба активны: select ---
                tcpDeferred != null && btAcceptDeferred != null -> {
                    select {
                        tcpDeferred.onAwait { socket ->
                            if (socket != null) {
                                Log.e(TAG, "Accepted TCP connection")
                                Pair(
                                    WifiLanTransport(
                                        context = this@SyncServerService,
                                        preConnectedSocket = socket
                                    ),
                                    "wifi_lan"
                                )
                            } else null
                        }
                        btAcceptDeferred.onAwait { socket ->
                            if (socket != null) {
                                Log.e(TAG, "Accepted Bluetooth connection")
                                Pair(
                                    BluetoothTransport(
                                        context = this@SyncServerService,
                                        preConnectedSocket = socket
                                    ),
                                    "bluetooth"
                                )
                            } else null
                        }
                    }
                }

                // --- Только TCP (useBluetooth = false) ---
                tcpDeferred != null -> {
                    val socket = tcpDeferred.await()
                    if (socket != null) {
                        Log.e(TAG, "Accepted TCP connection (BT disabled)")
                        Pair(
                            WifiLanTransport(
                                context = this@SyncServerService,
                                preConnectedSocket = socket
                            ),
                            "wifi_lan"
                        )
                    } else null
                }

                // --- Только Bluetooth (BLUETOOTH_ONLY) ---
                btAcceptDeferred != null -> {
                    val socket = btAcceptDeferred.await()
                    if (socket != null) {
                        Log.e(TAG, "Accepted Bluetooth connection (TCP disabled)")
                        Pair(
                            BluetoothTransport(
                                context = this@SyncServerService,
                                preConnectedSocket = socket
                            ),
                            "bluetooth"
                        )
                    } else null
                }

                // --- Ничего ---
                else -> null
            }

            selectedTransportName = result?.second
            Log.e(TAG, "awaitTransport selected: $selectedTransportName")
            result?.first
        } finally {
            try {
                btServer?.close()
                Log.e(TAG, "BluetoothServerSocket closed")
            } catch (e: Exception) {
                Log.e(TAG, "Error closing btServer: ${e.message}")
            }

            // Отменяем проигравшего (или обоих, если ничего не выбрано).
            when (selectedTransportName) {
                "wifi_lan" -> btAcceptDeferred?.cancel()
                "bluetooth" -> tcpDeferred?.cancel()
                else -> {
                    tcpDeferred?.cancel()
                    btAcceptDeferred?.cancel()
                }
            }
        }
    }

    private fun stopServer() {
        Log.e(
            TAG,
            "!!! stopServer CALLED !!! " +
                    "isStopping=${isStopping.get()}, " +
                    "isRunning=${isRunning.get()}"
        )
        Log.e(
            TAG,
            "stopServer: stackTrace=\n" +
                    Thread.currentThread().stackTrace
                        .take(15)
                        .joinToString("\n") { it.toString() }
        )

        if (isStopping.getAndSet(true)) {
            Log.e(TAG, "stopServer: already stopping, return")
            return
        }

        updateStatus(ServerStatus.Stopped)
        try {
            serverSocket?.close()
        } catch (_: Exception) { /* ignore */ }
        serverSocket = null
        releaseWakeLock()
        isRunning.set(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "AlReaderXSync::SyncWakeLock"
        ).apply {
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
        Log.e(TAG, "WakeLock acquired")
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.e(TAG, "WakeLock released")
            }
        }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.server_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.server_notification_channel_desc)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        Log.e(
            TAG,
            "!!! onDestroy CALLED !!! " +
                    "status=${_statusFlow.value}, " +
                    "isRunning=${isRunning.get()}, " +
                    "isStopping=${isStopping.get()}"
        )
        Log.e(
            TAG,
            "onDestroy: stackTrace=\n" +
                    Thread.currentThread().stackTrace
                        .take(15)
                        .joinToString("\n") { it.toString() }
        )

        updateStatus(ServerStatus.Stopped)
        releaseWakeLock()
        try {
            serverSocket?.close()
        } catch (_: Exception) { /* ignore */ }
        serverSocket = null
        serviceScope.cancel()
        transportScope.cancel()
        isRunning.set(false)
        isStopping.set(false)
        super.onDestroy()
    }
}