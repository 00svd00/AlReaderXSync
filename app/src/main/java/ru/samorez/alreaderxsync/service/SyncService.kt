package ru.samorez.alreaderxsync.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.samorez.alreaderxsync.MainActivity
import ru.samorez.alreaderxsync.R
import ru.samorez.alreaderxsync.data.DeviceRole
import ru.samorez.alreaderxsync.data.SyncDirection
import ru.samorez.alreaderxsync.data.SyncMode
import ru.samorez.alreaderxsync.data.SyncState
import ru.samorez.alreaderxsync.data.TransportConfig
import ru.samorez.alreaderxsync.data.TransportMode
import ru.samorez.alreaderxsync.protocol.SyncMessage
import ru.samorez.alreaderxsync.protocol.TaskSpec
import ru.samorez.alreaderxsync.storage.DirectoryAccessManager
import ru.samorez.alreaderxsync.storage.SettingsRepository
import ru.samorez.alreaderxsync.sync.SyncRepository
import ru.samorez.alreaderxsync.transport.BluetoothPairingHelper
import ru.samorez.alreaderxsync.transport.BluetoothTransport
import ru.samorez.alreaderxsync.transport.SyncTransport
import ru.samorez.alreaderxsync.transport.WifiHelper
import ru.samorez.alreaderxsync.transport.WifiLanTransport
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground-сервис «клиентской» стороны синхронизации (Phone).
 *
 * В [SyncServiceState.Running] публикуется имя транспорта
 * ("wifi_lan" / "bluetooth"), чтобы UI мог показать, случился ли
 * fallback на Bluetooth.
 *
 * Режим транспорта берётся из [TransportConfig.transportMode]:
 *  - AUTO: Wi-Fi LAN → Bluetooth fallback.
 *  - BLUETOOTH_ONLY: сразу Bluetooth, без Wi-Fi LAN.
 */
class SyncService : Service() {

    companion object {
        private const val TAG = "SyncService"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "sync_service_channel"
        private const val WAKE_LOCK_TIMEOUT_MS = 2 * 60 * 60 * 1000L

        const val ACTION_START = "ru.samorez.alreaderxsync.SYNC_START"
        const val ACTION_CANCEL = "ru.samorez.alreaderxsync.SYNC_CANCEL"
        const val EXTRA_PEER_MAC = "peer_mac"

        private val _stateFlow =
            MutableStateFlow<SyncServiceState>(SyncServiceState.Idle)
        val stateFlow: StateFlow<SyncServiceState> = _stateFlow.asStateFlow()

        fun start(context: Context, peerMac: String) {
            val intent = Intent(context, SyncService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PEER_MAC, peerMac)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancel(context: Context) {
            val intent = Intent(context, SyncService::class.java).apply {
                action = ACTION_CANCEL
            }
            context.startService(intent)
        }
    }

    /** Состояния сервиса, наблюдаемые из UI. */
    sealed class SyncServiceState {
        object Idle : SyncServiceState()

        data class Running(
            val status: String,
            val progress: Int,
            val currentFile: String?,
            val taskName: String?,
            val taskIndex: Int,
            val totalTasks: Int,
            val transportName: String?     // "wifi_lan" / "bluetooth"
        ) : SyncServiceState()

        data class Complete(
            val transferred: Int,
            val errors: Int
        ) : SyncServiceState()

        data class Error(val message: String) : SyncServiceState()
    }

    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO
    )
    private var syncJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val isRunning = AtomicBoolean(false)

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var directoryAccessManager: DirectoryAccessManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepository(this)
        directoryAccessManager = DirectoryAccessManager(this)
        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        when (intent?.action) {
            ACTION_START -> {
                val peerMac = intent.getStringExtra(EXTRA_PEER_MAC)
                if (peerMac != null && !isRunning.get()) {
                    startSync(peerMac)
                }
            }
            ACTION_CANCEL -> cancelSync()
        }
        return START_NOT_STICKY
    }

    private fun startSync(peerMac: String) {
        isRunning.set(true)
        startForeground(
            NOTIFICATION_ID,
            buildNotification(getString(R.string.notification_preparing), 0)
        )
        acquireLocks()

        syncJob = serviceScope.launch {
            try {
                runSync(peerMac)
            } catch (e: CancellationException) {
                Log.d(TAG, "Sync cancelled")
                _stateFlow.value = SyncServiceState.Error(
                    getString(R.string.sync_error_cancelled)
                )
            } catch (e: Exception) {
                Log.e(TAG, "Sync error: ${e.message}", e)
                _stateFlow.value = SyncServiceState.Error(
                    e.message ?: getString(R.string.sync_error_unknown)
                )
            } finally {
                releaseLocks()
                isRunning.set(false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private suspend fun runSync(peerMac: String) {
        val role = settingsRepository.getRole() ?: DeviceRole.PHONE
        val mode = settingsRepository.getSyncMode()
            ?: SyncMode.MERGE_NEWEST_WINS
        val direction = settingsRepository.getSyncDirection()
            ?: SyncDirection.PHONE_TO_READER

        // --- Режим транспорта ---
        val config: TransportConfig = settingsRepository.getTransportConfig()
        Log.d(TAG, "TransportMode: ${config.transportMode}")

        val booksInside = settingsRepository.isBooksInsideAlreader()
        val tasks = if (booksInside) {
            listOf(TaskSpec("AlReaderX", listOf("Books")))
        } else {
            listOf(TaskSpec("AlReaderX"), TaskSpec("Books"))
        }

        val connectingText = getString(R.string.notification_connecting)
        updateNotification(connectingText, 0)
        _stateFlow.value = SyncServiceState.Running(
            status = connectingText,
            progress = 0,
            currentFile = null,
            taskName = null,
            taskIndex = 0,
            totalTasks = tasks.size,
            transportName = null
        )

        // --- Handshake через Bluetooth ---
        val phoneIp = WifiHelper(this).getLocalIpAddress()

        val request = SyncMessage.HandshakeRequest(
            role = "phone",
            tasks = tasks,
            mode = mode.name,
            direction = direction.name,
            phoneIp = phoneIp
        )

        val bluetoothHelper = BluetoothPairingHelper(this)
        val handshake = bluetoothHelper.performHandshakeAsClient(
            peerMacAddress = peerMac,
            request = request,
            timeoutMs = 120_000L,
            retryDelayMs = 2_000L,
            maxRetries = 30
        ) ?: throw Exception(getString(R.string.sync_error_connect_failed))

        val response = handshake.response
            ?: throw Exception(getString(R.string.sync_error_no_response))
        if (!response.accepted) {
            throw Exception(
                getString(
                    R.string.sync_error_rejected_format,
                    response.errorMessage ?: getString(R.string.sync_error_unknown_reason)
                )
            )
        }

        Log.d(
            TAG,
            "Handshake OK: readerIp=${response.readerIp.ifBlank { "(empty)" }}, " +
                    "readerPort=${response.readerPort}"
        )

        var totalTransferred = 0
        var totalErrors = 0

        for ((index, taskSpec) in tasks.withIndex()) {
            if (!currentCoroutineContext().isActive) break

            val taskName = taskSpec.name
            val root = when (taskName) {
                "AlReaderX" -> directoryAccessManager
                    .getDocumentFile(DirectoryAccessManager.KEY_ALREADER_DIR)
                "Books" -> directoryAccessManager
                    .getDocumentFile(DirectoryAccessManager.KEY_BOOKS_DIR)
                else -> null
            }
            if (root == null) {
                Log.w(TAG, "Task $taskName: root not configured")
                continue
            }

            var transportName: String? = null
            var transport: SyncTransport? = null

            // ================== Выбор транспорта ==================
            when (config.transportMode) {
                TransportMode.BLUETOOTH_ONLY -> {
                    // ===== Только Bluetooth =====
                    Log.d(TAG, "BLUETOOTH_ONLY mode, using Bluetooth")
                    updateNotification(
                        getString(
                            R.string.notification_task_bt_format,
                            index + 1,
                            tasks.size,
                            taskName
                        ),
                        0
                    )
                    // ← задержка, чтобы Server успел открыть BluetoothServerSocket
                    delay(500L)
                    val bt = BluetoothTransport(
                        context = applicationContext,
                        clientMaxRetries = config.bluetoothMaxRetries,
                        clientRetryDelayMs = config.bluetoothRetryDelayMs
                    )
                    if (bt.connect(peerMac, isServer = false)) {
                        transport = bt
                        transportName = bt.name
                        Log.d(TAG, "Connected via Bluetooth (forced)")
                    } else {
                        Log.e(TAG, "Bluetooth failed for $taskName")
                        runCatching { bt.close() }
                        totalErrors++
                        continue
                    }
                }

                TransportMode.AUTO -> {
                    // Если readerIp пустой (Wi-Fi на Reader выключен) —
                    // сразу Bluetooth, без попытки Wi-Fi LAN.
                    val wifiIp = response.readerIp
                    if (wifiIp.isNullOrBlank() || wifiIp == "127.0.0.1" || response.readerPort <= 0) {
                        Log.w(
                            TAG,
                            "readerIp is empty/loopback/port=0 " +
                                    "(Wi-Fi off on reader?) — " +
                                    "skipping Wi-Fi LAN, using Bluetooth"
                        )
                        // ← КЛЮЧЕВОЕ: дать Server'у время открыть BluetoothServerSocket
                        //   после отправки HandshakeResponse. Без этой задержки Phone
                        //   подключается раньше, чем Server начинает слушать RFCOMM,
                        //   и Bluetooth-стек закрывает соединение.
                        Log.d(TAG, "Waiting 500ms for reader to open BluetoothServerSocket...")
                        delay(500L)
                        updateNotification(
                            getString(
                                R.string.notification_task_bt_format,
                                index + 1,
                                tasks.size,
                                taskName
                            ),
                            0
                        )

                        val bt = BluetoothTransport(
                            context = applicationContext,
                            clientMaxRetries = config.bluetoothMaxRetries,
                            clientRetryDelayMs = config.bluetoothRetryDelayMs
                        )
                        if (bt.connect(peerMac, isServer = false)) {
                            transport = bt
                            transportName = bt.name
                            Log.d(TAG, "Connected via Bluetooth (no Wi-Fi IP)")
                        } else {
                            Log.e(TAG, "Bluetooth failed for $taskName")
                            runCatching { bt.close() }
                            totalErrors++
                            continue
                        }
                    } else {
                        // ===== Wi-Fi LAN → Bluetooth fallback =====
                        updateNotification(
                            getString(
                                R.string.notification_task_wifi_format,
                                index + 1,
                                tasks.size,
                                taskName
                            ),
                            0
                        )

                        val wifiLan = WifiLanTransport(
                            context = applicationContext,
                            defaultPort = response.readerPort,
                            clientMaxRetries = config.wifiLanMaxRetries,
                            clientRetryDelayMs = config.wifiLanRetryDelayMs
                        )
                        val wifiAddress = "$wifiIp:${response.readerPort}"

                        if (wifiLan.connect(wifiAddress, isServer = false)) {
                            transport = wifiLan
                            transportName = wifiLan.name
                            Log.d(TAG, "Connected via Wi-Fi LAN")
                        } else {
                            Log.w(TAG, "Wi-Fi LAN failed, trying Bluetooth")
                            updateNotification(
                                getString(
                                    R.string.notification_task_bt_format,
                                    index + 1,
                                    tasks.size,
                                    taskName
                                ),
                                0
                            )
                            runCatching { wifiLan.close() }

                            if (!currentCoroutineContext().isActive) break
                            // ← задержка, чтобы Server успел открыть BluetoothServerSocket
                            delay(500L)
                            val bt = BluetoothTransport(
                                context = applicationContext,
                                clientMaxRetries = config.bluetoothMaxRetries,
                                clientRetryDelayMs = config.bluetoothRetryDelayMs
                            )
                            if (bt.connect(peerMac, isServer = false)) {
                                transport = bt
                                transportName = bt.name
                                Log.d(TAG, "Connected via Bluetooth (fallback)")
                            } else {
                                Log.e(TAG, "All transports failed for $taskName")
                                runCatching { bt.close() }
                                totalErrors++
                                continue
                            }
                        }
                    }
                }
            }

            if (transport == null) {
                Log.w(TAG, "No transport for $taskName")
                totalErrors++
                continue
            }

            Log.d(TAG, "Transport for $taskName: $transportName")

            // Начальный статус задачи — уже с именем транспорта.
            _stateFlow.value = SyncServiceState.Running(
                status = getString(
                    R.string.notification_task_format,
                    index + 1,
                    tasks.size,
                    taskName
                ),
                progress = 0,
                currentFile = null,
                taskName = taskName,
                taskIndex = index + 1,
                totalTasks = tasks.size,
                transportName = transportName
            )

            // --- Синхронизация задачи ---
            val currentTaskIndex = index
            val currentTaskName = taskName
            val currentTransportName = transportName
            val repository = SyncRepository(applicationContext)

            val result = repository.sync(
                transport = transport,
                sourceRoot = root,
                targetRoot = root,
                mode = mode,
                excludedPaths = taskSpec.excludePaths,
                localRole = role,
                direction = direction,
                onProgress = { state ->
                    if (state is SyncState.Transferring) {
                        val totalProgress =
                            ((currentTaskIndex * 100 + state.progress) /
                                    tasks.size)

                        val taskText = getString(
                            R.string.notification_task_format,
                            currentTaskIndex + 1,
                            tasks.size,
                            currentTaskName
                        )
                        updateNotification(taskText, totalProgress)
                        _stateFlow.value = SyncServiceState.Running(
                            status = taskText,
                            progress = totalProgress,
                            currentFile = state.currentFile,
                            taskName = currentTaskName,
                            taskIndex = currentTaskIndex + 1,
                            totalTasks = tasks.size,
                            transportName = currentTransportName
                        )
                    }
                }
            )

            totalTransferred += result.transferred
            totalErrors += result.errors.size

            runCatching { transport.close() }
        }

        _stateFlow.value = SyncServiceState.Complete(
            transferred = totalTransferred,
            errors = totalErrors
        )
        updateNotification(
            getString(
                R.string.notification_complete_format,
                totalTransferred,
                totalErrors
            ),
            100
        )

        delay(2000L)
    }

    private fun cancelSync() {
        Log.d(TAG, "cancelSync called")
        syncJob?.cancel()
        syncJob = null
        _stateFlow.value = SyncServiceState.Error(
            getString(R.string.sync_error_cancelled)
        )
        releaseLocks()
        isRunning.set(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireLocks() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "AlReaderXSync::PhoneSyncWakeLock"
        ).apply {
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
        Log.d(TAG, "WakeLock acquired")

        try {
            val wm = applicationContext.getSystemService(WIFI_SERVICE)
                    as WifiManager
            val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wm.createWifiLock(
                lockMode,
                "AlReaderXSync::PhoneSyncWifiLock"
            ).apply {
                acquire()
            }
            Log.d(TAG, "WifiLock acquired (mode=$lockMode)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire WifiLock: ${e.message}")
        }
    }

    private fun releaseLocks() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "WakeLock released")
            }
        }
        wakeLock = null

        wifiLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "WifiLock released")
            }
        }
        wifiLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_sync),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_sync_desc)
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String, progress: Int): Notification {
        val intent = Intent(
            this,
            MainActivity::class.java
        ).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val cancelIntent = Intent(this, SyncService::class.java).apply {
            action = ACTION_CANCEL
        }
        val cancelPi = PendingIntent.getService(
            this, 1, cancelIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val contentView = RemoteViews(
            packageName,
            R.layout.notification_sync
        ).apply {
            setTextViewText(
                R.id.notificationTitle,
                getString(R.string.notification_title)
            )
            setTextViewText(R.id.notificationText, text)

            if (progress > 0) {
                setViewVisibility(
                    R.id.notificationProgress,
                    View.VISIBLE
                )
                setProgressBar(R.id.notificationProgress, 100, progress, false)
            } else {
                setViewVisibility(
                    R.id.notificationProgress,
                    View.GONE
                )
            }
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setCustomContentView(contentView)
            .setCustomBigContentView(contentView)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.notification_cancel),
                cancelPi
            )
            .build()
    }

    private fun updateNotification(text: String, progress: Int) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text, progress))
    }

    override fun onDestroy() {
        releaseLocks()
        syncJob?.cancel()
        serviceScope.cancel()
        isRunning.set(false)
        _stateFlow.value = SyncServiceState.Idle
        super.onDestroy()
    }
}