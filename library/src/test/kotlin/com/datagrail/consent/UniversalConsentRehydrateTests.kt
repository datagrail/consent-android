package com.datagrail.consent

import com.datagrail.consent.models.*
import com.datagrail.consent.network.ConfigService
import com.datagrail.consent.network.ConsentService
import com.datagrail.consent.storage.ConsentStorage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Manager-level universal consent rehydration (TRUST-2491).
 *
 * [ConsentManager.fetchUniversalConsent] already reconciled and returned a record, but nothing
 * applied it — so these assert the part that was missing: that a stored record actually reaches
 * local state, and that a miss does not.
 *
 * Storage is a stateful stub rather than a plain mock so the assertions can go through the real
 * [ConsentManager.needsConsent] / [ConsentManager.isCategoryEnabled] read paths. Stubbing
 * `loadPreferences` to a fixed value would let a rehydrate that never persisted anything still
 * pass, which is exactly the bug under test.
 */
class UniversalConsentRehydrateTests {
    private lateinit var sut: ConsentManager

    @Mock
    private lateinit var mockStorage: ConsentStorage

    @Mock
    private lateinit var mockConfigService: ConfigService

    @Mock
    private lateinit var mockConsentService: ConsentService

    private var storedPreferences: ConsentPreferences? = null
    private var storedConfigVersion: String? = null

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)

        storedPreferences = null
        storedConfigVersion = null
        whenever(mockStorage.savePreferences(any())).doAnswer { invocation ->
            storedPreferences = invocation.getArgument(0)
            Unit
        }
        whenever(mockStorage.loadPreferences()).doAnswer { storedPreferences }
        whenever(mockStorage.saveConfigVersion(any())).doAnswer { invocation ->
            storedConfigVersion = invocation.getArgument(0)
            Unit
        }
        whenever(mockStorage.loadConfigVersion()).doAnswer { storedConfigVersion }

        sut = ConsentManager(mockStorage, mockConfigService, mockConsentService)
    }

    // MARK: - Acceptance: web consent resolves on a fresh install, no banner

    @Test
    fun `web consent rehydrates on a fresh install and suppresses the banner`() =
        runTest {
            sut.currentConfig = universalConfig()
            // Fresh install: nothing stored, so the banner would otherwise show.
            assertNull(mockStorage.loadPreferences())
            assertTrue("banner shows before rehydration", sut.needsConsent())

            stubRecord(marketing = true)

            assertTrue(
                "a found record must rehydrate local state",
                sut.rehydrateFromUniversalConsent("user@example.com", "dg_key", TrackingSignal.AUTHORIZED),
            )

            // The web opt-in is now visible to every local read path...
            assertTrue(sut.isCategoryEnabled("category_marketing"))
            assertTrue(sut.getCategories()!!.isCategoryEnabled("category_marketing"))
            // ...and the banner does not re-prompt a user who already answered elsewhere.
            assertFalse("banner suppressed after rehydration", sut.needsConsent())
        }

    /**
     * The rehydrated record is stamped with the CURRENT config version, not the writing device's.
     * Carrying a stale version over would fail the version check in [ConsentManager.needsConsent]
     * and re-prompt immediately, undoing the rehydration.
     */
    @Test
    fun `rehydration stamps the running config version not the record's`() =
        runTest {
            val config = universalConfig()
            sut.currentConfig = config
            stubRecord(marketing = true, configVersion = "some-older-version")

            sut.rehydrateFromUniversalConsent("user@example.com", "dg_key", TrackingSignal.AUTHORIZED)

            assertEquals(config.version, storedConfigVersion)
            assertFalse(sut.needsConsent())
        }

    // MARK: - Acceptance: a miss shows the banner and writes nothing

    @Test
    fun `a read miss leaves local state empty and keeps the banner visible`() =
        runTest {
            sut.currentConfig = universalConfig()
            whenever(mockConsentService.getUniversalConsent(any(), any(), any())).thenReturn(null)

            assertFalse(sut.rehydrateFromUniversalConsent("user@example.com", "dg_key", TrackingSignal.AUTHORIZED))

            // Nothing persisted: "no record" is the absence of a signal, not a denial. Writing an
            // empty record here would fabricate a choice and hide the banner meant to collect it.
            verify(mockStorage, never()).savePreferences(any())
            assertNull(mockStorage.loadPreferences())
            assertTrue(sut.needsConsent())
        }

    /**
     * A found record whose cookieOptions map is empty carries no category state to apply. Saving
     * it would store preferences with nothing in them, and because
     * [ConsentPreferences.isCategoryEnabled] defaults an unknown key to false, that reads back as
     * a blanket opt-out the user never made — while also suppressing the banner.
     */
    @Test
    fun `a found record with no cookie options writes nothing`() =
        runTest {
            sut.currentConfig = universalConfig()
            whenever(mockConsentService.getUniversalConsent(any(), any(), any())).thenReturn(
                UniversalConsentRecord(
                    status = "found",
                    consentPreferences = UniversalConsentPreferences(isCustomised = true, cookieOptions = emptyMap()),
                ),
            )

            assertFalse(sut.rehydrateFromUniversalConsent("user@example.com", "dg_key", TrackingSignal.AUTHORIZED))

            verify(mockStorage, never()).savePreferences(any())
            assertTrue(sut.needsConsent())
        }

    // MARK: - Merge rule, both directions of disagreement

    /**
     * Local says marketing OFF, remote says ON. The remote record is a real stored choice made by
     * this same person on another device, and no signal is active, so it wins.
     */
    @Test
    fun `remote opt-in overrides local opt-out when no signal applies`() =
        runTest {
            sut.currentConfig = universalConfig()
            storedPreferences = localPreferences(marketing = false)
            stubRecord(marketing = true)

            sut.rehydrateFromUniversalConsent("user@example.com", "dg_key", TrackingSignal.AUTHORIZED)

            assertTrue(sut.isCategoryEnabled("category_marketing"))
        }

    /**
     * Local says marketing ON, remote says OFF. Same rule, opposite direction: the stored remote
     * record is authoritative, so rehydrating must turn the local value off rather than quietly
     * keeping the more permissive of the two.
     */
    @Test
    fun `remote opt-out overrides local opt-in`() =
        runTest {
            sut.currentConfig = universalConfig()
            storedPreferences = localPreferences(marketing = true)
            stubRecord(marketing = false)

            sut.rehydrateFromUniversalConsent("user@example.com", "dg_key", TrackingSignal.AUTHORIZED)

            assertFalse(sut.isCategoryEnabled("category_marketing"))
        }

    // MARK: - Signals suppress the rehydrated state

    @Test
    fun `the device signal suppresses rehydrated marketing`() =
        runTest {
            sut.currentConfig = universalConfig()
            stubRecord(marketing = true)

            sut.rehydrateFromUniversalConsent("user@example.com", "dg_key", TrackingSignal.DENIED)

            // Marketing was true in the stored record; this device has opted out of ad tracking.
            assertFalse(sut.isCategoryEnabled("category_marketing"))
            assertTrue("essential survives suppression", sut.isCategoryEnabled("category_essential"))
        }

    /**
     * Android has no GPC of its own, so a web-recorded GPC reaches this device only via the
     * record's stored field. Ignoring it would fire marketing tags for an opted-out user.
     */
    @Test
    fun `stored web gpc suppresses even when the device signal is permissive`() =
        runTest {
            sut.currentConfig = universalConfig()
            stubRecord(marketing = true, gpc = true)

            sut.rehydrateFromUniversalConsent("user@example.com", "dg_key", TrackingSignal.AUTHORIZED)

            assertFalse(sut.isCategoryEnabled("category_marketing"))
            assertTrue(sut.isCategoryEnabled("category_essential"))
        }

    // MARK: - Preconditions

    @Test
    fun `rehydrate throws when not initialized`() =
        runTest {
            assertThrows(ConsentException.NotInitialized::class.java) {
                kotlinx.coroutines.runBlocking {
                    sut.rehydrateFromUniversalConsent("user@example.com", "dg_key", TrackingSignal.AUTHORIZED)
                }
            }
            verify(mockStorage, never()).savePreferences(any())
        }

    @Test
    fun `rehydrate rejects when universal consent is disabled`() =
        runTest {
            sut.currentConfig = baseUniversalConfig().copy(universalConsent = UniversalConsentConfig(enabled = false))

            assertThrows(ConsentException.ValidationError::class.java) {
                kotlinx.coroutines.runBlocking {
                    sut.rehydrateFromUniversalConsent("user@example.com", "dg_key", TrackingSignal.AUTHORIZED)
                }
            }
            verify(mockStorage, never()).savePreferences(any())
        }

    // MARK: - Helpers

    private suspend fun stubRecord(
        marketing: Boolean,
        gpc: Boolean = false,
        configVersion: String? = null,
    ) {
        whenever(mockConsentService.getUniversalConsent(any(), any(), any())).thenReturn(
            UniversalConsentRecord(
                status = "found",
                consentPreferences =
                    UniversalConsentPreferences(
                        isCustomised = true,
                        cookieOptions =
                            mapOf(
                                "category_essential" to true,
                                "category_marketing" to marketing,
                            ),
                    ),
                platform = "web",
                configVersion = configVersion,
                gpc = gpc,
            ),
        )
    }

    private fun localPreferences(marketing: Boolean): ConsentPreferences =
        ConsentPreferences(
            isCustomised = true,
            cookieOptions =
                listOf(
                    CategoryConsent(gtmKey = "category_essential", isEnabled = true),
                    CategoryConsent(gtmKey = "category_marketing", isEnabled = marketing),
                ),
        )

    private fun universalConfig(): ConsentConfig = baseUniversalConfig()

    private fun baseUniversalConfig(): ConsentConfig {
        val categoryElements =
            listOf("category_essential" to true, "category_marketing" to false).map { (gtmKey, alwaysOn) ->
                ConsentLayerCategory(
                    id = java.util.UUID.randomUUID().toString(),
                    consentCategoryId = java.util.UUID.randomUUID().toString(),
                    order = 1,
                    hidden = false,
                    primitive = "dg-category-essential",
                    alwaysOn = alwaysOn,
                    gtmKey = gtmKey,
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
            version = "config-version-current",
            consentContainerVersionId = java.util.UUID.randomUUID().toString(),
            dgCustomerId = "ac46d8ad-a67a-431f-a5d5-9e3eb922dae7",
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
                    initial = listOf("category_essential", "category_marketing"),
                    gpc = emptyList(),
                    optout = emptyList(),
                ),
            layout = layout,
            consentProjectId = "proj_abc123",
            universalConsent = UniversalConsentConfig(enabled = true),
        )
    }
}
