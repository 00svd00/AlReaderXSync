package ru.samorez.alreaderxsync.storage

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.Settings
import androidx.documentfile.provider.DocumentFile

/**
 * Менеджер доступа к каталогам через SAF (Storage Access Framework).
 *
 * Работа с внешним хранилищем ведётся исключительно через SAF,
 * разрешение MANAGE_EXTERNAL_STORAGE не используется.
 */
class DirectoryAccessManager(private val context: Context) {

    companion object {
        /** Ключ SharedPreferences для каталога AlReader. */
        const val KEY_ALREADER_DIR = "alreader_dir"

        /** Ключ SharedPreferences для каталога книг. */
        const val KEY_BOOKS_DIR = "books_dir"

        /** Имя файла SharedPreferences (единое хранилище настроек). */
        const val PREFS_NAME = "sync_settings"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Создаёт Intent для выбора каталога через SAF.
     *
     * Устанавливаются флаги на чтение и запись, а также
     * FLAG_GRANT_PERSISTABLE_URI_PERMISSION не требуется, поскольку
     * persistable-права запрашиваются явно через takePersistableUriPermission.
     */
    fun createOpenDocumentTreeIntent(): Intent {
        return Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }

    /**
     * Обрабатывает результат выбора каталога.
     *
     * @param uri URI, полученный из ActivityResult.
     * @param key ключ SharedPreferences (KEY_ALREADER_DIR или KEY_BOOKS_DIR).
     * @return true, если удалось получить persistable-права и сохранить URI.
     */
    fun handleTreeUriResult(uri: Uri?, key: String): Boolean {
        if (uri == null) return false
        return try {
            // Забираем persistable-права на чтение и запись
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            // Сохраняем URI в SharedPreferences
            prefs.edit().putString(key, uri.toString()).apply()
            true
        } catch (e: SecurityException) {
            // Права не были предоставлены или уже отозваны
            false
        }
    }

    /**
     * Возвращает сохранённый URI каталога для указанного ключа.
     */
    fun getConfiguredUri(key: String): Uri? {
        val value = prefs.getString(key, null) ?: return null
        return try {
            Uri.parse(value)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Возвращает DocumentFile для сохранённого каталога.
     *
     * Проверяет, что каталог доступен на чтение и запись.
     * Если доступ отсутствует — возвращает null.
     */
    fun getDocumentFile(key: String): DocumentFile? {
        val uri = getConfiguredUri(key) ?: return null
        val docFile = DocumentFile.fromTreeUri(context, uri) ?: return null
        return if (docFile.canRead() && docFile.canWrite()) docFile else null
    }

    /**
     * Проверяет, есть ли у приложения persistable-права на указанный URI.
     */
    fun hasPersistedAccess(uri: Uri): Boolean {
        return context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }
    }

    /**
     * Освобождает persistable-права и удаляет сохранённый URI из SharedPreferences.
     */
    fun releaseAccess(key: String) {
        val uri = getConfiguredUri(key)
        if (uri != null) {
            try {
                context.contentResolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: SecurityException) {
                // Права уже могли быть отозваны — игнорируем
            }
        }
        prefs.edit().remove(key).apply()
    }
}