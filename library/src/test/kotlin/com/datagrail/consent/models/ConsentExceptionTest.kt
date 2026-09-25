package com.datagrail.consent.models

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for ConsentException HTTP error classification and the shared retry predicate.
 */
class ConsentExceptionTest {
    // MARK: - HttpError.isClientError

    @Test
    fun `isClientError is true for definite 4xx responses`() {
        for (statusCode in listOf(400, 401, 403, 404, 422)) {
            assertTrue("$statusCode should be a client error", ConsentException.HttpError(statusCode).isClientError)
        }
    }

    @Test
    fun `isClientError is false for 408, 429 and 5xx responses`() {
        for (statusCode in listOf(408, 429, 500, 503)) {
            assertFalse("$statusCode should not be a client error", ConsentException.HttpError(statusCode).isClientError)
        }
    }

    // MARK: - isRetryable

    @Test
    fun `isRetryable is false for definite rejections`() {
        assertFalse(ConsentException.isRetryable(ConsentException.HttpError(404)))
        assertFalse(ConsentException.isRetryable(ConsentException.HttpError(422)))
        assertFalse(ConsentException.isRetryable(ConsentException.ConfigNotPublished()))
    }

    @Test
    fun `isRetryable is true for transient and non-HTTP failures`() {
        assertTrue(ConsentException.isRetryable(ConsentException.HttpError(408)))
        assertTrue(ConsentException.isRetryable(ConsentException.HttpError(429)))
        assertTrue(ConsentException.isRetryable(ConsentException.HttpError(500)))
        assertTrue(ConsentException.isRetryable(ConsentException.NetworkError("timeout")))
        assertTrue(ConsentException.isRetryable(ConsentException.ParseError("bad json")))
        assertTrue(ConsentException.isRetryable(RuntimeException("boom")))
    }

    // MARK: - Compatibility

    @Test
    fun `HttpError is a NetworkError with the legacy message`() {
        val error: Exception = ConsentException.HttpError(404)

        assertTrue(error is ConsentException.NetworkError)
        assertTrue(error is ConsentException)
        assertEquals("Network error: HTTP 404", error.message)
    }

    @Test
    fun `ConfigNotPublished is a NetworkError that keeps its cause and omits the URL`() {
        val cause = ConsentException.HttpError(403)
        val error: Exception = ConsentException.ConfigNotPublished(cause)

        assertTrue(error is ConsentException.NetworkError)
        assertSame(cause, error.cause)
        assertFalse(error.message!!.contains("http"))
        assertFalse(error.message!!.contains("/"))
    }
}
