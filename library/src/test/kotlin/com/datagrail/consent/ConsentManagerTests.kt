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
import org.mockito.kotlin.isNull
import org.mockito.kotlin.never
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

    /**
     * Regression: a category with `alwaysOn = false` whose gtmKey contains "essential" is essential.
     * The banner (getEssentialCategoryKeys) force-enables/hides it; before the shared definition,
     * ConsentManager.getEssentialCategories treated it as non-essential, so the universal-consent
     * reconcile/backfill would let it be suppressed — the two paths disagreed. Both must now agree,
     * so we assert the manager path AND the shared definition the banner uses both include it.
     */
    @Test
    fun `essential-named category with alwaysOn false is essential to both the manager and the shared definition`() {
        // Given
        val config =
            createMockConfig(
                listOf(
                    MockCategory("essential_analytics", alwaysOn = false),
                    MockCategory("category_marketing", alwaysOn = false),
                ),
            )
        sut.currentConfig = config

        // When
        val managerEssential = sut.getEssentialCategories()
        // The single definition the banner (BannerDialog.getEssentialCategoryKeys) delegates to.
        val bannerEssential = config.essentialCategoryKeys()

        // Then - both paths agree the "essential"-named, non-alwaysOn category is essential
        assertTrue("manager treats essential_analytics as essential", managerEssential.contains("essential_analytics"))
        assertTrue("banner treats essential_analytics as essential", bannerEssential.contains("essential_analytics"))
        assertFalse("manager excludes marketing", managerEssential.contains("category_marketing"))
        assertFalse("banner excludes marketing", bannerEssential.contains("category_marketing"))
        assertEquals("both paths produce the same essential set", managerEssential.toSet(), bannerEssential)
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
    fun `isUniversalConsentEnabled reflects readiness, not just the enabled flag`() {
        assertFalse("no config loaded", sut.isUniversalConsentEnabled())

        sut.currentConfig = createBaseConfig()
        assertFalse("universalConsent absent", sut.isUniversalConsentEnabled())

        sut.currentConfig =
            createBaseConfig().copy(
                universalConsent = UniversalConsentConfig(enabled = false),
                consentProjectId = "proj_abc123",
            )
        assertFalse("universalConsent disabled", sut.isUniversalConsentEnabled())

        // Enabled but no project id is a live misconfiguration: this predicate must NOT diverge from
        // universalConsentReady (which every entry point gates on), or a caller could trust a `true`
        // here and then hit a ValidationError on setUserIdentifier.
        sut.currentConfig =
            createBaseConfig().copy(
                universalConsent = UniversalConsentConfig(enabled = true),
                consentProjectId = null,
            )
        assertFalse("enabled but no consentProjectId is not ready", sut.isUniversalConsentEnabled())

        sut.currentConfig =
            createBaseConfig().copy(
                universalConsent = UniversalConsentConfig(enabled = true),
                consentProjectId = "proj_abc123",
            )
        assertTrue("enabled and configured", sut.isUniversalConsentEnabled())
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

    @Test
    fun `setUserIdentifier rejects when consentProjectId is blank`() =
        runTest {
            // A config that serializes consentProjectId as "" (rather than null/omitted) must not
            // pass the readiness gate — computeUserHash would otherwise hash an empty project
            // segment and collapse every such customer onto one hash namespace.
            sut.currentConfig =
                createBaseConfig().copy(
                    consentProjectId = "   ",
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
     * WRITE-THROUGH / sync-on-change: on a FOUND record the write carries the user's CURRENT LOCAL
     * choice — the choice they made on THIS device before associating their identity — and NEVER
     * re-POSTs the record it just fetched. Re-POSTing the fetched map would discard that local
     * choice (the launch-blocking "lose an explicit opt-out" / revocation-resurrection class) and
     * only echo state the edge already holds.
     */
    @Test
    fun `setUserIdentifier writes the local choice, not the fetched record, on a found record`() =
        runTest {
            sut.currentConfig =
                universalConfig(
                    listOf(
                        MockCategory("category_essential", alwaysOn = true),
                        MockCategory("category_marketing", alwaysOn = false),
                    ),
                )
            // The user opted marketing OFF locally before associating their identity...
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
            // ...while the stored record disagrees (marketing ON).
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
                ),
            )
            // RE-SYNC: already bound to this identity. On a LOGIN a found record now wins and
            // nothing is written (TRUST-2902), so write-through is a re-sync behavior.
            bindDeviceTo("user@example.com")

            sut.setUserIdentifier(
                "user@example.com",
                "dg_key",
                trackingSignal = TrackingSignal.AUTHORIZED,
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
            assertEquals(
                "write carries the user's CURRENT LOCAL choice, not the fetched record",
                false,
                prefsCaptor.firstValue.cookieOptions["category_marketing"],
            )
        }

    /**
     * Signals are a READ-TIME view only: when a genuine local change is written through, a live
     * device signal (here DENIED) suppresses marketing in LOCAL storage but must NEVER be folded
     * into the write body. The store holds raw choices and the server never merges, so persisting
     * this device's transient signal would read back as a revocation the user never made — for
     * every device on their identifier.
     */
    @Test
    fun `setUserIdentifier does not fold a live device signal into the write body`() =
        runTest {
            sut.currentConfig =
                universalConfig(
                    listOf(
                        MockCategory("category_essential", alwaysOn = true),
                        MockCategory("category_marketing", alwaysOn = false),
                    ),
                )
            // The user's genuine local choice: marketing ON.
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
            // A stored record exists, so the rehydrate persists a reconciled (suppressed) view.
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
                ),
            )

            // RE-SYNC: already bound to this identity, so the found record does not suppress the
            // write-through (on a LOGIN the record wins and nothing is written, TRUST-2902).
            bindDeviceTo("user@example.com")

            // A DENIED device signal must suppress marketing LOCALLY but never on the wire.
            sut.setUserIdentifier(
                "user@example.com",
                "dg_key",
                trackingSignal = TrackingSignal.DENIED,
                getSignature = signatureProvider(),
            )

            // Local storage got the reconciled (suppressed) view: marketing forced off.
            val storedCaptor = argumentCaptor<ConsentPreferences>()
            verify(mockStorage).savePreferences(storedCaptor.capture())
            assertEquals(
                "local state suppressed under DENIED",
                false,
                storedCaptor.firstValue.cookieOptions.first { it.gtmKey == "category_marketing" }.isEnabled,
            )

            // The write carries the user's RAW local opt-in, unsuppressed — the signal is not folded in.
            val prefsCaptor = argumentCaptor<UniversalConsentPreferences>()
            verify(mockConsentService).saveUniversalConsent(
                any(),
                any(),
                prefsCaptor.capture(),
                any(),
                eq(false),
                any(),
            )
            assertEquals(
                "write carries the raw local opt-in, not the suppressed local view",
                true,
                prefsCaptor.firstValue.cookieOptions["category_marketing"],
            )
        }

    /**
     * ADOPT-WITHOUT-POST: a FOUND record with no genuine local change (a fresh install that merely
     * adopted it — no stored preferences) is applied to LOCAL state by the rehydrate and NOTHING is
     * POSTed. Re-POSTing would only echo state the edge already holds. This also preserves an
     * unbannered record's isCustomised=false: it is never written, so it can never be flipped to
     * true by every device that opens the app.
     */
    @Test
    fun `setUserIdentifier adopts a found record without posting when there is no local change`() =
        runTest {
            sut.currentConfig =
                universalConfig(
                    listOf(
                        MockCategory("category_essential", alwaysOn = true),
                        MockCategory("category_marketing", alwaysOn = false),
                    ),
                )
            // No local choice on this device (fresh install), and loadPreferences returns null.
            whenever(mockConsentService.getUniversalConsent(any(), any(), any())).thenReturn(
                UniversalConsentRecord(
                    status = "found",
                    consentPreferences =
                        UniversalConsentPreferences(
                            isCustomised = false,
                            cookieOptions =
                                mapOf(
                                    "category_essential" to true,
                                    "category_marketing" to true,
                                ),
                        ),
                ),
            )

            sut.setUserIdentifier("user@example.com", "dg_key", getSignature = signatureProvider())

            // Nothing was POSTed: the record is adopted, not re-POSTed.
            verify(mockConsentService, never()).saveUniversalConsent(any(), any(), any(), any(), any(), any())

            // The record was adopted into local state, stamped customised so the banner does not
            // re-prompt a user who already answered on another device.
            val storedCaptor = argumentCaptor<ConsentPreferences>()
            verify(mockStorage).savePreferences(storedCaptor.capture())
            assertTrue(
                "local copy stays customised so needsConsent() does not re-prompt",
                storedCaptor.firstValue.isCustomised,
            )
        }

    /**
     * A rehydrate READ FAILURE (as opposed to a miss) blocks the WRITE and propagates: the server
     * never merges, so overwriting a record we could not read would silently erase the user's real
     * cross-device choice (the TRUST-2491 corruption class). The caller can retry, which re-reads.
     */
    @Test
    fun `setUserIdentifier blocks the write when the rehydrate read fails`() =
        runTest {
            sut.currentConfig =
                universalConfig(
                    listOf(
                        MockCategory("category_essential", alwaysOn = true),
                        MockCategory("category_marketing", alwaysOn = false),
                    ),
                )
            whenever(mockConsentService.getUniversalConsent(any(), any(), any()))
                .thenThrow(RuntimeException("network down"))
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

            assertThrows(RuntimeException::class.java) {
                runBlocking {
                    sut.setUserIdentifier("user@example.com", "dg_key", getSignature = signatureProvider())
                }
            }

            // A record we could not read is never overwritten.
            verify(mockConsentService, never()).saveUniversalConsent(any(), any(), any(), any(), any(), any())
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

    /**
     * The public API-key-only overload (DataGrailConsent.setUserIdentifier without getSignature)
     * delegates here with getSignature = null — the limited mode of the shared cross-SDK contract:
     * "omitting getSignature performs a limited, API-key-only write (no signature headers)". The
     * null must reach saveUniversalConsent verbatim so ConsentService sends no signature headers.
     */
    @Test
    fun `setUserIdentifier without a signature performs an api-key-only write`() =
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

            // getSignature omitted — the unsigned public path.
            sut.setUserIdentifier("user@example.com", "dg_key")

            verify(mockConsentService).saveUniversalConsent(
                any(),
                eq("user@example.com"),
                any(),
                eq("dg_key"),
                eq(false),
                isNull(),
            )
        }

    // MARK: - TRUST-2902 login vs re-sync / logout-to-neutral

    /** (1) LOGIN + record exists + explicit local choice: the record wins; adopted, nothing written. */
    @Test
    fun `login with a found record adopts it and drops the local choice`() =
        runTest {
            sut.currentConfig = twoCategoryUniversalConfig()
            whenever(mockStorage.loadPreferences()).thenReturn(explicitChoice())
            whenever(mockConsentService.getUniversalConsent(any(), any(), any())).thenReturn(foundRecord())

            sut.setUserIdentifier("user@example.com", "dg_key", getSignature = signatureProvider())

            verify(mockConsentService, never()).saveUniversalConsent(any(), any(), any(), any(), any(), any())
            val storedCaptor = argumentCaptor<ConsentPreferences>()
            verify(mockStorage).savePreferences(storedCaptor.capture())
            assertEquals(
                "the record (marketing on) replaced the local opt-out",
                true,
                storedCaptor.firstValue.cookieOptions.first { it.gtmKey == "category_marketing" }.isEnabled,
            )
            verify(mockStorage).saveBoundUserHash(hashFor("user@example.com"))
        }

    /** (2) LOGIN + record exists + no local choice: adopted, nothing written, device binds. */
    @Test
    fun `login with a found record and no local choice adopts it and binds`() =
        runTest {
            sut.currentConfig = twoCategoryUniversalConfig()
            whenever(mockConsentService.getUniversalConsent(any(), any(), any())).thenReturn(foundRecord())

            sut.setUserIdentifier("user@example.com", "dg_key", getSignature = signatureProvider())

            verify(mockConsentService, never()).saveUniversalConsent(any(), any(), any(), any(), any(), any())
            verify(mockStorage).savePreferences(any())
            verify(mockStorage).saveBoundUserHash(hashFor("user@example.com"))
        }

    /** (3) LOGIN + no record + explicit local choice: the RAW local choice is attached and written. */
    @Test
    fun `login with no record writes the explicit local choice`() =
        runTest {
            sut.currentConfig = twoCategoryUniversalConfig()
            whenever(mockStorage.loadPreferences()).thenReturn(explicitChoice())

            sut.setUserIdentifier("user@example.com", "dg_key", getSignature = signatureProvider())

            val prefsCaptor = argumentCaptor<UniversalConsentPreferences>()
            verify(mockConsentService).saveUniversalConsent(any(), any(), prefsCaptor.capture(), any(), any(), any())
            assertTrue(prefsCaptor.firstValue.isCustomised)
            assertEquals(false, prefsCaptor.firstValue.cookieOptions["category_marketing"])
            verify(mockStorage).saveBoundUserHash(hashFor("user@example.com"))
        }

    /** (4) LOGIN + no record + defaults only (nothing stored): nothing written, local untouched, binds. */
    @Test
    fun `login with no record and only defaults writes nothing`() =
        runTest {
            sut.currentConfig = twoCategoryUniversalConfig()

            sut.setUserIdentifier("user@example.com", "dg_key", getSignature = signatureProvider())

            verify(mockConsentService, never()).saveUniversalConsent(any(), any(), any(), any(), any(), any())
            verify(mockStorage, never()).savePreferences(any())
            verify(mockStorage, never()).clearPreferences()
            verify(mockStorage).saveBoundUserHash(hashFor("user@example.com"))
        }

    /** Bound to A, B logs in with no record: A's local state is not B's; nothing written, local neutral. */
    @Test
    fun `login as a different identity does not write the previous user's state`() =
        runTest {
            sut.currentConfig = twoCategoryUniversalConfig()
            whenever(mockStorage.loadPreferences()).thenReturn(explicitChoice(), null)
            bindDeviceTo("alice@example.com")
            val notified = mutableListOf<ConsentPreferences>()

            sut.setUserIdentifier(
                "bob@example.com",
                "dg_key",
                getSignature = signatureProvider(),
                onRehydrated = { notified.add(it) },
            )

            verify(mockConsentService, never()).saveUniversalConsent(any(), any(), any(), any(), any(), any())
            verify(mockStorage).clearPreferences()
            verify(mockStorage, never()).clearAll()
            verify(mockStorage).saveBoundUserHash(hashFor("bob@example.com"))
            assertEquals("listener sees the neutral defaults", listOf(sut.getDefaultPreferences()), notified)
        }

    /** Bound to A, B logs in and B has a record: B's record is adopted, nothing written. */
    @Test
    fun `login as a different identity with a found record adopts it`() =
        runTest {
            sut.currentConfig = twoCategoryUniversalConfig()
            whenever(mockStorage.loadPreferences()).thenReturn(explicitChoice())
            whenever(mockConsentService.getUniversalConsent(any(), any(), any())).thenReturn(foundRecord())
            bindDeviceTo("alice@example.com")

            sut.setUserIdentifier("bob@example.com", "dg_key", getSignature = signatureProvider())

            verify(mockConsentService, never()).saveUniversalConsent(any(), any(), any(), any(), any(), any())
            verify(mockStorage, never()).clearPreferences()
            verify(mockStorage).saveBoundUserHash(hashFor("bob@example.com"))
        }

    /** RE-SYNC + no record + explicit local choice: written (sync-on-change still works while logged in). */
    @Test
    fun `resync with no record writes the explicit local choice`() =
        runTest {
            sut.currentConfig = twoCategoryUniversalConfig()
            whenever(mockStorage.loadPreferences()).thenReturn(explicitChoice())
            bindDeviceTo("USER@example.com ")

            sut.setUserIdentifier("user@example.com", "dg_key", getSignature = signatureProvider())

            verify(mockConsentService).saveUniversalConsent(any(), any(), any(), any(), any(), any())
            verify(mockStorage, never()).clearPreferences()
            verify(mockStorage).saveBoundUserHash(hashFor("user@example.com"))
        }

    /** RE-SYNC + no record + nothing stored: config defaults are no longer seeded. */
    @Test
    fun `resync with no record and only defaults writes nothing`() =
        runTest {
            sut.currentConfig = twoCategoryUniversalConfig()
            bindDeviceTo("user@example.com")

            sut.setUserIdentifier("user@example.com", "dg_key", getSignature = signatureProvider())

            verify(mockConsentService, never()).saveUniversalConsent(any(), any(), any(), any(), any(), any())
            verify(mockStorage).saveBoundUserHash(hashFor("user@example.com"))
        }

    /** A read failure leaves the binding untouched. */
    @Test
    fun `setUserIdentifier does not bind when the read fails`() =
        runTest {
            sut.currentConfig = twoCategoryUniversalConfig()
            whenever(mockStorage.loadPreferences()).thenReturn(explicitChoice())
            whenever(mockConsentService.getUniversalConsent(any(), any(), any()))
                .thenThrow(RuntimeException("network down"))

            assertThrows(RuntimeException::class.java) {
                runBlocking { sut.setUserIdentifier("user@example.com", "dg_key", getSignature = signatureProvider()) }
            }

            verify(mockStorage, never()).saveBoundUserHash(any())
            verify(mockStorage, never()).clearBoundUserHash()
            verify(mockStorage, never()).clearPreferences()
        }

    /** A failed write never binds, so a retry is still recognised as a login. */
    @Test
    fun `setUserIdentifier does not bind when the write fails`() =
        runTest {
            sut.currentConfig = twoCategoryUniversalConfig()
            whenever(mockStorage.loadPreferences()).thenReturn(explicitChoice())
            whenever(mockConsentService.saveUniversalConsent(any(), any(), any(), any(), any(), any()))
                .thenAnswer { throw ConsentException.NetworkError("503") }

            assertThrows(ConsentException.NetworkError::class.java) {
                runBlocking { sut.setUserIdentifier("user@example.com", "dg_key", getSignature = signatureProvider()) }
            }

            verify(mockStorage, never()).saveBoundUserHash(any())
        }

    /**
     * Logout returns local state to neutral non-destructively: binding and explicit choice are
     * cleared, the listener gets the defaults, and nothing else (unique id, config cache/version,
     * locale, pending queue, loaded config, server record) is touched.
     */
    @Test
    fun `clearUserIdentifier returns to neutral without touching anything else`() {
        sut.currentConfig = twoCategoryUniversalConfig()
        val notified = mutableListOf<ConsentPreferences>()

        sut.clearUserIdentifier { notified.add(it) }

        verify(mockStorage).clearBoundUserHash()
        verify(mockStorage).clearPreferences()
        verify(mockStorage, never()).clearAll()
        verify(mockStorage, never()).resetIdentifier()
        verify(mockStorage, never()).saveConfigVersion(any())
        verify(mockStorage, never()).saveConfigCache(any())
        verify(mockStorage, never()).saveLocaleCode(any())
        verify(mockStorage, never()).savePendingEvents(any())
        verifyNoInteractions(mockConsentService)
        verifyNoInteractions(mockConfigService)
        assertNotNull("SDK stays initialized", sut.currentConfig)
        assertEquals("listener sees the neutral defaults", listOf(sut.getDefaultPreferences()), notified)
        assertTrue("banner shows again once the stored choice is gone", sut.needsConsent())
    }

    @Test
    fun `clearUserIdentifier is idempotent when not bound`() {
        sut.currentConfig = twoCategoryUniversalConfig()

        sut.clearUserIdentifier()
        sut.clearUserIdentifier()

        verify(mockStorage, org.mockito.Mockito.times(2)).clearBoundUserHash()
        verifyNoInteractions(mockConsentService)
    }

    /** The destructive reset() clears everything, which includes the identity binding. */
    @Test
    fun `reset clears all storage including the identity binding`() {
        sut.currentConfig = twoCategoryUniversalConfig()

        sut.reset()

        verify(mockStorage).clearAll()
        assertNull(sut.currentConfig)
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

    /**
     * Taxonomy drift: a cross-device record omits an essential/always-on category this config
     * defines (e.g. a newer or platform-specific GTM key the writing platform never had).
     * Reconciliation must still surface it enabled — ConsentPreferences.isCategoryEnabled falls
     * back to false for any key missing from the persisted map, which would silently disable an
     * always-on category with no banner to recover it.
     */
    @Test
    fun `rehydrate keeps an always-on category the record omitted enabled`() =
        runTest {
            sut.currentConfig =
                universalConfig(
                    listOf(
                        MockCategory("category_essential", alwaysOn = true),
                        MockCategory("category_marketing", alwaysOn = false),
                    ),
                )
            // The record was written on a platform whose taxonomy lacked category_essential.
            whenever(mockConsentService.getUniversalConsent(any(), any(), any())).thenReturn(
                UniversalConsentRecord(
                    status = "found",
                    consentPreferences =
                        UniversalConsentPreferences(
                            isCustomised = true,
                            cookieOptions = mapOf("category_marketing" to true),
                        ),
                ),
            )

            sut.rehydrateFromUniversalConsent("user@example.com", "dg_key")

            val storedCaptor = argumentCaptor<ConsentPreferences>()
            verify(mockStorage).savePreferences(storedCaptor.capture())
            assertEquals(
                "always-on category absent from the record is surfaced enabled, not dropped",
                true,
                storedCaptor.firstValue.cookieOptions.firstOrNull { it.gtmKey == "category_essential" }?.isEnabled,
            )
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

    private fun twoCategoryUniversalConfig(): ConsentConfig =
        universalConfig(
            listOf(
                MockCategory("category_essential", alwaysOn = true),
                MockCategory("category_marketing", alwaysOn = false),
            ),
        )

    /** An explicit local choice (a stored record): marketing opted out. */
    private fun explicitChoice(): ConsentPreferences =
        ConsentPreferences(
            isCustomised = true,
            cookieOptions =
                listOf(
                    CategoryConsent(gtmKey = "category_essential", isEnabled = true),
                    CategoryConsent(gtmKey = "category_marketing", isEnabled = false),
                ),
        )

    private fun foundRecord(): UniversalConsentRecord =
        UniversalConsentRecord(
            status = "found",
            consentPreferences =
                UniversalConsentPreferences(
                    isCustomised = true,
                    cookieOptions = mapOf("category_essential" to true, "category_marketing" to true),
                ),
        )

    /** The user hash the manager binds for [identifier] under the currently loaded config. */
    private fun hashFor(identifier: String): String {
        val config = sut.currentConfig!!
        return ConsentService.computeUserHash(config.dgCustomerId, config.consentProjectId!!, identifier)
    }

    /** Stub the persisted identity binding as [identifier]'s hash under the loaded config. */
    private fun bindDeviceTo(identifier: String) {
        whenever(mockStorage.loadBoundUserHash()).thenReturn(hashFor(identifier))
    }

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
