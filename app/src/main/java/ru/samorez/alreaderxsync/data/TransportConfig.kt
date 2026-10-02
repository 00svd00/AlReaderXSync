package ru.samorez.alreaderxsync.data

/**
 * Конфигурация транспортов и параметров подключения.
 *
 * ВНИМАНИЕ: Wi-Fi Direct не реализован в текущей версии.
 * Поля для него намеренно отсутствуют. Класс
 * WifiDirectTransport остаётся заглушкой на будущее
 * и не добавляется в список активных транспортов.
 *
 * Retry-параметры определяют, сколько раз клиент пытается
 * подключиться к серверу через каждый транспорт и с какой
 * задержкой между попытками.
 *
 * @param transportMode режим транспорта: AUTO / BLUETOOTH_ONLY.
 * @param wifiLanTimeoutMs таймаут ожидания подключения Wi-Fi LAN.
 * @param bluetoothTimeoutMs таймаут ожидания подключения Bluetooth.
 * @param wifiLanMaxRetries число попыток Wi-Fi LAN.
 * @param wifiLanRetryDelayMs задержка между попытками Wi-Fi LAN.
 * @param bluetoothMaxRetries число попыток Bluetooth.
 * @param bluetoothRetryDelayMs задержка между попытками Bluetooth.
 */
data class TransportConfig(
    // Режим выбора транспорта
    val transportMode: TransportMode = TransportMode.AUTO,

    // Таймауты операций (мс)
    val wifiLanTimeoutMs: Long = 5_000L,
    val bluetoothTimeoutMs: Long = 10_000L,

    // Retry для клиента
    val wifiLanMaxRetries: Int = 3,
    val wifiLanRetryDelayMs: Long = 1_000L,
    val bluetoothMaxRetries: Int = 3,
    val bluetoothRetryDelayMs: Long = 1_000L
)