package io.github.romanvht.byedpi.utility

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import io.github.romanvht.byedpi.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Performs a neutral, direct connectivity check using the device's normal network route.
 * It does not configure a proxy, modify VPN settings, or attempt to classify DPI filtering.
 */
object NetworkDiagnostics {
    private const val TEST_HOST = "example.com"
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 5_000

    suspend fun run(context: Context): String = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        buildString {
            appendLine(appContext.getString(R.string.diagnostic_network_title))
            appendLine(networkState(appContext))

            val dnsStarted = SystemClock.elapsedRealtime()
            try {
                val addresses = InetAddress.getAllByName(TEST_HOST)
                val duration = SystemClock.elapsedRealtime() - dnsStarted
                appendLine(
                    appContext.getString(
                        R.string.diagnostic_dns_success,
                        addresses.size,
                        duration
                    )
                )
            } catch (error: Exception) {
                val duration = SystemClock.elapsedRealtime() - dnsStarted
                appendLine(
                    appContext.getString(
                        R.string.diagnostic_dns_failed,
                        error.javaClass.simpleName,
                        duration
                    )
                )
            }

            var connection: HttpsURLConnection? = null
            val httpsStarted = SystemClock.elapsedRealtime()
            try {
                connection = (URL("https://$TEST_HOST/").openConnection() as HttpsURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    requestMethod = "GET"
                    instanceFollowRedirects = true
                    setRequestProperty("Cache-Control", "no-cache")
                    setRequestProperty("Connection", "close")
                }
                val responseCode = connection.responseCode
                val duration = SystemClock.elapsedRealtime() - httpsStarted
                appendLine(
                    appContext.getString(
                        R.string.diagnostic_https_success,
                        responseCode,
                        duration
                    )
                )
            } catch (error: Exception) {
                val duration = SystemClock.elapsedRealtime() - httpsStarted
                appendLine(
                    appContext.getString(
                        R.string.diagnostic_https_failed,
                        error.javaClass.simpleName,
                        duration
                    )
                )
            } finally {
                connection?.disconnect()
            }

            appendLine()
            append(appContext.getString(R.string.diagnostic_network_note))
        }
    }

    private fun networkState(context: Context): String {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val connected: Boolean
        val validated: Boolean?

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = manager.activeNetwork
            val capabilities = network?.let(manager::getNetworkCapabilities)
            connected = capabilities != null
            validated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } else {
            @Suppress("DEPRECATION")
            val info = manager.activeNetworkInfo
            connected = info?.isConnected == true
            validated = null
        }

        val status = when {
            !connected -> context.getString(R.string.diagnostic_network_disconnected)
            validated == true -> context.getString(R.string.diagnostic_network_validated)
            validated == false -> context.getString(R.string.diagnostic_network_unvalidated)
            else -> context.getString(R.string.diagnostic_network_connected)
        }
        return context.getString(R.string.diagnostic_network_state, status)
    }
}
