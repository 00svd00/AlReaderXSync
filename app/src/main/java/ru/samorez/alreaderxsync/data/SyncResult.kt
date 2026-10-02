package ru.samorez.alreaderxsync.data

/**
 * Итоговый результат выполнения синхронизации.
 *
 * @property transferred количество успешно переданных файлов.
 * @property deleted количество удалённых файлов на целевом устройстве.
 * @property errors список ошибок, возникших в процессе синхронизации.
 * @property transportUsed имя транспорта, через который выполнялась синхронизация.
 */
data class SyncResult(
    val transferred: Int,
    val deleted: Int,
    val errors: List<String>,
    val transportUsed: String
)