package com.datagrail.consent.webview

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebView
import com.datagrail.consent.DataGrailConsent
import com.datagrail.consent.models.ConsentException
import com.datagrail.consent.models.ConsentPreferences
import com.datagrail.consent.utils.ConsentLogger
import java.util.UUID

/**
 * Helper for injecting DataGrail consent preferences into Android WebViews via HTTP cookies.
 *
 * This helper synchronizes consent preferences from the native SDK to web content by injecting
 * HTTP cookies **before** the page loads, ensuring consent is available from the first network request.
 *
 * ## Usage
 *
 * ### Load WebView with consent cookies (recommended)
 * ```kotlin
 * val webView = findViewById<WebView>(R.id.webView)
 * val url = "https://example.com"
 *
 * DataGrailWebViewHelper.loadWebViewWithConsent(webView, url) { result ->
 *     result.fold(
 *         onSuccess = { Log.d("Consent", "Cookies injected, page loaded") },
 *         onFailure = { e -> Log.e("Consent", "Error: ${e.message}") }
 *     )
 * }
 * ```
 *
 * ### Update cookies when consent changes
 * ```kotlin
 * DataGrailConsent.getInstance().onConsentChanged { _ ->
 *     DataGrailWebViewHelper.updateConsentCookies(webView) { result ->
 *         // Handle result
 *     }
 * }
 * ```
 *
 * ### Manual cookie injection (load URL separately)
 * ```kotlin
 * DataGrailWebViewHelper.injectConsentCookies(webView, url) { result ->
 *     if (result.isSuccess) {
 *         webView.loadUrl(url)
 *     }
 * }
 * ```
 *
 * ## Cookie Format
 *
 * Three cookies are injected:
 * - `datagrail_consent_preferences[_s]` - Consent state per category (e.g., "cat1:1|cat2:0")
 * - `datagrail_consent_id[_s]` - User identifier ("{customerId}.{uuid}")
 * - `datagrail_consent_version[_s]` - Config version string
 *
 * The `_s` suffix is added when `allCookieSubdomains` is enabled in the SDK configuration.
 */
object DataGrailWebViewHelper {
    private const val COOKIE_PREFERENCES = "datagrail_consent_preferences"
    private const val COOKIE_ID = "datagrail_consent_id"
    private const val COOKIE_VERSION = "datagrail_consent_version"
    private const val COOKIE_SUFFIX = "_s"
    private const val COOKIE_MAX_AGE = 31536000 // 1 year in seconds

    // WebView-specific UUID storage (separate from core SDK)
    private const val WEBVIEW_PREFS_NAME = "com.datagrail.consent.webview"
    private const val KEY_WEBVIEW_UUID = "webview_user_uuid"

    /**
     * Inject consent cookies into a WebView and load the URL.
     *
     * This is the recommended method for loading web content with consent. It:
     * 1. Retrieves current consent configuration and preferences
     * 2. Injects three HTTP cookies into the WebView
     * 3. Loads the specified URL
     *
     * @param webView The WebView to inject cookies into and load
     * @param url The URL to load (must include protocol, e.g., "https://example.com")
     * @param callback Called with Result.success when cookies are injected and page loads,
     *                 or Result.failure if an error occurs
     */
    @JvmStatic
    fun loadWebViewWithConsent(
        webView: WebView,
        url: String,
        callback: ((Result<Unit>) -> Unit)? = null,
    ) {
        try {
            // Get SDK instance and validate initialization
            val sdk = DataGrailConsent.getInstance()
            val config = sdk.getConfig()
                ?: throw ConsentException.NotInitialized()

            val preferences = sdk.getCategories()
                ?: throw ConsentException.ValidationError("No consent preferences available")

            // Get or create WebView UUID from local storage
            val uuid = getOrCreateWebViewUuid(webView.context)

            // Extract cookie domain from target URL
            val crossSubdomain = config.plugins.allCookieSubdomains
            val cookieDomain = getCookieDomain(url, crossSubdomain)

            // Build cookie values
            val prefsValue = buildPreferencesCookie(preferences)
            val idValue = buildConsentIdCookie(config.dgCustomerId, uuid)
            val versionValue = config.version

            // Determine cookie names (with suffix if cross-subdomain enabled)
            val suffix = if (crossSubdomain) COOKIE_SUFFIX else ""
            val prefsName = "$COOKIE_PREFERENCES$suffix"
            val idName = "$COOKIE_ID$suffix"
            val versionName = "$COOKIE_VERSION$suffix"

            // Get CookieManager and enable cookies
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(webView, true)

            // Inject cookies
            val cookies = listOf(
                formatCookie(prefsName, prefsValue, cookieDomain),
                formatCookie(idName, idValue, cookieDomain),
                formatCookie(versionName, versionValue, cookieDomain),
            )

            cookies.forEach { cookie ->
                cookieManager.setCookie(url, cookie)
                ConsentLogger.d("WebView: Set cookie: $cookie")
            }

            // Ensure cookies are persisted
            cookieManager.flush()

            // Load the URL
            webView.loadUrl(url)

            ConsentLogger.d("WebView: Injected consent cookies and loaded URL: $url")
            callback?.invoke(Result.success(Unit))
        } catch (e: Exception) {
            ConsentLogger.e("WebView: Failed to inject cookies: ${e.message}")
            callback?.invoke(Result.failure(e))
        }
    }

    /**
     * Inject consent cookies into a WebView without loading a URL.
     *
     * Use this method when you want to inject cookies but handle the URL loading yourself.
     * You must call `webView.loadUrl()` separately after this method succeeds.
     *
     * @param webView The WebView to inject cookies into
     * @param url The target URL (used to determine cookie domain, not loaded)
     * @param callback Called with Result.success when cookies are injected,
     *                 or Result.failure if an error occurs
     */
    @JvmStatic
    fun injectConsentCookies(
        webView: WebView,
        url: String,
        callback: ((Result<Unit>) -> Unit)? = null,
    ) {
        try {
            // Get SDK instance and validate initialization
            val sdk = DataGrailConsent.getInstance()
            val config = sdk.getConfig()
                ?: throw ConsentException.NotInitialized()

            val preferences = sdk.getCategories()
                ?: throw ConsentException.ValidationError("No consent preferences available")

            // Get or create WebView UUID from local storage
            val uuid = getOrCreateWebViewUuid(webView.context)

            // Extract cookie domain from target URL
            val crossSubdomain = config.plugins.allCookieSubdomains
            val cookieDomain = getCookieDomain(url, crossSubdomain)

            // Build cookie values
            val prefsValue = buildPreferencesCookie(preferences)
            val idValue = buildConsentIdCookie(config.dgCustomerId, uuid)
            val versionValue = config.version

            // Determine cookie names (with suffix if cross-subdomain enabled)
            val suffix = if (crossSubdomain) COOKIE_SUFFIX else ""
            val prefsName = "$COOKIE_PREFERENCES$suffix"
            val idName = "$COOKIE_ID$suffix"
            val versionName = "$COOKIE_VERSION$suffix"

            // Get CookieManager and enable cookies
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(webView, true)

            // Inject cookies
            val cookies = listOf(
                formatCookie(prefsName, prefsValue, cookieDomain),
                formatCookie(idName, idValue, cookieDomain),
                formatCookie(versionName, versionValue, cookieDomain),
            )

            cookies.forEach { cookie ->
                cookieManager.setCookie(url, cookie)
                ConsentLogger.d("WebView: Set cookie: $cookie")
            }

            // Ensure cookies are persisted
            cookieManager.flush()

            ConsentLogger.d("WebView: Injected consent cookies for URL: $url")
            callback?.invoke(Result.success(Unit))
        } catch (e: Exception) {
            ConsentLogger.e("WebView: Failed to inject cookies: ${e.message}")
            callback?.invoke(Result.failure(e))
        }
    }

    /**
     * Update consent cookies in a WebView that's already loaded.
     *
     * Use this method when consent preferences change after the page has loaded.
     * The WebView must have a currently loaded URL for cookie domain extraction.
     *
     * Note: This updates cookies but does not reload the page. The web application
     * may need to detect cookie changes and refresh its consent state.
     *
     * @param webView The WebView to update cookies in
     * @param callback Called with Result.success when cookies are updated,
     *                 or Result.failure if an error occurs
     */
    @JvmStatic
    fun updateConsentCookies(
        webView: WebView,
        callback: ((Result<Unit>) -> Unit)? = null,
    ) {
        try {
            // Get current URL from WebView
            val url = webView.url
                ?: throw ConsentException.ValidationError("WebView has no loaded URL")

            // Use injectConsentCookies to update (same logic without loading)
            injectConsentCookies(webView, url) { result ->
                result.fold(
                    onSuccess = {
                        ConsentLogger.d("WebView: Updated consent cookies")
                        callback?.invoke(Result.success(Unit))
                    },
                    onFailure = { e ->
                        ConsentLogger.e("WebView: Failed to update cookies: ${e.message}")
                        callback?.invoke(Result.failure(e))
                    },
                )
            }
        } catch (e: Exception) {
            ConsentLogger.e("WebView: Failed to update cookies: ${e.message}")
            callback?.invoke(Result.failure(e))
        }
    }

    /**
     * Get or create a persistent UUID for WebView cookie identification.
     * This UUID is stored separately from the core SDK storage and is used
     * specifically for the datagrail_consent_id cookie.
     *
     * The UUID is lowercase to match JavaScript's crypto.randomUUID() format.
     *
     * @param context Android context (obtained from WebView)
     * @return The persistent WebView UUID (lowercase)
     */
    private fun getOrCreateWebViewUuid(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(
            WEBVIEW_PREFS_NAME,
            Context.MODE_PRIVATE,
        )

        val existingUuid = prefs.getString(KEY_WEBVIEW_UUID, null)
        if (existingUuid != null) {
            return existingUuid
        }

        // Generate new UUID (lowercase to match JS crypto.randomUUID())
        val newUuid = UUID.randomUUID().toString().lowercase()
        prefs.edit().putString(KEY_WEBVIEW_UUID, newUuid).apply()

        ConsentLogger.d("WebView: Generated new UUID: $newUuid")
        return newUuid
    }

    /**
     * Extract cookie domain from URL based on cross-subdomain configuration.
     *
     * @param url The target URL
     * @param crossSubdomain If true, extract root domain with leading dot (e.g., ".example.com")
     *                       If false, use exact host (e.g., "www.example.com")
     * @return The cookie domain, or null if URL has no host
     */
    private fun getCookieDomain(
        url: String,
        crossSubdomain: Boolean,
    ): String? {
        val uri = Uri.parse(url)
        val host = uri.host ?: return null

        return if (crossSubdomain) {
            // Extract root domain: "www.example.com" -> ".example.com"
            val parts = host.split(".")
            if (parts.size >= 2) {
                ".${parts.takeLast(2).joinToString(".")}"
            } else {
                host
            }
        } else {
            host // Exact domain
        }
    }

    /**
     * Build the preferences cookie value from consent preferences.
     *
     * Format: "gtmKey1:1|gtmKey2:0|gtmKey3:1"
     * where 1 = enabled, 0 = disabled
     *
     * @param preferences The consent preferences
     * @return Formatted cookie value
     */
    private fun buildPreferencesCookie(preferences: ConsentPreferences): String {
        return preferences.cookieOptions
            .joinToString("|") { "${it.gtmKey}:${if (it.isEnabled) "1" else "0"}" }
    }

    /**
     * Build the consent ID cookie value.
     *
     * Format: "{customerId}.{userUuid}"
     *
     * The UUID is generated once and persisted in SharedPreferences.
     * It's lowercase to match JavaScript's crypto.randomUUID() format.
     *
     * @param customerId The customer ID from config
     * @param uuid The persistent WebView UUID
     * @return Formatted cookie value
     */
    private fun buildConsentIdCookie(
        customerId: String,
        uuid: String,
    ): String {
        return "$customerId.$uuid"
    }

    /**
     * Format a cookie string with standard attributes.
     *
     * @param name Cookie name
     * @param value Cookie value
     * @param domain Cookie domain (can be null)
     * @param maxAge Max age in seconds (default: 1 year)
     * @return Formatted cookie string
     */
    private fun formatCookie(
        name: String,
        value: String,
        domain: String?,
        maxAge: Int = COOKIE_MAX_AGE,
    ): String {
        val cookie = StringBuilder("$name=$value")
        domain?.let { cookie.append("; Domain=$it") }
        cookie.append("; Path=/")
        cookie.append("; Max-Age=$maxAge")
        return cookie.toString()
    }
}
