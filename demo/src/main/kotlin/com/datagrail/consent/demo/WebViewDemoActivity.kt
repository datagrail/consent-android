package com.datagrail.consent.demo

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
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
 * Demo activity showing how to inject DataGrail consent preferences into a WebView via HTTP cookies.
 *
 * This demonstrates:
 * - Injecting consent cookies before page load
 * - Updating consent cookies when preferences change
 * - Verifying cookie injection via JavaScript console
 */
class WebViewDemoActivity : AppCompatActivity() {
    private lateinit var urlInput: EditText
    private lateinit var goButton: Button
    private lateinit var verifyCookiesButton: Button
    private lateinit var checkApiButton: Button
    private lateinit var webView: WebView
    private lateinit var logTextView: TextView
    private lateinit var logScrollView: ScrollView
    private lateinit var clearLogButton: Button

    private val logEntries = mutableListOf<String>()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_webview_demo)

        supportActionBar?.title = "WebView Demo (Cookies)"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        initViews()
        setupWebView()
        setupButtons()

        log("[Demo] WebView Demo launched (cookie injection mode)")

        // Auto-load default URL on launch (matching iOS behavior)
        val defaultUrl = urlInput.text.toString()
        if (defaultUrl.isNotEmpty()) {
            loadUrl(defaultUrl)
        }
    }

    private fun initViews() {
        urlInput = findViewById(R.id.urlInput)
        goButton = findViewById(R.id.goButton)
        verifyCookiesButton = findViewById(R.id.getPreferencesButton) // Keep as "Get Preferences"
        checkApiButton = findViewById(R.id.checkApiButton)
        webView = findViewById(R.id.webView)
        logTextView = findViewById(R.id.logTextView)
        logScrollView = findViewById(R.id.logScrollView)
        clearLogButton = findViewById(R.id.clearLogButton)

        // Keep original button text "Get Preferences" (matches iOS)
        // No need to change button text - it's already "Get Preferences" in XML

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

        // Set WebViewClient to handle navigation within the WebView
        webView.webViewClient =
            object : android.webkit.WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    log("[WebView] ✅ Page loaded: $url")
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: android.webkit.WebResourceRequest?,
                ): Boolean {
                    // Keep navigation within the WebView
                    return false
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

        // Listen for consent changes and update cookies
        DataGrailConsent.getInstance().onConsentChanged { _ ->
            log("[Android SDK] Consent changed, updating cookies...")
            DataGrailWebViewHelper.updateConsentCookies(webView) { result ->
                result.fold(
                    onSuccess = { log("[Android SDK] Cookies updated successfully") },
                    onFailure = { e -> log("[Android SDK] Cookie update failed: ${e.message}") },
                )
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

        verifyCookiesButton.setOnClickListener {
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
        log("[Demo] Loading URL with consent cookies: $url")
        var urlToLoad = url
        if (!urlToLoad.startsWith("http://") && !urlToLoad.startsWith("https://")) {
            urlToLoad = "https://$urlToLoad"
        }

        DataGrailWebViewHelper.loadWebViewWithConsent(webView, urlToLoad) { result ->
            result.fold(
                onSuccess = {
                    log("[Android SDK] Cookies injected successfully, page loaded")
                    // Auto-check cookies after a short delay
                    webView.postDelayed({
                        checkConsentCookies()
                    }, 500)
                },
                onFailure = { e ->
                    log("[Android SDK] Cookie injection failed: ${e.message}")
                },
            )
        }
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

    private fun checkConsentCookies() {
        // Read cookies natively using CookieManager (not via JavaScript)
        // This matches iOS behavior which uses native WKHTTPCookieStore
        val cookieManager = android.webkit.CookieManager.getInstance()
        val url = webView.url

        if (url != null) {
            val allCookies = cookieManager.getCookie(url)

            if (allCookies != null) {
                val cookieList = allCookies.split("; ")
                val consentCookies = cookieList.filter { it.startsWith("datagrail_consent") }

                if (consentCookies.isNotEmpty()) {
                    log("[Auto-Check] Found ${consentCookies.size} DataGrail cookie(s):")
                    consentCookies.forEach { cookie ->
                        log("[Auto-Check]   - $cookie")
                    }
                } else {
                    log("[Auto-Check] ⚠️ No DataGrail consent cookies found")
                }
            } else {
                log("[Auto-Check] No cookies found for this URL")
            }
        } else {
            log("[Auto-Check] WebView has no URL")
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
                    hasSetConsentPreferences: typeof window.DG_BANNER_API?.setConsentPreferences === 'function'
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
