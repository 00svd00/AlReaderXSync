package ru.samorez.alreaderxsync.data

/**
 * Роль устройства в процессе синхронизации.
 *
 * PHONE  — телефон (ведущее устройство, обычно инициирует обмен).
 * READER — читалка (AlReader X / e-ink устройство).
 */
enum class DeviceRole {
    PHONE,
    READER
}