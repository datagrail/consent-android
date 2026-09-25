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
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * First-class `ccpa_optout` (TRUST-2591): the user's explicit "Do Not Sell or Share" choice, set by
 * the host app, never derived from a category or the ad-tracking signal, gated on the wire by
 * `syncOptout`, and replaced by a found record on login (TRUST-2902 rule). Same rule as the
 * web/iOS/React Native SDKs.
 *
 * Storage is a stateful stub so the flag, preferences and binding round-trip through the manager.
 */
class UniversalConsentCcpaOptoutTests {
    private lateinit var sut: ConsentManager

    @Mock
    private lateinit var mockStorage: ConsentStorage

    @Mock
    private lateinit var mockConfigService: ConfigService

    @Mock
    private lateinit var mockConsentService: ConsentService

    private var storedPreferences: ConsentPreferences? = null
    private var storedCcpaOptout = false
    private var storedBoundHash: String? = null

    private val userA = "alice@example.com"
    private val userB = "bob@example.com"
    private val apiKey = "dg_key"

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        storedPreferences = null
        storedCcpaOptout = false
        storedBoundHash = null
        whenever(mockStorage.savePreferences(any())).doAnswer { storedPreferences = it.getArgument(0) }
        whenever(mockStorage.loadPreferences()).doAnswer { storedPreferences }
        whenever(mockStorage.clearPreferences()).doAnswer { storedPreferences = null }
        whenever(mockStorage.saveCcpaOptout(any())).doAnswer { storedCcpaOptout = it.getArgument(0) }
        whenever(mockStorage.loadCcpaOptout()).doAnswer { storedCcpaOptout }
        whenever(mockStorage.clearCcpaOptout()).doAnswer { storedCcpaOptout = false }
        whenever(mockStorage.saveBoundUserHash(any())).doAnswer { storedBoundHash = it.getArgument(0) }
        whenever(mockStorage.loadBoundUserHash()).doAnswer { storedBoundHash }
        whenever(mockStorage.clearBoundUserHash()).doAnswer { storedBoundHash = null }
        sut = ConsentManager(mockStorage, mockConfigService, mockConsentService)
        sut.currentConfig = config(syncOptout = true)
    }

    // MARK: - Setter / getter

    @Test
    fun `setter persists and the getter reads it back, changing no category`() =
        runTest {
            storedPreferences = choice(marketing = true)
            assertFalse(sut.getCcpaOptout())

            sut.setCcpaOptout(true)

            assertTrue(sut.getCcpaOptout())
            assertEquals(choice(marketing = true), storedPreferences)
            verifyNoWrite()

            sut.setCcpaOptout(false)
            assertFalse(sut.getCcpaOptout())
        }

    @Test
    fun `setter writes through for the bound identity with the gate on`() =
        runTest {
            storedPreferences = choice(marketing = true)
            recordIs(null)
            sut.setUserIdentifier(userA, apiKey)
            assertEquals(listOf(false), writtenCcpa())

            sut.setCcpaOptout(true)

            assertEquals(listOf(false, true), writtenCcpa())
        }

    @Test
    fun `setter stays local with the gate off`() =
        runTest {
            sut.currentConfig = config(syncOptout = false)
            storedPreferences = choice(marketing = true)
            recordIs(null)
            sut.setUserIdentifier(userA, apiKey)

            sut.setCcpaOptout(true)

            verify(mockConsentService, times(1)).saveUniversalConsent(any(), any(), any(), any(), any(), anyOrNull())
            assertTrue(sut.getCcpaOptout())
        }

    @Test
    fun `setter stays local when bound but no session in this process`() =
        runTest {
            storedPreferences = choice(marketing = true)
            storedBoundHash = hashFor(userA)

            sut.setCcpaOptout(true)

            verifyNoWrite()
            assertTrue(sut.getCcpaOptout())
        }

    @Test
    fun `setter stays local after clearUserIdentifier`() =
        runTest {
            storedPreferences = choice(marketing = true)
            recordIs(null)
            sut.setUserIdentifier(userA, apiKey)
            sut.clearUserIdentifier()

            sut.setCcpaOptout(true)

            verify(mockConsentService, times(1)).saveUniversalConsent(any(), any(), any(), any(), any(), anyOrNull())
        }

    // MARK: - Wire field

    @Test
    fun `never derived from marketing rejection or a limited tracking signal`() =
        runTest {
            storedPreferences = choice(marketing = false)
            recordIs(null)

            sut.setUserIdentifier(userA, apiKey, trackingSignal = TrackingSignal.DENIED)

            assertEquals(listOf(false), writtenCcpa())
            assertFalse(sut.getCcpaOptout())
        }

    @Test
    fun `re-sync write-through carries the local flag, not the record's`() =
        runTest {
            storedBoundHash = hashFor(userA)
            storedPreferences = choice(marketing = false)
            storedCcpaOptout = true
            recordIs(record(ccpaOptout = false))

            sut.setUserIdentifier(userA, apiKey)

            assertEquals(listOf(true), writtenCcpa())
            assertTrue(sut.getCcpaOptout())
        }

    @Test
    fun `re-sync adopt takes the record value with the gate on`() =
        runTest {
            storedBoundHash = hashFor(userA)
            recordIs(record(ccpaOptout = true))

            sut.setUserIdentifier(userA, apiKey)

            verifyNoWrite()
            assertTrue(sut.getCcpaOptout())
        }

    @Test
    fun `re-sync adopt keeps a local-only flag with the gate off`() =
        runTest {
            sut.currentConfig = config(syncOptout = false)
            storedBoundHash = hashFor(userA)
            storedCcpaOptout = true
            recordIs(record(ccpaOptout = false))

            sut.setUserIdentifier(userA, apiKey)

            verifyNoWrite()
            assertTrue(sut.getCcpaOptout())
        }

    // MARK: - Login (TRUST-2902 rule)

    @Test
    fun `login found record replaces a pre-login flag without writing`() =
        runTest {
            storedPreferences = choice(marketing = true)
            storedCcpaOptout = true
            recordIs(record(ccpaOptout = false))

            sut.setUserIdentifier(userA, apiKey)

            verifyNoWrite()
            assertFalse(sut.getCcpaOptout())
        }

    @Test
    fun `login found record carrying true is adopted`() =
        runTest {
            recordIs(record(ccpaOptout = true))

            sut.setUserIdentifier(userA, apiKey)

            assertTrue(sut.getCcpaOptout())
        }

    @Test
    fun `login found signal-only record still replaces the flag`() =
        runTest {
            storedCcpaOptout = true
            recordIs(UniversalConsentRecord(status = "found", ccpaOptout = false))

            sut.setUserIdentifier(userA, apiKey)

            verifyNoWrite()
            assertFalse(sut.getCcpaOptout())
        }

    @Test
    fun `login miss with an explicit choice attaches the flag`() =
        runTest {
            storedPreferences = choice(marketing = true)
            storedCcpaOptout = true
            recordIs(null)

            sut.setUserIdentifier(userA, apiKey)

            assertEquals(listOf(true), writtenCcpa())
        }

    @Test
    fun `login miss with only a setter call writes nothing and keeps the flag`() =
        runTest {
            sut.setCcpaOptout(true)
            recordIs(null)

            sut.setUserIdentifier(userA, apiKey)

            verifyNoWrite()
            assertTrue(sut.getCcpaOptout())
            assertNull(storedPreferences)
        }

    @Test
    fun `login miss while bound to another identity clears the flag`() =
        runTest {
            storedBoundHash = hashFor(userA)
            storedCcpaOptout = true
            recordIs(null)

            sut.setUserIdentifier(userB, apiKey)

            verifyNoWrite()
            assertFalse(sut.getCcpaOptout())
        }

    // MARK: - Neutral / reset

    @Test
    fun `clearUserIdentifier resets the flag`() {
        storedBoundHash = hashFor(userA)
        storedCcpaOptout = true

        sut.clearUserIdentifier()

        assertFalse(sut.getCcpaOptout())
    }

    @Test
    fun `reset wipes storage, the flag included`() {
        sut.reset()

        verify(mockStorage).clearAll()
    }

    // MARK: - Helpers

    private suspend fun recordIs(record: UniversalConsentRecord?) {
        whenever(mockConsentService.getUniversalConsent(any(), any(), any())).thenReturn(record)
    }

    private suspend fun writtenCcpa(): List<Boolean> {
        val captor = argumentCaptor<Boolean>()
        verify(mockConsentService, org.mockito.Mockito.atLeastOnce())
            .saveUniversalConsent(any(), any(), any(), eq(apiKey), captor.capture(), anyOrNull())
        return captor.allValues
    }

    private suspend fun verifyNoWrite() {
        verify(mockConsentService, never()).saveUniversalConsent(any(), any(), any(), any(), any(), anyOrNull())
    }

    private fun hashFor(identifier: String): String {
        val config = sut.currentConfig!!
        return ConsentService.computeUserHash(config.dgCustomerId, config.consentProjectId!!, identifier)
    }

    private fun choice(marketing: Boolean): ConsentPreferences =
        ConsentPreferences(
            isCustomised = true,
            cookieOptions =
                listOf(
                    CategoryConsent(gtmKey = "category_essential", isEnabled = true),
                    CategoryConsent(gtmKey = "category_marketing", isEnabled = marketing),
                ),
        )

    private fun record(ccpaOptout: Boolean): UniversalConsentRecord =
        UniversalConsentRecord(
            status = "found",
            consentPreferences =
                UniversalConsentPreferences(
                    isCustomised = true,
                    cookieOptions = mapOf("category_essential" to true, "category_marketing" to true),
                ),
            ccpaOptout = ccpaOptout,
        )

    private fun config(syncOptout: Boolean): ConsentConfig {
        val categoryElements =
            listOf("category_essential" to true, "category_marketing" to false).map { (gtmKey, alwaysOn) ->
                ConsentLayerCategory(
                    id =
                        java.util.UUID
                            .randomUUID()
                            .toString(),
                    consentCategoryId =
                        java.util.UUID
                            .randomUUID()
                            .toString(),
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
                id =
                    java.util.UUID
                        .randomUUID()
                        .toString(),
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
                id =
                    java.util.UUID
                        .randomUUID()
                        .toString(),
                name = "Main Layer",
                position = "bottom",
                showCloseButton = true,
                bannerApiId = "main",
                elements = listOf(element),
            )
        val layout =
            Layout(
                id =
                    java.util.UUID
                        .randomUUID()
                        .toString(),
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
            consentContainerVersionId =
                java.util.UUID
                    .randomUUID()
                    .toString(),
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
                    initial = listOf("category_essential"),
                    gpc = emptyList(),
                    optout = emptyList(),
                ),
            layout = layout,
            consentProjectId = "proj_abc123",
            universalConsent = UniversalConsentConfig(enabled = true, syncOptout = syncOptout),
        )
    }
}
