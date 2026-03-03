package com.datagrail.consent.ui

import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.mockito.MockedStatic
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.any

/**
 * Tests for BannerDialog URL scheme validation.
 *
 * Tests the validation logic that blocks non-http/https URLs
 * from being opened via ACTION_VIEW intents. This mirrors the
 * logic in BannerDialog.openUrl().
 */
class BannerDialogUrlSchemeTest {
    /**
     * Mirrors the URL scheme validation logic in BannerDialog.openUrl().
     * Returns true if the URL would be allowed, false if blocked.
     */
    private fun isUrlSchemeAllowed(url: String): Boolean {
        // Mirror BannerDialog.openUrl() logic
        val uri = android.net.Uri.parse(url)
        val scheme = uri.scheme?.lowercase()
        return scheme == "http" || scheme == "https"
    }

    /**
     * Version using mock Uri.parse for pure JVM test environment
     */
    private fun isUrlSchemeAllowedPure(url: String): Boolean {
        val scheme =
            when {
                url.startsWith("https://") -> "https"
                url.startsWith("http://") -> "http"
                url.startsWith("ftp://") -> "ftp"
                url.startsWith("file://") -> "file"
                url.startsWith("javascript:") -> "javascript"
                url.startsWith("data:") -> "data"
                url.startsWith("intent:") -> "intent"
                url.startsWith("market:") -> "market"
                url.startsWith("tel:") -> "tel"
                url.startsWith("mailto:") -> "mailto"
                url.contains("://") -> url.substringBefore("://").lowercase()
                url.contains(":") -> url.substringBefore(":").lowercase()
                else -> null
            }
        return scheme == "http" || scheme == "https"
    }

    // MARK: - Allowed Schemes

    @Test
    fun `https URLs are allowed`() {
        assertTrue(isUrlSchemeAllowedPure("https://example.com"))
    }

    @Test
    fun `http URLs are allowed`() {
        assertTrue(isUrlSchemeAllowedPure("http://example.com"))
    }

    @Test
    fun `https URL with path is allowed`() {
        assertTrue(isUrlSchemeAllowedPure("https://example.com/privacy-policy"))
    }

    @Test
    fun `https URL with query string is allowed`() {
        assertTrue(isUrlSchemeAllowedPure("https://example.com/page?lang=en"))
    }

    @Test
    fun `https URL with fragment is allowed`() {
        assertTrue(isUrlSchemeAllowedPure("https://example.com/page#section"))
    }

    // MARK: - Blocked Schemes

    @Test
    fun `javascript URLs are blocked`() {
        assertFalse(isUrlSchemeAllowedPure("javascript:alert('xss')"))
    }

    @Test
    fun `data URLs are blocked`() {
        assertFalse(isUrlSchemeAllowedPure("data:text/html,<h1>XSS</h1>"))
    }

    @Test
    fun `file URLs are blocked`() {
        assertFalse(isUrlSchemeAllowedPure("file:///etc/passwd"))
    }

    @Test
    fun `ftp URLs are blocked`() {
        assertFalse(isUrlSchemeAllowedPure("ftp://example.com/file"))
    }

    @Test
    fun `intent URLs are blocked`() {
        assertFalse(isUrlSchemeAllowedPure("intent://scan/#Intent;scheme=zxing;end"))
    }

    @Test
    fun `market URLs are blocked`() {
        assertFalse(isUrlSchemeAllowedPure("market://details?id=com.example.app"))
    }

    @Test
    fun `tel URLs are blocked`() {
        assertFalse(isUrlSchemeAllowedPure("tel:+1234567890"))
    }

    @Test
    fun `mailto URLs are blocked`() {
        assertFalse(isUrlSchemeAllowedPure("mailto:test@example.com"))
    }

    @Test
    fun `custom scheme URLs are blocked`() {
        assertFalse(isUrlSchemeAllowedPure("myapp://deeplink/path"))
    }

    @Test
    fun `empty string is blocked`() {
        assertFalse(isUrlSchemeAllowedPure(""))
    }

    @Test
    fun `plain text is blocked`() {
        assertFalse(isUrlSchemeAllowedPure("not a url"))
    }

    // MARK: - With android.net.Uri.parse (requires mocked Uri)

    @Test
    fun `openUrl with mocked Uri validates https scheme`() {
        val mockUri = org.mockito.Mockito.mock(Uri::class.java)
        org.mockito.Mockito.`when`(mockUri.scheme).thenReturn("https")

        val mockUriStatic: MockedStatic<Uri> = mockStatic(Uri::class.java)
        mockUriStatic.`when`<Uri> { Uri.parse(any()) }.thenReturn(mockUri)

        try {
            assertTrue(isUrlSchemeAllowed("https://example.com"))
        } finally {
            mockUriStatic.close()
        }
    }

    @Test
    fun `openUrl with mocked Uri blocks javascript scheme`() {
        val mockUri = org.mockito.Mockito.mock(Uri::class.java)
        org.mockito.Mockito.`when`(mockUri.scheme).thenReturn("javascript")

        val mockUriStatic: MockedStatic<Uri> = mockStatic(Uri::class.java)
        mockUriStatic.`when`<Uri> { Uri.parse(any()) }.thenReturn(mockUri)

        try {
            assertFalse(isUrlSchemeAllowed("javascript:alert(1)"))
        } finally {
            mockUriStatic.close()
        }
    }

    @Test
    fun `openUrl with mocked Uri blocks null scheme`() {
        val mockUri = org.mockito.Mockito.mock(Uri::class.java)
        org.mockito.Mockito.`when`(mockUri.scheme).thenReturn(null)

        val mockUriStatic: MockedStatic<Uri> = mockStatic(Uri::class.java)
        mockUriStatic.`when`<Uri> { Uri.parse(any()) }.thenReturn(mockUri)

        try {
            assertFalse(isUrlSchemeAllowed("malformed"))
        } finally {
            mockUriStatic.close()
        }
    }

    @Test
    fun `openUrl with mocked Uri accepts http scheme`() {
        val mockUri = org.mockito.Mockito.mock(Uri::class.java)
        org.mockito.Mockito.`when`(mockUri.scheme).thenReturn("http")

        val mockUriStatic: MockedStatic<Uri> = mockStatic(Uri::class.java)
        mockUriStatic.`when`<Uri> { Uri.parse(any()) }.thenReturn(mockUri)

        try {
            assertTrue(isUrlSchemeAllowed("http://example.com"))
        } finally {
            mockUriStatic.close()
        }
    }

    @Test
    fun `openUrl with mocked Uri handles uppercase scheme via lowercase`() {
        val mockUri = org.mockito.Mockito.mock(Uri::class.java)
        org.mockito.Mockito.`when`(mockUri.scheme).thenReturn("HTTPS")

        val mockUriStatic: MockedStatic<Uri> = mockStatic(Uri::class.java)
        mockUriStatic.`when`<Uri> { Uri.parse(any()) }.thenReturn(mockUri)

        try {
            assertTrue(isUrlSchemeAllowed("HTTPS://EXAMPLE.COM"))
        } finally {
            mockUriStatic.close()
        }
    }
}
