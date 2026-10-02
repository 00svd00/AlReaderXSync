package ru.samorez.alreaderxsync.transport

import android.content.Context
import android.util.Log
import ru.samorez.alreaderxsync.data.FileEntry
import java.io.InputStream

/**
 * Транспорт поверх Wi-Fi Direct (P2P).
 *
 * ВНИМАНИЕ: пока не реализован. Все методы протокола бросают
 * UnsupportedOperationException, connect() возвращает false.
 * Полная реализация — на будущее (P2P discovery + createGroup +
 * requestConnectionInfo + ServerSocket/Socket).
 *
 * В текущей архитектуре addresses["wifi_direct"] не заполняется,
 * поэтому этот транспорт по факту не будет вызываться.
 */
class WifiDirectTransport(
    @Suppress("UNUSED_PARAMETER") private val context: Context
) : SyncTransport {

    override val name = "wifi_direct"

    companion object {
        private const val TAG = "WifiDirectTransport"
    }

    /**
     * Установить соединение.
     * Минимальная версия: Wi-Fi Direct пока не реализован.
     */
    override suspend fun connect(address: String, isServer: Boolean): Boolean {
        Log.d(TAG, "Wi-Fi Direct not implemented yet, returning false")
        return false
    }

    override suspend fun sendMetadata(entries: List<FileEntry>) {
        throw UnsupportedOperationException("Wi-Fi Direct not implemented")
    }

    override suspend fun receiveMetadata(): List<FileEntry> {
        throw UnsupportedOperationException("Wi-Fi Direct not implemented")
    }

    override suspend fun sendRequest(paths: List<String>) {
        throw UnsupportedOperationException("Wi-Fi Direct not implemented")
    }

    override suspend fun receiveRequest(): List<String> {
        throw UnsupportedOperationException("Wi-Fi Direct not implemented")
    }

    override suspend fun sendFile(entry: FileEntry, input: InputStream) {
        throw UnsupportedOperationException("Wi-Fi Direct not implemented")
    }

    override suspend fun receiveFile(): Pair<FileEntry, InputStream> {
        throw UnsupportedOperationException("Wi-Fi Direct not implemented")
    }

    override suspend fun sendComplete(transferred: Int, deleted: Int) {
        throw UnsupportedOperationException("Wi-Fi Direct not implemented")
    }

    override suspend fun receiveComplete(): Pair<Int, Int> {
        throw UnsupportedOperationException("Wi-Fi Direct not implemented")
    }

    override suspend fun close() {
        // no-op: ресурсы не выделялись.
    }
}