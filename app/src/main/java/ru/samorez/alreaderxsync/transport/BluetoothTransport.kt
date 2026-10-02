package ru.samorez.alreaderxsync.transport

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ru.samorez.alreaderxsync.data.FileEntry
import ru.samorez.alreaderxsync.protocol.FileManifestEntry
import ru.samorez.alreaderxsync.protocol.SyncMessage
import ru.samorez.alreaderxsync.protocol.SyncProtocol
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Транспорт поверх Bluetooth (RFCOMM).
 *
 * ВАЖНО: keepalive через 0x00-байты УБРАН. Он портил данные,
 * попадая в середину JSON или бинарных потоков. Вместо этого
 * рекомендуется обходить каталог ДО подключения (см. SyncService
 * и SyncRepository — scan выполняется до transport.connect()).
 *
 * @param context контекст приложения.
 * @param preConnectedSocket уже установленный извне BluetoothSocket.
 * @param clientMaxRetries число попыток клиента.
 * @param clientRetryDelayMs задержка между попытками клиента.
 */
class BluetoothTransport(
    private val context: Context,
    private val preConnectedSocket: BluetoothSocket? = null,
    private val clientMaxRetries: Int = CLIENT_MAX_RETRIES,
    private val clientRetryDelayMs: Long = CLIENT_RETRY_DELAY_MS
) : SyncTransport {

    override val name = "bluetooth"

    companion object {
        /** UUID сервиса для RFCOMM-соединения. */
        val SERVICE_UUID: UUID =
            UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66")

        /** Имя серверного сокета. */
        const val SERVICE_NAME = "AlReaderXSync"

        /** Таймаут ожидания accept на сервере — 120 секунд. */
        const val SERVER_ACCEPT_TIMEOUT_MS = 120_000L

        /** Задержка между попытками подключения клиента (по умолчанию). */
        const val CLIENT_RETRY_DELAY_MS = 1_000L

        /** Максимальное число попыток клиента (по умолчанию). */
        const val CLIENT_MAX_RETRIES = 3

        private const val TAG = "BluetoothTransport"
    }

    private val adapter: BluetoothAdapter? =
        BluetoothAdapter.getDefaultAdapter()

    private var serverSocket: BluetoothServerSocket? = null
    private var socket: BluetoothSocket? = preConnectedSocket
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    init {
        if (preConnectedSocket != null) {
            try {
                inputStream = preConnectedSocket.inputStream
                outputStream = preConnectedSocket.outputStream
                Log.d(
                    TAG,
                    "Pre-connected socket initialized: " +
                            "input=${inputStream?.javaClass?.simpleName}, " +
                            "output=${outputStream?.javaClass?.simpleName}, " +
                            "socket.isConnected=${preConnectedSocket.isConnected}"
                )
            } catch (e: IOException) {
                Log.e(TAG, "Pre-connected socket init failed: ${e.message}", e)
            }
        }
    }

    override suspend fun connect(
        address: String,
        isServer: Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        if (preConnectedSocket != null) {
            Log.d(TAG, "Using pre-connected socket")
            return@withContext inputStream != null && outputStream != null
        }

        if (!hasConnectPermission()) {
            Log.d(TAG, "No BLUETOOTH_CONNECT permission")
            return@withContext false
        }

        if (isServer) connectAsServer() else connectAsClient(address)
    }

    private fun hasConnectPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    private suspend fun connectAsServer(): Boolean {
        try {
            Log.d(TAG, "Server: opening BluetoothServerSocket")
            serverSocket = adapter?.listenUsingRfcommWithServiceRecord(
                SERVICE_NAME, SERVICE_UUID
            )

            val s = withTimeoutOrNull(SERVER_ACCEPT_TIMEOUT_MS) {
                serverSocket?.accept()
            }
            if (s == null) {
                Log.d(TAG, "Server: accept timeout")
                return false
            }
            socket = s
            inputStream = s.inputStream
            outputStream = s.outputStream
            Log.d(TAG, "Server: accepted from ${s.remoteDevice.address}")
            return true
        } catch (e: IOException) {
            Log.d(TAG, "Server: IOException: ${e.message}")
            return false
        } catch (e: SecurityException) {
            Log.d(TAG, "Server: SecurityException: ${e.message}")
            return false
        } finally {
            try { serverSocket?.close() } catch (e: Exception) {}
            serverSocket = null
        }
    }

    private suspend fun connectAsClient(address: String): Boolean {
        var attempt = 0
        while (attempt < clientMaxRetries) {
            attempt++
            Log.d(
                TAG,
                "Client: attempt $attempt/$clientMaxRetries to $address"
            )
            try {
                val device = adapter?.getRemoteDevice(address)
                    ?: return false
                adapter?.cancelDiscovery()
                val s = device.createRfcommSocketToServiceRecord(SERVICE_UUID)
                s.connect()
                socket = s
                inputStream = s.inputStream
                outputStream = s.outputStream
                Log.d(TAG, "Client: connected")
                return true
            } catch (e: IOException) {
                Log.d(TAG, "Client: attempt $attempt failed: ${e.message}")
                try { socket?.close() } catch (ex: Exception) {}
                socket = null
                if (attempt < clientMaxRetries) {
                    delay(clientRetryDelayMs)
                }
            } catch (e: SecurityException) {
                Log.d(TAG, "Client: SecurityException: ${e.message}")
                return false
            }
        }
        Log.d(TAG, "Client: all $clientMaxRetries attempts failed")
        return false
    }

    // ==================== Метаданные ====================

    override suspend fun sendMetadata(entries: List<FileEntry>) {
        Log.d(
            TAG,
            "sendMetadata: entering, entries=${entries.size}, " +
                    "outputStream=${outputStream?.javaClass?.simpleName}"
        )
        val manifest = SyncMessage.Manifest(
            entries.map {
                FileManifestEntry(it.relativePath, it.size, it.lastModified)
            }
        )
        try {
            SyncProtocol.sendMessage(outputStream!!, manifest)
            Log.d(TAG, "sendMetadata: sent successfully")
        } catch (e: Exception) {
            Log.e(TAG, "sendMetadata: failed: ${e.message}", e)
            throw e
        }
    }

    override suspend fun receiveMetadata(): List<FileEntry> {
        Log.d(
            TAG,
            "receiveMetadata: entering, " +
                    "inputStream=${inputStream?.javaClass?.simpleName}"
        )
        try {
            val msg = SyncProtocol.readMessage(inputStream!!)
            Log.d(
                TAG,
                "receiveMetadata: got message type=${msg::class.simpleName}"
            )
            val entries = if (msg is SyncMessage.Manifest) {
                msg.files.map {
                    FileEntry(
                        uri = Uri.EMPTY,
                        relativePath = it.path,
                        size = it.size,
                        lastModified = it.mtime,
                        isDirectory = false
                    )
                }
            } else {
                Log.w(TAG, "receiveMetadata: unexpected message type")
                emptyList()
            }
            Log.d(TAG, "receiveMetadata: returning ${entries.size} entries")
            return entries
        } catch (e: Exception) {
            Log.e(TAG, "receiveMetadata: failed: ${e.message}", e)
            throw e
        }
    }

    override suspend fun sendRequest(paths: List<String>) {
        Log.d(TAG, "sendRequest: entering, paths=${paths.size}")
        try {
            SyncProtocol.sendMessage(
                outputStream!!,
                SyncMessage.TransferRequest(paths)
            )
            Log.d(TAG, "sendRequest: sent successfully")
        } catch (e: Exception) {
            Log.e(TAG, "sendRequest: failed: ${e.message}", e)
            throw e
        }
    }

    override suspend fun receiveRequest(): List<String> {
        Log.d(TAG, "receiveRequest: entering")
        try {
            val msg = SyncProtocol.readMessage(inputStream!!)
            Log.d(
                TAG,
                "receiveRequest: got message type=${msg::class.simpleName}"
            )
            val paths = if (msg is SyncMessage.TransferRequest) msg.paths
            else emptyList()
            Log.d(TAG, "receiveRequest: returning ${paths.size} paths")
            return paths
        } catch (e: Exception) {
            Log.e(TAG, "receiveRequest: failed: ${e.message}", e)
            throw e
        }
    }

    override suspend fun sendFile(entry: FileEntry, input: InputStream) {
        Log.d(
            TAG,
            "sendFile: entering, path=${entry.relativePath}, " +
                    "size=${entry.size}"
        )
        try {
            SyncProtocol.sendFileHeader(
                outputStream!!, entry.relativePath, entry.size
            )
            val sent = SyncProtocol.sendFileBytes(outputStream!!, input)
            Log.d(TAG, "sendFile: sent $sent bytes for ${entry.relativePath}")
        } catch (e: Exception) {
            Log.e(
                TAG,
                "sendFile: failed for ${entry.relativePath}: ${e.message}", e
            )
            throw e
        }
    }

    override suspend fun receiveFile(): Pair<FileEntry, InputStream> {
        Log.d(TAG, "receiveFile: entering, waiting for header")
        try {
            val headerMsg = SyncProtocol.readMessage(inputStream!!)
            Log.d(TAG, "receiveFile: got header type=${headerMsg::class.simpleName}")
            if (headerMsg !is SyncMessage.FileHeader) {
                throw IOException("Expected FileHeader, got $headerMsg")
            }
            if (headerMsg !is SyncMessage.FileHeader) {
                throw IOException("Expected FileHeader, got $headerMsg")
            }
            Log.d(TAG, "receiveFile: path=${headerMsg.path}, size=${headerMsg.size}")
            val bytes = ByteArray(headerMsg.size.toInt())
            var read = 0
            var lastLog = System.currentTimeMillis()
            while (read < headerMsg.size) {
                val n = try {
                    inputStream!!.read(bytes, read, (headerMsg.size - read).toInt())
                } catch (e: Exception) {
                    Log.e(TAG, "receiveFile: read threw at $read/${headerMsg.size}: ${e.message}")
                    throw e
                }
                if (n < 0) {
                    Log.e(TAG, "receiveFile: EOF at $read/${headerMsg.size} bytes")
                    throw IOException("Unexpected EOF at $read/${headerMsg.size}")
                }
                read += n

                val now = System.currentTimeMillis()
                if (now - lastLog > 500) {
                    Log.d(TAG, "receiveFile: progress $read/${headerMsg.size} bytes")
                    lastLog = now
                }
            }
            Log.d(TAG, "receiveFile: read ${bytes.size} bytes")
            val entry = FileEntry(
                uri = Uri.EMPTY,
                relativePath = headerMsg.path,
                size = headerMsg.size,
                lastModified = 0L,
                isDirectory = false
            )
            return Pair(entry, ByteArrayInputStream(bytes))
        } catch (e: Exception) {
            Log.e(TAG, "receiveFile: failed: ${e.message}", e)
            throw e
        }
    }

    override suspend fun sendComplete(transferred: Int, deleted: Int) {
        Log.d(
            TAG,
            "sendComplete: entering, transferred=$transferred, " +
                    "deleted=$deleted"
        )
        try {
            SyncProtocol.sendMessage(
                outputStream!!,
                SyncMessage.Complete(transferred, deleted)
            )
            Log.d(TAG, "sendComplete: sent successfully")
        } catch (e: Exception) {
            Log.e(TAG, "sendComplete: failed: ${e.message}", e)
            throw e
        }
    }

    override suspend fun receiveComplete(): Pair<Int, Int> {
        Log.d(TAG, "receiveComplete: entering")
        try {
            val msg = SyncProtocol.readMessage(inputStream!!)
            Log.d(
                TAG,
                "receiveComplete: got message type=${msg::class.simpleName}"
            )
            val result = if (msg is SyncMessage.Complete)
                Pair(msg.transferred, msg.deleted)
            else Pair(0, 0)
            Log.d(TAG, "receiveComplete: returning $result")
            return result
        } catch (e: Exception) {
            Log.e(TAG, "receiveComplete: failed: ${e.message}", e)
            throw e
        }
    }
    // ==================== Ping Pong ====================
    override suspend fun sendPing() {
        Log.d(TAG, "sendPing: entering")
        try {
            val nonce = System.currentTimeMillis()
            SyncProtocol.sendMessage(outputStream!!, SyncMessage.Ping(nonce))
            outputStream!!.flush()
            Log.d(TAG, "sendPing: sent nonce=$nonce, waiting for Pong...")

            // Ждём Pong — это гарантирует, что Phone прочитал
            // всё, что было отправлено ДО Ping
            val response = SyncProtocol.readMessage(inputStream!!)
            if (response is SyncMessage.Pong) {
                Log.d(TAG, "sendPing: got Pong nonce=${response.nonce}")
            } else {
                Log.w(TAG, "sendPing: unexpected response $response")
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendPing: failed: ${e.message}", e)
            throw e
        }
    }
    override suspend fun awaitPing() {
        Log.d(TAG, "awaitPing: entering")
        try {
            val msg = SyncProtocol.readMessage(inputStream!!)
            if (msg is SyncMessage.Ping) {
                Log.d(TAG, "awaitPing: got Ping nonce=${msg.nonce}, sending Pong")
                SyncProtocol.sendMessage(outputStream!!, SyncMessage.Pong(msg.nonce))
                outputStream!!.flush()
                Log.d(TAG, "awaitPing: Pong sent")
            } else {
                Log.w(TAG, "awaitPing: unexpected message $msg")
            }
        } catch (e: Exception) {
            Log.e(TAG, "awaitPing: failed: ${e.message}", e)
            throw e
        }
    }
    // ==================== Закрытие ====================

    override suspend fun close() {
        Log.d(TAG, "close: entering")
        try { inputStream?.close() } catch (e: Exception) {}
        try { outputStream?.close() } catch (e: Exception) {}
        if (preConnectedSocket == null) {
            try { socket?.close() } catch (e: Exception) {}
        }
        try { serverSocket?.close() } catch (e: Exception) {}
        inputStream = null
        outputStream = null
        socket = null
        serverSocket = null
        Log.d(TAG, "close: done")
    }
}