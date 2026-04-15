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
    }

    private fun initViews() {
        urlInput = findViewById(R.id.urlInput)
        goButton = findViewById(R.id.goButton)
        verifyCookiesButton = findViewById(R.id.getPreferencesButton) // Reuse existing button
        checkApiButton = findViewById(R.id.checkApiButton)
        webView = findViewById(R.id.webView)
        logTextView = findViewById(R.id.logTextView)
        logScrollView = findViewById(R.id.logScrollView)
        clearLogButton = findViewById(R.id.clearLogButton)

        // Update button text
        verifyCookiesButton.text = "Verify Cookies"

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
            verifyCookies()
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
                    // Verify cookies after a short delay
                    webView.postDelayed({
                        verifyCookies()
                    }, 500)
                },
                onFailure = { e ->
                    log("[Android SDK] Cookie injection failed: ${e.message}")
                },
            )
        }
    }

    private fun verifyCookies() {
        log("[Test] Reading cookies via JavaScript...")

        val script =
            """
            (function() {
                const cookies = document.cookie;
                if (cookies) {
                    const cookieArray = cookies.split('; ');
                    const dgCookies = cookieArray.filter(c => c.startsWith('datagrail_'));
                    if (dgCookies.length > 0) {
                        return 'Found ' + dgCookies.length + ' DataGrail cookie(s):\n' + dgCookies.join('\n');
                    } else {
                        return 'No DataGrail cookies found. All cookies:\n' + cookieArray.join('\n');
                    }
                } else {
                    return 'No cookies found';
                }
            })();
            """.trimIndent()

        webView.evaluateJavascript(script) { result ->
            if (result != null && result != "null") {
                val cleanResult = result.trim('"').replace("\\n", "\n")
                log("[Test] Cookies:")
                cleanResult.split("\n").forEach { line ->
                    log("[Test]   $line")
                }
            } else {
                log("[Test] No cookies returned")
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
                };

                // Also check if we can read consent cookies
                const cookies = document.cookie.split('; ');
                const prefCookie = cookies.find(c => c.startsWith('datagrail_consent_preferences'));
                const idCookie = cookies.find(c => c.startsWith('datagrail_consent_id'));
                const versionCookie = cookies.find(c => c.startsWith('datagrail_consent_version'));

                checks.hasPreferencesCookie = !!prefCookie;
                checks.hasIdCookie = !!idCookie;
                checks.hasVersionCookie = !!versionCookie;

                return JSON.stringify(checks, null, 2);
            })();
            """.trimIndent()

        webView.evaluateJavascript(script) { result ->
            if (result != null && result != "null") {
                val cleanResult = result.trim('"').replace("\\n", "\n").replace("\\\"", "\"")
                log("[Test] API & Cookie Status:")
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
