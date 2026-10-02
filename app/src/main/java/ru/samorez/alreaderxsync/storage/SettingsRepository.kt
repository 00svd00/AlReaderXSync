package ru.samorez.alreaderxsync.storage

import android.content.Context
import android.content.SharedPreferences
import ru.samorez.alreaderxsync.data.DeviceRole
import ru.samorez.alreaderxsync.data.SyncDirection
import ru.samorez.alreaderxsync.data.SyncMode
import ru.samorez.alreaderxsync.data.TransportConfig
import ru.samorez.alreaderxsync.data.TransportMode

/**
 * Репозиторий настроек приложения.
 *
 * Использует единый файл SharedPreferences "sync_settings",
 * тот же, что и [DirectoryAccessManager].
 *
 * Все методы синхронные, поскольку SharedPreferences работает быстро.
 */
class SettingsRepository(private val context: Context) {

    companion object {
        /** Имя файла SharedPreferences (должно совпадать с DirectoryAccessManager). */
        const val PREFS_NAME = "sync_settings"

        /** Книги лежат внутри каталога AlReader. */
        const val KEY_BOOKS_INSIDE_ALREADER = "books_inside_alreader"

        /** Режим транспорта: AUTO / BLUETOOTH_ONLY. */
        const val KEY_TRANSPORT_MODE = "transport_mode"

        /** Таймаут Wi-Fi LAN в миллисекундах. */
        const val KEY_WIFI_LAN_TIMEOUT = "wifi_lan_timeout_ms"

        /** Таймаут Bluetooth в миллисекундах. */
        const val KEY_BLUETOOTH_TIMEOUT = "bluetooth_timeout_ms"

        /** Максимальное число повторов для Wi-Fi LAN. */
        const val KEY_WIFI_LAN_MAX_RETRIES = "wifi_lan_max_retries"

        /** Задержка между повторами Wi-Fi LAN в миллисекундах. */
        const val KEY_WIFI_LAN_RETRY_DELAY = "wifi_lan_retry_delay_ms"

        /** Максимальное число повторов для Bluetooth. */
        const val KEY_BLUETOOTH_MAX_RETRIES = "bluetooth_max_retries"

        /** Задержка между повторами Bluetooth в миллисекундах. */
        const val KEY_BLUETOOTH_RETRY_DELAY = "bluetooth_retry_delay_ms"

        /** Показывать предупреждение об AlReader. */
        const val KEY_SHOW_ALREADER_WARNING = "show_alreader_warning"

        /** Роль устройства: "phone" или "reader". */
        const val KEY_ROLE = "device_role"

        /** MAC-адрес последнего устройства, с которым синхронизировались. */
        const val KEY_LAST_PEER_MAC = "last_peer_mac"

        /** Человекочитаемое имя последнего устройства. */
        const val KEY_LAST_PEER_NAME = "last_peer_name"

        /** Режим синхронизации (имя значения enum SyncMode). */
        const val KEY_SYNC_MODE = "sync_mode"

        /** Направление синхронизации (имя значения enum SyncDirection). */
        const val KEY_SYNC_DIRECTION = "sync_direction"

        // --- Значения по умолчанию ---
        private const val DEFAULT_WIFI_LAN_TIMEOUT = 5_000L
        private const val DEFAULT_BLUETOOTH_TIMEOUT = 10_000L
        private const val DEFAULT_WIFI_LAN_MAX_RETRIES = 3
        private const val DEFAULT_WIFI_LAN_RETRY_DELAY = 1_000L
        private const val DEFAULT_BLUETOOTH_MAX_RETRIES = 3
        private const val DEFAULT_BLUETOOTH_RETRY_DELAY = 1_000L
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // --- Книги внутри AlReader ---

    fun isBooksInsideAlreader(): Boolean =
        prefs.getBoolean(KEY_BOOKS_INSIDE_ALREADER, true)

    fun setBooksInsideAlreader(value: Boolean) {
        prefs.edit().putBoolean(KEY_BOOKS_INSIDE_ALREADER, value).apply()
    }

    // --- Конфигурация транспортов ---

    /**
     * Читает конфигурацию транспортов из SharedPreferences.
     * Отсутствующие ключи заменяются значениями по умолчанию.
     *
     * Wi-Fi Direct больше не поддерживается — соответствующие
     * ключи и поля TransportConfig удалены.
     */
    fun getTransportConfig(): TransportConfig {
        val modeName = prefs.getString(
            KEY_TRANSPORT_MODE,
            TransportMode.AUTO.name
        ) ?: TransportMode.AUTO.name

        val mode = try {
            TransportMode.valueOf(modeName)
        } catch (e: IllegalArgumentException) {
            // Если в SharedPreferences оказалось неизвестное значение —
            // используем AUTO.
            TransportMode.AUTO
        }

        return TransportConfig(
            transportMode = mode,
            wifiLanTimeoutMs = prefs.getLong(
                KEY_WIFI_LAN_TIMEOUT, DEFAULT_WIFI_LAN_TIMEOUT
            ),
            bluetoothTimeoutMs = prefs.getLong(
                KEY_BLUETOOTH_TIMEOUT, DEFAULT_BLUETOOTH_TIMEOUT
            ),
            wifiLanMaxRetries = prefs.getInt(
                KEY_WIFI_LAN_MAX_RETRIES, DEFAULT_WIFI_LAN_MAX_RETRIES
            ),
            wifiLanRetryDelayMs = prefs.getLong(
                KEY_WIFI_LAN_RETRY_DELAY, DEFAULT_WIFI_LAN_RETRY_DELAY
            ),
            bluetoothMaxRetries = prefs.getInt(
                KEY_BLUETOOTH_MAX_RETRIES, DEFAULT_BLUETOOTH_MAX_RETRIES
            ),
            bluetoothRetryDelayMs = prefs.getLong(
                KEY_BLUETOOTH_RETRY_DELAY, DEFAULT_BLUETOOTH_RETRY_DELAY
            )
        )
    }

    /**
     * Сохраняет конфигурацию транспортов в SharedPreferences.
     */
    fun saveTransportConfig(config: TransportConfig) {
        prefs.edit().apply {
            putString(KEY_TRANSPORT_MODE, config.transportMode.name)
            putLong(KEY_WIFI_LAN_TIMEOUT, config.wifiLanTimeoutMs)
            putLong(KEY_BLUETOOTH_TIMEOUT, config.bluetoothTimeoutMs)
            putInt(KEY_WIFI_LAN_MAX_RETRIES, config.wifiLanMaxRetries)
            putLong(KEY_WIFI_LAN_RETRY_DELAY, config.wifiLanRetryDelayMs)
            putInt(KEY_BLUETOOTH_MAX_RETRIES, config.bluetoothMaxRetries)
            putLong(KEY_BLUETOOTH_RETRY_DELAY, config.bluetoothRetryDelayMs)
        }.apply()
    }

    // --- Предупреждение об AlReader ---

    fun shouldShowAlreaderWarning(): Boolean =
        prefs.getBoolean(KEY_SHOW_ALREADER_WARNING, true)

    fun setShowAlreaderWarning(value: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_ALREADER_WARNING, value).apply()
    }

    // --- Роль устройства ---

    fun getRole(): DeviceRole? {
        return when (prefs.getString(KEY_ROLE, null)) {
            "phone" -> DeviceRole.PHONE
            "reader" -> DeviceRole.READER
            else -> null
        }
    }

    fun setRole(role: DeviceRole?) {
        val editor = prefs.edit()
        if (role == null) {
            editor.remove(KEY_ROLE)
        } else {
            val value = when (role) {
                DeviceRole.PHONE -> "phone"
                DeviceRole.READER -> "reader"
            }
            editor.putString(KEY_ROLE, value)
        }
        editor.apply()
    }

    // --- Последнее устройство-партнёр ---

    fun getLastPeerMac(): String? =
        prefs.getString(KEY_LAST_PEER_MAC, null)

    fun setLastPeerMac(mac: String?) {
        prefs.edit().apply {
            if (mac == null) remove(KEY_LAST_PEER_MAC)
            else putString(KEY_LAST_PEER_MAC, mac)
        }.apply()
    }

    fun getLastPeerName(): String? =
        prefs.getString(KEY_LAST_PEER_NAME, null)

    fun setLastPeerName(name: String?) {
        prefs.edit().apply {
            if (name == null) remove(KEY_LAST_PEER_NAME)
            else putString(KEY_LAST_PEER_NAME, name)
        }.apply()
    }

    // --- Режим и направление синхронизации ---

    fun getSyncMode(): SyncMode? {
        val name = prefs.getString(KEY_SYNC_MODE, null) ?: return null
        return try {
            SyncMode.valueOf(name)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    fun setSyncMode(mode: SyncMode?) {
        prefs.edit().apply {
            if (mode == null) remove(KEY_SYNC_MODE)
            else putString(KEY_SYNC_MODE, mode.name)
        }.apply()
    }

    fun getSyncDirection(): SyncDirection? {
        val name = prefs.getString(KEY_SYNC_DIRECTION, null) ?: return null
        return try {
            SyncDirection.valueOf(name)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    fun setSyncDirection(direction: SyncDirection?) {
        prefs.edit().apply {
            if (direction == null) remove(KEY_SYNC_DIRECTION)
            else putString(KEY_SYNC_DIRECTION, direction.name)
        }.apply()
    }

    // --- Сброс ---

    /**
     * Очищает все настройки приложения, кроме URI каталогов,
     * которыми управляет [DirectoryAccessManager].
     */
    fun resetAll() {
        prefs.edit().apply {
            remove(KEY_BOOKS_INSIDE_ALREADER)
            remove(KEY_TRANSPORT_MODE)
            remove(KEY_WIFI_LAN_TIMEOUT)
            remove(KEY_BLUETOOTH_TIMEOUT)
            remove(KEY_WIFI_LAN_MAX_RETRIES)
            remove(KEY_WIFI_LAN_RETRY_DELAY)
            remove(KEY_BLUETOOTH_MAX_RETRIES)
            remove(KEY_BLUETOOTH_RETRY_DELAY)
            remove(KEY_SHOW_ALREADER_WARNING)
            remove(KEY_ROLE)
            remove(KEY_LAST_PEER_MAC)
            remove(KEY_LAST_PEER_NAME)
            remove(KEY_SYNC_MODE)
            remove(KEY_SYNC_DIRECTION)
        }.apply()
    }
}