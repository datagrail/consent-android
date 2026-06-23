package com.datagrail.consent.network

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

// Pure-JVM test: PairingService no longer touches android.net.Uri, so no Robolectric.
class PairingServiceTest {
    private lateinit var mockNetworkClient: NetworkClient
    private lateinit var pairingService: PairingService

    @Before
    fun setup() {
        mockNetworkClient = mock()
        pairingService =
            PairingService(
                networkClient = mockNetworkClient,
                apiBaseUrl = "https://api.example.com",
                apiKey = "test-key",
            )
    }

    @Test
    fun testQrUrl_queryStringCorrectness() {
        val url =
            pairingService.qrUrl(
                publicBaseUrl = "https://example.com",
                customerId = "cust123",
                userHash = "a".repeat(64),
                configUrl = "https://config.example.com/consent.json",
            )

        assertTrue(url.startsWith("https://example.com/tv?"))
        assertTrue(url.contains("customer_id=cust123"))
        assertTrue(url.contains("user_hash=${"a".repeat(64)}"))
        // config_url should be URL-encoded
        assertTrue(url.contains("config_url=https%3A%2F%2Fconfig.example.com%2Fconsent.json"))
    }

    @Test
    fun testQrUrl_configUrlUrlEncoded() {
        val url =
            pairingService.qrUrl(
                publicBaseUrl = "https://example.com",
                customerId = "cust",
                userHash = "a".repeat(64),
                configUrl = "https://example.com/config?param=value&other=test",
            )

        // Special characters should be encoded
        assertTrue(url.contains("config_url=https%3A%2F%2Fexample.com%2Fconfig%3Fparam%3Dvalue%26other%3Dtest"))
    }

    @Test
    fun testFetchConsent_notFound() =
        runTest {
            val responseJson = """{"status": "not_found"}"""
            whenever(
                mockNetworkClient.request(
                    url = any(),
                    method = eq(HTTPMethod.GET),
                    body = eq(null),
                    headers = any(),
                ),
            ).thenReturn(responseJson)

            val result = pairingService.fetchConsent("cust", "a".repeat(64))

            assertTrue(result is PairingRead.NotFound)
        }

    @Test
    fun testFetchConsent_foundWithMapAdapter() =
        runTest {
            // Server returns cookieOptions as a MAP (the real API shape)
            val responseJson =
                """
            {
                "status": "found",
                "consent_preferences": {
                    "isCustomised": true,
                    "cookieOptions": {
                        "dg-category-marketing": false,
                        "dg-category-analytics": true,
                        "dg-category-essential": true
                    }
                },
                "updated_at": "2026-06-02T12:34:56Z"
            }
                """.trimIndent()

            whenever(
                mockNetworkClient.request(
                    url = any(),
                    method = eq(HTTPMethod.GET),
                    body = eq(null),
                    headers = any(),
                ),
            ).thenReturn(responseJson)

            val result = pairingService.fetchConsent("cust", "a".repeat(64))

            // Verify the map was adapted to an array of CategoryConsent
            assertTrue(result is PairingRead.Found)
            val found = result as PairingRead.Found

            assertEquals(true, found.preferences.isCustomised)
            assertEquals(3, found.preferences.cookieOptions.size)

            // Verify each category was adapted correctly
            val marketing = found.preferences.cookieOptions.find { it.gtmKey == "dg-category-marketing" }
            assertNotNull(marketing)
            assertEquals(false, marketing?.isEnabled)

            val analytics = found.preferences.cookieOptions.find { it.gtmKey == "dg-category-analytics" }
            assertNotNull(analytics)
            assertEquals(true, analytics?.isEnabled)

            assertEquals("2026-06-02T12:34:56Z", found.updatedAt)
        }

    @Test
    fun testFetchConsent_foundWithoutUpdatedAt() =
        runTest {
            val responseJson =
                """
            {
                "status": "found",
                "consent_preferences": {
                    "isCustomised": false,
                    "cookieOptions": {
                        "dg-category-essential": true
                    }
                }
            }
                """.trimIndent()

            whenever(
                mockNetworkClient.request(
                    url = any(),
                    method = eq(HTTPMethod.GET),
                    body = eq(null),
                    headers = any(),
                ),
            ).thenReturn(responseJson)

            val result = pairingService.fetchConsent("cust", "a".repeat(64))

            assertTrue(result is PairingRead.Found)
            val found = result as PairingRead.Found

            assertEquals(null, found.updatedAt)
            assertEquals(1, found.preferences.cookieOptions.size)
        }

    @Test
    fun testFetchConsent_includesApiKeyHeader() =
        runTest {
            val responseJson = """{"status": "not_found"}"""

            var capturedHeaders: Map<String, String>? = null
            whenever(
                mockNetworkClient.request(
                    url = any(),
                    method = eq(HTTPMethod.GET),
                    body = eq(null),
                    headers = any(),
                ),
            ).thenAnswer { invocation ->
                capturedHeaders = invocation.getArgument(3)
                responseJson
            }

            pairingService.fetchConsent("cust", "a".repeat(64))

            assertNotNull(capturedHeaders)
            assertEquals("test-key", capturedHeaders?.get("X-DG-Api-Key"))
            assertEquals("no-cache", capturedHeaders?.get("Cache-Control"))
        }

    @Test
    fun testFetchConsent_noApiKey() =
        runTest {
            val serviceWithoutKey =
                PairingService(
                    networkClient = mockNetworkClient,
                    apiBaseUrl = "https://api.example.com",
                    apiKey = null,
                )

            val responseJson = """{"status": "not_found"}"""

            var capturedHeaders: Map<String, String>? = null
            whenever(
                mockNetworkClient.request(
                    url = any(),
                    method = eq(HTTPMethod.GET),
                    body = eq(null),
                    headers = any(),
                ),
            ).thenAnswer { invocation ->
                capturedHeaders = invocation.getArgument(3)
                responseJson
            }

            serviceWithoutKey.fetchConsent("cust", "a".repeat(64))

            assertNotNull(capturedHeaders)
            assertEquals(null, capturedHeaders?.get("X-DG-Api-Key"))
            assertEquals("no-cache", capturedHeaders?.get("Cache-Control"))
        }
}
