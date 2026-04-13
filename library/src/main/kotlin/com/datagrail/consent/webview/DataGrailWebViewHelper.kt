package com.datagrail.consent.webview

import android.webkit.WebView
import com.datagrail.consent.DataGrailConsent
import com.datagrail.consent.models.ConsentPreferences
import com.datagrail.consent.utils.ConsentLogger
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Helper for injecting DataGrail consent preferences into Android WebViews.
 *
 * ## Usage
 *
 * ### Initial injection (recommended)
 * Inject consent preferences when a page starts loading:
 *
 * ```kotlin
 * webView.webViewClient = object : WebViewClient() {
 *     override fun onPageFinished(view: WebView?, url: String?) {
 *         super.onPageFinished(view, url)
 *         view?.let { DataGrailWebViewHelper.injectConsentPreferences(it) }
 *     }
 * }
 * ```
 *
 * ### Update after consent changes
 * Update preferences in an already-loaded WebView:
 *
 * ```kotlin
 * DataGrailConsent.getInstance().onConsentChanged { _ ->
 *     DataGrailWebViewHelper.updateConsentPreferences(webView) { success ->
 *         Log.d("Consent", "WebView update: $success")
 *     }
 * }
 * ```
 */
object DataGrailWebViewHelper {
    private val json = Json { encodeDefaults = true }

    /**
     * Inject consent preferences into a WebView.
     * Call this in WebViewClient.onPageStarted() or onPageFinished() to inject preferences
     * as early as possible.
     *
     * @param webView The WebView to inject into
     */
    @JvmStatic
    fun injectConsentPreferences(webView: WebView) {
        try {
            val preferences = DataGrailConsent.getInstance().getCategories()
            if (preferences == null) {
                ConsentLogger.d("WebView injection: No consent preferences available")
                return
            }

            val script = createInjectionScript(preferences)
            webView.evaluateJavascript(script, null)
            ConsentLogger.d("WebView injection: Injected consent preferences")
        } catch (e: Exception) {
            ConsentLogger.e("WebView injection failed: ${e.message}")
        }
    }

    /**
     * Update consent preferences in an already-loaded WebView.
     * Use this when consent preferences change after the page has loaded.
     *
     * @param webView The WebView to update
     * @param callback Called with true if successful, false otherwise
     */
    @JvmStatic
    fun updateConsentPreferences(
        webView: WebView,
        callback: ((Boolean) -> Unit)? = null,
    ) {
        try {
            val preferences = DataGrailConsent.getInstance().getCategories()
            if (preferences == null) {
                ConsentLogger.d("WebView update: No consent preferences available")
                callback?.invoke(false)
                return
            }

            val script = createInjectionScript(preferences)
            webView.evaluateJavascript(script) { result ->
                ConsentLogger.d("WebView update: Preferences updated (result: $result)")
                callback?.invoke(true)
            }
        } catch (e: Exception) {
            ConsentLogger.e("WebView update failed: ${e.message}")
            callback?.invoke(false)
        }
    }

    /**
     * Create JavaScript to inject consent preferences.
     * This script:
     * 1. Stores preferences in window.datagrailConsent for debugging
     * 2. Calls DG_BANNER_API.setConsentPreferences() if available
     *
     * @param preferences The consent preferences to inject
     * @return JavaScript code as a string
     */
    private fun createInjectionScript(preferences: ConsentPreferences): String {
        val preferencesJSON = json.encodeToString(preferences)

        // Create the injection script that calls DG_BANNER_API.setConsentPreferences
        return """
            (function() {
                try {
                    const preferences = $preferencesJSON;
                    const config = { "runPreferenceCallbacks": false };

                    // Store preferences globally for debugging
                    window.datagrailConsent = preferences;
                    console.log('[DataGrail Android SDK] Preferences stored in window.datagrailConsent:', JSON.stringify(preferences));

                    // Try to set preferences via API if available
                    if (window.DG_BANNER_API && typeof window.DG_BANNER_API.setConsentPreferences === 'function') {
                        console.log('[DataGrail Android SDK] Calling DG_BANNER_API.setConsentPreferences...');
                        try {
                            const result = window.DG_BANNER_API.setConsentPreferences(preferences, config);
                            console.log('[DataGrail Android SDK] setConsentPreferences called successfully, result:', result);
                        } catch (apiError) {
                            console.error('[DataGrail Android SDK] setConsentPreferences threw error:', apiError.message);
                        }
                    } else {
                        console.log('[DataGrail Android SDK] DG_BANNER_API not available (type=' + typeof window.DG_BANNER_API + ')');
                    }
                } catch (error) {
                    console.error('[DataGrail Android SDK] Error injecting consent:', error.message, error.stack);
                }
            })();
        """.trimIndent()
    }
}
