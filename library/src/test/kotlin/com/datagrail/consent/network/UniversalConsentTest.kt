package com.datagrail.consent.network

import com.datagrail.consent.models.*
import com.datagrail.consent.storage.ConsentStorage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.*

/**
 * Tests for Universal Consent support (TRUST-1843):
 * - Golden user_hash vector (must be identical across all SDKs)
 * - Mandatory client-side GPC reconciliation
 * - Signed POST attaches the required headers
 */
class UniversalConsentTest {
    @Mock
    private lateinit var mockNetworkClient: NetworkClient

    @Mock
    private lateinit var mockStorage: ConsentStorage

    private lateinit var service: ConsentService

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        whenever(mockStorage.getOrCreateUniqueId()).thenReturn("test-unique-id")
        service = ConsentService(mockNetworkClient, mockStorage, "consent.example.com")
    }

    // MARK: - Golden hash vector

    @Test
    fun `computeUserHash reproduces the cross-SDK golden vector`() {
        val hash =
            ConsentService.computeUserHash(
                dgCustomerId = "ac46d8ad-a67a-431f-a5d5-9e3eb922dae7",
                consentProjectId = "proj_abc123",
                identifier = "user@example.com",
            )

        assertEquals(
            "1fee132c298d615098190e3e75f9c7e05db20d6cff6398f686fcebc67d1d87a4",
            hash,
        )
        assertEquals("hash must be 64 hex chars", 64, hash.length)
    }

    @Test
    fun `computeUserHash does not normalize the identifier`() {
        // Verbatim identifier — the hash depends on the exact bytes, no lowercasing/trimming.
        val lower = ConsentService.computeUserHash("cid", "pid", "User@Example.com")
        val upper = ConsentService.computeUserHash("cid", "pid", "user@example.com")
        assertNotEquals(lower, upper)
    }

    // MARK: - GPC reconciliation

    @Test
    fun `GPC true suppresses non-essential categories even when stored map says true`() {
        val stored =
            mapOf(
                "dg-category-essential" to true,
                "dg-category-marketing" to true,
                "dg-category-performance" to true,
            )

        val reconciled =
            GpcReconciliation.reconcile(
                cookieOptions = stored,
                gpc = true,
                essentialKeys = setOf("dg-category-essential"),
            )

        assertTrue("essential preserved", reconciled["dg-category-essential"]!!)
        assertFalse("marketing suppressed by GPC", reconciled["dg-category-marketing"]!!)
        assertFalse("performance suppressed by GPC", reconciled["dg-category-performance"]!!)
    }

    @Test
    fun `GPC false leaves the stored map unchanged`() {
        val stored =
            mapOf(
                "dg-category-essential" to true,
                "dg-category-marketing" to true,
            )

        val reconciled =
            GpcReconciliation.reconcile(
                cookieOptions = stored,
                gpc = false,
                essentialKeys = setOf("dg-category-essential"),
            )

        assertEquals(stored, reconciled)
    }

    // MARK: - Signed POST

    @Test
    fun `saveUniversalConsent attaches signature headers and hits CloudFront path`() =
        runTest {
            whenever(mockNetworkClient.request(any(), any(), anyOrNull(), anyOrNull())).thenReturn("")

            val config =
                ConsentServiceSecurityTest.createTestConfig().copy(
                    dgCustomerId = "ac46d8ad-a67a-431f-a5d5-9e3eb922dae7",
                    consentProjectId = "proj_abc123",
                    universalConsent = UniversalConsentConfig(enabled = true, syncOptout = false),
                )

            val provider: SignatureProvider = { _, _ ->
                UniversalConsentSignature(
                    signature = "deadbeefsig",
                    keyId = "key-1",
                    timestamp = 1_700_000_000L,
                )
            }

            service.saveUniversalConsent(
                config = config,
                identifier = "user@example.com",
                preferences =
                    UniversalConsentPreferences(
                        isCustomised = true,
                        cookieOptions = mapOf("dg-category-marketing" to false),
                    ),
                apiKey = "dg_live_key",
                getSignature = provider,
            )

            val urlCaptor = argumentCaptor<String>()
            val methodCaptor = argumentCaptor<HTTPMethod>()
            val bodyCaptor = argumentCaptor<String>()
            val headersCaptor = argumentCaptor<Map<String, String>>()
            verify(mockNetworkClient).request(
                urlCaptor.capture(),
                methodCaptor.capture(),
                bodyCaptor.capture(),
                headersCaptor.capture(),
            )

            // CloudFront behavior — no /api/v1/ prefix.
            assertEquals("https://consent.example.com/universal_consent", urlCaptor.firstValue)
            assertEquals(HTTPMethod.POST, methodCaptor.firstValue)

            val headers = headersCaptor.firstValue
            assertEquals("dg_live_key", headers["X-DG-Api-Key"])
            assertEquals("deadbeefsig", headers["X-DG-Signature"])
            assertEquals("1700000000", headers["X-DG-Timestamp"])
            assertEquals("key-1", headers["X-DG-Key-Id"])
            assertNotNull("nonce present", headers["X-DG-Nonce"])

            // Body carries the golden user_hash and a MAP cookieOptions.
            val body = bodyCaptor.firstValue
            assertTrue(
                "body carries golden user_hash",
                body.contains("1fee132c298d615098190e3e75f9c7e05db20d6cff6398f686fcebc67d1d87a4"),
            )
            assertTrue("cookieOptions is a map", body.contains("\"cookieOptions\":{"))
        }

    @Test
    fun `saveUniversalConsent invokes the signature provider with customer id and user hash`() =
        runTest {
            whenever(mockNetworkClient.request(any(), any(), anyOrNull(), anyOrNull())).thenReturn("")

            val config =
                ConsentServiceSecurityTest.createTestConfig().copy(
                    dgCustomerId = "ac46d8ad-a67a-431f-a5d5-9e3eb922dae7",
                    consentProjectId = "proj_abc123",
                )

            var seenCustomerId: String? = null
            var seenUserHash: String? = null
            val provider: SignatureProvider = { customerId, userHash ->
                seenCustomerId = customerId
                seenUserHash = userHash
                UniversalConsentSignature("sig", "k", 1L)
            }

            service.saveUniversalConsent(
                config = config,
                identifier = "user@example.com",
                preferences = UniversalConsentPreferences(),
                apiKey = "key",
                getSignature = provider,
            )

            assertEquals("ac46d8ad-a67a-431f-a5d5-9e3eb922dae7", seenCustomerId)
            assertEquals(
                "1fee132c298d615098190e3e75f9c7e05db20d6cff6398f686fcebc67d1d87a4",
                seenUserHash,
            )
        }

    @Test
    fun `getUniversalConsent returns null on not_found`() =
        runTest {
            whenever(mockNetworkClient.request(any(), any(), anyOrNull(), anyOrNull()))
                .thenReturn("""{"status":"not_found"}""")

            val config =
                ConsentServiceSecurityTest.createTestConfig().copy(consentProjectId = "proj_abc123")

            val result = service.getUniversalConsent(config, "user@example.com", "key")
            assertNull(result)
        }

    @Test
    fun `getUniversalConsent sends API key and CloudFront GET url`() =
        runTest {
            whenever(mockNetworkClient.request(any(), any(), anyOrNull(), anyOrNull()))
                .thenReturn("""{"status":"found","consent_preferences":{"isCustomised":true,"cookieOptions":{"dg-category-marketing":true}},"gpc":false}""")

            val config =
                ConsentServiceSecurityTest.createTestConfig().copy(
                    dgCustomerId = "ac46d8ad-a67a-431f-a5d5-9e3eb922dae7",
                    consentProjectId = "proj_abc123",
                )

            val record = service.getUniversalConsent(config, "user@example.com", "dg_live_key")
            assertNotNull(record)
            assertTrue(record!!.isFound)

            val urlCaptor = argumentCaptor<String>()
            val methodCaptor = argumentCaptor<HTTPMethod>()
            val headersCaptor = argumentCaptor<Map<String, String>>()
            verify(mockNetworkClient).request(
                urlCaptor.capture(),
                methodCaptor.capture(),
                anyOrNull(),
                headersCaptor.capture(),
            )

            assertEquals(HTTPMethod.GET, methodCaptor.firstValue)
            assertTrue(urlCaptor.firstValue.startsWith("https://consent.example.com/universal_consent?"))
            assertFalse("no /api/v1/ prefix", urlCaptor.firstValue.contains("/api/v1/"))
            assertTrue(
                urlCaptor.firstValue.contains("user_hash=1fee132c298d615098190e3e75f9c7e05db20d6cff6398f686fcebc67d1d87a4"),
            )
            assertEquals("dg_live_key", headersCaptor.firstValue["X-DG-Api-Key"])
        }
}
