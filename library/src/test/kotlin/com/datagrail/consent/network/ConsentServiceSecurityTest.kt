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

    // MARK: - saveOpen POST Tests

    @Test
    fun `saveOpen sends POST request with JSON body`() =
        runTest {
            whenever(mockNetworkClient.request(any(), any(), anyOrNull(), anyOrNull())).thenReturn("")

            service.saveOpen(testConfig)

            val urlCaptor = argumentCaptor<String>()
            val methodCaptor = argumentCaptor<HTTPMethod>()
            val bodyCaptor = argumentCaptor<String>()
            verify(mockNetworkClient).request(urlCaptor.capture(), methodCaptor.capture(), bodyCaptor.capture(), anyOrNull())

            assertEquals("Should use POST", HTTPMethod.POST, methodCaptor.firstValue)
            assertTrue("Should target /save_open", urlCaptor.firstValue.endsWith("/save_open"))
            assertTrue("Should start with https", urlCaptor.firstValue.startsWith("https://"))

            val body = bodyCaptor.firstValue
            assertTrue("Body should contain customer", body.contains("\"customer\":\"test-customer-id\""))
            assertTrue("Body should contain user_id", body.contains("\"user_id\":"))
            assertTrue("Body should contain policy_name", body.contains("\"policy_name\":"))
            assertTrue("Body should contain action", body.contains("\"action\":\"open\""))
            assertTrue("Body should contain user_agent", body.contains("\"user_agent\":"))
            assertTrue("Body should contain language", body.contains("\"language\":"))
            assertTrue("Body should contain timestamp", body.contains("\"timestamp\":"))
        }

    @Test
    fun `saveOpen uses analyticsEndpoint when configured`() =
        runTest {
            val configWithEndpoint = testConfig.copy(analyticsEndpoint = "analytics.example.com")
            whenever(mockNetworkClient.request(any(), any(), anyOrNull(), anyOrNull())).thenReturn("")

            service.saveOpen(configWithEndpoint)

            val urlCaptor = argumentCaptor<String>()
            verify(mockNetworkClient).request(urlCaptor.capture(), any(), anyOrNull(), anyOrNull())

            assertTrue(
                "Should use analyticsEndpoint, got: ${urlCaptor.firstValue}",
                urlCaptor.firstValue.startsWith("https://analytics.example.com/"),
            )
        }

    @Test
    fun `saveOpen falls back to privacyDomain when analyticsEndpoint is null`() =
        runTest {
            whenever(mockNetworkClient.request(any(), any(), anyOrNull(), anyOrNull())).thenReturn("")

            service.saveOpen(testConfig)

            val urlCaptor = argumentCaptor<String>()
            verify(mockNetworkClient).request(urlCaptor.capture(), any(), anyOrNull(), anyOrNull())

            assertTrue(
                "Should use privacyDomain, got: ${urlCaptor.firstValue}",
                urlCaptor.firstValue.startsWith("https://consent.example.com/"),
            )
        }

    @Test
    fun `saveOpen body does not contain unescaped HTML in customer field`() =
        runTest {
            val configWithHtml =
                testConfig.copy(dgCustomerId = "<script>alert('xss')</script>")
            whenever(mockNetworkClient.request(any(), any(), anyOrNull(), anyOrNull())).thenReturn("")

            service.saveOpen(configWithHtml)

            val bodyCaptor = argumentCaptor<String>()
            verify(mockNetworkClient).request(any(), any(), bodyCaptor.capture(), anyOrNull())
            val body = bodyCaptor.firstValue

            // JSON encoding escapes angle brackets
            assertFalse("Body should not contain raw <script>", body.contains("<script>"))
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
                consentPolicy = ConsentPolicy("GDPR", true),
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
