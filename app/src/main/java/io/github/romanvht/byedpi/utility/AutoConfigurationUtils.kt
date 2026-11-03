package io.github.romanvht.byedpi.utility

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.RouteInfo
import android.net.InetAddresses
import android.os.Build
import androidx.core.content.edit
import io.github.romanvht.byedpi.data.Mode
import io.github.romanvht.byedpi.data.PrivateDnsState
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Populates only general network fields from the currently active Android network.
 * It deliberately does not inspect, select, or modify ByeDPI command-line/UI strategies.
 */
object AutoConfigurationUtils {
    data class Result(
        val applied: Boolean,
        val message: String
    )

    fun apply(context: Context): Result {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork
            ?: return Result(false, "Активная сеть не обнаружена. Настройки не изменены.")

        val capabilities = manager.getNetworkCapabilities(network)
            ?: return Result(false, "Не удалось получить параметры сети. Настройки не изменены.")

        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return Result(false, "Сеть не сообщает о доступе к интернету. Настройки не изменены.")
        }

        val properties = manager.getLinkProperties(network)
            ?: return Result(false, "Не удалось получить параметры IP/DNS. Настройки не изменены.")

        val preferences = context.getPreferences()
        val mode = preferences.mode()
        val updates = mutableListOf<String>()
        val editor = preferences.edit()

        if (mode == Mode.VPN) {
            when (val privateDns = PrivateDnsUtils.getState(context)) {
                is PrivateDnsState.Configured -> {
                    updates += "DNS: сохранён системный Private DNS (${privateDns.hostname})"
                }
                else -> {
                    val dnsAddress = chooseSystemDns(properties)
                    val dnsValue = dnsAddress?.hostAddress?.substringBefore('%').orEmpty()
                    val currentDns = preferences.getStringNotNull("dns_ip", "1.1.1.1")
                    if (currentDns != dnsValue) {
                        editor.putString("dns_ip", dnsValue)
                        updates += if (dnsValue.isBlank()) {
                            "DNS: системный DNS (адрес роутера/оператора)"
                        } else {
                            "DNS: ${dnsValue} (получен от текущей сети)"
                        }
                    } else {
                        updates += if (dnsValue.isBlank()) "DNS: системный DNS" else "DNS: ${dnsValue} (без изменений)"
                    }
                }
            }

            val ipv6Available = hasUsableIpv6(properties)
            val currentIpv6 = preferences.getBoolean("ipv6_enable", false)
            if (currentIpv6 != ipv6Available) {
                editor.putBoolean("ipv6_enable", ipv6Available)
            }
            updates += "IPv6: ${if (ipv6Available) "доступен, включён" else "нет глобального IPv6-маршрута, выключен"}"
        } else {
            updates += "Режим Proxy: DNS и IPv6-поля VPN не изменялись"
        }

        // The local listening endpoint is not inferred from the public network.
        // Repair it only if the saved UI values are malformed; command-line values remain untouched.
        if (!preferences.getCmdEnable()) {
            val savedHost = preferences.getStringNotNull("byedpi_proxy_ip", "127.0.0.1")
            if (!isNumericIpLiteral(savedHost)) {
                editor.putString("byedpi_proxy_ip", "127.0.0.1")
                updates += "Локальный IP прокси: восстановлено 127.0.0.1 (предыдущее значение некорректно)"
            }

            val savedPort = preferences.getStringNotNull("byedpi_proxy_port", "1080").toIntOrNull()
            if (savedPort == null || savedPort !in 1..65535) {
                editor.putString("byedpi_proxy_port", "1080")
                updates += "Порт прокси: восстановлено 1080 (предыдущее значение некорректно)"
            }
        }

        editor.apply()

        val networkName = networkName(capabilities)
        val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val header = "Сеть: ${networkName}\nИнтернет: ${if (validated) "подтверждён Android" else "не подтверждён Android"}"
        return Result(true, header + "\n" + updates.joinToString("\n"))
    }

    private fun networkName(capabilities: NetworkCapabilities): String = when {
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "мобильная сеть"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN-соединение"
        else -> "другая сеть"
    }

    private fun chooseSystemDns(properties: LinkProperties): InetAddress? {
        val servers = properties.dnsServers
        // Prefer IPv4 for compatibility, while accepting a globally routable IPv6 DNS server.
        return servers.firstOrNull { it is Inet4Address && isPublicUnicast(it) }
            ?: servers.firstOrNull { it is Inet6Address && isPublicUnicast(it) }
    }

    private fun isPublicUnicast(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress ||
            address.isLinkLocalAddress || address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) return false

        if (address is Inet6Address) {
            val first = address.address[0].toInt() and 0xff
            // Exclude IPv6 unique-local addresses (fc00::/7).
            if ((first and 0xfe) == 0xfc) return false
        }

        if (address is Inet4Address) {
            val bytes = address.address.map { it.toInt() and 0xff }
            val first = bytes[0]
            val second = bytes[1]
            // Exclude shared address space (100.64.0.0/10), which is not globally routable.
            if (first == 100 && second in 64..127) return false
        }

        return true
    }

    private fun hasUsableIpv6(properties: LinkProperties): Boolean {
        val hasGlobalAddress = properties.linkAddresses.any { linkAddress ->
            val address = linkAddress.address
            address is Inet6Address && isPublicUnicast(address)
        }
        val hasDefaultRoute = properties.routes.any { route: RouteInfo ->
            route.isDefaultRoute && route.destination.address is Inet6Address
        }
        return hasGlobalAddress && hasDefaultRoute
    }

    private fun isNumericIpLiteral(value: String): Boolean {
        val address = value.trim().removeSurrounding("[", "]")
        if (address.isBlank() || '%' in address) return false

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                InetAddresses.isNumericAddress(address)
            } else if (':' in address) {
                InetAddress.getByName(address) is Inet6Address
            } else {
                val parts = address.split('.')
                parts.size == 4 && parts.all { part ->
                    part.isNotEmpty() && part.length <= 3 && part.toIntOrNull()?.let { it in 0..255 } == true
                }
            }
        } catch (_: Exception) {
            false
        }
    }
}
