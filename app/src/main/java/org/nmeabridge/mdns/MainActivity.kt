package org.nmeabridge.mdns

import android.app.Activity
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.URL
import java.util.ArrayDeque
import java.util.HashSet
import java.util.Locale
import java.util.concurrent.Executors

// Kotlin port of the working Android Java v0.1.0.
// Discovery, HTTP validation, WebView and diagnostic behavior are unchanged.
class MainActivity : Activity() {
    companion object {
        private const val TYPE = "_http._tcp."
    }

    private var nsd: NsdManager? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var searching = false
    private var resolving = false
    private var opened = false
    private var generation = 0
    private val queue = ArrayDeque<NsdServiceInfo>()
    private val seen = HashSet<String>()
    private val executor = Executors.newSingleThreadExecutor()

    private lateinit var status: TextView
    private lateinit var logs: TextView
    private lateinit var list: LinearLayout
    private lateinit var panel: LinearLayout
    private lateinit var browser: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        nsd = getSystemService(Context.NSD_SERVICE) as? NsdManager

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }
        status = TextView(this).apply { textSize = 17f }

        val scan = Button(this).apply {
            text = "Найти снова"
            setOnClickListener { start() }
        }

        panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        logs = TextView(this).apply {
            textSize = 12f
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply { addView(logs) }
        panel.addView(list)
        panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        browser = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            webViewClient = WebViewClient()
        }

        root.addView(scan)
        root.addView(status)
        root.addView(panel, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(browser, LinearLayout.LayoutParams(-1, 0, 1f))
        browser.visibility = View.GONE

        setContentView(root)
        start()
    }

    private fun log(message: String) {
        logs.append("$message\n")
        if (logs.length() > 12000) {
            logs.text = logs.text.subSequence(logs.length() - 9000, logs.length())
        }
    }

    private fun start() {
        stop()

        val token = ++generation
        queue.clear()
        seen.clear()
        resolving = false
        opened = false
        list.removeAllViews()
        logs.text = ""
        status.text = "Поиск mDNS $TYPE"
        browser.stopLoading()
        browser.visibility = View.GONE
        panel.visibility = View.VISIBLE

        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifi != null) {
                multicastLock = wifi.createMulticastLock("NMEABridgeDiscovery").apply {
                    setReferenceCounted(false)
                    acquire()
                }
                log("MulticastLock: OK")
            }
        } catch (e: Exception) {
            log("MulticastLock: $e")
        }

        val manager = nsd
        if (manager == null) {
            status.text = "NsdManager недоступен"
            return
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                runOnUiThread { log("Поиск запущен") }
            }

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onServiceFound(service: NsdServiceInfo) {
                runOnUiThread {
                    if (token != generation || opened) return@runOnUiThread

                    val key = service.serviceName + "|" + service.serviceType
                    if (!seen.add(key)) return@runOnUiThread

                    log("Найден: " + service.serviceName)
                    val button = Button(this@MainActivity).apply {
                        setAllCaps(false)
                        text = service.serviceName + " — проверить HTTP"
                        setOnClickListener {
                            queue.addLast(service)
                            next(token)
                        }
                    }
                    list.addView(button)

                    val name = service.serviceName.lowercase(Locale.ROOT)
                    if (name.contains("nmea") || name.contains("bridge")) {
                        queue.addLast(service)
                        next(token)
                    }
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                runOnUiThread { log("Потерян: " + service.serviceName) }
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                runOnUiThread {
                    if (token == generation) {
                        status.text = "Ошибка поиска $errorCode"
                        stop()
                    }
                }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }

        discovery = listener
        try {
            manager.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            searching = true
        } catch (e: Exception) {
            log("Ошибка: $e")
            stop()
        }
    }

    private fun next(token: Int) {
        if (resolving || opened || token != generation) return

        val service = queue.pollFirst() ?: return
        resolving = true
        status.text = "Определение IP: " + service.serviceName

        try {
            nsd?.resolveService(service, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    runOnUiThread {
                        if (token != generation) return@runOnUiThread
                        log("Resolve error: $errorCode")
                        resolving = false
                        next(token)
                    }
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    runOnUiThread { probe(serviceInfo, token) }
                }
            })
        } catch (e: Exception) {
            log("Resolve error: $e")
            resolving = false
            next(token)
        }
    }

    private fun probe(service: NsdServiceInfo, token: Int) {
        val host = service.host
        val port = service.port
        if (host !is Inet4Address || port < 1) {
            log("Нет IPv4 или корректного порта")
            resolving = false
            next(token)
            return
        }

        val url = "http://" + host.hostAddress + ":" + port + "/"
        status.text = "Проверяю $url"

        executor.execute {
            var code = -1
            var error = ""
            var connection: HttpURLConnection? = null

            try {
                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 2200
                    readTimeout = 2200
                    instanceFollowRedirects = false
                }
                code = connection.responseCode
            } catch (e: Exception) {
                error = e.toString()
            } finally {
                connection?.disconnect()
            }

            runOnUiThread {
                if (token != generation || opened) return@runOnUiThread

                if (code in 200..399) {
                    opened = true
                    status.text = "HTTP $code · $url"
                    log("HTTP доступен: $url")
                    stop()
                    panel.visibility = View.GONE
                    browser.visibility = View.VISIBLE
                    browser.loadUrl(url)
                } else {
                    log("HTTP недоступен: $url $code $error")
                    status.text = "HTTP не ответил. Поиск продолжается."
                    resolving = false
                    next(token)
                }
            }
        }
    }

    private fun stop() {
        val listener = discovery
        if (searching && listener != null) {
            try {
                nsd?.stopServiceDiscovery(listener)
            } catch (_: Exception) {
            }
        }
        discovery = null
        searching = false

        multicastLock?.let { heldLock ->
            try {
                if (heldLock.isHeld) heldLock.release()
            } catch (_: Exception) {
            }
        }
        multicastLock = null
    }

    @Deprecated("Preserved from the original Java activity")
    override fun onBackPressed() {
        if (opened && browser.canGoBack()) {
            browser.goBack()
        } else if (opened) {
            start()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        generation++
        stop()
        executor.shutdownNow()
        browser.destroy()
        super.onDestroy()
    }
}
