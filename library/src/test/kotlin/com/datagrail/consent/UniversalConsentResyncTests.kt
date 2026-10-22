package com.datagrail.consent

import com.datagrail.consent.models.*
import com.datagrail.consent.network.ConfigService
import com.datagrail.consent.network.ConsentService
import com.datagrail.consent.storage.ConsentStorage
import com.datagrail.consent.storage.UniversalConsentSyncState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
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
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * A same-identity re-sync (TRUST-2902): with nothing changed on this device since the last sync, the
 * record is adopted and nothing is written, also after a process restart; a user change is written
 * as the user's raw choice. The edge stamps every write with its write time, so re-sending a choice
 * the user did not change would beat a newer choice made on another device. Same rule as iOS.
 *
 * Storage is a stateful stub that outlives the manager, so [relaunch] can rebuild the manager over
 * the same persisted state the way a process restart does.
 */
class UniversalConsentResyncTests {
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
    private var storedCcpaWritePending = false
    private var storedSyncState: UniversalConsentSyncState? = null

    private val userA = "alice@example.com"
    private val userB = "bob@example.com"
    private val apiKey = "dg_key"

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        whenever(mockStorage.saveCcpaWritePending(any())).doAnswer { storedCcpaWritePending = it.getArgument(0) }
        whenever(mockStorage.loadCcpaWritePending()).doAnswer { storedCcpaWritePending }
        whenever(mockStorage.savePreferences(any())).doAnswer { storedPreferences = it.getArgument(0) }
        whenever(mockStorage.loadPreferences()).doAnswer { storedPreferences }
        whenever(mockStorage.clearPreferences()).doAnswer { storedPreferences = null }
        whenever(mockStorage.saveCcpaOptout(any())).doAnswer { storedCcpaOptout = it.getArgument(0) }
        whenever(mockStorage.loadCcpaOptout()).doAnswer { storedCcpaOptout }
        whenever(mockStorage.clearCcpaOptout()).doAnswer { storedCcpaOptout = false }
        whenever(mockStorage.saveBoundUserHash(any())).doAnswer { storedBoundHash = it.getArgument(0) }
        whenever(mockStorage.loadBoundUserHash()).doAnswer { storedBoundHash }
        whenever(mockStorage.clearBoundUserHash()).doAnswer {
            storedBoundHash = null
            storedSyncState = null
        }
        whenever(mockStorage.saveSyncState(any())).doAnswer { storedSyncState = it.getArgument(0) }
        whenever(mockStorage.loadSyncState()).doAnswer { storedSyncState }
        whenever(mockStorage.clearSyncState()).doAnswer { storedSyncState = null }
        whenever(mockStorage.clearAll()).doAnswer {
            storedPreferences = null
            storedCcpaOptout = false
            storedBoundHash = null
            storedCcpaWritePending = false
            storedSyncState = null
        }
        relaunch()
    }

    // MARK: - Nothing changed on this device

    @Test
    fun `unchanged re-sync adopts another device's newer choice without writing`() =
        runTest {
            recordIs(record(marketing = true))
            sut.setUserIdentifier(userA, apiKey)

            // Another device turned marketing off since.
            recordIs(record(marketing = false))
            sut.setUserIdentifier(userA, apiKey)

            verifyNoWrite()
            assertEquals(choice(marketing = false), storedPreferences)
        }

    @Test
    fun `unchanged re-sync after a relaunch writes nothing`() =
        runTest {
            recordIs(record(marketing = true))
            sut.setUserIdentifier(userA, apiKey)

            relaunch()
            recordIs(record(marketing = false))
            sut.setUserIdentifier(userA, apiKey)

            verifyNoWrite()
            assertEquals(choice(marketing = false), storedPreferences)
        }

    @Test
    fun `signal-suppressed view is not written by a re-sync after a relaunch`() =
        runTest {
            recordIs(record(marketing = true))
            sut.setUserIdentifier(userA, apiKey, trackingSignal = TrackingSignal.DENIED)
            assertEquals(choice(marketing = false), storedPreferences)

            relaunch()
            sut.setUserIdentifier(userA, apiKey, trackingSignal = TrackingSignal.DENIED)
            relaunch()
            sut.setUserIdentifier(userA, apiKey, trackingSignal = TrackingSignal.DENIED)

            verifyNoWrite()
            assertEquals(choice(marketing = false), storedPreferences)
        }

    @Test
    fun `write-through after an unchanged re-sync after a relaunch sends the record's raw opt-in`() =
        runTest {
            recordIs(record(marketing = true))
            sut.setUserIdentifier(userA, apiKey, trackingSignal = TrackingSignal.DENIED)

            relaunch()
            sut.setUserIdentifier(userA, apiKey, trackingSignal = TrackingSignal.DENIED)
            sut.setCcpaOptout(true)

            assertEquals(true, writtenPreferences().single().cookieOptions["category_marketing"])
        }

    @Test
    fun `pending setter choice after a relaunch is written by an unchanged re-sync`() =
        runTest {
            recordIs(record(marketing = true))
            sut.setUserIdentifier(userA, apiKey)

            relaunch()
            // No session after the restart, so the setter stays local and pending.
            sut.setCcpaOptout(true)
            verifyNoWrite()
            assertTrue(storedCcpaWritePending)

            // Another device turned marketing off since; that stays as the record has it.
            recordIs(record(marketing = false))
            sut.setUserIdentifier(userA, apiKey)

            verify(mockConsentService).saveUniversalConsent(any(), any(), any(), eq(apiKey), eq(true), anyOrNull())
            assertEquals(false, writtenPreferences().single().cookieOptions["category_marketing"])
            assertFalse(storedCcpaWritePending)
            assertTrue(sut.getCcpaOptout())
            assertEquals(choice(marketing = false), storedPreferences)
        }

    // MARK: - The user changed something

    @Test
    fun `user change then a re-sync after a relaunch writes the raw choice`() =
        runTest {
            recordIs(record(marketing = true))
            sut.setUserIdentifier(userA, apiKey, trackingSignal = TrackingSignal.DENIED)
            sut.savePreferences(choice(marketing = true, analytics = false)) {}

            relaunch()
            sut.setUserIdentifier(userA, apiKey, trackingSignal = TrackingSignal.DENIED)

            // The user's raw saved choice; the denied signal only shapes local state.
            assertEquals(
                mapOf("category_essential" to true, "category_marketing" to true, "category_analytics" to false),
                writtenPreferences().single().cookieOptions,
            )
        }

    @Test
    fun `re-sync writing a user change through keeps that change locally`() =
        runTest {
            recordIs(record(marketing = true))
            sut.setUserIdentifier(userA, apiKey)
            val userChoice = choice(marketing = false, analytics = true)
            sut.savePreferences(userChoice) {}

            sut.setUserIdentifier(userA, apiKey)

            assertEquals(false, writtenPreferences().single().cookieOptions["category_marketing"])
            assertEquals("not the record's older view", userChoice, storedPreferences)
            assertEquals(userChoice, storedSyncState?.syncedLocal)
            assertEquals(userChoice, storedSyncState?.syncedRaw)
        }

    @Test
    fun `save during an in-flight write is written by the next re-sync`() =
        runTest {
            storedPreferences = choice(marketing = true)
            recordIs(null)
            val writeGate = CompletableDeferred<Unit>()
            whenever(mockConsentService.saveUniversalConsent(any(), any(), any(), any(), any(), anyOrNull()))
                .doSuspendableAnswer { writeGate.await() }
            val seed = launch { sut.setUserIdentifier(userA, apiKey) }
            testScheduler.runCurrent()

            // The user saves a new choice while the seed write is in flight.
            sut.savePreferences(choice(marketing = false)) {}
            writeGate.complete(Unit)
            seed.join()

            recordIs(record(marketing = true))
            sut.setUserIdentifier(userA, apiKey)

            val written = writtenPreferences()
            assertEquals(2, written.size)
            assertEquals(false, written.last().cookieOptions["category_marketing"])
        }

    // MARK: - Standalone rehydrate

    @Test
    fun `standalone rehydrate under a denied signal is not written by the next re-sync`() =
        runTest {
            recordIs(record(marketing = true))
            sut.setUserIdentifier(userA, apiKey)

            assertTrue(sut.rehydrateFromUniversalConsent(userA, apiKey, TrackingSignal.DENIED))
            assertEquals("local state holds the suppressed view", choice(marketing = false), storedPreferences)
            sut.setUserIdentifier(userA, apiKey, trackingSignal = TrackingSignal.DENIED)

            verifyNoWrite()
            sut.setCcpaOptout(true)
            assertEquals(
                "the record's raw opt-in",
                true,
                writtenPreferences().single().cookieOptions["category_marketing"],
            )
        }

    @Test
    fun `standalone rehydrate of a GPC record is not written by the next re-sync after a relaunch`() =
        runTest {
            recordIs(record(marketing = true))
            sut.setUserIdentifier(userA, apiKey)

            // Another device recorded GPC since; the raw opt-in is unchanged.
            recordIs(record(marketing = true, gpc = true))
            assertTrue(sut.rehydrateFromUniversalConsent(userA, apiKey))
            assertEquals(choice(marketing = false), storedPreferences)
            val state = requireNotNull(storedSyncState)
            assertEquals("what the rehydrate stored", storedPreferences, state.syncedLocal)
            val rawOptions = requireNotNull(state.syncedRaw).cookieOptions
            val rawMarketing = rawOptions.single { it.gtmKey == "category_marketing" }
            assertTrue("the record's raw opt-in", rawMarketing.isEnabled)

            relaunch()
            sut.setUserIdentifier(userA, apiKey)

            verifyNoWrite()
        }

    @Test
    fun `standalone rehydrate for another identity clears the sync state`() =
        runTest {
            recordIs(record(marketing = true))
            sut.setUserIdentifier(userA, apiKey)

            assertTrue(sut.rehydrateFromUniversalConsent(userB, apiKey))

            assertNull("the stored view is no longer what user A's sync left", storedSyncState)
            assertEquals(hashFor(userA), storedBoundHash)
        }

    // MARK: - Saves and setter calls during a sync's read

    @Test
    fun `save during a re-sync read is written by the next re-sync`() =
        runTest {
            recordIs(record(marketing = true))
            sut.setUserIdentifier(userA, apiKey)

            // The record is gone (a miss), and the user saves a new choice while the read is in flight.
            val readGate = holdTheRead(null)
            val resync = launch { sut.setUserIdentifier(userA, apiKey) }
            testScheduler.runCurrent()
            sut.savePreferences(choice(marketing = false, analytics = true)) {}
            readGate.complete(Unit)
            resync.join()
            verifyNoWrite()

            recordIs(null)
            sut.setUserIdentifier(userA, apiKey)

            assertEquals(false, writtenPreferences().single().cookieOptions["category_marketing"])
        }

    @Test
    fun `save the re-sync's adopt overwrote is not later written as the suppressed view`() =
        runTest {
            recordIs(record(marketing = true))
            sut.setUserIdentifier(userA, apiKey, trackingSignal = TrackingSignal.DENIED)

            val readGate = holdTheRead(record(marketing = true))
            val resync = launch { sut.setUserIdentifier(userA, apiKey, trackingSignal = TrackingSignal.DENIED) }
            testScheduler.runCurrent()
            sut.savePreferences(choice(marketing = true, analytics = false)) {}
            // The adopt lands after the save and overwrites it with the record's suppressed view.
            readGate.complete(Unit)
            resync.join()
            assertEquals(choice(marketing = false), storedPreferences)

            recordIs(record(marketing = true))
            sut.setUserIdentifier(userA, apiKey, trackingSignal = TrackingSignal.DENIED)

            verifyNoWrite()
        }

    @Test
    fun `setter during a first login read is not reverted by the next re-sync or rehydrate`() =
        runTest {
            val readGate = holdTheRead(record(marketing = true))
            val login = launch { sut.setUserIdentifier(userA, apiKey) }
            testScheduler.runCurrent()
            sut.setCcpaOptout(true)
            readGate.complete(Unit)
            login.join()
            assertTrue(sut.getCcpaOptout())
            assertTrue("no write carried it, so it is pending", storedCcpaWritePending)

            recordIs(record(marketing = true))
            sut.rehydrateFromUniversalConsent(userA, apiKey)
            assertTrue("the record's older false does not replace it", sut.getCcpaOptout())
            verifyNoWrite()

            // The next re-sync carries it, even with no category change.
            sut.setUserIdentifier(userA, apiKey)
            verify(mockConsentService).saveUniversalConsent(any(), any(), any(), eq(apiKey), eq(true), anyOrNull())
            assertFalse(storedCcpaWritePending)
            assertTrue(sut.getCcpaOptout())
        }

    @Test
    fun `logout during a re-sync read keeps what was stored after it`() =
        runTest {
            recordIs(record(marketing = true))
            sut.setUserIdentifier(userA, apiKey)

            val readGate = holdTheRead(record(marketing = true))
            val resync = launch { sut.setUserIdentifier(userA, apiKey) }
            testScheduler.runCurrent()
            sut.clearUserIdentifier()
            sut.savePreferences(choice(marketing = false, analytics = true)) {}
            sut.setCcpaOptout(true)
            readGate.complete(Unit)
            resync.join()

            assertEquals(choice(marketing = false, analytics = true), storedPreferences)
            assertTrue(sut.getCcpaOptout())
            assertNull(storedBoundHash)
        }

    // MARK: - The persisted session's lifetime

    @Test
    fun `logout clears the persisted sync state`() =
        runTest {
            recordIs(record(marketing = true))
            sut.setUserIdentifier(userA, apiKey)
            assertEquals(hashFor(userA), storedSyncState?.userHash)

            sut.clearUserIdentifier()

            assertNull(storedSyncState)
        }

    @Test
    fun `reset clears the persisted sync state`() =
        runTest {
            recordIs(record(marketing = true))
            sut.setUserIdentifier(userA, apiKey)

            sut.reset()

            assertNull(storedSyncState)
        }

    @Test
    fun `identity switch replaces the persisted sync state`() =
        runTest {
            recordIs(record(marketing = true))
            sut.setUserIdentifier(userA, apiKey)

            recordIs(null)
            sut.setUserIdentifier(userB, apiKey)

            assertEquals(hashFor(userB), storedBoundHash)
            val state = requireNotNull(storedSyncState)
            assertEquals("user A's sync state is replaced", hashFor(userB), state.userHash)
            assertNull("A's state returned to neutral; nothing of it is kept", state.syncedLocal)
            assertNull(state.syncedRaw)
        }

    @Test
    fun `sync state for another identity is dropped on launch`() =
        runTest {
            recordIs(record(marketing = true))
            sut.setUserIdentifier(userA, apiKey)
            storedBoundHash = hashFor(userB)

            relaunch()

            assertNull(storedSyncState)
        }

    // MARK: - Helpers

    /** Build a fresh manager over the same storage, as a process restart does. */
    private fun relaunch() {
        sut = ConsentManager(mockStorage, mockConfigService, mockConsentService)
        sut.currentConfig = universalConsentTestConfig(syncOptout = true)
    }

    private suspend fun recordIs(record: UniversalConsentRecord?) {
        whenever(mockConsentService.getUniversalConsent(any(), any(), any())).thenReturn(record)
    }

    /** Serve [record] only once the returned gate completes, so a test can act during the read. */
    private suspend fun holdTheRead(record: UniversalConsentRecord?): CompletableDeferred<Unit> {
        val gate = CompletableDeferred<Unit>()
        whenever(mockConsentService.getUniversalConsent(any(), any(), any())).doSuspendableAnswer {
            gate.await()
            record
        }
        return gate
    }

    private suspend fun writtenPreferences(): List<UniversalConsentPreferences> {
        val captor = argumentCaptor<UniversalConsentPreferences>()
        verify(mockConsentService, org.mockito.Mockito.atLeastOnce())
            .saveUniversalConsent(any(), any(), captor.capture(), eq(apiKey), any(), anyOrNull())
        return captor.allValues
    }

    private suspend fun verifyNoWrite() {
        verify(mockConsentService, never()).saveUniversalConsent(any(), any(), any(), any(), any(), anyOrNull())
    }

    private fun hashFor(identifier: String): String {
        val config = sut.currentConfig!!
        return ConsentService.computeUserHash(config.dgCustomerId, config.consentProjectId!!, identifier)
    }

    private fun choice(
        marketing: Boolean,
        analytics: Boolean? = null,
    ): ConsentPreferences =
        ConsentPreferences(
            isCustomised = true,
            cookieOptions =
                listOfNotNull(
                    CategoryConsent(gtmKey = "category_essential", isEnabled = true),
                    CategoryConsent(gtmKey = "category_marketing", isEnabled = marketing),
                    analytics?.let { CategoryConsent(gtmKey = "category_analytics", isEnabled = it) },
                ),
        )

    private fun record(
        marketing: Boolean,
        gpc: Boolean = false,
    ): UniversalConsentRecord =
        UniversalConsentRecord(
            status = "found",
            consentPreferences =
                UniversalConsentPreferences(
                    isCustomised = true,
                    cookieOptions = mapOf("category_essential" to true, "category_marketing" to marketing),
                ),
            ccpaOptout = false,
            gpc = gpc,
        )
}
