package ru.samorez.alreaderxsync

import org.junit.Test
import ru.samorez.alreaderxsync.data.TransportConfig
import ru.samorez.alreaderxsync.data.TransportMode
import kotlin.test.assertEquals

class TransportConfigTest {

    @Test
    fun `default is AUTO`() {
        val config = TransportConfig()
        assertEquals(TransportMode.AUTO, config.transportMode)
    }

    @Test
    fun `AUTO keeps wifi settings`() {
        val config = TransportConfig(
            transportMode = TransportMode.AUTO,
            wifiLanMaxRetries = 5,
            wifiLanRetryDelayMs = 2000L,
            bluetoothMaxRetries = 2,
            bluetoothRetryDelayMs = 500L
        )
        assertEquals(5, config.wifiLanMaxRetries)
        assertEquals(2000L, config.wifiLanRetryDelayMs)
        assertEquals(2, config.bluetoothMaxRetries)
        assertEquals(500L, config.bluetoothRetryDelayMs)
    }

    @Test
    fun `BLUETOOTH_ONLY preserves all fields`() {
        val config = TransportConfig(
            transportMode = TransportMode.BLUETOOTH_ONLY,
            wifiLanTimeoutMs = 1_000L,
            bluetoothTimeoutMs = 10_000L
        )
        assertEquals(TransportMode.BLUETOOTH_ONLY, config.transportMode)
        assertEquals(1_000L, config.wifiLanTimeoutMs)
        assertEquals(10_000L, config.bluetoothTimeoutMs)
    }

    @Test
    fun `copy changes only specified fields`() {
        val original = TransportConfig()
        val copy = original.copy(transportMode = TransportMode.BLUETOOTH_ONLY)
        assertEquals(TransportMode.BLUETOOTH_ONLY, copy.transportMode)
        assertEquals(original.wifiLanMaxRetries, copy.wifiLanMaxRetries)
    }
}