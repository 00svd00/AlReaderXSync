package ru.samorez.alreaderxsync.data

/**
 * Состояние процесса синхронизации, используемое для отображения прогресса в UI.
 */
sealed class SyncState {

    /** Синхронизация не запущена. */
    object Idle : SyncState()

    /** Идёт обнаружение источника/цели (обход дерева документов). */
    object Discovering : SyncState()

    /** Устанавливается соединение с выбранным транспортом. */
    data class Connecting(val transportName: String) : SyncState()

    /** Сравнение списков файлов и построение плана синхронизации. */
    object Comparing : SyncState()

    /** Передача файлов. */
    data class Transferring(
        val currentFile: String,
        val progress: Int,
        val transferred: Int,
        val total: Int
    ) : SyncState()

    /** Синхронизация успешно завершена. */
    data class Complete(val result: SyncResult) : SyncState()

    /** Произошла ошибка. */
    data class Error(val message: String) : SyncState()
}