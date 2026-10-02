package ru.samorez.alreaderxsync.protocol

import kotlinx.serialization.Serializable

/**
 * Список сообщений, которыми обмениваются клиент и сервер синхронизации.
 *
 * Используется sealed-класс, чтобы сериализация работала корректно
 * и все возможные типы сообщений были известны на этапе компиляции.
 * Поле-дискриминатор настраивается в [SyncProtocol] как "type".
 */
@Serializable
sealed class SyncMessage {

    /** Приветствие при установлении соединения (устаревшее, оставлено для совместимости). */
    @Serializable
    data class Hello(val role: String, val protocolVersion: Int) : SyncMessage()

    /** Манифест — список файлов с их метаданными. */
    @Serializable
    data class Manifest(val files: List<FileManifestEntry>) : SyncMessage()

    /** Запрос на передачу конкретных файлов по их путям. */
    @Serializable
    data class TransferRequest(val paths: List<String>) : SyncMessage()

    /** Заголовок файла перед передачей его содержимого. */
    @Serializable
    data class FileHeader(val path: String, val size: Long) : SyncMessage()

    /** Завершение сеанса синхронизации со статистикой. */
    @Serializable
    data class Complete(val transferred: Int, val deleted: Int) : SyncMessage()

    /** Сообщение об ошибке. */
    @Serializable
    data class Error(val message: String) : SyncMessage()

    /**
     * Запрос на установление сеанса синхронизации.
     *
     * Отправляется телефоном ридеру. Содержит список задач, режим
     * синхронизации и направление передачи данных.
     *
     * @param role       роль инициатора; всегда "phone"
     * @param tasks      список задач синхронизации
     * @param mode       режим: "FULL_REPLACE" или "MERGE_NEWEST_WINS"
     * @param direction  направление: "PHONE_TO_READER" или "READER_TO_PHONE"
     * @param phoneIp    IP телефона (нужен для обратной синхронизации,
     *                   когда ридер сам подключается к телефону)
     */
    @Serializable
    data class HandshakeRequest(
        val role: String,
        val tasks: List<TaskSpec>,
        val mode: String,
        val direction: String,
        val phoneIp: String?
    ) : SyncMessage()

    /**
     * Ответ ридера на [HandshakeRequest].
     *
     * @param readerIp      IP ридера, к которому при необходимости
     *                      может подключиться телефон
     * @param readerPort    порт ServerSocket ридера
     * @param accepted      принят ли запрос
     * @param errorMessage  текст ошибки, если accepted = false
     */
    @Serializable
    data class HandshakeResponse(
        val readerIp: String,
        val readerPort: Int,
        val accepted: Boolean,
        val errorMessage: String? = null
    ) : SyncMessage()

    /**
     * Ping — служебное сообщение для синхронизации буфера.
     *
     * Отправляется ПЕРЕД close() на стороне источника
     * (после Complete). Его write() блокируется, пока приёмник
     * не вычитает все предыдущие данные из Bluetooth-буфера.
     * Это гарантирует, что последний файл дойдёт до приёмника
     * до того, как сокет будет закрыт.
     *
     * Приёмник должен ответить Pong (или просто проигнорировать),
     * а затем подтвердить закрытие.
     */
    @Serializable
    data class Ping(val nonce: Long = System.currentTimeMillis()) : SyncMessage()

    /**
     * Pong — ответ на Ping.
     */
    @Serializable
    data class Pong(val nonce: Long) : SyncMessage()
}

/**
 * Запись о файле в манифесте.
 *
 * @param path  относительный путь файла
 * @param size  размер файла в байтах
 * @param mtime время последней модификации (Unix timestamp, мс)
 */
@Serializable
data class FileManifestEntry(
    val path: String,
    val size: Long,
    val mtime: Long
)

/**
 * Описание одной задачи синхронизации.
 *
 * @param name          имя задачи, например "AlReaderX" или "Books"
 * @param excludePaths  пути, которые нужно исключить из задачи
 *                      (например, подпапка "Books" внутри задачи)
 */
@Serializable
data class TaskSpec(
    val name: String,
    val excludePaths: List<String> = emptyList()
)