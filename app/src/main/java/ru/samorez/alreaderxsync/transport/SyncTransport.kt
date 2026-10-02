package ru.samorez.alreaderxsync.transport

import ru.samorez.alreaderxsync.data.FileEntry
import java.io.InputStream

/**
 * Базовый интерфейс транспортного канала синхронизации.
 * Реализации: Wi-Fi LAN, Wi-Fi Direct, Bluetooth.
 *
 * Протокол обмена построен на парных методах:
 *  - метаданные (Manifest):        sendMetadata / receiveMetadata
 *  - запрос на передачу (Request): sendRequest  / receiveRequest
 *  - файл (Header + raw bytes):    sendFile     / receiveFile
 *  - завершение (Complete):        sendComplete / receiveComplete
 */
interface SyncTransport {

    /** Имя транспорта: "wifi_lan", "wifi_direct", "bluetooth". */
    val name: String

    /**
     * Установить соединение.
     *
     * @param address Адрес партнёра:
     *   - для wifi_lan — IP-адрес (например, "192.168.1.42")
     *   - для bluetooth — MAC-адрес (например, "AA:BB:CC:DD:EE:FF")
     *   - для wifi_direct — group owner address
     * @param isServer true — этот узел открывает ServerSocket и ждёт
     *                 false — этот узел подключается как клиент с retry
     * @return true при успешном соединении, false при ошибке
     */
    suspend fun connect(address: String, isServer: Boolean): Boolean

    /**
     * Отправить Ping перед закрытием.
     * По умолчанию no-op — для транспортов, где не нужно.
     */
    suspend fun sendPing() {
        // no-op по умолчанию
    }

    /**
     * Прочитать Ping/Pong от партнёра.
     * По умолчанию no-op.
     */
    suspend fun awaitPing() {
        // no-op по умолчанию
    }

    /**
     * Закрыть все ресурсы транспорта (сокеты, потоки, каналы).
     */
    suspend fun close()

    // ==================== Метаданные (Manifest) ====================

    /**
     * Отправить метаданные о наборе файлов.
     */
    suspend fun sendMetadata(entries: List<FileEntry>)

    /**
     * Принять метаданные о наборе файлов.
     * @return список FileEntry или пустой список при ошибке/неожиданном сообщении.
     */
    suspend fun receiveMetadata(): List<FileEntry>

    // ==================== Запрос на передачу (TransferRequest) ====================

    /**
     * Отправить запрос на передачу указанных путей.
     */
    suspend fun sendRequest(paths: List<String>)

    /**
     * Принять запрос на передачу.
     * @return список путей или пустой список при ошибке/неожиданном сообщении.
     */
    suspend fun receiveRequest(): List<String>

    // ==================== Файл (FileHeader + raw bytes) ====================

    /**
     * Отправить содержимое одного файла.
     */
    suspend fun sendFile(entry: FileEntry, input: InputStream)

    /**
     * Принять один файл: метаданные + поток для чтения.
     */
    suspend fun receiveFile(): Pair<FileEntry, InputStream>

    // ==================== Завершение (Complete) ====================

    /**
     * Отправить сообщение о завершении передачи.
     * @param transferred количество успешно переданных файлов
     * @param deleted количество удалённых файлов
     */
    suspend fun sendComplete(transferred: Int, deleted: Int)

    /**
     * Принять сообщение о завершении передачи.
     * @return пара (transferred, deleted) или (0, 0) при ошибке.
     */
    suspend fun receiveComplete(): Pair<Int, Int>
}