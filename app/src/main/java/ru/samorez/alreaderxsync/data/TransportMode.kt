package ru.samorez.alreaderxsync.data

/**
 * Режим выбора транспорта.
 *
 *  AUTO            — Wi-Fi LAN (основной), при неудаче fallback
 *                    на Bluetooth.
 *  BLUETOOTH_ONLY  — сразу Bluetooth, без попытки Wi-Fi LAN.
 *                    Используется, если Wi-Fi LAN в сети
 *                    работает нестабильно (AP isolation,
 *                    мобильная сеть и т.д.).
 */
enum class TransportMode {
    AUTO,
    BLUETOOTH_ONLY
}