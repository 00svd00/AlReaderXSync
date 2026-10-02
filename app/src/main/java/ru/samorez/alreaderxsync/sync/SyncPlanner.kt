// ============================================================
// Файл: sync/SyncPlanner.kt
// Пакет: ru.samorez.alreaderxsync.sync
// ============================================================
package ru.samorez.alreaderxsync.sync

import android.util.Log
import ru.samorez.alreaderxsync.data.FileEntry
import ru.samorez.alreaderxsync.data.SyncMode
import ru.samorez.alreaderxsync.data.SyncPlan

/**
 * Построитель плана синхронизации.
 *
 * На основе списков источника и цели, а также выбранного [SyncMode],
 * формирует [SyncPlan] — какие файлы перенести и какие удалить.
 *
 * Ключевая идея: в обоих режимах переносятся только те файлы источника,
 * которых нет в цели или которые от неё отличаются. Режимы различаются
 * ТОЛЬКО политикой удаления:
 *  - FULL_REPLACE:       удаляем файлы цели, отсутствующие в источнике;
 *  - MERGE_NEWEST_WINS:  ничего не удаляем.
 *
 * Это устраняет неэффективность, при которой FULL_REPLACE повторно
 * передавал все файлы, даже если они уже есть на целевом устройстве.
 */
class SyncPlanner {

    /**
     * Строит план синхронизации.
     *
     * @param source список записей источника (откуда копируем).
     * @param target список записей цели (куда копируем / что чистим).
     * @param mode   режим синхронизации.
     * @return готовый [SyncPlan].
     */
    fun buildPlan(
        source: List<FileEntry>,
        target: List<FileEntry>,
        mode: SyncMode
    ): SyncPlan {
        // Работаем только с файлами — каталоги игнорируем.
        val sourceFiles = source.filter { !it.isDirectory }
        val targetFiles = target.filter { !it.isDirectory }

        Log.d(TAG, "buildPlan: mode=$mode")
        Log.d(TAG, "  sourceFiles=${sourceFiles.size}")
        Log.d(TAG, "  targetFiles=${targetFiles.size}")

        // Карта цели по relativePath — для O(1) доступа при сравнении.
        val targetMap = targetFiles.associateBy { it.relativePath }
        // Множество путей источника — для быстрой проверки при удалении.
        val sourcePaths = sourceFiles.map { it.relativePath }.toSet()

        // Общая логика для обоих режимов:
        // передаём отсутствующие или различающиеся файлы.
        var missing = 0
        var differs = 0
        var same = 0

        val toTransfer = sourceFiles.filter { src ->
            val tgt = targetMap[src.relativePath]
            if (tgt == null) {
                // Файла нет в цели — обязательно переносим.
                missing++
                true
            } else if (!FileFingerprint.quickCompare(src, tgt)) {
                // Файл есть, но отличается — переносим и логируем различие.
                differs++
                Log.d(
                    TAG,
                    "  differs: ${src.relativePath}, " +
                            "src(size=${src.size}, mtime=${src.lastModified}), " +
                            "tgt(size=${tgt.size}, mtime=${tgt.lastModified})"
                )
                true
            } else {
                // Файлы идентичны — переносить не нужно.
                same++
                false
            }
        }

        // Различие между режимами — только в политике удаления.
        val toDelete = when (mode) {
            SyncMode.FULL_REPLACE -> targetFiles.filter {
                // Удаляем файлы цели, которых нет в источнике.
                it.relativePath !in sourcePaths
            }
            SyncMode.MERGE_NEWEST_WINS -> emptyList()
        }

        Log.d(
            TAG,
            "  Result: missing=$missing, differs=$differs, same=$same, " +
                    "toTransfer=${toTransfer.size}, toDelete=${toDelete.size}"
        )

        return SyncPlan(
            toTransfer = toTransfer,
            toDelete = toDelete
        )
    }

    private companion object {
        const val TAG = "SyncPlanner"
    }
}