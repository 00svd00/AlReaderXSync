package ru.samorez.alreaderxsync

import android.net.Uri
import io.mockk.mockk
import org.junit.Test
import ru.samorez.alreaderxsync.data.FileEntry
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class FileEntryTest {

    // Uri.EMPTY в unit-тестах без Robolectric равен null,
    // поэтому используем MockK-мок, чтобы пройти проверку non-null в конструкторе FileEntry.
    private val fakeUri: Uri = mockk(relaxed = true)

    // ============================================================
    // contentHash по умолчанию
    // ============================================================

    @Test
    fun `default contentHash is null`() {
        val entry = FileEntry(
            uri = fakeUri,
            relativePath = "a.txt",
            size = 100,
            lastModified = 1000,
            isDirectory = false
        )
        assertNull(entry.contentHash)
    }

    // ============================================================
    // contentHash можно задать явно
    // ============================================================

    @Test
    fun `contentHash can be set`() {
        val entry = FileEntry(
            uri = fakeUri,
            relativePath = "a.db",
            size = 100,
            lastModified = 1000,
            isDirectory = false,
            contentHash = "abcd1234"
        )
        assertEquals("abcd1234", entry.contentHash)
    }

    // ============================================================
    // equals учитывает contentHash
    // ============================================================

    @Test
    fun `equality includes contentHash`() {
        val a = FileEntry(
            uri = fakeUri,
            relativePath = "a.db",
            size = 100,
            lastModified = 1000,
            isDirectory = false,
            contentHash = "aaaa"
        )
        val b = a.copy(contentHash = "bbbb")

        assertNotEquals(a, b)
    }

    // ============================================================
    // copy сохраняет contentHash
    // ============================================================

    @Test
    fun `copy preserves contentHash`() {
        val original = FileEntry(
            uri = fakeUri,
            relativePath = "a.db",
            size = 100,
            lastModified = 1000,
            isDirectory = false,
            contentHash = "aaaa"
        )
        val copy = original.copy(size = 200)

        assertEquals("aaaa", copy.contentHash)
        assertEquals(200, copy.size)
    }
}