package ru.samorez.alreaderxsync

import android.net.Uri
import io.mockk.mockk
import org.junit.Test
import ru.samorez.alreaderxsync.data.FileEntry
import ru.samorez.alreaderxsync.data.SyncMode
import ru.samorez.alreaderxsync.sync.SyncPlanner
import kotlin.test.assertEquals

class SyncPlannerTest {

    private val planner = SyncPlanner()

    // Uri.EMPTY в unit-тестах без Robolectric равен null,
    // поэтому используем MockK-мок, чтобы пройти проверку non-null в конструкторе FileEntry.
    private val fakeUri: Uri = mockk(relaxed = true)

    // ============================================================
    // FULL_REPLACE
    // ============================================================

    @Test
    fun `FULL_REPLACE transfers all when target empty`() {
        val source = listOf(
            file("a.txt", 100),
            file("b.txt", 200)
        )
        val target = emptyList<FileEntry>()

        val plan = planner.buildPlan(source, target, SyncMode.FULL_REPLACE)

        assertEquals(2, plan.toTransfer.size)
        assertEquals(0, plan.toDelete.size)
    }

    @Test
    fun `FULL_REPLACE transfers nothing when source and target identical`() {
        val source = listOf(
            file("a.txt", 100),
            file("b.txt", 200)
        )
        val target = listOf(
            file("a.txt", 100),
            file("b.txt", 200)
        )

        val plan = planner.buildPlan(source, target, SyncMode.FULL_REPLACE)

        assertEquals(0, plan.toTransfer.size)
        assertEquals(0, plan.toDelete.size)
    }

    @Test
    fun `FULL_REPLACE transfers only missing files`() {
        val source = listOf(
            file("a.txt", 100),
            file("b.txt", 200),
            file("c.txt", 300)
        )
        val target = listOf(
            file("a.txt", 100)
        )

        val plan = planner.buildPlan(source, target, SyncMode.FULL_REPLACE)

        assertEquals(2, plan.toTransfer.size)
        assertEquals(
            setOf("b.txt", "c.txt"),
            plan.toTransfer.map { it.relativePath }.toSet()
        )
        assertEquals(0, plan.toDelete.size)
    }

    @Test
    fun `FULL_REPLACE transfers differing files`() {
        val source = listOf(
            file("a.txt", 100),
            file("b.txt", 200)
        )
        val target = listOf(
            file("a.txt", 100),  // идентичен
            file("b.txt", 150)   // разный размер
        )

        val plan = planner.buildPlan(source, target, SyncMode.FULL_REPLACE)

        assertEquals(1, plan.toTransfer.size)
        assertEquals("b.txt", plan.toTransfer[0].relativePath)
    }

    @Test
    fun `FULL_REPLACE deletes files not in source`() {
        val source = listOf(
            file("a.txt", 100)
        )
        val target = listOf(
            file("a.txt", 100),
            file("b.txt", 200)   // лишний
        )

        val plan = planner.buildPlan(source, target, SyncMode.FULL_REPLACE)

        assertEquals(0, plan.toTransfer.size)
        assertEquals(1, plan.toDelete.size)
        assertEquals("b.txt", plan.toDelete[0].relativePath)
    }

    @Test
    fun `FULL_REPLACE combined - transfer missing, delete extra`() {
        val source = listOf(
            file("a.txt", 100),
            file("c.txt", 300)   // новый
        )
        val target = listOf(
            file("a.txt", 100),
            file("b.txt", 200)   // лишний
        )

        val plan = planner.buildPlan(source, target, SyncMode.FULL_REPLACE)

        assertEquals(1, plan.toTransfer.size)
        assertEquals("c.txt", plan.toTransfer[0].relativePath)
        assertEquals(1, plan.toDelete.size)
        assertEquals("b.txt", plan.toDelete[0].relativePath)
    }

    // ============================================================
    // MERGE_NEWEST_WINS
    // ============================================================

    @Test
    fun `MERGE_NEWEST_WINS transfers nothing when identical`() {
        val source = listOf(file("a.txt", 100))
        val target = listOf(file("a.txt", 100))

        val plan = planner.buildPlan(source, target, SyncMode.MERGE_NEWEST_WINS)

        assertEquals(0, plan.toTransfer.size)
        assertEquals(0, plan.toDelete.size)
    }

    @Test
    fun `MERGE_NEWEST_WINS never deletes`() {
        val source = listOf(file("a.txt", 100))
        val target = listOf(
            file("a.txt", 100),
            file("b.txt", 200)   // лишний
        )

        val plan = planner.buildPlan(source, target, SyncMode.MERGE_NEWEST_WINS)

        assertEquals(0, plan.toTransfer.size)
        assertEquals(0, plan.toDelete.size)
    }

    @Test
    fun `MERGE_NEWEST_WINS transfers missing`() {
        val source = listOf(
            file("a.txt", 100),
            file("b.txt", 200)
        )
        val target = listOf(file("a.txt", 100))

        val plan = planner.buildPlan(source, target, SyncMode.MERGE_NEWEST_WINS)

        assertEquals(1, plan.toTransfer.size)
        assertEquals("b.txt", plan.toTransfer[0].relativePath)
    }

    // ============================================================
    // contentHash
    // ============================================================

    @Test
    fun `same size but different hash means transfer`() {   // было: -> transfer
        val source = listOf(
            file("bookmarks.db", 100, hash = "aaaa")
        )
        val target = listOf(
            file("bookmarks.db", 100, hash = "bbbb")
        )

        val plan = planner.buildPlan(source, target, SyncMode.MERGE_NEWEST_WINS)

        assertEquals(1, plan.toTransfer.size)
    }

    @Test
    fun `same size and same hash means no transfer`() {     // было: -> no transfer
        val source = listOf(
            file("bookmarks.db", 100, hash = "aaaa")
        )
        val target = listOf(
            file("bookmarks.db", 100, hash = "aaaa")
        )

        val plan = planner.buildPlan(source, target, SyncMode.MERGE_NEWEST_WINS)

        assertEquals(0, plan.toTransfer.size)
    }

    @Test
    fun `same size no hash means no transfer (fallback to size)`() { // было: -> no transfer
        val source = listOf(file("book.txt", 100))
        val target = listOf(file("book.txt", 100))

        val plan = planner.buildPlan(source, target, SyncMode.MERGE_NEWEST_WINS)

        assertEquals(0, plan.toTransfer.size)
    }

    // ============================================================
    // Directories
    // ============================================================

    @Test
    fun `directories are ignored`() {
        val source = listOf(
            dir("folder"),
            file("a.txt", 100)
        )
        val target = emptyList<FileEntry>()

        val plan = planner.buildPlan(source, target, SyncMode.FULL_REPLACE)

        assertEquals(1, plan.toTransfer.size)
        assertEquals("a.txt", plan.toTransfer[0].relativePath)
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

    private fun dir(path: String): FileEntry = FileEntry(
        uri = fakeUri,
        relativePath = path,
        size = 0L,
        lastModified = 0L,
        isDirectory = true,
        contentHash = null
    )
}