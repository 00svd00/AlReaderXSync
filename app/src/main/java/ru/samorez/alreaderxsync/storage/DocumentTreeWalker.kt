package ru.samorez.alreaderxsync.storage

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.samorez.alreaderxsync.data.FileEntry
import ru.samorez.alreaderxsync.sync.FileFingerprint

/**
 * Рекурсивный обход дерева документов через [DocumentsContract] напрямую.
 *
 * Вместо медленных DocumentFile.listFiles() используем
 * ContentResolver.query() с projection — один запрос на каталог
 * вместо множества обращений через ContentResolver на каждый файл.
 *
 * @param context           Контекст приложения (ContentResolver + хэши)
 * @param root              Корневой DocumentFile дерева
 * @param excludedPaths     Список исключаемых относительных путей
 * @param excludedPatterns  Список glob-шаблонов для исключения по имени файла
 * @param computeHashes     Вычислять ли SHA-256 для «проблемных» файлов
 *                          (можно отключить для ускорения обхода)
 */
class DocumentTreeWalker(
    private val context: Context,
    private val root: DocumentFile,
    private val excludedPaths: List<String> = emptyList(),
    private val excludedPatterns: List<String> = listOf("*.db-wal", "*.db-shm"),
    private val computeHashes: Boolean = true
) {

    companion object {
        private const val TAG = "DocumentTreeWalker"

        /**
         * Файлы с этими расширениями считаются «проблемными»:
         * их содержимое может измениться без изменения размера.
         * Для них вычисляется SHA-256 для точного сравнения.
         */
        private val HASH_EXTENSIONS = setOf(
            ".db", ".sqlite", ".sqlite3",
            ".ini", ".profile"
        )

        /** Возвращает true, если для файла нужно считать SHA-256. */
        private fun shouldComputeHash(fileName: String): Boolean {
            val lower = fileName.lowercase()
            return HASH_EXTENSIONS.any { lower.endsWith(it) }
        }
    }

    /**
     * Информация о дочернем элементе каталога, полученная одним
     * запросом ContentResolver.query().
     */
    private data class ChildInfo(
        val documentId: String,
        val name: String,
        val mimeType: String,
        val size: Long,
        val mtime: Long,
        val isDirectory: Boolean
    )

    /**
     * Выполняет рекурсивный обход дерева и возвращает плоский список записей.
     * Логирует общее время обхода.
     */
    suspend fun walk(onProgress: ((Int) -> Unit)? = null): List<FileEntry> =
        withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            Log.d(TAG, "walk() started, root=${root.uri}")

            val result = mutableListOf<FileEntry>()

            try {
                // Получаем documentId корневого дерева.
                // Для tree-URI DocumentsContract.getTreeDocumentId() возвращает
                // идентификатор корневого документа дерева.
                val rootDocId = DocumentsContract.getTreeDocumentId(root.uri)
                walkRecursiveFast(
                    treeUri = root.uri,
                    documentId = rootDocId,
                    basePath = "",
                    result = result,
                    onProgress = onProgress
                )
            } catch (e: SecurityException) {
                Log.w(TAG, "SecurityException при обходе дерева", e)
            } catch (e: NullPointerException) {
                Log.w(TAG, "NullPointerException при обходе дерева", e)
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка при обходе дерева: ${e.message}", e)
            }

            val elapsed = System.currentTimeMillis() - startTime
            Log.d(TAG, "walk() finished: ${result.size} entries in ${elapsed}ms")

            onProgress?.invoke(result.size)
            result
        }

    /**
     * Рекурсивный обход одного каталога. Список детей получаем одним
     * запросом через [listChildrenFast]. Для подкаталогов — рекурсия.
     */
    private suspend fun walkRecursiveFast(
        treeUri: Uri,
        documentId: String,
        basePath: String,
        result: MutableList<FileEntry>,
        onProgress: ((Int) -> Unit)?
    ) {
        val children = listChildrenFast(treeUri, documentId)

        for (child in children) {
            // 1. Пропуск скрытых файлов и директорий
            if (child.name.startsWith(".")) continue

            val childPath =
                if (basePath.isEmpty()) child.name else "$basePath/${child.name}"

            // 2. Пропуск по excludedPaths
            if (isExcludedPath(childPath)) continue

            // 3. Пропуск по excludedPatterns
            if (matchesAnyPattern(child.name)) continue

            if (child.isDirectory) {
                // Директория: добавляем запись в результат и уходим вглубь
                val dirUri = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri, child.documentId
                )
                result.add(
                    FileEntry(
                        uri = dirUri,
                        relativePath = childPath,
                        size = 0L,
                        lastModified = child.mtime,
                        isDirectory = true,
                        contentHash = null
                    )
                )
                walkRecursiveFast(treeUri, child.documentId, childPath, result, onProgress)
            } else {
                // Файл: при необходимости считаем SHA-256
                val childUri = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri, child.documentId
                )

                val hash = if (computeHashes && shouldComputeHash(child.name)) {
                    try {
                        Log.d(TAG, "Computing hash for $childPath (size=${child.size})")
                        FileFingerprint.sha256(context, childUri)
                    } catch (e: Exception) {
                        Log.e(
                            TAG,
                            "Failed to compute hash for $childPath: ${e.message}",
                            e
                        )
                        null
                    }
                } else {
                    null
                }

                result.add(
                    FileEntry(
                        uri = childUri,
                        relativePath = childPath,
                        size = child.size,
                        lastModified = child.mtime,
                        isDirectory = false,
                        contentHash = hash
                    )
                )
            }

            onProgress?.invoke(result.size)
        }
    }

    /**
     * Быстрое получение списка дочерних элементов каталога одним
     * запросом через ContentResolver. Заменяет DocumentFile.listFiles().
     *
     * Дополнительное преимущество — не создаём DocumentFile-объекты
     * для каждого ребёнка (каждый из них — обёртка над ContentResolver).
     */
    private fun listChildrenFast(
        treeUri: Uri,
        parentDocumentId: String
    ): List<ChildInfo> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            parentDocumentId
        )

        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )

        val result = ArrayList<ChildInfo>()

        try {
            val cursor: Cursor? = context.contentResolver.query(
                childrenUri, projection, null, null, null
            )
            cursor?.use { c ->
                val colDocId = c.getColumnIndexOrThrow(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID
                )
                val colName = c.getColumnIndexOrThrow(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
                )
                val colMime = c.getColumnIndexOrThrow(
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                )
                val colSize = c.getColumnIndexOrThrow(
                    DocumentsContract.Document.COLUMN_SIZE
                )
                val colMtime = c.getColumnIndexOrThrow(
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED
                )

                while (c.moveToNext()) {
                    val docId = c.getString(colDocId) ?: continue
                    val name = c.getString(colName) ?: continue
                    val mime = c.getString(colMime) ?: ""
                    val size = if (c.isNull(colSize)) 0L else c.getLong(colSize)
                    val mtime = if (c.isNull(colMtime)) 0L else c.getLong(colMtime)
                    val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR

                    result.add(
                        ChildInfo(
                            documentId = docId,
                            name = name,
                            mimeType = mime,
                            size = size,
                            mtime = mtime,
                            isDirectory = isDir
                        )
                    )
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException при listChildrenFast($parentDocumentId)", e)
        } catch (e: NullPointerException) {
            Log.w(TAG, "NullPointerException при listChildrenFast($parentDocumentId)", e)
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка при listChildrenFast($parentDocumentId): ${e.message}", e)
        }

        return result
    }

    /**
     * Проверяет, попадает ли относительный путь под исключение.
     * Совпадение: либо точное равенство, либо путь лежит внутри
     * исключённой директории.
     */
    private fun isExcludedPath(relativePath: String): Boolean {
        if (excludedPaths.isEmpty()) return false
        for (excluded in excludedPaths) {
            if (relativePath == excluded) return true
            if (relativePath.startsWith("$excluded/")) return true
        }
        return false
    }

    /** Проверяет имя файла на соответствие хотя бы одному из excludedPatterns. */
    private fun matchesAnyPattern(name: String): Boolean {
        for (pattern in excludedPatterns) {
            if (globMatches(name, pattern)) return true
        }
        return false
    }

    /**
     * Проверяет соответствие имени glob-шаблону.
     * Поддерживаются: `*` (любая последовательность символов),
     * `?` (один символ). Регистрозависимо, полное совпадение строки.
     */
    fun globMatches(name: String, pattern: String): Boolean {
        // Экранируем спецсимволы regex, затем заменяем * и ?
        val sb = StringBuilder()
        for (ch in pattern) {
            when (ch) {
                '*' -> sb.append(".*")
                '?' -> sb.append('.')
                // Экранирование метасимволов регулярного выражения
                '.', '(', ')', '[', ']', '{', '}', '+', '^', '$', '|', '\\' -> {
                    sb.append('\\').append(ch)
                }
                else -> sb.append(ch)
            }
        }
        return Regex("^${sb}$").matches(name)
    }
}