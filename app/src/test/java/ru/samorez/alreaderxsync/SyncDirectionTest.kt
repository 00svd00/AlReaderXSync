package ru.samorez.alreaderxsync

import org.junit.Test
import ru.samorez.alreaderxsync.data.DeviceRole
import ru.samorez.alreaderxsync.data.SyncDirection
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DirectionLogicTest {

    // Скопируйте логику сюда, либо вынесите её из SyncRepository
    private fun isSource(
        direction: SyncDirection,
        localRole: DeviceRole
    ): Boolean = when (direction) {
        SyncDirection.PHONE_TO_READER -> localRole == DeviceRole.PHONE
        SyncDirection.READER_TO_PHONE -> localRole == DeviceRole.READER
    }

    @Test
    fun `phone is source in PHONE_TO_READER`() {
        assertTrue(isSource(SyncDirection.PHONE_TO_READER, DeviceRole.PHONE))
    }

    @Test
    fun `reader is not source in PHONE_TO_READER`() {
        assertFalse(isSource(SyncDirection.PHONE_TO_READER, DeviceRole.READER))
    }

    @Test
    fun `reader is source in READER_TO_PHONE`() {
        assertTrue(isSource(SyncDirection.READER_TO_PHONE, DeviceRole.READER))
    }

    @Test
    fun `phone is not source in READER_TO_PHONE`() {
        assertFalse(isSource(SyncDirection.READER_TO_PHONE, DeviceRole.PHONE))
    }
}