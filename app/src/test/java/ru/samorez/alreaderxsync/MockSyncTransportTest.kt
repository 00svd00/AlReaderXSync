package ru.samorez.alreaderxsync

import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.samorez.alreaderxsync.data.FileEntry
import java.io.ByteArrayInputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import io.mockk.mockk

class MockSyncTransportTest {

    @Test
    fun `connect stores address and flags`() = runTest {
        val t = MockSyncTransport("test")
        t.connect("AA:BB", isServer = false)
        assertTrue(t.connectCalled)
        assertEquals("AA:BB", t.connectedAddress)
        assertEquals(false, t.connectedIsServer)
    }

    @Test
    fun `connect returns configured result`() = runTest {
        val t = MockSyncTransport("test", connectResult = false)
        assertFalse(t.connect("", isServer = false))
    }

    @Test
    fun `sendFile stores bytes`() = runTest {
        val t = MockSyncTransport("test")
        val entry = FileEntry(
            uri = mockk(relaxed = true),
            relativePath = "a.txt",
            size = 3,
            lastModified = 0,
            isDirectory = false
        )
        t.sendFile(entry, ByteArrayInputStream("abc".toByteArray()))
        assertEquals("abc", String(t.sentFiles["a.txt"]!!))
    }

    @Test
    fun `receiveFile pops in order`() = runTest {
        val t = MockSyncTransport("test")
        val e1 = mockEntry("a.txt")
        val e2 = mockEntry("b.txt")
        t.enqueueFileToReceive(e1, "1".toByteArray())
        t.enqueueFileToReceive(e2, "2".toByteArray())

        val (first, _) = t.receiveFile()
        assertEquals("a.txt", first.relativePath)

        val (second, _) = t.receiveFile()
        assertEquals("b.txt", second.relativePath)
    }

    @Test
    fun `close increments counter`() = runTest {
        val t = MockSyncTransport("test")
        t.close()
        t.close()
        assertTrue(t.closed)
        assertEquals(2, t.closeCalledCount)
    }

    @Test
    fun `reset clears all state`() = runTest {
        val t = MockSyncTransport("test")
        t.connect("x", false)
        t.close()
        t.reset()
        assertFalse(t.connectCalled)
        assertFalse(t.closed)
        assertEquals(0, t.closeCalledCount)
    }

    private fun mockEntry(path: String) = FileEntry(
        uri = mockk(relaxed = true),
        relativePath = path,
        size = 0,
        lastModified = 0,
        isDirectory = false
    )
}