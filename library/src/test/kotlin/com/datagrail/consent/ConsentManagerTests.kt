package com.datagrail.consent

import com.datagrail.consent.models.*
import com.datagrail.consent.network.ConfigService
import com.datagrail.consent.network.ConsentService
import com.datagrail.consent.storage.ConsentStorage
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

/**
 * Tests for ConsentManager state management and category detection
 */
class ConsentManagerTests {
    private lateinit var sut: ConsentManager

    @Mock
    private lateinit var mockStorage: ConsentStorage

    @Mock
    private lateinit var mockConfigService: ConfigService

    @Mock
    private lateinit var mockConsentService: ConsentService

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        sut = ConsentManager(mockStorage, mockConfigService, mockConsentService)
    }

    // MARK: - getUserPreferences Tests

    @Test
    fun `getUserPreferences with no saved preferences returns null`() {
        // Given
        whenever(mockStorage.loadPreferences()).thenReturn(null)

        // When
        val preferences = sut.getUserPreferences()

        // Then
        assertNull(preferences)
    }

    @Test
    fun `getUserPreferences with saved preferences returns saved preferences`() {
        // Given
        val savedPreferences =
            ConsentPreferences(
                isCustomised = true,
                cookieOptions =
                    listOf(
                        CategoryConsent(gtmKey = "dg-category-essential", isEnabled = true),
                        CategoryConsent(gtmKey = "dg-category-marketing", isEnabled = false),
                    ),
            )
        whenever(mockStorage.loadPreferences()).thenReturn(savedPreferences)

        // When
        val preferences = sut.getUserPreferences()

        // Then
        assertNotNull(preferences)
        assertTrue(preferences!!.isCustomised)
        assertEquals(2, preferences.cookieOptions.size)
        assertTrue(preferences.isCategoryEnabled("dg-category-essential"))
        assertFalse(preferences.isCategoryEnabled("dg-category-marketing"))
    }

    // MARK: - getDefaultPreferences Tests

    @Test
    fun `getDefaultPreferences with no config returns null`() {
        // Given - no config loaded

        // When
        val preferences = sut.getDefaultPreferences()

        // Then
        assertNull(preferences)
    }

    @Test
    fun `getDefaultPreferences with config returns initial categories`() {
        // Given
        val config =
            createMockConfigWithInitialCategories(
                listOf(
                    "dg-category-essential",
                    "dg-category-marketing",
                    "dg-category-performance",
                    "dg-category-functional",
                ),
            )
        sut.currentConfig = config

        // When
        val preferences = sut.getDefaultPreferences()

        // Then
        assertNotNull(preferences)
        assertFalse(preferences!!.isCustomised)
        assertEquals(4, preferences.cookieOptions.size)

        // All categories from initialCategories.initial should be enabled
        assertTrue(preferences.isCategoryEnabled("dg-category-essential"))
        assertTrue(preferences.isCategoryEnabled("dg-category-marketing"))
        assertTrue(preferences.isCategoryEnabled("dg-category-performance"))
        assertTrue(preferences.isCategoryEnabled("dg-category-functional"))
    }

    // MARK: - getCategories Tests

    @Test
    fun `getCategories with no config and no preferences returns null`() {
        // Given
        whenever(mockStorage.loadPreferences()).thenReturn(null)

        // When
        val preferences = sut.getCategories()

        // Then
        assertNull(preferences)
    }

    @Test
    fun `getCategories with config but no saved preferences returns default preferences`() {
        // Given
        val config =
            createMockConfigWithInitialCategories(
                listOf(
                    "dg-category-essential",
                    "dg-category-marketing",
                    "dg-category-performance",
                    "dg-category-functional",
                ),
            )
        sut.currentConfig = config
        whenever(mockStorage.loadPreferences()).thenReturn(null)

        // When
        val preferences = sut.getCategories()

        // Then
        assertNotNull(preferences)
        assertFalse(preferences!!.isCustomised)
        assertEquals(4, preferences.cookieOptions.size)

        // All categories from initialCategories.initial should be enabled
        assertTrue(preferences.isCategoryEnabled("dg-category-essential"))
        assertTrue(preferences.isCategoryEnabled("dg-category-marketing"))
        assertTrue(preferences.isCategoryEnabled("dg-category-performance"))
        assertTrue(preferences.isCategoryEnabled("dg-category-functional"))
    }

    @Test
    fun `getCategories with saved preferences returns saved preferences`() {
        // Given
        val config =
            createMockConfigWithInitialCategories(
                listOf(
                    "dg-category-essential",
                    "dg-category-marketing",
                    "dg-category-performance",
                    "dg-category-functional",
                ),
            )
        sut.currentConfig = config

        val savedPreferences =
            ConsentPreferences(
                isCustomised = true,
                cookieOptions =
                    listOf(
                        CategoryConsent(gtmKey = "dg-category-essential", isEnabled = true),
                        CategoryConsent(gtmKey = "dg-category-marketing", isEnabled = false),
                        CategoryConsent(gtmKey = "dg-category-performance", isEnabled = false),
                        CategoryConsent(gtmKey = "dg-category-functional", isEnabled = true),
                    ),
            )
        whenever(mockStorage.loadPreferences()).thenReturn(savedPreferences)

        // When
        val preferences = sut.getCategories()

        // Then
        assertNotNull(preferences)
        assertTrue(preferences!!.isCustomised)
        assertTrue(preferences.isCategoryEnabled("dg-category-essential"))
        assertFalse(preferences.isCategoryEnabled("dg-category-marketing"))
        assertFalse(preferences.isCategoryEnabled("dg-category-performance"))
        assertTrue(preferences.isCategoryEnabled("dg-category-functional"))
    }

    // MARK: - isCategoryEnabled Tests

    @Test
    fun `isCategoryEnabled with no config or preferences returns false`() {
        // Given
        whenever(mockStorage.loadPreferences()).thenReturn(null)

        // When
        val isEnabled = sut.isCategoryEnabled("dg-category-marketing")

        // Then
        assertFalse(isEnabled)
    }

    @Test
    fun `isCategoryEnabled with config but no saved preferences uses initial categories`() {
        // Given
        val config =
            createMockConfigWithInitialCategories(
                listOf(
                    "dg-category-essential",
                    "dg-category-marketing",
                    "dg-category-performance",
                    "dg-category-functional",
                ),
            )
        sut.currentConfig = config
        whenever(mockStorage.loadPreferences()).thenReturn(null)

        // When/Then - Categories in initialCategories.initial should be enabled
        assertTrue(sut.isCategoryEnabled("dg-category-essential"))
        assertTrue(sut.isCategoryEnabled("dg-category-marketing"))
        assertTrue(sut.isCategoryEnabled("dg-category-performance"))
        assertTrue(sut.isCategoryEnabled("dg-category-functional"))

        // Category not in initial list should be disabled
        assertFalse(sut.isCategoryEnabled("dg-category-unknown"))
    }

    @Test
    fun `isCategoryEnabled with saved preferences uses saved values`() {
        // Given
        val config =
            createMockConfigWithInitialCategories(
                listOf("dg-category-essential", "dg-category-marketing"),
            )
        sut.currentConfig = config

        val savedPreferences =
            ConsentPreferences(
                isCustomised = true,
                cookieOptions =
                    listOf(
                        CategoryConsent(gtmKey = "dg-category-essential", isEnabled = true),
                        CategoryConsent(gtmKey = "dg-category-marketing", isEnabled = false),
                    ),
            )
        whenever(mockStorage.loadPreferences()).thenReturn(savedPreferences)

        // When/Then
        assertTrue(sut.isCategoryEnabled("dg-category-essential"))
        assertFalse(sut.isCategoryEnabled("dg-category-marketing"))
    }

    // MARK: - needsConsent Tests

    @Test
    fun `needsConsent with no config returns false`() {
        // Given - no config loaded

        // When
        val needsConsent = sut.needsConsent()

        // Then
        assertFalse(needsConsent)
    }

    @Test
    fun `needsConsent with showBanner false returns false`() {
        // Given
        val config = createMockConfigWithShowBanner(showBanner = false)
        sut.currentConfig = config
        whenever(mockStorage.loadPreferences()).thenReturn(null)

        // When
        val needsConsent = sut.needsConsent()

        // Then
        assertFalse(needsConsent)
    }

    @Test
    fun `needsConsent with showBanner true and no preferences returns true`() {
        // Given
        val config = createMockConfigWithShowBanner(showBanner = true)
        sut.currentConfig = config
        whenever(mockStorage.loadPreferences()).thenReturn(null)

        // When
        val needsConsent = sut.needsConsent()

        // Then
        assertTrue(needsConsent)
    }

    @Test
    fun `needsConsent with saved preferences and same version returns false`() {
        // Given
        val config = createMockConfigWithShowBanner(showBanner = true, version = "v1")
        sut.currentConfig = config

        val savedPreferences =
            ConsentPreferences(
                isCustomised = true,
                cookieOptions = listOf(CategoryConsent(gtmKey = "dg-category-essential", isEnabled = true)),
            )
        whenever(mockStorage.loadPreferences()).thenReturn(savedPreferences)
        whenever(mockStorage.loadConfigVersion()).thenReturn("v1")

        // When
        val needsConsent = sut.needsConsent()

        // Then
        assertFalse(needsConsent)
    }

    @Test
    fun `needsConsent with saved preferences but different version returns true`() {
        // Given
        val config = createMockConfigWithShowBanner(showBanner = true, version = "v2")
        sut.currentConfig = config

        val savedPreferences =
            ConsentPreferences(
                isCustomised = true,
                cookieOptions = listOf(CategoryConsent(gtmKey = "dg-category-essential", isEnabled = true)),
            )
        whenever(mockStorage.loadPreferences()).thenReturn(savedPreferences)
        whenever(mockStorage.loadConfigVersion()).thenReturn("v1")

        // When
        val needsConsent = sut.needsConsent()

        // Then
        assertTrue(needsConsent)
    }

    // MARK: - Essential Categories Tests

    @Test
    fun `getEssentialCategories with no config returns empty`() {
        // Given - no config loaded

        // When
        val essentialCategories = sut.getEssentialCategories()

        // Then
        assertTrue(essentialCategories.isEmpty())
    }

    @Test
    fun `getEssentialCategories with alwaysOn categories returns correct keys`() {
        // Given
        val config =
            createMockConfig(
                listOf(
                    MockCategory("category_essential", alwaysOn = true),
                    MockCategory("category_marketing", alwaysOn = false),
                    MockCategory("category_analytics", alwaysOn = false),
                ),
            )
        sut.currentConfig = config

        // When
        val essentialCategories = sut.getEssentialCategories()

        // Then
        assertEquals(1, essentialCategories.size)
        assertTrue(essentialCategories.contains("category_essential"))
        assertFalse(essentialCategories.contains("category_marketing"))
    }

    @Test
    fun `getEssentialCategories with multiple alwaysOn returns all`() {
        // Given
        val config =
            createMockConfig(
                listOf(
                    MockCategory("category_essential", alwaysOn = true),
                    MockCategory("category_functional", alwaysOn = true),
                    MockCategory("category_marketing", alwaysOn = false),
                ),
            )
        sut.currentConfig = config

        // When
        val essentialCategories = sut.getEssentialCategories()

        // Then
        assertEquals(2, essentialCategories.size)
        assertTrue(essentialCategories.contains("category_essential"))
        assertTrue(essentialCategories.contains("category_functional"))
        assertFalse(essentialCategories.contains("category_marketing"))
    }

    @Test
    fun `getEssentialCategories with no alwaysOn returns empty`() {
        // Given
        val config =
            createMockConfig(
                listOf(
                    MockCategory("category_marketing", alwaysOn = false),
                    MockCategory("category_analytics", alwaysOn = false),
                ),
            )
        sut.currentConfig = config

        // When
        val essentialCategories = sut.getEssentialCategories()

        // Then
        assertTrue(essentialCategories.isEmpty())
    }

    // MARK: - Reset Identifier Tests

    @Test
    fun `resetIdentifier delegates to storage`() {
        sut.resetIdentifier()

        org.mockito.Mockito.verify(mockStorage).resetIdentifier()
    }

    // MARK: - Universal Consent Tests

    @Test
    fun `isUniversalConsentEnabled reflects the config flag`() {
        assertFalse("no config loaded", sut.isUniversalConsentEnabled())

        sut.currentConfig = createBaseConfig()
        assertFalse("universalConsent absent", sut.isUniversalConsentEnabled())

        sut.currentConfig =
            createBaseConfig().copy(
                universalConsent = UniversalConsentConfig(enabled = false),
            )
        assertFalse("universalConsent disabled", sut.isUniversalConsentEnabled())

        sut.currentConfig =
            createBaseConfig().copy(
                universalConsent = UniversalConsentConfig(enabled = true),
            )
        assertTrue("universalConsent enabled", sut.isUniversalConsentEnabled())
    }

    @Test
    fun `setUserIdentifier rejects when universal consent is disabled`() =
        runTest {
            // The gate must stop the write BEFORE the service is touched — otherwise a customer
            // with the feature off still emits identified traffic to the universal store.
            sut.currentConfig = createBaseConfig()

            assertThrows(ConsentException.ValidationError::class.java) {
                runBlocking {
                    sut.setUserIdentifier("user@example.com", "dg_key", getSignature = signatureProvider())
                }
            }

            verifyNoInteractions(mockConsentService)
        }

    @Test
    fun `fetchUniversalConsent rejects when universal consent is disabled`() =
        runTest {
            sut.currentConfig = createBaseConfig()

            assertThrows(ConsentException.ValidationError::class.java) {
                runBlocking { sut.fetchUniversalConsent("user@example.com", "dg_key") }
            }

            verifyNoInteractions(mockConsentService)
        }

    @Test
    fun `setUserIdentifier throws when not initialized`() =
        runTest {
            assertThrows(ConsentException.NotInitialized::class.java) {
                runBlocking {
                    sut.setUserIdentifier("user@example.com", "dg_key", getSignature = signatureProvider())
                }
            }

            verifyNoInteractions(mockConsentService)
        }

    @Test
    fun `setUserIdentifier rejects when enabled but consentProjectId is missing`() =
        runTest {
            // enabled=true with a null consentProjectId is a live misconfiguration: both fields are
            // independently nullable. The single universalConsentReady gate must reject it here,
            // BEFORE any request reaches the service and fails deep in the network layer instead.
            sut.currentConfig =
                createBaseConfig().copy(
                    consentProjectId = null,
                    universalConsent = UniversalConsentConfig(enabled = true),
                )

            assertThrows(ConsentException.ValidationError::class.java) {
                runBlocking {
                    sut.setUserIdentifier("user@example.com", "dg_key", getSignature = signatureProvider())
                }
            }

            verifyNoInteractions(mockConsentService)
        }

    /**
     * The write carries the RAW preferences it was given, never a signal-suppressed view.
     *
     * The universal store holds raw choices and the server never merges, so suppressing before a
     * write would persist this device's transient signal as the user's choice — for every device on
     * their identifier. Limit-ad-tracking is an ad-personalization answer, not a marketing opt-out.
     * Suppression belongs to the read path, and the write path now takes no signal parameter at all:
     * there is nothing to pass and nothing for the manager to read, so no signal state can reach the
     * payload. That absence IS the fix.
     */
    @Test
    fun `setUserIdentifier writes the raw preferences with no signal applied`() =
        runTest {
            sut.currentConfig =
                universalConfig(
                    listOf(
                        MockCategory("category_essential", alwaysOn = true),
                        MockCategory("category_marketing", alwaysOn = false),
                    ),
                )
            whenever(mockStorage.loadPreferences()).thenReturn(
                ConsentPreferences(
                    isCustomised = true,
                    cookieOptions =
                        listOf(
                            CategoryConsent(gtmKey = "category_essential", isEnabled = true),
                            CategoryConsent(gtmKey = "category_marketing", isEnabled = true),
                        ),
                ),
            )

            sut.setUserIdentifier(
                "user@example.com",
                "dg_key",
                getSignature = signatureProvider(),
            )

            val prefsCaptor = argumentCaptor<UniversalConsentPreferences>()
            val ccpaCaptor = argumentCaptor<Boolean>()
            verify(mockConsentService).saveUniversalConsent(
                any(),
                eq("user@example.com"),
                prefsCaptor.capture(),
                eq("dg_key"),
                ccpaCaptor.capture(),
                any(),
            )

            val written = prefsCaptor.firstValue
            assertTrue("isCustomised carried through", written.isCustomised)
            assertEquals("essential preserved", true, written.cookieOptions["category_essential"])
            assertEquals("the user's real opt-in survives onto the wire", true, written.cookieOptions["category_marketing"])
            // ccpa_optout records a CCPA do-not-sell choice. The ad-tracking signal is narrower
            // than that, so deriving one from the other would write a legal opt-out the user
            // never made — the manager must never forward the signal here.
            assertFalse("device signal must not become a CCPA opt-out", ccpaCaptor.firstValue)
        }

    /**
     * An explicitly-passed raw record wins over local state. The read-then-write entry point relies
     * on this: rehydration persists the SUPPRESSED view locally, so if the write fell back to
     * `getCategories()` it would read that suppression back and store it as the user's choice.
     */
    @Test
    fun `setUserIdentifier writes explicitly-passed preferences over the suppressed local state`() =
        runTest {
            sut.currentConfig =
                universalConfig(
                    listOf(
                        MockCategory("category_essential", alwaysOn = true),
                        MockCategory("category_marketing", alwaysOn = false),
                    ),
                )
            // Local state as rehydration would have left it under a DENIED signal: marketing off.
            whenever(mockStorage.loadPreferences()).thenReturn(
                ConsentPreferences(
                    isCustomised = true,
                    cookieOptions =
                        listOf(
                            CategoryConsent(gtmKey = "category_essential", isEnabled = true),
                            CategoryConsent(gtmKey = "category_marketing", isEnabled = false),
                        ),
                ),
            )

            sut.setUserIdentifier(
                "user@example.com",
                "dg_key",
                preferences =
                    ConsentPreferences(
                        isCustomised = true,
                        cookieOptions =
                            listOf(
                                CategoryConsent(gtmKey = "category_essential", isEnabled = true),
                                CategoryConsent(gtmKey = "category_marketing", isEnabled = true),
                            ),
                    ),
                getSignature = signatureProvider(),
            )

            val prefsCaptor = argumentCaptor<UniversalConsentPreferences>()
            verify(mockConsentService).saveUniversalConsent(
                any(),
                any(),
                prefsCaptor.capture(),
                any(),
                eq(false),
                any(),
            )

            assertEquals(true, prefsCaptor.firstValue.cookieOptions["category_marketing"])
        }

    @Test
    fun `setUserIdentifier falls back to the stored map when no preferences are passed`() =
        runTest {
            sut.currentConfig =
                universalConfig(
                    listOf(
                        MockCategory("category_essential", alwaysOn = true),
                        MockCategory("category_marketing", alwaysOn = false),
                    ),
                )
            whenever(mockStorage.loadPreferences()).thenReturn(
                ConsentPreferences(
                    isCustomised = true,
                    cookieOptions =
                        listOf(
                            CategoryConsent(gtmKey = "category_essential", isEnabled = true),
                            CategoryConsent(gtmKey = "category_marketing", isEnabled = true),
                        ),
                ),
            )

            sut.setUserIdentifier(
                "user@example.com",
                "dg_key",
                getSignature = signatureProvider(),
            )

            val prefsCaptor = argumentCaptor<UniversalConsentPreferences>()
            verify(mockConsentService).saveUniversalConsent(
                any(),
                any(),
                prefsCaptor.capture(),
                any(),
                eq(false),
                any(),
            )

            assertEquals(true, prefsCaptor.firstValue.cookieOptions["category_marketing"])
        }

    @Test
    fun `fetchUniversalConsent reconciles the stored gpc on the returned record`() =
        runTest {
            // The server returns RAW data — a stored marketing:true alongside gpc:true is a
            // legitimate record shape, and the manager must not hand it back unreconciled.
            sut.currentConfig =
                universalConfig(
                    listOf(
                        MockCategory("category_essential", alwaysOn = true),
                        MockCategory("category_marketing", alwaysOn = false),
                    ),
                )
            whenever(mockConsentService.getUniversalConsent(any(), any(), any())).thenReturn(
                UniversalConsentRecord(
                    status = "found",
                    consentPreferences =
                        UniversalConsentPreferences(
                            isCustomised = true,
                            cookieOptions =
                                mapOf(
                                    "category_essential" to true,
                                    "category_marketing" to true,
                                ),
                        ),
                    gpc = true,
                ),
            )

            val record = sut.fetchUniversalConsent("user@example.com", "dg_key")

            val options = record!!.consentPreferences!!.cookieOptions
            assertEquals("essential preserved", true, options["category_essential"])
            assertEquals("marketing suppressed by stored gpc", false, options["category_marketing"])
        }

    @Test
    fun `fetchUniversalConsent returns null when no record exists`() =
        runTest {
            sut.currentConfig = universalConfig(listOf(MockCategory("category_essential", alwaysOn = true)))
            whenever(mockConsentService.getUniversalConsent(any(), any(), any())).thenReturn(null)

            assertNull(sut.fetchUniversalConsent("user@example.com", "dg_key"))
        }

    // MARK: - Helper Methods

    private fun signatureProvider(): SignatureProvider =
        { _ -> UniversalConsentSignature("sig", "key-1") }

    private fun universalConfig(categories: List<MockCategory>): ConsentConfig =
        createMockConfig(categories).copy(
            consentProjectId = "proj_abc123",
            universalConsent = UniversalConsentConfig(enabled = true),
        )

    private fun createMockConfigWithInitialCategories(initialCategories: List<String>): ConsentConfig {
        return createBaseConfig().copy(
            initialCategories =
                InitialCategories(
                    respectGpc = false,
                    respectDnt = false,
                    respectOptout = false,
                    initial = initialCategories,
                    gpc = listOf("dg-category-essential"),
                    optout = listOf("dg-category-essential"),
                ),
        )
    }

    private fun createMockConfigWithShowBanner(
        showBanner: Boolean,
        version: String = "v1",
    ): ConsentConfig {
        return createBaseConfig().copy(
            showBanner = showBanner,
            version = version,
        )
    }

    private fun createBaseConfig(): ConsentConfig {
        val layer =
            ConsentLayer(
                id = java.util.UUID.randomUUID().toString(),
                name = "Main Layer",
                position = "bottom",
                showCloseButton = true,
                bannerApiId = "main",
                elements = emptyList(),
            )

        val layout =
            Layout(
                id = java.util.UUID.randomUUID().toString(),
                name = "Default",
                description = null,
                status = "published",
                defaultLayout = true,
                collapsedOnMobile = false,
                firstLayerId = layer.id,
                consentLayers = mapOf(layer.id to layer),
            )

        return ConsentConfig(
            version = java.util.UUID.randomUUID().toString(),
            consentContainerVersionId = java.util.UUID.randomUUID().toString(),
            dgCustomerId = java.util.UUID.randomUUID().toString(),
            p = System.currentTimeMillis(),
            dch = "categorize",
            dc = "dg-category-essential",
            privacyDomain = "consent.datagrail.io",
            plugins =
                Plugins(
                    scriptControl = true,
                    allCookieSubdomains = true,
                    cookieBlocking = true,
                    localStorageBlocking = true,
                    syncOTConsent = false,
                ),
            testMode = false,
            ignoreDoNotTrack = false,
            trackingDetailsUrl = "https://example.com/tracking",
            consentMode = "optin",
            showBanner = true,
            consentPolicy = ConsentPolicy(name = "GDPR", default = true),
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
            layout = layout,
        )
    }

    private fun createMockConfig(categories: List<MockCategory>): ConsentConfig {
        val categoryElements =
            categories.map { mockCat ->
                ConsentLayerCategory(
                    id = java.util.UUID.randomUUID().toString(),
                    consentCategoryId = java.util.UUID.randomUUID().toString(),
                    order = 1,
                    hidden = false,
                    primitive = "dg-category-essential",
                    alwaysOn = mockCat.alwaysOn,
                    gtmKey = mockCat.gtmKey,
                    uuids = emptyList(),
                    cookiePatterns = emptyList(),
                    translations = emptyMap(),
                    showTrackingDetailsLink = false,
                )
            }

        val element =
            ConsentLayerElement(
                id = java.util.UUID.randomUUID().toString(),
                order = 1,
                type = "ConsentLayerCategoryElement",
                style = null,
                buttonAction = null,
                targetConsentLayer = null,
                categories = emptyList(),
                translations = null,
                links = null,
                consentLayerCategories = categoryElements,
                showTrackingDetailsLink = false,
                consentLayerCategoriesConfigId = null,
                trackingDetailsLinkTranslations = null,
            )

        val layer =
            ConsentLayer(
                id = java.util.UUID.randomUUID().toString(),
                name = "Main Layer",
                position = "bottom",
                showCloseButton = true,
                bannerApiId = "main",
                elements = listOf(element),
            )

        val layout =
            Layout(
                id = java.util.UUID.randomUUID().toString(),
                name = "Default",
                description = null,
                status = "published",
                defaultLayout = true,
                collapsedOnMobile = false,
                firstLayerId = layer.id,
                consentLayers = mapOf(layer.id to layer),
            )

        return ConsentConfig(
            version = java.util.UUID.randomUUID().toString(),
            consentContainerVersionId = java.util.UUID.randomUUID().toString(),
            dgCustomerId = java.util.UUID.randomUUID().toString(),
            p = System.currentTimeMillis(),
            dch = "categorize",
            dc = "dg-category-essential",
            privacyDomain = "consent.datagrail.io",
            plugins =
                Plugins(
                    scriptControl = true,
                    allCookieSubdomains = true,
                    cookieBlocking = true,
                    localStorageBlocking = true,
                    syncOTConsent = false,
                ),
            testMode = false,
            ignoreDoNotTrack = false,
            trackingDetailsUrl = "https://example.com/tracking",
            consentMode = "optin",
            showBanner = true,
            consentPolicy = ConsentPolicy(name = "GDPR", default = true),
            gppUsNat = false,
            initialCategories =
                InitialCategories(
                    respectGpc = false,
                    respectDnt = false,
                    respectOptout = false,
                    initial = listOf("category_essential"),
                    gpc = emptyList(),
                    optout = emptyList(),
                ),
            layout = layout,
        )
    }

    data class MockCategory(
        val gtmKey: String,
        val alwaysOn: Boolean,
    )
}
