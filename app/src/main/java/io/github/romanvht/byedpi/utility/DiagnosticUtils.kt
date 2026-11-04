package io.github.romanvht.byedpi.utility

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import java.net.Inet6Address
import java.text.DateFormat
import java.util.Date

/**
 * Read-only diagnostics for the Android default network.
 *
 * No remote endpoints are contacted. The report uses only values exposed by
 * ConnectivityManager and LinkProperties for the currently active network.
 */
object DiagnosticUtils {
    data class Report(
        val summary: String,
        val details: String,
        val recommendations: String,
        val updatedAt: String,
        val clipboardText: String
    )

    fun buildReport(context: Context): Report {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = activeNetwork(manager)
        if (network == null) {
            return report(
                summary = "Активное подключение не обнаружено",
                details = "Android не сообщил приложению об активной сети.",
                recommendations = "Проверьте, включён ли Wi-Fi или мобильная передача данных, и отключён ли режим полёта. Затем обновите отчёт.",
                updatedAt = now()
            )
        }

        val capabilities = try {
            manager.getNetworkCapabilities(network)
        } catch (_: SecurityException) {
            null
        }

        val properties = try {
            manager.getLinkProperties(network)
        } catch (_: SecurityException) {
            null
        }

        if (capabilities == null || properties == null) {
            return report(
                summary = "Не удалось прочитать параметры подключения",
                details = "Android не предоставил сведения о возможностях сети или IP-конфигурации.",
                recommendations = "Проверьте разрешение «Доступ к информации о подключении» в настройках Android и повторите диагностику.",
                updatedAt = now()
            )
        }

        val transports = transportNames(capabilities)
        val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val validated: Boolean? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } else null
        val captivePortal = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
        val notMetered = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)

        val addresses = properties.linkAddresses.mapNotNull { linkAddress ->
            val address = linkAddress.address
            val value = address.hostAddress?.substringBefore('%') ?: return@mapNotNull null
            val family = if (address is Inet6Address) "IPv6" else "IPv4"
            "$family: $value"
        }
        val dnsServers = properties.dnsServers.mapNotNull { address ->
            address.hostAddress?.substringBefore('%')
        }
        val defaultRoutes = properties.routes
            .filter { it.isDefaultRoute }
            .map { route ->
                val family = if (route.destination.address is Inet6Address) "IPv6" else "IPv4"
                val gateway = route.gateway?.hostAddress?.substringBefore('%')
                "$family: ${gateway?.takeIf { it.isNotBlank() && it != "0.0.0.0" && it != "::" } ?: "шлюз не отображается"}"
            }

        val privateDns = privateDnsDescription(properties)
        val proxy = proxyDescription(properties)
        val interfaceName = properties.interfaceName ?: "не сообщается"
        val bandwidth = listOfNotNull(
            capabilities.linkDownstreamBandwidthKbps.takeIf { it > 0 }?.let { "входящая ≈ ${it} Кбит/с" },
            capabilities.linkUpstreamBandwidthKbps.takeIf { it > 0 }?.let { "исходящая ≈ ${it} Кбит/с" }
        ).joinToString(", ").ifBlank { "оценка не предоставлена Android" }

        val hasIpv6 = addresses.any { it.startsWith("IPv6:") }
        val status = when {
            captivePortal -> "Обнаружена сеть с авторизацией или страницей входа"
            !hasInternet -> "Сеть подключена, но не заявляет доступ к интернету"
            validated == false -> "Сеть подключена, но Android не подтвердил доступ к интернету"
            validated == true -> "Android подтвердил доступ к интернету"
            else -> "Подключение обнаружено; проверка Android недоступна на этой версии"
        }

        val details = buildString {
            appendLine("Тип подключения: ${transports.ifBlank { "не определён" }}")
            appendLine("Интерфейс: $interfaceName")
            appendLine("Интернет заявлен сетью: ${yesNo(hasInternet)}")
            appendLine("Интернет подтверждён Android: ${validated?.let { yesNo(it) } ?: "неизвестно"}")
            appendLine("Сеть с порталом авторизации: ${yesNo(captivePortal)}")
            appendLine("Тарификация по трафику: ${if (notMetered) "сеть помечена как нетарифицируемая" else "может тарифицироваться"}")
            appendLine("Локальные адреса: ${addresses.ifEmpty { listOf("не предоставлены") }.joinToString("; ")}")
            appendLine("DNS-серверы: ${dnsServers.ifEmpty { listOf("не предоставлены") }.joinToString(", ")}")
            appendLine("Маршруты по умолчанию: ${defaultRoutes.ifEmpty { listOf("не отображаются") }.joinToString("; ")}")
            appendLine("Private DNS: $privateDns")
            appendLine("Системный прокси: $proxy")
            append("Оценка пропускной способности: $bandwidth")
        }

        val advice = mutableListOf<String>()
        when {
            captivePortal -> advice += "Откройте обычный браузер и завершите вход в сеть, если Android или оператор показывает страницу авторизации."
            !hasInternet -> advice += "Проверьте подключение к роутеру или мобильной сети, режим полёта и ограничения тарифа."
            validated == false -> advice += "Android не подтвердил интернет. Проверьте страницу авторизации, перезапустите подключение и при необходимости уточните состояние сети у оператора."
            validated == true -> advice += "Базовый доступ к интернету подтверждён Android. Это не проверяет доступность каждого отдельного сайта или приложения."
            else -> advice += "Статус проверки интернета недоступен на этой версии Android; проверьте открытие обычных сайтов вручную."
        }
        if (dnsServers.isEmpty()) {
            advice += "Адреса DNS не отображаются. Проверьте автоматическое получение сетевых параметров от роутера или оператора."
        } else {
            advice += "DNS-серверы получены от текущей сетевой конфигурации. Не меняйте их вручную только на основании этого отчёта."
        }
        if (defaultRoutes.isEmpty()) {
            advice += "Маршрут по умолчанию не отображается. Переподключитесь к сети; если проблема повторяется, проверьте настройки роутера или обратитесь к оператору."
        }
        if (!hasIpv6) {
            advice += "Глобальный IPv6-адрес не отображается. Это не обязательно ошибка: сеть может работать только через IPv4."
        }
        if (proxy != "не настроен") {
            advice += "Обнаружен системный прокси или PAC-настройка. Убедитесь, что они установлены намеренно и соответствуют вашей сети."
        }
        if (privateDns.startsWith("активен")) {
            advice += "Private DNS включён. Если возникают обычные ошибки разрешения имён, проверьте его состояние в системных настройках Android."
        }

        val detailsWithDevice = buildString {
            appendLine("Устройство: ${listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.distinct().joinToString(" ")}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine()
            append(details)
        }

        return report(
            summary = status,
            details = detailsWithDevice,
            recommendations = advice.joinToString("\n\n"),
            updatedAt = now()
        )
    }

    private fun activeNetwork(manager: ConnectivityManager): Network? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                manager.activeNetwork
            } else {
                @Suppress("DEPRECATION")
                manager.allNetworks.firstOrNull { network ->
                    @Suppress("DEPRECATION")
                    manager.getNetworkInfo(network)?.isConnected == true
                }
            }
        } catch (_: SecurityException) {
            null
        }
    }

    private fun transportNames(capabilities: NetworkCapabilities): String {
        val names = mutableListOf<String>()
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) names += "Wi-Fi"
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) names += "мобильная сеть"
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) names += "Ethernet"
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) names += "VPN-сеть"
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) names += "Bluetooth"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
        ) names += "Wi-Fi Aware"
        return names.distinct().joinToString(", ")
    }

    private fun privateDnsDescription(properties: LinkProperties): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return "состояние недоступно на этой версии Android"
        return when {
            properties.isPrivateDnsActive -> {
                val hostname = properties.privateDnsServerName
                if (hostname.isNullOrBlank()) "активен, имя сервера не предоставлено"
                else "активен: $hostname"
            }
            !properties.privateDnsServerName.isNullOrBlank() ->
                "имя задано (${properties.privateDnsServerName}), но режим не активен"
            else -> "не активен"
        }
    }

    private fun proxyDescription(properties: LinkProperties): String {
        val proxy = properties.httpProxy ?: return "не настроен"
        val host = proxy.host?.takeIf { it.isNotBlank() }
        if (host != null) return "$host:${proxy.port}"
        val pac = proxy.pacFileUrl
        return if (pac != null && pac.toString().isNotBlank()) "PAC: $pac" else "настройка присутствует"
    }

    private fun yesNo(value: Boolean): String = if (value) "да" else "нет"

    private fun now(): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())

    private fun report(
        summary: String,
        details: String,
        recommendations: String,
        updatedAt: String
    ): Report {
        val clipboardText = buildString {
            appendLine("Диагностика сети")
            appendLine(summary)
            appendLine()
            appendLine(details)
            appendLine()
            appendLine("Что проверить")
            append(recommendations)
        }
        return Report(summary, details, recommendations, updatedAt, clipboardText)
    }
}
