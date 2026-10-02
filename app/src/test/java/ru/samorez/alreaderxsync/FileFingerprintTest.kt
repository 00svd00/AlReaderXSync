package ru.samorez.alreaderxsync

import android.net.Uri
import io.mockk.mockk
import org.junit.Test
import ru.samorez.alreaderxsync.data.FileEntry
import ru.samorez.alreaderxsync.sync.FileFingerprint
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileFingerprintTest {

    // Uri.EMPTY в unit-тестах без Robolectric равен null,
    // поэтому используем MockK-мок, чтобы пройти проверку non-null в конструкторе FileEntry.
    private val fakeUri: Uri = mockk(relaxed = true)

    // ============================================================
    // Сравнение по размеру
    // ============================================================

    @Test
    fun `different size returns false`() {
        val a = file("a.txt", 100)
        val b = file("a.txt", 200)

        assertFalse(FileFingerprint.quickCompare(a, b))
    }

    @Test
    fun `same size no hashes returns true`() {
        val a = file("a.txt", 100)
        val b = file("a.txt", 100)

        assertTrue(FileFingerprint.quickCompare(a, b))
    }

    // ============================================================
    // Сравнение по хэшу
    // ============================================================

    @Test
    fun `same size same hashes returns true`() {
        val a = file("a.db", 100, hash = "aaaa")
        val b = file("a.db", 100, hash = "aaaa")

        assertTrue(FileFingerprint.quickCompare(a, b))
    }

    @Test
    fun `same size different hashes returns false`() {
        val a = file("a.db", 100, hash = "aaaa")
        val b = file("a.db", 100, hash = "bbbb")

        assertFalse(FileFingerprint.quickCompare(a, b))
    }

    @Test
    fun `same size hash only in one returns true (fallback to size)`() {
        val a = file("a.db", 100, hash = "aaaa")
        val b = file("a.db", 100, hash = null)

        assertTrue(FileFingerprint.quickCompare(a, b))
    }

    @Test
    fun `same size hash only in both null returns true`() {
        val a = file("a.txt", 100, hash = null)
        val b = file("a.txt", 100, hash = null)

        assertTrue(FileFingerprint.quickCompare(a, b))
    }

    // ============================================================
    // Разный размер и хэш
    // ============================================================

    @Test
    fun `different size and different hashes returns false`() {
        val a = file("a.db", 100, hash = "aaaa")
        val b = file("a.db", 200, hash = "bbbb")

        assertFalse(FileFingerprint.quickCompare(a, b))
    }

    @Test
    fun `different size but same hash returns false (size wins)`() {
        // Теоретически невозможно, но проверяем порядок проверок:
        // size проверяется раньше хэша.
        val a = file("a.db", 100, hash = "aaaa")
        val b = file("a.db", 200, hash = "aaaa")

        assertFalse(FileFingerprint.quickCompare(a, b))
    }

    // ============================================================
    // Helpers
    // ============================================================

    private fun file(
        path: String,
        size: Long,
        hash: String? = null
    ): FileEntry = FileEntry(
        uri = fakeUri,
        relativePath = path,
        size = size,
        lastModified = 0L,
        isDirectory = false,
        contentHash = hash
    )
}