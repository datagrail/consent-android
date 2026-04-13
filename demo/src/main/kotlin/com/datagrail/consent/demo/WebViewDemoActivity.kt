package com.datagrail.consent.demo

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.datagrail.consent.DataGrailConsent
import com.datagrail.consent.webview.DataGrailWebViewHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Demo activity showing how to inject DataGrail consent preferences into a WebView.
 *
 * This demonstrates:
 * - Injecting consent on page load
 * - Updating consent when it changes
 * - Verifying injection via JavaScript console
 */
class WebViewDemoActivity : AppCompatActivity() {
    private lateinit var urlInput: EditText
    private lateinit var goButton: Button
    private lateinit var getPreferencesButton: Button
    private lateinit var checkApiButton: Button
    private lateinit var webView: WebView
    private lateinit var logTextView: TextView
    private lateinit var logScrollView: ScrollView
    private lateinit var clearLogButton: Button

    private val logEntries = mutableListOf<String>()
    private var lastInjectedUrl: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_webview_demo)

        supportActionBar?.title = "WebView Demo"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        initViews()
        setupWebView()
        setupButtons()

        log("[Demo] WebView Demo launched")
    }

    private fun initViews() {
        urlInput = findViewById(R.id.urlInput)
        goButton = findViewById(R.id.goButton)
        getPreferencesButton = findViewById(R.id.getPreferencesButton)
        checkApiButton = findViewById(R.id.checkApiButton)
        webView = findViewById(R.id.webView)
        logTextView = findViewById(R.id.logTextView)
        logScrollView = findViewById(R.id.logScrollView)
        clearLogButton = findViewById(R.id.clearLogButton)

        // Set default URL
        urlInput.setText("https://datagrail.io")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
        }

        // Set WebViewClient to intercept page loads and inject consent
        webView.webViewClient =
            object : WebViewClient() {
                override fun onPageFinished(
                    view: WebView?,
                    url: String?,
                ) {
                    super.onPageFinished(view, url)
                    log("[WebView] Page loaded: $url")

                    // Only inject if this is a new URL (prevent duplicate injections on same page)
                    if (url != lastInjectedUrl) {
                        lastInjectedUrl = url

                        // Inject consent preferences after page loads
                        view?.let {
                            DataGrailWebViewHelper.injectConsentPreferences(it)
                            log("[Android SDK] Injected consent preferences into WebView")
                        }

                        // Automatically check if consent preferences were injected (with small delay)
                        view?.postDelayed({
                            checkInjectedConsent()
                        }, 500)
                    } else {
                        log("[WebView] Same URL, skipping injection")
                    }
                }
            }

        // Set WebChromeClient to capture console messages
        webView.webChromeClient =
            object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                    log("[JS] ${consoleMessage.message()}")
                    return true
                }
            }

        // Listen for consent changes and update the WebView
        DataGrailConsent.getInstance().onConsentChanged { _ ->
            log("[Android SDK] Consent changed, updating WebView...")
            DataGrailWebViewHelper.updateConsentPreferences(webView) { success ->
                log("[Android SDK] WebView update ${if (success) "succeeded" else "failed"}")
            }
        }
    }

    private fun setupButtons() {
        goButton.setOnClickListener {
            val url = urlInput.text.toString().trim()
            if (url.isNotEmpty()) {
                loadUrl(url)
            }
        }

        getPreferencesButton.setOnClickListener {
            getConsentPreferences()
        }

        checkApiButton.setOnClickListener {
            checkBannerAPI()
        }

        clearLogButton.setOnClickListener {
            logEntries.clear()
            logTextView.text = "Logs cleared"
            log("[Demo] Logs cleared")
        }
    }

    private fun loadUrl(url: String) {
        log("[Demo] Loading URL: $url")
        var urlToLoad = url
        if (!urlToLoad.startsWith("http://") && !urlToLoad.startsWith("https://")) {
            urlToLoad = "https://$urlToLoad"
        }
        webView.loadUrl(urlToLoad)
    }

    private fun getConsentPreferences() {
        log("[Test] Calling DG_BANNER_API.getConsentPreferences()...")

        val script =
            """
            (function() {
                if (window.DG_BANNER_API && typeof window.DG_BANNER_API.getConsentPreferences === 'function') {
                    const prefs = window.DG_BANNER_API.getConsentPreferences();
                    return JSON.stringify(prefs, null, 2);
                } else {
                    return "DG_BANNER_API.getConsentPreferences() not available";
                }
            })();
            """.trimIndent()

        webView.evaluateJavascript(script) { result ->
            if (result != null && result != "null") {
                val cleanResult = result.trim('"').replace("\\n", "\n").replace("\\\"", "\"")
                log("[Test] DG_BANNER_API.getConsentPreferences():")
                cleanResult.split("\n").forEach { line ->
                    log("[Test]   $line")
                }
            } else {
                log("[Test] No result returned")
            }
        }
    }

    private fun checkBannerAPI() {
        log("[Test] Checking DG_BANNER_API availability...")

        val script =
            """
            (function() {
                const checks = {
                    hasDG_BANNER_API: typeof window.DG_BANNER_API !== 'undefined',
                    hasGetConsentPreferences: typeof window.DG_BANNER_API?.getConsentPreferences === 'function',
                    hasSetConsentPreferences: typeof window.DG_BANNER_API?.setConsentPreferences === 'function',
                    hasDatagrailConsent: typeof window.datagrailConsent !== 'undefined'
                };
                return JSON.stringify(checks, null, 2);
            })();
            """.trimIndent()

        webView.evaluateJavascript(script) { result ->
            if (result != null && result != "null") {
                val cleanResult = result.trim('"').replace("\\n", "\n").replace("\\\"", "\"")
                log("[Test] API Status:")
                cleanResult.split("\n").forEach { line ->
                    log("[Test]   $line")
                }
            } else {
                log("[Test] No result returned")
            }
        }
    }

    private fun checkInjectedConsent() {
        val script =
            """
            (function() {
                if (window.datagrailConsent) {
                    return JSON.stringify(window.datagrailConsent, null, 2);
                } else {
                    return "window.datagrailConsent is undefined";
                }
            })();
            """.trimIndent()

        webView.evaluateJavascript(script) { result ->
            if (result != null && result != "null") {
                val cleanResult = result.trim('"').replace("\\n", "\n").replace("\\\"", "\"")
                if (!cleanResult.contains("undefined")) {
                    log("[Auto-Check] Consent preferences injected:")
                    cleanResult.split("\n").take(5).forEach { line ->
                        log("[Auto-Check]   $line")
                    }
                    if (cleanResult.split("\n").size > 5) {
                        log("[Auto-Check]   ...")
                    }
                } else {
                    log("[Auto-Check] $cleanResult")
                }
            }
        }
    }

    private fun log(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val entry = "[$timestamp] $message"
        logEntries.add(entry)

        runOnUiThread {
            logTextView.text = logEntries.joinToString("\n")
            // Scroll to bottom
            logScrollView.post { logScrollView.fullScroll(View.FOCUS_DOWN) }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
