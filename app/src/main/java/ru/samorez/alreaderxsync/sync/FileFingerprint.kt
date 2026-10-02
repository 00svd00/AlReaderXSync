// ============================================================
// Файл: sync/FileFingerprint.kt
// Пакет: ru.samorez.alreaderxsync.sync
// ============================================================
package ru.samorez.alreaderxsync.sync

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.samorez.alreaderxsync.data.FileEntry
import java.io.IOException
import java.security.MessageDigest

/**
 * Утилита для быстрого и точного сравнения файлов.
 *
 * [quickCompare] выполняет дешёвое сравнение по размеру с опциональной
 * проверкой contentHash, а [sha256] вычисляет криптографический хеш
 * содержимого файла.
 */
object FileFingerprint {

    private const val TAG = "FileFingerprint"

    /**
     * Быстрое сравнение двух записей.
     *
     * Логика:
     *  1. Если размеры различаются — файлы точно разные.
     *  2. Если размеры совпадают, но у обоих файлов есть contentHash
     *     (например, для .db, .ini, .profile) — сравниваются хэши.
     *  3. Иначе — файлы считаются идентичными.
     *
     * mtime НЕ используется, потому что при записи через SAF
     * ([android.content.ContentResolver.openOutputStream]) mtime
     * устанавливается в текущее время, а не сохраняется из источника.
     * Это приводило к ложным срабатываниям: даже только что скопированный
     * файл имел mtime, равный моменту синхронизации, и quickCompare
     * всегда возвращал false, из-за чего все файлы передавались заново.
     *
     * @return true, если записи считаются идентичными.
     */
    fun quickCompare(a: FileEntry, b: FileEntry): Boolean {
        // Разный размер — точно разные файлы.
        if (a.size != b.size) return false

        // Если у обоих есть хэши — сравниваем их.
        if (a.contentHash != null && b.contentHash != null) {
            val equal = a.contentHash == b.contentHash
            if (!equal) {
                Log.d(
                    TAG,
                    "quickCompare: hash differs for '${a.relativePath}': " +
                            "a=${a.contentHash}, b=${b.contentHash}"
                )
            }
            return equal
        }

        // Размер совпадает, хэши недоступны — считаем идентичными.
        return true
    }

    /**
     * Вычисляет SHA-256 хеш содержимого файла по его [uri].
     *
     * Чтение выполняется через [android.content.ContentResolver] на
     * [Dispatchers.IO], чтобы не блокировать главный поток.
     *
     * Может использоваться для точного сравнения содержимого,
     * когда быстрого сравнения по размеру недостаточно, а также для
     * заполнения поля [FileEntry.contentHash].
     *
     * @return hex-строка (нижний регистр) длиной 64 символа.
     * @throws IOException если не удалось открыть входной поток.
     */
    suspend fun sha256(context: Context, uri: Uri): String {
        return withContext(Dispatchers.IO) {
            val digest = MessageDigest.getInstance("SHA-256")
            context.contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } > 0) {
                    digest.update(buffer, 0, read)
                }
            } ?: throw IOException("Cannot open input stream for $uri")

            digest.digest().joinToString("") {
                "%02x".format(it)
            }
        }
    }
}