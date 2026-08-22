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
import java.util.Locale

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

    // The golden identifier above is ALREADY normalized, so it reproduces the vector whether
    // or not normalization runs. These are the cases that fail when a normalization step is
    // missing — keep them in lockstep with the web and iOS SDKs.
    @Test
    fun `computeUserHash normalizes messy identifiers to the golden vector`() {
        val messy =
            listOf(
                "  User@Example.com  ",
                "User@Example.COM",
                "\tuser@example.com\n",
            )

        messy.forEach { identifier ->
            assertEquals(
                "normalization must map <$identifier> onto the golden hash",
                "1fee132c298d615098190e3e75f9c7e05db20d6cff6398f686fcebc67d1d87a4",
                ConsentService.computeUserHash(
                    dgCustomerId = "ac46d8ad-a67a-431f-a5d5-9e3eb922dae7",
                    consentProjectId = "proj_abc123",
                    identifier = identifier,
                ),
            )
        }
    }

    // Hashing an empty normalized identifier yields SHA-256 of the bare tenant prefix —
    // a valid-looking hash shared by every such caller, which would collapse unrelated
    // users onto one consent record.
    @Test
    fun `computeUserHash rejects identifiers that are empty after normalization`() {
        listOf("", "   ", "\t\n").forEach { identifier ->
            assertThrows(
                "must reject <$identifier> rather than hashing the bare tenant prefix",
                ConsentException.ValidationError::class.java,
            ) {
                ConsentService.computeUserHash(
                    dgCustomerId = "ac46d8ad-a67a-431f-a5d5-9e3eb922dae7",
                    consentProjectId = "proj_abc123",
                    identifier = identifier,
                )
            }
        }
    }

    @Test
    fun `normalizeUserIdentifier applies NFC then trim then lowercase`() {
        assertEquals(
            "user@example.com",
            ConsentService.normalizeUserIdentifier("  User@Example.com  "),
        )
    }

    @Test
    fun `normalizeUserIdentifier composes decomposed unicode`() {
        // "e" + combining acute (U+0301) vs the precomposed "é" (U+00E9): distinct byte
        // sequences for the same name, which NFC must reconcile.
        val decomposed = "jos\u0065\u0301@example.com"
        val precomposed = "jos\u00e9@example.com"

        assertNotEquals(decomposed, precomposed)
        assertEquals(
            ConsentService.normalizeUserIdentifier(decomposed),
            ConsentService.normalizeUserIdentifier(precomposed),
        )
    }

    @Test
    fun `normalizeUserIdentifier lowercases independently of the device locale`() {
        // Turkish locale maps "I" to the dotless "ı"; pinning to Locale.ROOT keeps the hash
        // identical regardless of the user's phone settings.
        val default = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("id@example.com", ConsentService.normalizeUserIdentifier("ID@example.com"))
        } finally {
            Locale.setDefault(default)
        }
    }

    // MARK: - Signal reconciliation

    @Test
    fun `suppression forces non-essential categories off even when stored map says true`() {
        val stored =
            mapOf(
                "dg-category-essential" to true,
                "dg-category-marketing" to true,
                "dg-category-performance" to true,
            )

        val reconciled =
            SignalReconciliation.reconcile(
                cookieOptions = stored,
                suppress = true,
                essentialKeys = setOf("dg-category-essential"),
            )

        assertTrue("essential preserved", reconciled["dg-category-essential"]!!)
        assertFalse("marketing suppressed", reconciled["dg-category-marketing"]!!)
        assertFalse("performance suppressed", reconciled["dg-category-performance"]!!)
    }

    @Test
    fun `no suppression leaves the stored map unchanged`() {
        val stored =
            mapOf(
                "dg-category-essential" to true,
                "dg-category-marketing" to true,
            )

        val reconciled =
            SignalReconciliation.reconcile(
                cookieOptions = stored,
                suppress = false,
                essentialKeys = setOf("dg-category-essential"),
            )

        assertEquals(stored, reconciled)
    }

    @Test
    fun `suppression never enables a category the user turned off`() {
        // A signal may only suppress. Ad-tracking permission is not consent to marketing, so no
        // signal state may flip a stored false to true — that would silently opt a user back in.
        val stored =
            mapOf(
                "dg-category-essential" to true,
                "dg-category-marketing" to false,
            )

        for (suppress in listOf(true, false)) {
            val reconciled =
                SignalReconciliation.reconcile(
                    cookieOptions = stored,
                    suppress = suppress,
                    essentialKeys = setOf("dg-category-essential"),
                )

            assertFalse("marketing stays off (suppress=$suppress)", reconciled["dg-category-marketing"]!!)
        }
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

            // Capture the payload the SDK hands the callback so we can assert the SDK-owned
            // nonce/timestamp are the exact values sent in the headers.
            var seenPayload: UniversalConsentSigningPayload? = null
            val provider: SignatureProvider = { payload ->
                seenPayload = payload
                UniversalConsentSignature(
                    signature = "deadbeefsig",
                    keyId = "key-1",
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
                ccpaOptout = false,
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

            assertNotNull("provider was invoked with a payload", seenPayload)
            val payload = seenPayload!!
            val headers = headersCaptor.firstValue
            assertEquals("dg_live_key", headers["X-DG-Api-Key"])
            assertEquals("deadbeefsig", headers["X-DG-Signature"])
            assertEquals("key-1", headers["X-DG-Key-Id"])

            // Nonce: 128-bit as 32 lowercase hex, from the SDK (not UUID.randomUUID()).
            val nonce = headers["X-DG-Nonce"]
            assertNotNull("nonce present", nonce)
            assertTrue(
                "nonce must be 32 lowercase hex, was <$nonce>",
                nonce!!.matches(Regex("^[0-9a-f]{32}\$")),
            )

            // The SDK owns the timestamp and nonce: the header values MUST be the same ones it
            // put in the payload the callback signed over.
            assertEquals(
                "timestamp header == signed timestamp",
                payload.timestamp.toString(),
                headers["X-DG-Timestamp"],
            )
            assertEquals("nonce header == signed nonce", payload.nonce, nonce)

            // The callback received the exact canonical string {cid}:{userHash}:{ts}:{nonce}.
            assertEquals(
                "ac46d8ad-a67a-431f-a5d5-9e3eb922dae7:" +
                    "1fee132c298d615098190e3e75f9c7e05db20d6cff6398f686fcebc67d1d87a4:" +
                    "${payload.timestamp}:${payload.nonce}",
                payload.stringToSign,
            )

            // Body carries the golden user_hash and a MAP cookieOptions.
            val body = bodyCaptor.firstValue
            assertTrue(
                "body carries golden user_hash",
                body.contains("1fee132c298d615098190e3e75f9c7e05db20d6cff6398f686fcebc67d1d87a4"),
            )
            assertTrue("cookieOptions is a map", body.contains("\"cookieOptions\":{"))
        }

    @Test
    fun `default preferences still serialize isCustomised and cookieOptions`() =
        runTest {
            // kotlinx.serialization omits properties holding their declared default unless
            // encodeDefaults is on, so a default-constructed UniversalConsentPreferences would
            // go out as `"consent_preferences":{}` — no isCustomised, no cookieOptions map. An
            // unbannered user's preferences legitimately look exactly like that.
            whenever(mockNetworkClient.request(any(), any(), anyOrNull(), anyOrNull())).thenReturn("")

            val config =
                ConsentServiceSecurityTest.createTestConfig().copy(
                    consentProjectId = "proj_abc123",
                    universalConsent = UniversalConsentConfig(enabled = true, syncOptout = false),
                )

            service.saveUniversalConsent(
                config = config,
                identifier = "user@example.com",
                preferences = UniversalConsentPreferences(),
                apiKey = "dg_live_key",
                ccpaOptout = false,
                getSignature = { _ ->
                    UniversalConsentSignature("sig", "key-1")
                },
            )

            val bodyCaptor = argumentCaptor<String>()
            verify(mockNetworkClient).request(any(), any(), bodyCaptor.capture(), anyOrNull())
            val body = bodyCaptor.firstValue

            assertTrue("isCustomised is present", body.contains("\"isCustomised\":false"))
            assertTrue("cookieOptions map is present", body.contains("\"cookieOptions\":{"))
            assertFalse(
                "consent_preferences must not collapse to an empty object",
                body.contains("\"consent_preferences\":{}"),
            )
            // The other defaulted scalars on the request must survive too.
            assertTrue("ccpa_optout is present", body.contains("\"ccpa_optout\":false"))
        }

    @Test
    fun `signed POST carries X-DG-Api-Key alongside the signature headers`() =
        runTest {
            // The CloudFront Function needs the API key on EVERY request (reads AND writes) to
            // resolve customer/tier/secret from KVS before it can verify the signature. Guard
            // against a refactor that drops the key from the write path.
            whenever(mockNetworkClient.request(any(), any(), anyOrNull(), anyOrNull())).thenReturn("")

            val config =
                ConsentServiceSecurityTest.createTestConfig().copy(consentProjectId = "proj_abc123")

            val provider: SignatureProvider = { _ ->
                UniversalConsentSignature("sig", "key-1")
            }

            service.saveUniversalConsent(
                config = config,
                identifier = "user@example.com",
                preferences = UniversalConsentPreferences(),
                apiKey = "dg_live_key",
                ccpaOptout = false,
                getSignature = provider,
            )

            val headersCaptor = argumentCaptor<Map<String, String>>()
            verify(mockNetworkClient).request(any(), any(), anyOrNull(), headersCaptor.capture())
            val headers = headersCaptor.firstValue

            assertEquals("API key must be present on signed writes", "dg_live_key", headers["X-DG-Api-Key"])
            // And it must coexist with the signature headers, not replace them.
            assertNotNull("signature still present", headers["X-DG-Signature"])
            assertNotNull("key id still present", headers["X-DG-Key-Id"])
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
            val provider: SignatureProvider = { payload ->
                seenCustomerId = payload.customerId
                seenUserHash = payload.userHash
                UniversalConsentSignature("sig", "k")
            }

            service.saveUniversalConsent(
                config = config,
                identifier = "user@example.com",
                preferences = UniversalConsentPreferences(),
                apiKey = "key",
                ccpaOptout = false,
                getSignature = provider,
            )

            assertEquals("ac46d8ad-a67a-431f-a5d5-9e3eb922dae7", seenCustomerId)
            assertEquals(
                "1fee132c298d615098190e3e75f9c7e05db20d6cff6398f686fcebc67d1d87a4",
                seenUserHash,
            )
        }

    @Test
    fun `limited mode with no callback sends the api key only`() =
        runTest {
            // No signing callback configured -> API-key-only write. The signature/timestamp/nonce
            // headers MUST be absent, not empty.
            whenever(mockNetworkClient.request(any(), any(), anyOrNull(), anyOrNull())).thenReturn("")

            val config =
                ConsentServiceSecurityTest.createTestConfig().copy(
                    consentProjectId = "proj_abc123",
                    universalConsent = UniversalConsentConfig(enabled = true, syncOptout = false),
                )

            service.saveUniversalConsent(
                config = config,
                identifier = "user@example.com",
                preferences = UniversalConsentPreferences(),
                apiKey = "dg_live_key",
                ccpaOptout = false,
                getSignature = null,
            )

            val headersCaptor = argumentCaptor<Map<String, String>>()
            verify(mockNetworkClient).request(any(), any(), anyOrNull(), headersCaptor.capture())
            val headers = headersCaptor.firstValue

            assertEquals("dg_live_key", headers["X-DG-Api-Key"])
            assertNull("no signature in limited mode", headers["X-DG-Signature"])
            assertNull("no timestamp in limited mode", headers["X-DG-Timestamp"])
            assertNull("no nonce in limited mode", headers["X-DG-Nonce"])
            assertNull("no key id in limited mode", headers["X-DG-Key-Id"])
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
