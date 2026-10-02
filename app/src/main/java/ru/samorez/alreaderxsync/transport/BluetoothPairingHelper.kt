package ru.samorez.alreaderxsync.transport

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ru.samorez.alreaderxsync.protocol.SyncMessage
import ru.samorez.alreaderxsync.protocol.SyncProtocol
import java.io.IOException
import java.util.UUID

/**
 * Помощник для Bluetooth-пейринга и обмена handshake-сообщениями
 * между устройствами.
 *
 * Класс НЕ создаёт ActivityResultLauncher — это делает Activity
 * (MainActivity или SettingsActivity) и вызывает [pairDevice]
 * после получения разрешений.
 *
 * На Android 12+ (API 31+) разрешения BLUETOOTH_CONNECT и BLUETOOTH_SCAN
 * являются runtime-разрешениями: их нужно не только объявить в манифесте,
 * но и запросить у пользователя через ActivityResultLauncher.
 * Используйте [hasRequiredPermissions] и [getRequiredPermissions] для проверки.
 *
 * Handshake через Bluetooth:
 *  - Reader (сервер) открывает BluetoothServerSocket, ждёт Phone,
 *    читает HandshakeRequest, отвечает HandshakeResponse с IP и портом.
 *  - Phone (клиент) подключается к Reader по MAC, отправляет
 *    HandshakeRequest, читает HandshakeResponse.
 *
 * Обмен сообщениями выполняется через [SyncProtocol] в формате JSON.
 */
class BluetoothPairingHelper(private val context: Context) {

    companion object {
        private const val TAG = "BTHelper"

        /** UUID RFCOMM-сервиса для handshake. */
        val SERVICE_UUID: UUID =
            UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66")

        /** Имя сервиса при регистрации RFCOMM. */
        const val DEVICE_NAME = "AlReaderXSync"

        // Коды запросов для ActivityResultLauncher
        const val REQUEST_ENABLE_BT = 1001
        const val REQUEST_DISCOVERABLE = 1002

        /** Таймаут ожидания входящего соединения (сервер), 2 часа. */
        const val SERVER_TIMEOUT_MS = 2 * 60 * 60 * 1000L

        /** Таймаут подключения к партнёру (клиент). */
        const val CLIENT_TIMEOUT_MS = 120_000L

        // Разрешения для проверки (API 31+)
        val REQUIRED_PERMISSIONS_API_31 = arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN
        )

        // Разрешения для проверки (API < 31)
        val REQUIRED_PERMISSIONS_PRE_31 = arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }

    /**
     * Результат Bluetooth-handshake.
     *
     * Универсальная структура: для сервера заполнен [request],
     * для клиента — [response]. Второе поле всегда null.
     *
     * @param peerMac  MAC-адрес партнёра.
     * @param request  Запрос от Phone (для сервера) или null.
     * @param response Ответ от Reader (для клиента) или null.
     */
    data class HandshakeResult(
        val peerMac: String,
        val request: SyncMessage.HandshakeRequest?,
        val response: SyncMessage.HandshakeResponse?
    )

    private val adapter: BluetoothAdapter? =
        BluetoothAdapter.getDefaultAdapter()

    /** Доступен ли Bluetooth на устройстве. */
    fun isBluetoothAvailable(): Boolean = adapter != null

    /** Включён ли Bluetooth. */
    @SuppressLint("MissingPermission")
    fun isBluetoothEnabled(): Boolean =
        adapter?.isEnabled == true

    /** Вернуть массив разрешений, необходимых для текущей версии API. */
    fun getRequiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            REQUIRED_PERMISSIONS_API_31
        } else {
            REQUIRED_PERMISSIONS_PRE_31
        }
    }

    /** Проверить, все ли необходимые разрешения выданы. */
    fun hasRequiredPermissions(): Boolean {
        return getRequiredPermissions().all {
            ContextCompat.checkSelfPermission(context, it) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Получить список спаренных устройств.
     * Требует BLUETOOTH_CONNECT (API 31+).
     *
     * Возвращает пустой список, если разрешения не выданы или
     * произошла SecurityException.
     */
    @SuppressLint("MissingPermission")
    fun getBondedDevices(): List<BluetoothDevice> {
        if (!hasRequiredPermissions()) return emptyList()
        return try {
            adapter?.bondedDevices?.toList() ?: emptyList()
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    /**
     * Запустить discovery и вернуть Flow найденных устройств.
     * Discovery требует BLUETOOTH_SCAN (API 31+) и
     * ACCESS_FINE_LOCATION (API < 31).
     */
    @SuppressLint("MissingPermission")
    fun discoverDevices(): Flow<BluetoothDevice> = callbackFlow {
        if (adapter == null || !hasRequiredPermissions()) {
            close()
            return@callbackFlow
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == BluetoothDevice.ACTION_FOUND) {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(
                            BluetoothDevice.EXTRA_DEVICE,
                            BluetoothDevice::class.java
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    device?.let { trySend(it) }
                }
            }
        }

        context.registerReceiver(
            receiver,
            IntentFilter(BluetoothDevice.ACTION_FOUND)
        )

        try {
            adapter.startDiscovery()
        } catch (e: SecurityException) {
            // нет разрешения — игнорировать
        }

        awaitClose {
            try {
                adapter.cancelDiscovery()
            } catch (e: SecurityException) { /* ignore */ }
            try {
                context.unregisterReceiver(receiver)
            } catch (e: IllegalArgumentException) { /* ignore */ }
        }
    }

    /**
     * Инициировать пейринг и дождаться результата.
     * Возвращает true при успешном пейринге.
     *
     * Требует BLUETOOTH_CONNECT (API 31+).
     */
    @SuppressLint("MissingPermission")
    suspend fun pairDevice(device: BluetoothDevice): Boolean {
        if (device.bondState == BluetoothDevice.BOND_BONDED) return true

        return withContext(Dispatchers.IO) {
            try {
                device.createBond()
            } catch (e: SecurityException) {
                return@withContext false
            }

            // Ждать изменения bondState
            suspendCancellableCoroutine<Boolean> { cont ->
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context, intent: Intent) {
                        if (intent.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                            val dev = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                intent.getParcelableExtra(
                                    BluetoothDevice.EXTRA_DEVICE,
                                    BluetoothDevice::class.java
                                )
                            } else {
                                @Suppress("DEPRECATION")
                                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                            }
                            val state = intent.getIntExtra(
                                BluetoothDevice.EXTRA_BOND_STATE,
                                BluetoothDevice.BOND_NONE
                            )
                            if (dev?.address == device.address) {
                                when (state) {
                                    BluetoothDevice.BOND_BONDED -> {
                                        try {
                                            context.unregisterReceiver(this)
                                        } catch (e: Exception) { /* ignore */ }
                                        if (cont.isActive) {
                                            cont.resumeWith(Result.success(true))
                                        }
                                    }
                                    BluetoothDevice.BOND_NONE -> {
                                        try {
                                            context.unregisterReceiver(this)
                                        } catch (e: Exception) { /* ignore */ }
                                        if (cont.isActive) {
                                            cont.resumeWith(Result.success(false))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                context.registerReceiver(
                    receiver,
                    IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                )

                cont.invokeOnCancellation {
                    try {
                        context.unregisterReceiver(receiver)
                    } catch (e: Exception) { /* ignore */ }
                }
            }
        }
    }

    /**
     * Создать Intent для запроса обнаружимости устройства.
     * Длительность ограничена 300 секундами (ограничение системы).
     */
    @SuppressLint("MissingPermission")
    fun createDiscoverableIntent(durationSeconds: Int = 300): Intent {
        return Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
            putExtra(
                BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION,
                durationSeconds.coerceIn(1, 300)
            )
        }
    }

    /**
     * Reader-сторона: открыть BluetoothServerSocket, дождаться
     * подключения Phone, прочитать HandshakeRequest, отправить
     * HandshakeResponse с IP и портом.
     *
     * @param serverPort Порт, который Reader уже открыл (ServerSocket(0))
     * @param timeoutMs  Таймаут ожидания подключения (по умолчанию 2 часа)
     * @return [HandshakeResult] с peerMac и request, или null
     */
    @SuppressLint("MissingPermission")
    suspend fun performHandshakeAsServer(
        serverPort: Int,
        timeoutMs: Long = SERVER_TIMEOUT_MS
    ): HandshakeResult? = withContext(Dispatchers.IO) {
        var serverSocket: BluetoothServerSocket? = null
        var clientSocket: BluetoothSocket? = null
        try {
            serverSocket = adapter?.listenUsingRfcommWithServiceRecord(
                DEVICE_NAME, SERVICE_UUID
            )
            Log.d(TAG, "Handshake server: waiting for client...")

            clientSocket = withTimeoutOrNull(timeoutMs) {
                serverSocket?.accept()
            }
            if (clientSocket == null) {
                Log.d(TAG, "Handshake server: accept timeout")
                return@withContext null
            }

            val peerMac = clientSocket.remoteDevice.address
            Log.d(TAG, "Handshake server: accepted from $peerMac")

            val input = clientSocket.inputStream
            val output = clientSocket.outputStream

            // Читаем HandshakeRequest
            val request = SyncProtocol.readMessage(input)
            if (request !is SyncMessage.HandshakeRequest) {
                Log.e(
                    TAG,
                    "Handshake server: expected HandshakeRequest, got $request"
                )
                return@withContext null
            }
            Log.d(
                TAG,
                "Handshake server: received request, tasks=${request.tasks.size}"
            )

            // Отправляем HandshakeResponse.
            //
            // ВАЖНО: отсутствие Wi-Fi IP НЕ является ошибкой.
            // Handshake успешен всегда; readerIp — лишь подсказка
            // для Phone, куда подключаться по Wi-Fi LAN. Если IP
            // нет (Wi-Fi выключен), Phone сразу перейдёт на Bluetooth.
            val myIp = WifiHelper(context).getLocalIpAddress()
            if (myIp == null) {
                Log.w(
                    TAG,
                    "Handshake server: no local IP (Wi-Fi off?). " +
                            "Will proceed with Bluetooth only."
                )
            }

            SyncProtocol.sendMessage(
                output,
                SyncMessage.HandshakeResponse(
                    readerIp = myIp ?: "",
                    readerPort = serverPort,
                    accepted = true,
                    errorMessage = null
                )
            )
            Log.d(
                TAG,
                "Handshake server: sent response " +
                        "${myIp ?: "(no IP)"}:$serverPort"
            )

            HandshakeResult(peerMac, request, null)
        } catch (e: Exception) {
            Log.e(TAG, "Handshake server error: ${e.message}", e)
            null
        } finally {
            try { clientSocket?.close() } catch (e: Exception) { /* ignore */ }
            try { serverSocket?.close() } catch (e: Exception) { /* ignore */ }
        }
    }

    /**
     * Phone-сторона: подключиться к Reader по MAC-адресу, отправить
     * HandshakeRequest, прочитать HandshakeResponse.
     *
     * @param peerMacAddress MAC-адрес Reader
     * @param request        HandshakeRequest с параметрами синхронизации
     * @param timeoutMs      Общий таймаут
     * @param retryDelayMs   Задержка между попытками connect
     * @param maxRetries     Максимум попыток
     * @return [HandshakeResult] с peerMac и response, или null
     */
    @SuppressLint("MissingPermission")
    suspend fun performHandshakeAsClient(
        peerMacAddress: String,
        request: SyncMessage.HandshakeRequest,
        timeoutMs: Long = CLIENT_TIMEOUT_MS,
        retryDelayMs: Long = 2_000L,
        maxRetries: Int = 30
    ): HandshakeResult? = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var attempt = 0

        while (attempt < maxRetries && System.currentTimeMillis() < deadline) {
            attempt++
            Log.d(TAG, "Handshake client: attempt $attempt to $peerMacAddress")
            var socket: BluetoothSocket? = null
            try {
                val device = adapter?.getRemoteDevice(peerMacAddress)
                    ?: return@withContext null
                adapter?.cancelDiscovery()
                socket = device.createRfcommSocketToServiceRecord(SERVICE_UUID)
                socket.connect()

                val input = socket.inputStream
                val output = socket.outputStream

                // Отправляем HandshakeRequest
                SyncProtocol.sendMessage(output, request)
                Log.d(TAG, "Handshake client: sent request")

                // Читаем HandshakeResponse
                val response = SyncProtocol.readMessage(input)
                if (response !is SyncMessage.HandshakeResponse) {
                    Log.e(
                        TAG,
                        "Handshake client: expected HandshakeResponse, " +
                                "got $response"
                    )
                    return@withContext null
                }
                if (!response.accepted) {
                    Log.e(
                        TAG,
                        "Handshake client: rejected: ${response.errorMessage}"
                    )
                    return@withContext null
                }

                Log.d(
                    TAG,
                    "Handshake client: received " +
                            "${response.readerIp}:${response.readerPort}"
                )
                socket.close()
                return@withContext HandshakeResult(
                    peerMacAddress, null, response
                )
            } catch (e: IOException) {
                Log.d(
                    TAG,
                    "Handshake client: attempt $attempt failed: ${e.message}"
                )
                try { socket?.close() } catch (ex: Exception) { /* ignore */ }
                delay(retryDelayMs)
            } catch (e: Exception) {
                Log.e(TAG, "Handshake client error: ${e.message}", e)
                try { socket?.close() } catch (ex: Exception) { /* ignore */ }
                return@withContext null
            }
        }
        Log.d(TAG, "Handshake client: all attempts failed")
        null
    }
}