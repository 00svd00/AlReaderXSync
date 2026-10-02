package ru.samorez.alreaderxsync.data

import android.net.Uri

/**
 * Запись о файле или директории, полученная при обходе дерева документов.
 *
 * @param uri            URI документа (content://...)
 * @param relativePath   Путь относительно корня обхода, без ведущего слэша,
 *                       например "subdir/file.txt"
 * @param size           Размер в байтах (0 для директорий)
 * @param lastModified   Время последнего изменения (millis, 0 если неизвестно)
 * @param isDirectory    Признак директории
 * @param contentHash    SHA-256 содержимого файла (см. ниже)
 */
data class FileEntry(
    val uri: Uri,
    val relativePath: String,
    val size: Long,
    val lastModified: Long,
    val isDirectory: Boolean,
    /**
     * SHA-256 содержимого файла.
     *
     * Заполняется только для «проблемных» файлов, где size
     * не является надёжным показателем изменения содержимого
     * (например, SQLite-базы .db, текстовые настройки .ini, .profile).
     *
     * Для остальных файлов (книги, картинки, скины) остаётся null,
     * чтобы избежать лишнего чтения и подсчёта хэша.
     *
     * Используется в FileFingerprint.quickCompare: если у обоих
     * файлов contentHash != null — сравниваются хэши, иначе — size.
     */
    val contentHash: String? = null
)