package ru.samorez.alreaderxsync.transport

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.delay
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Утилита для работы с Wi-Fi.
 *
 * Предоставляет методы для проверки/включения Wi-Fi, ожидания получения IP-адреса,
 * получения локального IP и проверки принадлежности двух адресов одной подсети.
 */
class WifiHelper(private val context: Context) {

    /**
     * Проверяет, включён ли Wi-Fi.
     *
     * - На API < 29 используется [WifiManager.isWifiEnabled].
     * - На API >= 29 используется [ConnectivityManager] + [NetworkCapabilities]
     *   (проверяется наличие транспорта TRANSPORT_WIFI).
     */
    fun isWifiEnabled(): Boolean {
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // До Android 10 (API 29) можно напрямую спросить WifiManager
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiManager?.isWifiEnabled == true
        } else {
            // Начиная с Android 10 прямое чтение состояния Wi-Fi ограничено —
            // используем ConnectivityManager и NetworkCapabilities
            val connectivityManager = context.applicationContext
                .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val network = connectivityManager?.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        }
    }

    /**
     * Гарантирует, что Wi-Fi включён.
     *
     * Возвращает `true`, если Wi-Fi уже включён (или удалось включить на старых API).
     * Возвращает `false`, если требуется действие пользователя (API >= 29) —
     * в этом случае открывается системная панель настроек Wi-Fi.
     *
     * @param activity активити для запуска системного диалога (может быть null).
     */
    suspend fun ensureWifiEnabled(activity: Activity?): Boolean {
        // Если уже включён — ничего делать не нужно
        if (isWifiEnabled()) return true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // На Android 10+ приложение не может включить Wi-Fi программно.
            // Открываем системную панель, чтобы пользователь включил вручную.
            activity?.startActivity(Intent(Settings.Panel.ACTION_WIFI))
            return false
        } else {
            // На старых API можно включить Wi-Fi через WifiManager
            @Suppress("DEPRECATION")
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return false

            @Suppress("DEPRECATION")
            wifiManager.isWifiEnabled = true

            // Ждём до 10 секунд, пока Wi-Fi действительно включится
            val deadline = System.currentTimeMillis() + 10_000L
            while (System.currentTimeMillis() < deadline) {
                if (isWifiEnabled()) return true
                delay(500L)
            }
            return isWifiEnabled()
        }
    }

    /**
     * Ожидает получения ненулевого IP-адреса Wi-Fi.
     *
     * Опрашивает [WifiManager.getConnectionInfo] каждые 500 мс.
     * Возвращает строку с IP-адресом или `null`, если по таймауту адрес так и не был получен.
     *
     * @param timeoutMs максимальное время ожидания в миллисекундах.
     */
    suspend fun waitForWifiIp(timeoutMs: Long = 10_000L): String? {
        val wifiManager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return null

        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            @Suppress("DEPRECATION")
            val ipInt = wifiManager.connectionInfo?.ipAddress ?: 0
            if (ipInt != 0) {
                return intToIp(ipInt)
            }
            else{
                return "127.0.0.1"
            }
            delay(500L)
        }
        return null
    }

    /**
     * Возвращает локальный IPv4-адрес Wi-Fi интерфейса.
     *
     * Перебирает все сетевые интерфейсы и ищет первый подходящий IPv4-адрес,
     * исключая loopback и link-local (169.254.x.x).
     */
    fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.asSequence() ?: return "127.0.0.1"

            for (networkInterface in interfaces) {
                // Пропускаем выключенные, loopback и виртуальные интерфейсы
                if (!networkInterface.isUp || networkInterface.isLoopback || networkInterface.isVirtual) continue

                // Ищем первый подходящий IPv4 адрес
                val address = networkInterface.inetAddresses.asSequence().firstOrNull { inetAddress ->
                    !inetAddress.isLoopbackAddress &&
                            inetAddress is Inet4Address &&
                            !inetAddress.hostAddress.orEmpty().startsWith("169.254.")
                }

                if (address != null) {
                    return address.hostAddress ?: "127.0.0.1"
                }
            }
        } catch (_: Exception) {
            // Игнорируем ошибки перечисления интерфейсов
        }

        // Если ничего не нашли или произошла ошибка — возвращаем localhost
        return "127.0.0.1"
    }

    /**
     * Проверяет, находятся ли два IPv4-адреса в одной подсети.
     *
     * Сравниваются первые [prefixLength] бит адресов.
     *
     * @param ip1 первый IPv4-адрес в виде строки.
     * @param ip2 второй IPv4-адрес в виде строки.
     * @param prefixLength длина префикса подсети (по умолчанию 24).
     */
    fun isSameSubnet(ip1: String, ip2: String, prefixLength: Int = 24): Boolean {
        val addr1 = ipToInt(ip1) ?: return false
        val addr2 = ipToInt(ip2) ?: return false

        // Ограничиваем длину префикса допустимым диапазоном
        val safePrefix = prefixLength.coerceIn(0, 32)

        // Формируем маску подсети: safePrefix старших бит = 1
        val mask = if (safePrefix == 0) 0 else (-1 shl (32 - safePrefix))

        return (addr1 and mask) == (addr2 and mask)
    }

    /**
     * Преобразует IPv4-адрес из int (little-endian, как в WifiManager) в строку.
     *
     * WifiManager возвращает адрес в порядке little-endian, поэтому байты
     * нужно выводить в обратном порядке.
     */
    private fun intToIp(ip: Int): String {
        return "${ip and 0xFF}.${(ip shr 8) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 24) and 0xFF}"
    }

    /**
     * Вспомогательный метод: преобразует строку IPv4 в Int (big-endian).
     * Возвращает null при некорректном формате.
     */
    private fun ipToInt(ip: String): Int? {
        val parts = ip.split(".")
        if (parts.size != 4) return null
        var result = 0
        for (part in parts) {
            val octet = part.toIntOrNull() ?: return null
            if (octet !in 0..255) return null
            result = (result shl 8) or octet
        }
        return result
    }
}