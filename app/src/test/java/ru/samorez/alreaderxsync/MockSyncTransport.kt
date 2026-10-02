// src/test/java/ru/samorez/alreaderxsync/MockSyncTransport.kt
package ru.samorez.alreaderxsync

import kotlinx.coroutines.delay
import ru.samorez.alreaderxsync.data.FileEntry
import ru.samorez.alreaderxsync.transport.SyncTransport
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Mock-реализация [SyncTransport] для unit-тестов.
 *
 * Хранит отправленные и «принимаемые» данные в памяти.
 * Позволяет настраивать поведение: задержку connect,
 * успех/неудачу connect, ошибки при sendFile / receiveFile.
 */
class MockSyncTransport(
    override val name: String,
    var connectResult: Boolean = true,
    var connectDelayMs: Long = 0L,
    var shouldFailOnSendFile: Boolean = false,
    var shouldFailOnReceiveFile: Boolean = false
) : SyncTransport {

    // ============================================================
    // Хранилище для тестов
    // ============================================================

    /** Все метаданные, отправленные через [sendMetadata]. */
    val sentMetadata: MutableList<List<FileEntry>> = mutableListOf()

    /** Все запросы, отправленные через [sendRequest]. */
    val sentRequests: MutableList<List<String>> = mutableListOf()

    /** Все файлы, отправленные через [sendFile]: relativePath -> содержимое. */
    val sentFiles: MutableMap<String, ByteArray> = mutableMapOf()

    /** Все Complete-сообщения, отправленные через [sendComplete]. */
    val sentCompletes: MutableList<Pair<Int, Int>> = mutableListOf()

    /** Данные, которые будет возвращать [receiveMetadata]. */
    var metadataToReceive: List<FileEntry> = emptyList()

    /** Данные, которые будет возвращать [receiveRequest]. */
    var requestToReceive: List<String> = emptyList()

    /**
     * Очередь файлов, которые будет возвращать [receiveFile].
     * Каждый элемент — пара (запись, содержимое).
     */
    val filesToReceive: MutableList<Pair<FileEntry, ByteArray>> = mutableListOf()

    /** Данные, которые будет возвращать [receiveComplete]. */
    var completeToReceive: Pair<Int, Int> = Pair(0, 0)

    // ============================================================
    // Состояние
    // ============================================================

    /** Был ли вызван [connect]. */
    var connectCalled: Boolean = false

    /** Адрес, переданный в последний вызов [connect]. */
    var connectedAddress: String? = null

    /** Флаг isServer, переданный в последний вызов [connect]. */
    var connectedIsServer: Boolean? = null

    /** Был ли вызван [close]. */
    var closed: Boolean = false

    /** Сколько раз был вызван [close]. */
    var closeCalledCount: Int = 0

    // ============================================================
    // SyncTransport implementation
    // ============================================================

    override suspend fun connect(
        address: String,
        isServer: Boolean
    ): Boolean {
        connectCalled = true
        connectedAddress = address
        connectedIsServer = isServer

        // Эмулируем задержку подключения, если она задана.
        if (connectDelayMs > 0) {
            delay(connectDelayMs)
        }

        return connectResult
    }

    override suspend fun sendMetadata(entries: List<FileEntry>) {
        sentMetadata.add(entries)
    }

    override suspend fun receiveMetadata(): List<FileEntry> {
        return metadataToReceive
    }

    override suspend fun sendRequest(paths: List<String>) {
        sentRequests.add(paths)
    }

    override suspend fun receiveRequest(): List<String> {
        return requestToReceive
    }

    override suspend fun sendFile(
        entry: FileEntry,
        input: InputStream
    ) {
        if (shouldFailOnSendFile) {
            throw IOException("Mock sendFile failure for ${entry.relativePath}")
        }
        val bytes = input.readBytes()
        sentFiles[entry.relativePath] = bytes
    }

    override suspend fun receiveFile(): Pair<FileEntry, InputStream> {
        if (shouldFailOnReceiveFile) {
            throw IOException("Mock receiveFile failure")
        }
        if (filesToReceive.isEmpty()) {
            throw IOException("No files to receive")
        }
        val (entry, bytes) = filesToReceive.removeAt(0)
        return Pair(entry, ByteArrayInputStream(bytes))
    }

    override suspend fun sendComplete(transferred: Int, deleted: Int) {
        sentCompletes.add(Pair(transferred, deleted))
    }

    override suspend fun receiveComplete(): Pair<Int, Int> {
        return completeToReceive
    }

    override suspend fun close() {
        closed = true
        closeCalledCount++
    }

    // ============================================================
    // Вспомогательные методы
    // ============================================================

    /**
     * Сбросить всё состояние к начальному.
     * Полезно для @Before в тестах.
     */
    fun reset() {
        sentMetadata.clear()
        sentRequests.clear()
        sentFiles.clear()
        sentCompletes.clear()
        filesToReceive.clear()
        metadataToReceive = emptyList()
        requestToReceive = emptyList()
        completeToReceive = Pair(0, 0)

        connectCalled = false
        connectedAddress = null
        connectedIsServer = null
        closed = false
        closeCalledCount = 0

        connectResult = true
        connectDelayMs = 0L
        shouldFailOnSendFile = false
        shouldFailOnReceiveFile = false
    }

    /**
     * Поставить файл в очередь на приём через [receiveFile].
     */
    fun enqueueFileToReceive(entry: FileEntry, content: ByteArray) {
        filesToReceive.add(Pair(entry, content))
    }
}