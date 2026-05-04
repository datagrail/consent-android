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
 * Tests for ConsentService security enhancements:
 * - URL parameter encoding in saveOpen()
 * - Pending events queue cap (MAX_PENDING_EVENTS = 100)
 */
class ConsentServiceSecurityTest {
    @Mock
    private lateinit var mockNetworkClient: NetworkClient

    @Mock
    private lateinit var mockStorage: ConsentStorage

    private lateinit var service: ConsentService

    private val testConfig = createTestConfig()

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        whenever(mockStorage.getOrCreateUniqueId()).thenReturn("test-unique-id")
        service = ConsentService(mockNetworkClient, mockStorage, "consent.example.com")
    }

    // MARK: - URL Encoding Tests

    @Test
    fun `saveOpen URL-encodes customerId parameter`() =
        runTest {
            val configWithSpecialChars =
                testConfig.copy(dgCustomerId = "customer&id=with spaces")
            whenever(mockNetworkClient.request(any(), any(), anyOrNull(), anyOrNull())).thenReturn("")

            service.saveOpen(configWithSpecialChars)

            val urlCaptor = argumentCaptor<String>()
            verify(mockNetworkClient).request(urlCaptor.capture(), any(), anyOrNull(), anyOrNull())
            val capturedUrl = urlCaptor.firstValue

            // Should be URL-encoded: & -> %26, = -> %3D, space -> +
            assertTrue(
                "URL should contain encoded customerId, got: $capturedUrl",
                capturedUrl.contains("customerId=customer%26id%3Dwith+spaces"),
            )
        }

    @Test
    fun `saveOpen URL-encodes all query parameters`() =
        runTest {
            whenever(mockNetworkClient.request(any(), any(), anyOrNull(), anyOrNull())).thenReturn("")

            service.saveOpen(testConfig)

            val urlCaptor = argumentCaptor<String>()
            verify(mockNetworkClient).request(urlCaptor.capture(), any(), anyOrNull(), anyOrNull())
            val capturedUrl = urlCaptor.firstValue

            // Verify URL structure
            assertTrue("Should start with https", capturedUrl.startsWith("https://"))
            assertTrue("Should contain save_open endpoint", capturedUrl.contains("/save_open"))
            assertTrue("Should contain customerId param", capturedUrl.contains("customerId="))
            assertTrue("Should contain sessionId param", capturedUrl.contains("sessionId="))
            assertTrue("Should contain uniqueId param", capturedUrl.contains("uniqueId="))
            assertTrue("Should contain policy_name param", capturedUrl.contains("policy_name="))
        }

    @Test
    fun `saveOpen does not contain unencoded special characters in params`() =
        runTest {
            val configWithHtml =
                testConfig.copy(dgCustomerId = "<script>alert('xss')</script>")
            whenever(mockNetworkClient.request(any(), any(), anyOrNull(), anyOrNull())).thenReturn("")

            service.saveOpen(configWithHtml)

            val urlCaptor = argumentCaptor<String>()
            verify(mockNetworkClient).request(urlCaptor.capture(), any(), anyOrNull(), anyOrNull())
            val capturedUrl = urlCaptor.firstValue

            assertFalse("URL should not contain raw <", capturedUrl.contains("<script>"))
            assertFalse("URL should not contain raw >", capturedUrl.contains("</script>"))
        }

    // MARK: - Policy UUID Tests

    @Test
    fun `savePreferences sends real policy name and policyUuid in body`() =
        runTest {
            whenever(mockNetworkClient.request(any(), any(), anyOrNull(), anyOrNull())).thenReturn("")

            val preferences =
                ConsentPreferences(
                    isCustomised = true,
                    cookieOptions = listOf(CategoryConsent("cat1", true)),
                )

            service.savePreferences(preferences, testConfig)

            val bodyCaptor = argumentCaptor<String>()
            verify(mockNetworkClient).request(any(), any(), bodyCaptor.capture(), anyOrNull())
            val capturedBody = bodyCaptor.firstValue

            assertTrue(
                "Body should contain real policy name",
                capturedBody.contains("\"consentPolicy\":\"GDPR\""),
            )
            assertTrue(
                "Body should contain policyUuid",
                capturedBody.contains("\"policyUuid\":\"a1b2c3d4-e5f6-7890-abcd-ef1234567890\""),
            )
        }

    @Test
    fun `savePreferences omits policyUuid when uuid is null`() =
        runTest {
            whenever(mockNetworkClient.request(any(), any(), anyOrNull(), anyOrNull())).thenReturn("")

            val configWithoutUuid = testConfig.copy(
                consentPolicy = ConsentPolicy(name = "GDPR", default = true),
            )
            val preferences =
                ConsentPreferences(
                    isCustomised = true,
                    cookieOptions = listOf(CategoryConsent("cat1", true)),
                )

            service.savePreferences(preferences, configWithoutUuid)

            val bodyCaptor = argumentCaptor<String>()
            verify(mockNetworkClient).request(any(), any(), bodyCaptor.capture(), anyOrNull())
            val capturedBody = bodyCaptor.firstValue

            assertTrue(
                "Body should contain real policy name",
                capturedBody.contains("\"consentPolicy\":\"GDPR\""),
            )
            assertFalse(
                "Body should not contain policyUuid key when null",
                capturedBody.contains("policyUuid"),
            )
        }

    @Test
    fun `saveOpen sends real policy name and policy_uuid in URL`() =
        runTest {
            whenever(mockNetworkClient.request(any(), any(), anyOrNull(), anyOrNull())).thenReturn("")

            service.saveOpen(testConfig)

            val urlCaptor = argumentCaptor<String>()
            verify(mockNetworkClient).request(urlCaptor.capture(), any(), anyOrNull(), anyOrNull())
            val capturedUrl = urlCaptor.firstValue

            assertTrue(
                "URL should contain real policy name",
                capturedUrl.contains("policy_name=GDPR"),
            )
            assertTrue(
                "URL should contain policy_uuid param",
                capturedUrl.contains("policy_uuid=a1b2c3d4-e5f6-7890-abcd-ef1234567890"),
            )
        }

    @Test
    fun `saveOpen omits policy_uuid param when uuid is null`() =
        runTest {
            whenever(mockNetworkClient.request(any(), any(), anyOrNull(), anyOrNull())).thenReturn("")

            val configWithoutUuid = testConfig.copy(
                consentPolicy = ConsentPolicy(name = "GDPR", default = true),
            )

            service.saveOpen(configWithoutUuid)

            val urlCaptor = argumentCaptor<String>()
            verify(mockNetworkClient).request(urlCaptor.capture(), any(), anyOrNull(), anyOrNull())
            val capturedUrl = urlCaptor.firstValue

            assertTrue(
                "URL should contain real policy name",
                capturedUrl.contains("policy_name=GDPR"),
            )
            assertFalse(
                "URL should not contain policy_uuid when uuid is null",
                capturedUrl.contains("policy_uuid"),
            )
        }

    // MARK: - Queue Cap Tests

    @Test
    fun `savePreferences caps pending events at 100 on failure`() =
        runTest {
            // Set up 99 existing events
            val existingEvents = (1..99).map { """{"type":"save_open","url":"https://x.com","timestamp":"$it"}""" }
            whenever(mockStorage.loadPendingEvents()).thenReturn(existingEvents)
            whenever(mockNetworkClient.request(any(), any(), anyOrNull(), anyOrNull()))
                .thenThrow(RuntimeException("network error"))

            val preferences =
                ConsentPreferences(
                    isCustomised = true,
                    cookieOptions = listOf(CategoryConsent("cat1", true)),
                )

            try {
                service.savePreferences(preferences, testConfig)
            } catch (_: ConsentException.NetworkError) {
                // Expected
            }

            // 99 existing + 1 new = 100, which is at the cap
            val eventsCaptor = argumentCaptor<List<String>>()
            verify(mockStorage).savePendingEvents(eventsCaptor.capture())
            assertEquals(100, eventsCaptor.firstValue.size)
        }

    @Test
    fun `savePreferences drops oldest events when queue exceeds 100`() =
        runTest {
            // Set up 100 existing events
            val existingEvents = (1..100).map { """{"type":"save_open","url":"https://x.com","timestamp":"$it"}""" }
            whenever(mockStorage.loadPendingEvents()).thenReturn(existingEvents)
            whenever(mockNetworkClient.request(any(), any(), anyOrNull(), anyOrNull()))
                .thenThrow(RuntimeException("network error"))

            val preferences =
                ConsentPreferences(
                    isCustomised = true,
                    cookieOptions = listOf(CategoryConsent("cat1", true)),
                )

            try {
                service.savePreferences(preferences, testConfig)
            } catch (_: ConsentException.NetworkError) {
                // Expected
            }

            // 100 existing + 1 new = 101 -> should be trimmed to 100, oldest dropped
            val eventsCaptor = argumentCaptor<List<String>>()
            verify(mockStorage).savePendingEvents(eventsCaptor.capture())
            val savedEvents = eventsCaptor.firstValue
            assertEquals(100, savedEvents.size)

            // The first existing event (timestamp "1") should have been dropped
            assertFalse("Oldest event should be dropped", savedEvents.any { it.contains("\"timestamp\":\"1\"") })
            // The newest existing event should still be present
            assertTrue("Newest existing event should remain", savedEvents.any { it.contains("\"timestamp\":\"100\"") })
        }

    @Test
    fun `saveOpen caps pending events at 100 on failure`() =
        runTest {
            val existingEvents = (1..100).map { """{"type":"save_open","url":"https://x.com","timestamp":"$it"}""" }
            whenever(mockStorage.loadPendingEvents()).thenReturn(existingEvents)
            whenever(mockNetworkClient.request(any(), any(), anyOrNull(), anyOrNull()))
                .thenThrow(RuntimeException("network error"))

            service.saveOpen(testConfig)

            val eventsCaptor = argumentCaptor<List<String>>()
            verify(mockStorage).savePendingEvents(eventsCaptor.capture())
            assertEquals(100, eventsCaptor.firstValue.size)
        }

    @Test
    fun `savePreferences with empty queue adds event normally`() =
        runTest {
            whenever(mockStorage.loadPendingEvents()).thenReturn(emptyList())
            whenever(mockNetworkClient.request(any(), any(), anyOrNull(), anyOrNull()))
                .thenThrow(RuntimeException("network error"))

            val preferences =
                ConsentPreferences(
                    isCustomised = true,
                    cookieOptions = listOf(CategoryConsent("cat1", true)),
                )

            try {
                service.savePreferences(preferences, testConfig)
            } catch (_: ConsentException.NetworkError) {
                // Expected
            }

            val eventsCaptor = argumentCaptor<List<String>>()
            verify(mockStorage).savePendingEvents(eventsCaptor.capture())
            assertEquals(1, eventsCaptor.firstValue.size)
        }

    // MARK: - Helpers

    companion object {
        fun createTestConfig(): ConsentConfig {
            val layer =
                ConsentLayer(
                    id = "layer1",
                    name = "Main",
                    position = "bottom",
                    showCloseButton = true,
                    bannerApiId = "main",
                    elements =
                        listOf(
                            ConsentLayerElement(
                                id = "elem1",
                                order = 1,
                                type = "ConsentLayerTextElement",
                                translations =
                                    mapOf(
                                        "en" to ElementTranslation("t1", "en", "Test", null, null),
                                    ),
                            ),
                        ),
                )

            return ConsentConfig(
                version = "1.0.0",
                consentContainerVersionId = "container1",
                dgCustomerId = "test-customer-id",
                p = 0,
                dch = "categorize",
                dc = "dg-category-essential",
                privacyDomain = "consent.example.com",
                plugins =
                    Plugins(
                        scriptControl = false,
                        allCookieSubdomains = false,
                        cookieBlocking = false,
                        localStorageBlocking = false,
                        syncOTConsent = false,
                    ),
                testMode = false,
                ignoreDoNotTrack = false,
                trackingDetailsUrl = "https://example.com/tracking",
                consentMode = "optin",
                showBanner = true,
                consentPolicy =
                    ConsentPolicy(
                        name = "GDPR",
                        default = true,
                        uuid = "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
                    ),
                gppUsNat = false,
                initialCategories =
                    InitialCategories(
                        respectGpc = false,
                        respectDnt = false,
                        respectOptout = false,
                        initial = listOf("dg-category-essential"),
                        gpc = emptyList(),
                        optout = emptyList(),
                    ),
                layout =
                    Layout(
                        id = "layout1",
                        name = "Default",
                        description = null,
                        status = "published",
                        defaultLayout = true,
                        collapsedOnMobile = false,
                        firstLayerId = "layer1",
                        consentLayers = mapOf("layer1" to layer),
                    ),
            )
        }
    }
}
