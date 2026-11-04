package io.github.romanvht.byedpi.activities

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import io.github.romanvht.byedpi.utility.DiagnosticUtils

/**
 * Read-only network diagnostics screen.
 *
 * This activity does not start a VPN, proxy, native network engine, test runner,
 * or any traffic-modification component.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var detailsView: TextView
    private lateinit var recommendationsView: TextView
    private lateinit var statusView: TextView
    private lateinit var updatedView: TextView
    private var callbackRegistered = false

    private val connectivityManager: ConnectivityManager
        get() = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refreshFromCallback()
        override fun onLost(network: Network) = refreshFromCallback()
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) =
            refreshFromCallback()
        override fun onLinkPropertiesChanged(
            network: Network,
            linkProperties: android.net.LinkProperties
        ) = refreshFromCallback()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(28))
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }
        setContentView(scroll)

        val heading = TextView(this).apply {
            text = "Диагностика сети"
            textSize = 26f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        root.addView(heading, matchWrap())

        val subtitle = TextView(this).apply {
            text = "Сведения о текущем подключении Android. Приложение не отправляет проверочные запросы и не меняет настройки сети."
            textSize = 14f
            setPadding(0, dp(8), 0, dp(16))
        }
        root.addView(subtitle, matchWrap())

        statusView = bodyText(17f, true)
        addCard(root, "Состояние подключения", statusView)

        detailsView = bodyText(14f, false)
        addCard(root, "Параметры сети", detailsView)

        recommendationsView = bodyText(14f, false)
        addCard(root, "Что проверить", recommendationsView)

        updatedView = bodyText(12f, false)
        updatedView.setPadding(dp(2), dp(10), dp(2), dp(4))
        root.addView(updatedView, matchWrap())

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val refreshButton = MaterialButton(this).apply {
            text = "Обновить"
            setOnClickListener { refreshReport() }
        }
        val copyButton = MaterialButton(this).apply {
            text = "Копировать отчёт"
            setOnClickListener { copyReport() }
        }
        actions.addView(refreshButton, LinearLayout.LayoutParams(0, dp(52), 1f).apply {
            marginEnd = dp(6)
        })
        actions.addView(copyButton, LinearLayout.LayoutParams(0, dp(52), 1f).apply {
            marginStart = dp(6)
        })
        root.addView(actions, matchWrap().apply { topMargin = dp(10) })

        val note = bodyText(13f, false).apply {
            text = "В этой сборке отсутствуют VPN/Proxy-службы, сетевой движок ByeDPI, тестер стратегий, список команд и плитка быстрого запуска. Значения DPI-параметров не рассчитываются и не применяются."
        }
        addCard(root, "Ограничения приложения", note)

        val privacy = bodyText(12f, false).apply {
            text = "Отчёт может содержать локальные IP-адреса, DNS-серверы и имя Private DNS. Перед отправкой другим людям проверьте его содержимое."
        }
        root.addView(privacy, matchWrap().apply {
            topMargin = dp(12)
        })

        refreshReport()
    }

    override fun onStart() {
        super.onStart()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                connectivityManager.registerDefaultNetworkCallback(networkCallback)
            } else {
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                connectivityManager.registerNetworkCallback(request, networkCallback)
            }
            callbackRegistered = true
        } catch (_: Exception) {
            callbackRegistered = false
        }
        refreshReport()
    }

    override fun onStop() {
        if (callbackRegistered) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback)
            } catch (_: Exception) {
                // The callback may already have been removed by the system.
            }
            callbackRegistered = false
        }
        super.onStop()
    }

    private fun refreshFromCallback() {
        runOnUiThread {
            if (!isFinishing && ::detailsView.isInitialized) refreshReport()
        }
    }

    private fun refreshReport() {
        val report = DiagnosticUtils.buildReport(this)
        statusView.text = report.summary
        detailsView.text = report.details
        recommendationsView.text = report.recommendations
        updatedView.text = "Обновлено: ${report.updatedAt}"
    }

    private fun copyReport() {
        val report = DiagnosticUtils.buildReport(this)
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Диагностика сети", report.clipboardText))
        Toast.makeText(this, "Отчёт скопирован", Toast.LENGTH_SHORT).show()
    }

    private fun addCard(parent: LinearLayout, title: String, content: TextView) {
        val cardContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(16))
        }
        val titleView = TextView(this).apply {
            text = title
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
        }
        cardContent.addView(titleView, matchWrap())
        cardContent.addView(content, matchWrap())

        val card = MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            cardElevation = dp(2).toFloat()
            addView(cardContent)
        }
        parent.addView(card, matchWrap().apply {
            topMargin = dp(8)
            bottomMargin = dp(8)
        })
    }

    private fun bodyText(size: Float, strong: Boolean): TextView =
        TextView(this).apply {
            textSize = size
            if (strong) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setLineSpacing(dp(3).toFloat(), 1.12f)
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        }

    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}
