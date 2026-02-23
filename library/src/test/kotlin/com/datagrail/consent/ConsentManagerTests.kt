package com.datagrail.consent

import com.datagrail.consent.models.*
import com.datagrail.consent.network.ConfigService
import com.datagrail.consent.network.ConsentService
import com.datagrail.consent.storage.ConsentStorage
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
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

    // MARK: - Helper Methods

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
                theme = "neutral",
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
                theme = "neutral",
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
