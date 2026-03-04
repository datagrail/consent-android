package com.datagrail.consent.network

import com.datagrail.consent.models.ConsentException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for NetworkClient security enhancements:
 * - HTTPS enforcement
 * - Exponential backoff with jitter
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NetworkClientSecurityTest {
    private val networkClient = NetworkClient()

    // MARK: - HTTPS Enforcement

    @Test
    fun `request with http URL throws NetworkError`() =
        runTest {
            try {
                networkClient.request("http://example.com/api")
                fail("Expected NetworkError for HTTP URL")
            } catch (e: ConsentException.NetworkError) {
                assertTrue(e.message!!.contains("HTTPS"))
            }
        }

    @Test
    fun `request with ftp URL throws NetworkError`() =
        runTest {
            try {
                networkClient.request("ftp://example.com/file")
                fail("Expected NetworkError for FTP URL")
            } catch (e: ConsentException.NetworkError) {
                assertTrue(e.message!!.contains("HTTPS"))
            }
        }

    @Test
    fun `request with file URL throws NetworkError`() =
        runTest {
            try {
                networkClient.request("file:///etc/passwd")
                fail("Expected NetworkError for file URL")
            } catch (e: ConsentException.NetworkError) {
                assertTrue(e.message!!.contains("HTTPS"))
            }
        }

    @Test
    fun `request with javascript URL throws NetworkError`() =
        runTest {
            try {
                networkClient.request("javascript:alert(1)")
                fail("Expected NetworkError for javascript URL")
            } catch (e: Exception) {
                // May throw NetworkError or MalformedURLException wrapped in NetworkError
                assertTrue(e is ConsentException.NetworkError || e is java.net.MalformedURLException)
            }
        }

    // MARK: - Retry with Jitter

    @Test
    fun `retryWithBackoff succeeds on first attempt`() =
        runTest {
            var callCount = 0
            val result =
                networkClient.retryWithBackoff(maxAttempts = 3, baseDelayMs = 100) {
                    callCount++
                    "success"
                }

            assertEquals("success", result)
            assertEquals(1, callCount)
        }

    @Test
    fun `retryWithBackoff retries on failure and succeeds`() =
        runTest {
            var callCount = 0
            val result =
                networkClient.retryWithBackoff(maxAttempts = 3, baseDelayMs = 100) {
                    callCount++
                    if (callCount < 3) throw RuntimeException("fail")
                    "success"
                }

            assertEquals("success", result)
            assertEquals(3, callCount)
        }

    @Test
    fun `retryWithBackoff throws after all attempts exhausted`() =
        runTest {
            var callCount = 0
            try {
                networkClient.retryWithBackoff(maxAttempts = 3, baseDelayMs = 100) {
                    callCount++
                    throw RuntimeException("always fails")
                }
                fail("Expected exception after exhausted retries")
            } catch (e: RuntimeException) {
                assertEquals("always fails", e.message)
            }
            assertEquals(3, callCount)
        }

    @Test
    fun `retryWithBackoff delay includes jitter`() =
        runTest {
            var callCount = 0

            networkClient.retryWithBackoff(maxAttempts = 2, baseDelayMs = 1000) {
                callCount++
                if (callCount < 2) throw RuntimeException("fail")
                "success"
            }

            // Base delay for attempt 1 = 1000 * 2^1 = 2000ms
            // With up to 25% jitter = 2000..2500ms
            val elapsed = currentTime
            assertTrue("Delay should be at least base exponential (2000ms), was ${elapsed}ms", elapsed >= 2000)
            assertTrue("Delay should be at most base + 25% jitter (2500ms), was ${elapsed}ms", elapsed <= 2500)
        }

    @Test
    fun `retryWithBackoff delays increase exponentially`() =
        runTest {
            var callCount = 0
            val startTime = currentTime

            try {
                networkClient.retryWithBackoff(maxAttempts = 4, baseDelayMs = 100) {
                    callCount++
                    throw RuntimeException("fail")
                }
            } catch (_: RuntimeException) {
                // Expected
            }

            assertEquals(4, callCount)
            val totalTime = currentTime - startTime
            // Delays: 200 + 400 + 800 = 1400ms base, plus up to 25% jitter on each
            // Minimum = 1400, Maximum = 1400 + 50 + 100 + 200 = 1750
            assertTrue("Total delay ($totalTime) should be >= 1400", totalTime >= 1400)
            assertTrue("Total delay ($totalTime) should be <= 1750", totalTime <= 1750)
        }
}
