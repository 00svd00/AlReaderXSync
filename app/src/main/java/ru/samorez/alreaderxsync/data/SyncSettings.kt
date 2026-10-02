package ru.samorez.alreaderxsync.data

/**
 * Настройки синхронизации, выбранные пользователем.
 *
 * Является обёрткой над [TransportConfig] и содержит те же поля:
 * режим выбора транспорта, таймауты и retry-параметры.
 *
 * Retry-параметры используются WifiLanTransport / BluetoothTransport
 * при клиентском подключении. Дефолт — 3 попытки × 1 сек.
 *
 * ВАЖНО: Wi-Fi Direct исключён из модели, потому что
 * WifiDirectTransport пока не реализован (заглушка).
 */
data class SyncSettings(
    val transportMode: TransportMode = TransportMode.AUTO,
    val wifiLanTimeoutMs: Long = 10_000L,
    val bluetoothTimeoutMs: Long = 60_000L,
    val wifiLanMaxRetries: Int = 3,
    val wifiLanRetryDelayMs: Long = 1_000L,
    val bluetoothMaxRetries: Int = 3,
    val bluetoothRetryDelayMs: Long = 1_000L
) {
    /**
     * Преобразует настройки в [TransportConfig].
     * Wi-Fi Direct не передаётся — он не используется.
     */
    fun toTransportConfig(): TransportConfig = TransportConfig(
        transportMode = transportMode,
        wifiLanTimeoutMs = wifiLanTimeoutMs,
        bluetoothTimeoutMs = bluetoothTimeoutMs,
        wifiLanMaxRetries = wifiLanMaxRetries,
        wifiLanRetryDelayMs = wifiLanRetryDelayMs,
        bluetoothMaxRetries = bluetoothMaxRetries,
        bluetoothRetryDelayMs = bluetoothRetryDelayMs
    )

    companion object {
        /**
         * Обратное преобразование: [TransportConfig] → [SyncSettings].
         * Полезно при чтении настроек из SharedPreferences (SettingsRepository)
         * и при инициализации UI-состояния.
         */
        fun fromTransportConfig(config: TransportConfig): SyncSettings =
            SyncSettings(
                transportMode = config.transportMode,
                wifiLanTimeoutMs = config.wifiLanTimeoutMs,
                bluetoothTimeoutMs = config.bluetoothTimeoutMs,
                wifiLanMaxRetries = config.wifiLanMaxRetries,
                wifiLanRetryDelayMs = config.wifiLanRetryDelayMs,
                bluetoothMaxRetries = config.bluetoothMaxRetries,
                bluetoothRetryDelayMs = config.bluetoothRetryDelayMs
            )
    }
}