package ru.samorez.alreaderxsync.transport

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import ru.samorez.alreaderxsync.data.FileEntry
import ru.samorez.alreaderxsync.protocol.FileManifestEntry
import ru.samorez.alreaderxsync.protocol.SyncMessage
import ru.samorez.alreaderxsync.protocol.SyncProtocol
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Транспорт поверх обычного Wi-Fi (LAN).
 * Работает через ServerSocket/Socket.
 * НЕ использует WifiP2pManager и НЕ отключает обычный Wi-Fi.
 *
 * Retry-параметры вынесены в конструктор, чтобы MainActivity
 * могла передать значения из TransportConfig (SettingsRepository).
 *
 * @param context контекст приложения.
 * @param defaultPort порт по умолчанию.
 * @param preConnectedSocket уже установленный извне сокет (для сервера).
 * @param clientMaxRetries число попыток клиента.
 * @param clientRetryDelayMs задержка между попытками клиента.
 */
class WifiLanTransport(
    private val context: Context,
    private val defaultPort: Int = PORT,
    private val preConnectedSocket: Socket? = null,
    private val clientMaxRetries: Int = CLIENT_MAX_RETRIES,
    private val clientRetryDelayMs: Long = CLIENT_RETRY_DELAY_MS
) : SyncTransport {

    override val name = "wifi_lan"

    companion object {
        /** Порт по умолчанию. */
        const val PORT = 8988

        /** Таймаут ожидания accept на сервере — 120 секунд. */
        const val SERVER_ACCEPT_TIMEOUT_MS = 120_000

        /** Таймаут одной попытки connect на клиенте — 3 секунды. */
        const val CLIENT_CONNECT_TIMEOUT_MS = 3_000

        /** Задержка между попытками клиента — 1 секунда. */
        const val CLIENT_RETRY_DELAY_MS = 1_000L

        /** Число попыток клиента — 3. */
        const val CLIENT_MAX_RETRIES = 3

        private const val TAG = "WifiLanTransport"
    }

    private var actualPort: Int = defaultPort
    private var serverSocket: ServerSocket? = null
    private var socket: Socket? = preConnectedSocket
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    init {
        if (preConnectedSocket != null) {
            try {
                inputStream = preConnectedSocket.getInputStream()
                outputStream = preConnectedSocket.getOutputStream()
                Log.d(TAG, "Pre-connected socket initialized")
            } catch (e: IOException) {
                Log.e(TAG, "Pre-connected socket init error: ${e.message}")
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
        if (isServer) {
            connectAsServer()
        } else {
            val (host, port) = parseAddress(address)
            connectAsClient(host, port)
        }
    }

    private fun parseAddress(address: String): Pair<String, Int> {
        val idx = address.lastIndexOf(':')
        if (idx <= 0) return Pair(address, defaultPort)
        val port = address.substring(idx + 1).toIntOrNull() ?: defaultPort
        val host = address.substring(0, idx)
        return Pair(host, port)
    }

    private suspend fun connectAsServer(): Boolean {
        try {
            Log.d(TAG, "Server: opening ServerSocket on port $defaultPort")
            serverSocket = ServerSocket(defaultPort).apply {
                soTimeout = SERVER_ACCEPT_TIMEOUT_MS
                reuseAddress = true
            }
            actualPort = serverSocket?.localPort ?: defaultPort
            Log.d(TAG, "Server: bound to port $actualPort")

            socket = serverSocket?.accept() ?: return false
            inputStream = socket?.getInputStream()
            outputStream = socket?.getOutputStream()
            Log.d(
                TAG,
                "Server: accepted from ${socket?.inetAddress?.hostAddress}"
            )
            return true
        } catch (e: SocketTimeoutException) {
            Log.e(TAG, "Server: accept timeout")
            closeServerSocketQuietly()
            return false
        } catch (e: IOException) {
            Log.e(TAG, "Server error: ${e.message}")
            closeServerSocketQuietly()
            return false
        }
    }

    /**
     * Клиентская сторона: retry-цикл Socket.connect() с таймаутом.
     *
     * Число попыток и задержка берутся из параметров конструктора
     * (clientMaxRetries / clientRetryDelayMs). Это позволяет
     * MainActivity передать значения из TransportConfig.
     */
    private suspend fun connectAsClient(
        host: String,
        port: Int
    ): Boolean {
        var attempt = 0
        while (attempt < clientMaxRetries) {
            attempt++
            Log.d(
                TAG,
                "Client: attempt $attempt/$clientMaxRetries to $host:$port"
            )
            try {
                val s = Socket()
                s.connect(
                    InetSocketAddress(host, port),
                    CLIENT_CONNECT_TIMEOUT_MS
                )
                socket = s
                inputStream = s.getInputStream()
                outputStream = s.getOutputStream()
                Log.d(TAG, "Client: connected")
                return true
            } catch (e: IOException) {
                Log.d(TAG, "Client: attempt $attempt failed: ${e.message}")
                try { socket?.close() } catch (ex: Exception) {}
                socket = null
                if (attempt < clientMaxRetries) {
                    delay(clientRetryDelayMs)
                }
            }
        }
        Log.d(TAG, "Client: all $clientMaxRetries attempts failed")
        return false
    }

    private fun closeServerSocketQuietly() {
        try { serverSocket?.close() } catch (e: Exception) {}
        serverSocket = null
    }

    fun getActualPort(): Int = actualPort

    // ==================== Метаданные ====================

    override suspend fun sendMetadata(entries: List<FileEntry>) {
        val manifest = SyncMessage.Manifest(
            entries.map {
                FileManifestEntry(it.relativePath, it.size, it.lastModified)
            }
        )
        SyncProtocol.sendMessage(outputStream!!, manifest)
        Log.d(TAG, "sendMetadata: ${entries.size} entries")
    }

    override suspend fun receiveMetadata(): List<FileEntry> {
        val msg = SyncProtocol.readMessage(inputStream!!)
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
        } else emptyList()
        Log.d(TAG, "receiveMetadata: ${entries.size} entries")
        return entries
    }

    override suspend fun sendRequest(paths: List<String>) {
        SyncProtocol.sendMessage(
            outputStream!!,
            SyncMessage.TransferRequest(paths)
        )
        Log.d(TAG, "sendRequest: ${paths.size} paths")
    }

    override suspend fun receiveRequest(): List<String> {
        val msg = SyncProtocol.readMessage(inputStream!!)
        val paths = if (msg is SyncMessage.TransferRequest) msg.paths
        else emptyList()
        Log.d(TAG, "receiveRequest: ${paths.size} paths")
        return paths
    }

    override suspend fun sendFile(entry: FileEntry, input: InputStream) {
        SyncProtocol.sendFileHeader(
            outputStream!!, entry.relativePath, entry.size
        )
        val sent = SyncProtocol.sendFileBytes(outputStream!!, input)
        Log.d(TAG, "sendFile: ${entry.relativePath}, $sent bytes")
    }

    override suspend fun receiveFile(): Pair<FileEntry, InputStream> {
        val headerMsg = SyncProtocol.readMessage(inputStream!!)
        if (headerMsg !is SyncMessage.FileHeader) {
            throw IOException("Expected FileHeader, got $headerMsg")
        }
        val bytes = ByteArray(headerMsg.size.toInt())
        var read = 0
        while (read < headerMsg.size) {
            val n = inputStream!!.read(
                bytes, read, (headerMsg.size - read).toInt()
            )
            if (n < 0) throw IOException("Unexpected EOF")
            read += n
        }
        val entry = FileEntry(
            uri = Uri.EMPTY,
            relativePath = headerMsg.path,
            size = headerMsg.size,
            lastModified = 0L,
            isDirectory = false
        )
        Log.d(TAG, "receiveFile: ${entry.relativePath}, ${bytes.size} bytes")
        return Pair(entry, ByteArrayInputStream(bytes))
    }

    override suspend fun sendComplete(transferred: Int, deleted: Int) {
        SyncProtocol.sendMessage(
            outputStream!!,
            SyncMessage.Complete(transferred, deleted)
        )
        outputStream!!.flush()
        Log.d(
            TAG,
            "sendComplete: transferred=$transferred, deleted=$deleted"
        )
    }

    override suspend fun receiveComplete(): Pair<Int, Int> {
        val msg = SyncProtocol.readMessage(inputStream!!)
        val result = if (msg is SyncMessage.Complete)
            Pair(msg.transferred, msg.deleted)
        else Pair(0, 0)
        Log.d(TAG, "receiveComplete: $result")
        return result
    }

    override suspend fun close() {
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
        actualPort = defaultPort
    }
}