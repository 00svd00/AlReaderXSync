package ru.samorez.alreaderxsync.data

/**
 * План синхронизации.
 *
 * Содержит списки файлов, которые необходимо перенести и удалить,
 * вычисленные [ru.samorez.alreaderxsync.sync.SyncPlanner].
 *
 * @property toTransfer список записей, подлежащих переносу (копированию).
 * @property toDelete   список записей, подлежащих удалению.
 */
data class SyncPlan(
    val toTransfer: List<FileEntry>,
    val toDelete: List<FileEntry>
)