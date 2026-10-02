package ru.samorez.alreaderxsync

import kotlinx.serialization.SerializationException
import org.junit.Test
import ru.samorez.alreaderxsync.protocol.FileManifestEntry
import ru.samorez.alreaderxsync.protocol.SyncMessage
import ru.samorez.alreaderxsync.protocol.SyncProtocol
import ru.samorez.alreaderxsync.protocol.TaskSpec
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SyncProtocolTest {

    // SyncProtocol — object, экземпляр не создаётся.
    // Используем прямые вызовы SyncProtocol.encode / SyncProtocol.decode.

    // ============================================================
    // Существующие типы
    // ============================================================

    @Test
    fun `Hello round-trip`() {
        val msg = SyncMessage.Hello("phone", 1)
        val encoded = SyncProtocol.encode(msg)
        val decoded = SyncProtocol.decode(encoded.trim())
        assertEquals(msg, decoded)
    }

    @Test
    fun `Manifest round-trip`() {
        val msg = SyncMessage.Manifest(
            listOf(
                FileManifestEntry("a.txt", 100, 1000),
                FileManifestEntry("b.txt", 200, 2000)
            )
        )
        val encoded = SyncProtocol.encode(msg)
        val decoded = SyncProtocol.decode(encoded.trim())
        assertEquals(msg, decoded)
    }

    @Test
    fun `Complete round-trip`() {
        val msg = SyncMessage.Complete(10, 2)
        val encoded = SyncProtocol.encode(msg)
        val decoded = SyncProtocol.decode(encoded.trim())
        assertEquals(msg, decoded)
    }

    // ============================================================
    // Новые типы (Handshake)
    // ============================================================

    @Test
    fun `HandshakeRequest round-trip`() {
        val msg = SyncMessage.HandshakeRequest(
            role = "phone",
            tasks = listOf(
                TaskSpec("AlReaderX", listOf("Books")),
                TaskSpec("Books", emptyList())
            ),
            mode = "FULL_REPLACE",
            direction = "PHONE_TO_READER",
            phoneIp = "192.168.1.42"
        )
        val encoded = SyncProtocol.encode(msg)
        val decoded = SyncProtocol.decode(encoded.trim())
        assertEquals(msg, decoded)
    }

    @Test
    fun `HandshakeRequest with null phoneIp`() {
        val msg = SyncMessage.HandshakeRequest(
            role = "phone",
            tasks = emptyList(),
            mode = "MERGE_NEWEST_WINS",
            direction = "READER_TO_PHONE",
            phoneIp = null
        )
        val encoded = SyncProtocol.encode(msg)
        val decoded = SyncProtocol.decode(encoded.trim())
        assertEquals(msg, decoded)
    }

    @Test
    fun `HandshakeResponse round-trip`() {
        val msg = SyncMessage.HandshakeResponse(
            readerIp = "192.168.1.10",
            readerPort = 43561,
            accepted = true,
            errorMessage = null
        )
        val encoded = SyncProtocol.encode(msg)
        val decoded = SyncProtocol.decode(encoded.trim())
        assertEquals(msg, decoded)
    }

    @Test
    fun `HandshakeResponse rejected`() {
        val msg = SyncMessage.HandshakeResponse(
            readerIp = "",
            readerPort = 0,
            accepted = false,
            errorMessage = "Cannot get local IP"
        )
        val encoded = SyncProtocol.encode(msg)
        val decoded = SyncProtocol.decode(encoded.trim())
        assertEquals(msg, decoded)
    }

    // ============================================================
    // UTF-8 (кириллица)
    // ============================================================

    @Test
    fun `Manifest with cyrillic paths round-trip`() {
        val msg = SyncMessage.Manifest(
            listOf(
                FileManifestEntry(
                    "Свиридов_О__Системный_практик_VI.fb2.zip",
                    239975, 1700000000000
                ),
                FileManifestEntry(
                    "Вишневский Сергей Викторович/Имеет....fb2.zip",
                    230618, 1700000000000
                )
            )
        )
        val encoded = SyncProtocol.encode(msg)
        val decoded = SyncProtocol.decode(encoded.trim())
        assertEquals(msg, decoded)
    }

    @Test
    fun `encode produces valid UTF-8 bytes`() {
        val msg = SyncMessage.TransferRequest(listOf("книга.fb2"))
        val encoded = SyncProtocol.encode(msg)
        val bytes = encoded.toByteArray(Charsets.UTF_8)
        val restored = String(bytes, Charsets.UTF_8)
        assertEquals(encoded, restored)
    }

    @Test
    fun `decode handles UTF-8 bytes correctly`() {
        val original = SyncMessage.TransferRequest(
            listOf("Плоды проклятого древа.fb2.sdr")
        )
        val encoded = SyncProtocol.encode(original)
        val bytes = encoded.toByteArray(Charsets.UTF_8)
        val line = String(bytes, Charsets.UTF_8).trim()
        val decoded = SyncProtocol.decode(line)
        assertEquals(original, decoded)
    }

    // ============================================================
    // Ошибки
    // ============================================================

    @Test
    fun `decode invalid json throws`() {
        assertFailsWith<SerializationException> {
            SyncProtocol.decode("not a json")
        }
    }

    @Test
    fun `decode empty string throws`() {
        assertFailsWith<SerializationException> {
            SyncProtocol.decode("")
        }
    }

    // ============================================================
    // TaskSpec
    // ============================================================

    @Test
    fun `TaskSpec round-trip`() {
        val spec = TaskSpec("AlReaderX", listOf("Books", "Sync"))
        val encoded = SyncProtocol.encode(
            SyncMessage.HandshakeRequest(
                role = "phone",
                tasks = listOf(spec),
                mode = "FULL_REPLACE",
                direction = "PHONE_TO_READER",
                phoneIp = null
            )
        )
        val decoded = SyncProtocol.decode(encoded.trim())
                as SyncMessage.HandshakeRequest
        assertEquals(spec, decoded.tasks[0])
    }

    @Test
    fun `TaskSpec with empty excludePaths`() {
        val spec = TaskSpec("Books", emptyList())
        val encoded = SyncProtocol.encode(
            SyncMessage.HandshakeRequest(
                role = "phone",
                tasks = listOf(spec),
                mode = "FULL_REPLACE",
                direction = "PHONE_TO_READER",
                phoneIp = null
            )
        )
        val decoded = SyncProtocol.decode(encoded.trim())
                as SyncMessage.HandshakeRequest
        assertEquals(spec, decoded.tasks[0])
        assertTrue(decoded.tasks[0].excludePaths.isEmpty())
    }
    // ============================================================
    // Ping / Pong (буферная синхронизация перед close)
    // ============================================================

    @Test
    fun `Ping round-trip`() {
        val msg = SyncMessage.Ping(nonce = 1234567890L)
        val encoded = SyncProtocol.encode(msg)
        val decoded = SyncProtocol.decode(encoded.trim())
        assertEquals(msg, decoded)
    }

    @Test
    fun `Ping default nonce is currentTimeMillis`() {
        val before = System.currentTimeMillis()
        val msg = SyncMessage.Ping()
        val after = System.currentTimeMillis()
        assertTrue(msg.nonce in before..after)
    }

    @Test
    fun `Pong round-trip`() {
        val msg = SyncMessage.Pong(nonce = 9876543210L)
        val encoded = SyncProtocol.encode(msg)
        val decoded = SyncProtocol.decode(encoded.trim())
        assertEquals(msg, decoded)
    }

    @Test
    fun `Ping and Pong are different types`() {
        val ping = SyncProtocol.encode(SyncMessage.Ping(1L))
        val pong = SyncProtocol.encode(SyncMessage.Pong(1L))
        // Разные JSON, чтобы decode не путал типы
        assertTrue(ping != pong)
        assertTrue(SyncProtocol.decode(ping.trim()) is SyncMessage.Ping)
        assertTrue(SyncProtocol.decode(pong.trim()) is SyncMessage.Pong)
    }
}