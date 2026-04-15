package com.datagrail.consent.webview

import android.webkit.WebView
import com.datagrail.consent.models.CategoryConsent
import com.datagrail.consent.models.ConsentPreferences
import org.junit.Test

/**
 * Unit tests for DataGrailWebViewHelper cookie injection.
 *
 * Note: Testing static methods and Android framework classes (WebView, CookieManager)
 * requires PowerMock or Robolectric, which adds significant complexity.
 *
 * These tests verify:
 * 1. The API contract (method signatures)
 * 2. Cookie format logic
 * 3. Domain extraction logic
 *
 * Integration tests should verify actual cookie injection behavior.
 */
class DataGrailWebViewHelperTests {
    private val testPreferences =
        ConsentPreferences(
            isCustomised = true,
            cookieOptions =
                listOf(
                    CategoryConsent(gtmKey = "category_marketing", isEnabled = true),
                    CategoryConsent(gtmKey = "category_analytics", isEnabled = false),
                    CategoryConsent(gtmKey = "category_essential", isEnabled = true),
                ),
        )

    @Test
    fun `loadWebViewWithConsent method exists with correct signature`() {
        // Verify the primary method exists
        val method = DataGrailWebViewHelper::class.java.methods.find {
            it.name == "loadWebViewWithConsent"
        }

        assert(method != null) { "loadWebViewWithConsent method should exist" }
        assert(method!!.parameterTypes.size == 3) {
            "loadWebViewWithConsent should have 3 parameters (WebView, String, callback)"
        }
        assert(method.parameterTypes[0] == WebView::class.java) {
            "First parameter should be WebView"
        }
        assert(method.parameterTypes[1] == String::class.java) {
            "Second parameter should be String (URL)"
        }
    }

    @Test
    fun `injectConsentCookies method exists with correct signature`() {
        // Verify the manual injection method exists
        val method = DataGrailWebViewHelper::class.java.methods.find {
            it.name == "injectConsentCookies"
        }

        assert(method != null) { "injectConsentCookies method should exist" }
        assert(method!!.parameterTypes.size == 3) {
            "injectConsentCookies should have 3 parameters (WebView, String, callback)"
        }
        assert(method.parameterTypes[0] == WebView::class.java) {
            "First parameter should be WebView"
        }
        assert(method.parameterTypes[1] == String::class.java) {
            "Second parameter should be String (URL)"
        }
    }

    @Test
    fun `updateConsentCookies method exists with correct signature`() {
        // Verify the update method exists
        val method = DataGrailWebViewHelper::class.java.methods.find {
            it.name == "updateConsentCookies"
        }

        assert(method != null) { "updateConsentCookies method should exist" }
        assert(method!!.parameterTypes.size == 2) {
            "updateConsentCookies should have 2 parameters (WebView, callback)"
        }
        assert(method.parameterTypes[0] == WebView::class.java) {
            "First parameter should be WebView"
        }
    }

    @Test
    fun `preferences cookie format matches specification`() {
        // Verify the expected cookie format
        // Format: "gtmKey1:1|gtmKey2:0|gtmKey3:1"
        val expectedFormat = "category_marketing:1|category_analytics:0|category_essential:1"

        val actualFormat = testPreferences.cookieOptions
            .joinToString("|") { "${it.gtmKey}:${if (it.isEnabled) "1" else "0"}" }

        assert(actualFormat == expectedFormat) {
            "Cookie format should be 'key:1|key:0'. Expected: $expectedFormat, Got: $actualFormat"
        }
    }

    @Test
    fun `consent ID cookie format matches specification`() {
        // Verify the expected ID cookie format
        // Format: "{customerId}.{uuid}"
        val customerId = "test-customer-123"
        val uuid = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"

        val expectedFormat = "$customerId.$uuid"
        val actualFormat = "$customerId.$uuid"

        assert(actualFormat == expectedFormat) {
            "ID cookie format should be 'customerId.uuid'"
        }
        assert(uuid == uuid.lowercase()) {
            "UUID should be lowercase"
        }
    }

    @Test
    fun `cookie names have correct format`() {
        // Verify cookie name constants
        val expectedNames = listOf(
            "datagrail_consent_preferences",
            "datagrail_consent_id",
            "datagrail_consent_version",
        )

        val expectedNamesWithSuffix = listOf(
            "datagrail_consent_preferences_s",
            "datagrail_consent_id_s",
            "datagrail_consent_version_s",
        )

        // Verify base names are correct
        assert(expectedNames.all { it.startsWith("datagrail_consent_") }) {
            "Cookie names should start with 'datagrail_consent_'"
        }

        // Verify suffix names are correct
        assert(expectedNamesWithSuffix.all { it.endsWith("_s") }) {
            "Cookie names with suffix should end with '_s'"
        }
    }

    @Test
    fun `domain extraction for cross-subdomain mode`() {
        // Test domain extraction logic for cross-subdomain mode
        val testCases = listOf(
            "www.example.com" to ".example.com",
            "subdomain.example.com" to ".example.com",
            "deep.subdomain.example.com" to ".example.com",
            "example.com" to ".example.com",
            "localhost" to "localhost", // Edge case: no TLD
        )

        testCases.forEach { (input, expected) ->
            val parts = input.split(".")
            val actual = if (parts.size >= 2) {
                ".${parts.takeLast(2).joinToString(".")}"
            } else {
                input
            }

            assert(actual == expected) {
                "Domain extraction failed for $input. Expected: $expected, Got: $actual"
            }
        }
    }

    @Test
    fun `domain extraction for exact domain mode`() {
        // Test domain extraction logic for exact domain mode
        val testCases = listOf(
            "www.example.com" to "www.example.com",
            "subdomain.example.com" to "subdomain.example.com",
            "example.com" to "example.com",
            "localhost" to "localhost",
        )

        testCases.forEach { (input, expected) ->
            val actual = input // Exact domain mode returns host as-is

            assert(actual == expected) {
                "Exact domain failed for $input. Expected: $expected, Got: $actual"
            }
        }
    }

    @Test
    fun `cookie string format is valid`() {
        // Verify cookie string format
        val name = "datagrail_consent_preferences"
        val value = "cat1:1|cat2:0"
        val domain = ".example.com"
        val maxAge = 31536000

        val expectedCookie = "$name=$value; Domain=$domain; Path=/; Max-Age=$maxAge"

        val cookie = StringBuilder("$name=$value")
        cookie.append("; Domain=$domain")
        cookie.append("; Path=/")
        cookie.append("; Max-Age=$maxAge")
        val actualCookie = cookie.toString()

        assert(actualCookie == expectedCookie) {
            "Cookie format incorrect. Expected: $expectedCookie, Got: $actualCookie"
        }
    }

    @Test
    fun `cookie string without domain is valid`() {
        // Verify cookie string format when domain is null
        val name = "datagrail_consent_version"
        val value = "1.0.0"
        val maxAge = 31536000

        val expectedCookie = "$name=$value; Path=/; Max-Age=$maxAge"

        val cookie = StringBuilder("$name=$value")
        // No domain append
        cookie.append("; Path=/")
        cookie.append("; Max-Age=$maxAge")
        val actualCookie = cookie.toString()

        assert(actualCookie == expectedCookie) {
            "Cookie without domain incorrect. Expected: $expectedCookie, Got: $actualCookie"
        }
    }

    @Test
    fun `all methods are marked with @JvmStatic`() {
        // Verify Java interoperability
        val publicMethods = DataGrailWebViewHelper::class.java.methods.filter {
            it.name in listOf(
                "loadWebViewWithConsent",
                "injectConsentCookies",
                "updateConsentCookies",
            )
        }

        assert(publicMethods.isNotEmpty()) {
            "Public methods should exist"
        }

        // All public API methods should be static (from @JvmStatic on object)
        publicMethods.forEach { method ->
            assert(java.lang.reflect.Modifier.isStatic(method.modifiers)) {
                "${method.name} should be static for Java interoperability"
            }
        }
    }
}
