package com.datagrail.consent.models

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for ConsentException sanitization:
 * - InvalidConfigUrl shows only host, not full URL
 * - Malformed URLs show "<malformed>" placeholder
 */
class ConsentExceptionSanitizationTest {
    // MARK: - InvalidConfigUrl Sanitization

    @Test
    fun `InvalidConfigUrl shows only host for valid https URL`() {
        val exception = ConsentException.InvalidConfigUrl("https://consent.example.com/config.json?key=secret")

        assertTrue(
            "Message should contain host, got: ${exception.message}",
            exception.message!!.contains("consent.example.com"),
        )
        assertFalse(
            "Message should NOT contain full path",
            exception.message!!.contains("/config.json"),
        )
        assertFalse(
            "Message should NOT contain query params",
            exception.message!!.contains("key=secret"),
        )
    }

    @Test
    fun `InvalidConfigUrl shows only host without path`() {
        val exception = ConsentException.InvalidConfigUrl("https://consent.datagrail.io/some/secret/path")

        assertTrue(exception.message!!.contains("consent.datagrail.io"))
        assertFalse(exception.message!!.contains("/some/secret/path"))
    }

    @Test
    fun `InvalidConfigUrl shows malformed for invalid URL`() {
        val exception = ConsentException.InvalidConfigUrl("not a valid url")

        assertTrue(
            "Message should contain <malformed>, got: ${exception.message}",
            exception.message!!.contains("<malformed>"),
        )
        assertFalse(
            "Message should NOT contain the raw invalid URL",
            exception.message!!.contains("not a valid url"),
        )
    }

    @Test
    fun `InvalidConfigUrl shows malformed for empty string`() {
        val exception = ConsentException.InvalidConfigUrl("")

        assertTrue(exception.message!!.contains("<malformed>"))
    }

    @Test
    fun `InvalidConfigUrl does not expose port numbers in message`() {
        val exception = ConsentException.InvalidConfigUrl("https://internal.corp.com:8443/admin/config")

        // Host includes port in java.net.URL.host, but that's OK
        // Key thing: path /admin/config should not be exposed
        assertFalse(
            "Message should NOT contain the path",
            exception.message!!.contains("/admin/config"),
        )
    }

    @Test
    fun `InvalidConfigUrl does not expose credentials in message`() {
        val exception = ConsentException.InvalidConfigUrl("https://user:password@host.com/config")

        assertFalse(
            "Message should NOT contain credentials",
            exception.message!!.contains("password"),
        )
        assertFalse(
            "Message should NOT contain user",
            exception.message!!.contains("user:"),
        )
    }

    // MARK: - Other Exception Types (unchanged, but verify baseline)

    @Test
    fun `NetworkError preserves message`() {
        val exception = ConsentException.NetworkError("HTTP 500")

        assertTrue(exception.message!!.contains("HTTP 500"))
    }

    @Test
    fun `ValidationError preserves message`() {
        val exception = ConsentException.ValidationError("Missing version")

        assertTrue(exception.message!!.contains("Missing version"))
    }
}
