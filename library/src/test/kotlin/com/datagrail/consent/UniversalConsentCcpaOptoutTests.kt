package com.datagrail.consent

import com.datagrail.consent.models.*
import com.datagrail.consent.network.ConfigService
import com.datagrail.consent.network.ConsentService
import com.datagrail.consent.storage.ConsentStorage
import com.datagrail.consent.storage.UniversalConsentSyncState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * First-class `ccpa_optout` (TRUST-2591): the user's explicit "Do Not Sell or Share" choice, set by
 * the host app, never derived from a category or the ad-tracking signal, gated on the wire by
 * `syncOptout`, and replaced by a found record on login whatever the gate (TRUST-2902 rule); a
 * re-sync adopt is gated. Same rule as the web/iOS/React Native SDKs.
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
    private var storedCcpaWritePending = false
    private var storedSyncState: UniversalConsentSyncState? = null
    private var storedDecisionTs: Long? = null

    private val userA = "alice@example.com"
    private val userB = "bob@example.com"
    private val apiKey = "dg_key"

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        storedPreferences = null
        storedCcpaOptout = false
        storedBoundHash = null
        storedCcpaWritePending = false
        storedSyncState = null
        storedDecisionTs = null
        whenever(mockStorage.saveSyncState(any())).doAnswer { storedSyncState = it.getArgument(0) }
        whenever(mockStorage.loadSyncState()).doAnswer { storedSyncState }
        whenever(mockStorage.clearSyncState()).doAnswer { storedSyncState = null }
        whenever(mockStorage.saveCcpaWritePending(any())).doAnswer { storedCcpaWritePending = it.getArgument(0) }
        whenever(mockStorage.loadCcpaWritePending()).doAnswer { storedCcpaWritePending }
        whenever(mockStorage.savePreferences(any(), anyOrNull())).doAnswer {
            storedPreferences = it.getArgument(0)
            storedDecisionTs = it.getArgument(1)
        }
        whenever(mockStorage.replacePreferencesKeepingDecisionTs(any())).doAnswer { storedPreferences = it.getArgument(0) }
        whenever(mockStorage.loadPreferences()).doAnswer { storedPreferences }
        whenever(mockStorage.loadDecisionTs()).doAnswer { storedDecisionTs }
        whenever(mockStorage.clearPreferences()).doAnswer {
            storedPreferences = null
            storedDecisionTs = null
        }
        whenever(mockStorage.saveCcpaOptout(any())).doAnswer { storedCcpaOptout = it.getArgument(0) }
        whenever(mockStorage.saveCcpaChoice(any(), any())).doAnswer { storedCcpaOptout = it.getArgument(0) }
        whenever(mockStorage.loadCcpaOptout()).doAnswer { storedCcpaOptout }
        whenever(mockStorage.clearCcpaOptout()).doAnswer { storedCcpaOptout = false }
        whenever(mockStorage.saveBoundUserHash(any())).doAnswer { storedBoundHash = it.getArgument(0) }
        whenever(mockStorage.loadBoundUserHash()).doAnswer { storedBoundHash }
        whenever(mockStorage.clearBoundUserHash()).doAnswer {
            storedBoundHash = null
            storedSyncState = null
        }
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

            verify(mockConsentService, times(1)).saveUniversalConsent(any(), any(), any(), any(), any(), anyOrNull(), anyOrNull(), any())
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

            verify(mockConsentService, times(1)).saveUniversalConsent(any(), any(), any(), any(), any(), anyOrNull(), anyOrNull(), any())
        }

    @Test
    fun `write-through aborts when a logout lands before the POST`() =
        runTest {
            storedPreferences = choice(marketing = true)
            recordIs(null)
            sut.setUserIdentifier(userA, apiKey) // binds the identity; one login write
            assertEquals(listOf(false), writtenCcpa())

            // Simulate clearUserIdentifier() landing after setCcpaOptout's synchronous checks pass but
            // before it issues the write-through: the last read before the POST triggers the logout
            // (one-shot — clearUserIdentifier itself reads preferences again), which bumps
            // identityOperationGeneration and unbinds. The generation guard must then skip the POST so
            // a stale opt-out never lands against the identity the device just left.
            var logoutTriggered = false
            whenever(mockStorage.loadPreferences()).doAnswer {
                val current = storedPreferences
                if (!logoutTriggered) {
                    logoutTriggered = true
                    sut.clearUserIdentifier()
                }
                current
            }

            sut.setCcpaOptout(true)

            // Still only the login write — no stale CCPA write-through — and the logout left the flag
            // neutral.
            verify(mockConsentService, times(1)).saveUniversalConsent(any(), any(), any(), any(), any(), anyOrNull(), anyOrNull(), any())
            assertFalse(sut.getCcpaOptout())
        }

    @Test
    fun `setter skips the stale re-persist when a logout landed before the write-through ran`() =
        runTest {
            storedPreferences = choice(marketing = true)
            recordIs(null)
            sut.setUserIdentifier(userA, apiKey) // binds the identity

            // The adapter persists the flag synchronously and captures the generation, THEN launches
            // the write-through. Simulate a clearUserIdentifier() landing in that gap: it bumps the
            // generation and clears the flag before the launched coroutine runs with the stale token.
            val opGeneration = sut.identityOperationGenerationSnapshot()
            sut.saveCcpaOptout(true) // the adapter's synchronous persist
            sut.clearUserIdentifier() // logout lands before the launched write-through

            sut.setCcpaOptout(true, opGeneration) // the launched coroutine, carrying the pre-logout token

            // The stale coroutine neither re-persisted the opt-out nor wrote through: the logout's
            // neutral state stands.
            assertFalse(sut.getCcpaOptout())
            verify(mockConsentService, times(1)).saveUniversalConsent(any(), any(), any(), any(), any(), anyOrNull(), anyOrNull(), any())
        }

    @Test
    fun `adapter write-through does not re-persist over a newer synchronous toggle`() =
        runTest {
            storedPreferences = choice(marketing = true)
            recordIs(null)
            sut.setUserIdentifier(userA, apiKey)

            // setCcpaOptout(true); setCcpaOptout(false) on Main: both synchronous persists run first,
            // then the launched write-throughs.
            val opGeneration = sut.identityOperationGenerationSnapshot()
            sut.saveCcpaOptout(true)
            sut.saveCcpaOptout(false)
            sut.setCcpaOptout(true, opGeneration, persistsLocally = false)

            assertFalse("the last choice stands", sut.getCcpaOptout())
            assertEquals(listOf(false, false), writtenCcpa())
        }

    @Test
    fun `write-through posts the latest stored flag, not the captured parameter`() =
        runTest {
            storedPreferences = choice(marketing = true)
            recordIs(null)
            sut.setUserIdentifier(userA, apiKey)
            assertEquals(listOf(false), writtenCcpa())

            // A newer toggle reaches storage after this call persisted its own value but before the
            // POST reads it back — the write must carry the LATEST stored flag so two rapid toggles
            // completing out of order cannot reverse the user's intent.
            var bumped = false
            whenever(mockStorage.loadPreferences()).doAnswer {
                if (!bumped) {
                    bumped = true
                    storedCcpaOptout = false
                }
                storedPreferences
            }

            sut.setCcpaOptout(true)

            assertEquals(listOf(false, false), writtenCcpa())
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
            // Made after the record's latest decision, so it is written through (TRUST-3224).
            storedDecisionTs = System.currentTimeMillis() / 1000
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

    @Test
    fun `re-sync adopts a signal-only record's flag with no local choice and no write`() =
        runTest {
            // Re-sync (bound to the same identity) against a signal-only record: ccpa_optout set but
            // no consent_preferences, e.g. a web DNSMPI opt-out made before any category choice. The
            // record is authoritative for the flag and must replace the local value, matching the
            // LOGIN signal-only path — even though there are no raw preferences to write back.
            storedBoundHash = hashFor(userA)
            storedCcpaOptout = false
            recordIs(UniversalConsentRecord(status = "found", ccpaOptout = true))

            sut.setUserIdentifier(userA, apiKey)

            verifyNoWrite()
            assertTrue(sut.getCcpaOptout())
        }

    @Test
    fun `write-through re-resolves a rotated config_json apiKey, not the one bound at setUserIdentifier`() =
        runTest {
            // Host relies on config.json for the key (passes null); it binds under key_v1.
            sut.currentConfig =
                config(syncOptout = true).copy(
                    universalConsent = UniversalConsentConfig(enabled = true, syncOptout = true, apiKey = "key_v1"),
                )
            storedPreferences = choice(marketing = true)
            recordIs(null)
            sut.setUserIdentifier(userA, apiKey = null)

            // The edge key rotates server-side via config.json — no client release, no new login.
            sut.currentConfig =
                sut.currentConfig!!.copy(
                    universalConsent = UniversalConsentConfig(enabled = true, syncOptout = true, apiKey = "key_v2"),
                )

            sut.setCcpaOptout(true)

            // The opt-out write re-resolves against the live config and uses the CURRENT key, not the
            // stale one captured at bind time (TRUST-2603 review on #65).
            val keyCaptor = argumentCaptor<String>()
            verify(mockConsentService, times(2))
                .saveUniversalConsent(any(), any(), any(), keyCaptor.capture(), any(), anyOrNull(), anyOrNull(), any())
            assertEquals("key_v1", keyCaptor.firstValue)
            assertEquals("key_v2", keyCaptor.secondValue)
        }

    @Test
    fun `write-through keeps honoring an explicit apiKey passed at setUserIdentifier`() =
        runTest {
            // Host passed an explicit key; an explicit value always wins over config.json, so a later
            // config.json key must NOT override it on the opt-out write.
            sut.currentConfig =
                config(syncOptout = true).copy(
                    universalConsent = UniversalConsentConfig(enabled = true, syncOptout = true, apiKey = "config_key"),
                )
            storedPreferences = choice(marketing = true)
            recordIs(null)
            sut.setUserIdentifier(userA, apiKey = "explicit_key")

            sut.setCcpaOptout(true)

            val keyCaptor = argumentCaptor<String>()
            verify(mockConsentService, times(2))
                .saveUniversalConsent(any(), any(), any(), keyCaptor.capture(), any(), anyOrNull(), anyOrNull(), any())
            assertEquals("explicit_key", keyCaptor.firstValue)
            assertEquals("explicit_key", keyCaptor.secondValue)
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
    fun `login found record replaces a pre-login flag with the gate off too`() =
        runTest {
            // A login is a full record-wins reset, so the record's ccpa_optout is adopted whatever
            // the syncOptout gate, the same as the web and iOS SDKs. Only the re-sync adopt is gated.
            sut.currentConfig = config(syncOptout = false)
            storedPreferences = choice(marketing = true)
            storedCcpaOptout = true
            recordIs(record(ccpaOptout = false))

            sut.setUserIdentifier(userA, apiKey)

            verifyNoWrite()
            assertFalse(sut.getCcpaOptout())
        }

    @Test
    fun `login found record carrying true is adopted with the gate off`() =
        runTest {
            sut.currentConfig = config(syncOptout = false)
            recordIs(record(ccpaOptout = true))

            sut.setUserIdentifier(userA, apiKey)

            assertTrue(sut.getCcpaOptout())
        }

    @Test
    fun `login found signal-only record replaces the flag with the gate off`() =
        runTest {
            sut.currentConfig = config(syncOptout = false)
            storedCcpaOptout = true
            recordIs(UniversalConsentRecord(status = "found", ccpaOptout = false))

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

    @Test
    fun `login miss while bound to another identity clears the flag with the gate off too`() =
        runTest {
            sut.currentConfig = config(syncOptout = false)
            storedBoundHash = hashFor(userA)
            storedCcpaOptout = true
            recordIs(null)

            sut.setUserIdentifier(userB, apiKey)

            assertFalse("the previous identity's flag never carries over", sut.getCcpaOptout())
        }

    // MARK: - A choice that has not reached the record yet

    @Test
    fun `an unsent bound choice is not replaced by the record on a re-sync with no category choice`() =
        runTest {
            storedBoundHash = hashFor(userA)
            recordIs(record(ccpaOptout = false))
            sut.setUserIdentifier(userA, apiKey)
            storedPreferences = null

            sut.setCcpaOptout(true)
            sut.setUserIdentifier(userA, apiKey)

            verifyNoWrite()
            assertTrue(sut.getCcpaOptout())
        }

    @Test
    fun `an unsent bound choice is not replaced by rehydrateFromUniversalConsent`() =
        runTest {
            storedBoundHash = hashFor(userA)
            sut.setCcpaOptout(true)
            recordIs(record(ccpaOptout = false))

            sut.rehydrateFromUniversalConsent(userA, apiKey)

            assertTrue(sut.getCcpaOptout())
        }

    @Test
    fun `a failed write-through keeps the choice pending, and a later successful write clears it`() =
        runTest {
            storedPreferences = choice(marketing = true)
            recordIs(null)
            sut.setUserIdentifier(userA, apiKey)
            whenever(mockConsentService.saveUniversalConsent(any(), any(), any(), any(), any(), anyOrNull(), anyOrNull(), any()))
                .doAnswer { throw ConsentException.NetworkError("offline") }

            try {
                sut.setCcpaOptout(true)
                fail("expected the write-through to fail")
            } catch (expected: ConsentException.NetworkError) {
                // The local flag is kept and stays pending.
            }
            assertTrue(storedCcpaWritePending)

            whenever(mockConsentService.saveUniversalConsent(any(), any(), any(), any(), any(), anyOrNull(), anyOrNull(), any()))
                .thenReturn(Unit)
            sut.setCcpaOptout(true)
            assertFalse(storedCcpaWritePending)
        }

    // MARK: - Interleaving with an in-flight login / rehydrate

    @Test
    fun `a setter call during a login read is not overwritten by the found record`() =
        runTest {
            whenever(mockConsentService.getUniversalConsent(any(), any(), any())).doAnswer {
                // The adapter's synchronous half of setCcpaOptout, landing while the GET is outstanding.
                sut.saveCcpaOptout(true)
                record(ccpaOptout = false)
            }

            sut.setUserIdentifier(userA, apiKey)

            assertTrue(sut.getCcpaOptout())
        }

    @Test
    fun `a logout during rehydrateFromUniversalConsent does not restore the previous user's state`() =
        runTest {
            storedBoundHash = hashFor(userA)
            whenever(mockConsentService.getUniversalConsent(any(), any(), any())).doAnswer {
                sut.clearUserIdentifier()
                record(ccpaOptout = true)
            }

            val rehydrated = sut.rehydrateFromUniversalConsent(userA, apiKey)

            assertFalse(rehydrated)
            assertFalse(sut.getCcpaOptout())
            assertNull(storedPreferences)
        }

    @Test
    fun `write-through re-reads the gate under the lock`() =
        runTest {
            storedPreferences = choice(marketing = true)
            recordIs(null)
            sut.setUserIdentifier(userA, apiKey)
            val readGate = CompletableDeferred<Unit>()
            whenever(mockConsentService.getUniversalConsent(any(), any(), any())).doSuspendableAnswer {
                readGate.await()
                null
            }
            // A re-sync holds the lock across its read; the setter passes its fast path and waits.
            val resync = launch { sut.setUserIdentifier(userA, apiKey) }
            val setter = launch { sut.setCcpaOptout(true) }
            testScheduler.runCurrent()
            // A config reload turns the gate off while the setter is queued.
            sut.currentConfig = config(syncOptout = false)
            readGate.complete(Unit)
            resync.join()
            setter.join()

            // Only the login seed: the unchanged re-sync writes nothing, and the setter doesn't write through.
            verify(mockConsentService, times(1)).saveUniversalConsent(any(), any(), any(), any(), any(), anyOrNull(), anyOrNull(), any())
        }

    @Test
    fun `a setter call racing a rehydrate adopt is not overwritten by the record`() {
        storedBoundHash = hashFor(userA)

        racePausedSetter(record(ccpaOptout = false)) { sut.rehydrateFromUniversalConsent(userA, apiKey) }

        assertTrue(sut.getCcpaOptout())
        assertTrue(storedCcpaWritePending)
    }

    @Test
    fun `a setter call racing a login adopt is not overwritten by the record`() {
        racePausedSetter(record(ccpaOptout = false)) { sut.setUserIdentifier(userA, apiKey) }

        assertTrue(sut.getCcpaOptout())
        assertTrue(storedCcpaWritePending)
    }

    /**
     * Runs the adapter's synchronous half of `setCcpaOptout(true)` on its own thread, paused right
     * after it stores the flag (before the revision bump), and runs [sync] on another thread in that
     * window. [sync] captures its CCPA revision before its GET, so the window is open once the GET
     * (answered with [found]) is reached. Without the setter and the sync's revision check sharing
     * one lock, [sync] then finishes inside the window and adopts the record's value over the newer
     * choice; with it, [sync] blocks on the lock, and the setter is released once it does.
     */
    private fun racePausedSetter(
        found: UniversalConsentRecord,
        sync: suspend () -> Unit,
    ) {
        val flagStored = CountDownLatch(1)
        val readReached = CountDownLatch(1)
        val resume = CountDownLatch(1)
        lateinit var setter: Thread
        var setterFailure: Throwable? = null
        var syncFailure: Throwable? = null
        whenever(mockStorage.saveCcpaChoice(any(), any())).doAnswer {
            storedCcpaOptout = it.getArgument(0)
            if (Thread.currentThread() === setter) {
                flagStored.countDown()
                resume.await(5, TimeUnit.SECONDS)
            }
        }
        runBlocking {
            whenever(mockConsentService.getUniversalConsent(any(), any(), any())).doAnswer {
                readReached.countDown()
                found
            }
        }
        setter = thread(start = false) { runCatching { sut.saveCcpaOptout(true) }.onFailure { setterFailure = it } }
        val syncThread = thread(start = false) { runCatching { runBlocking { sync() } }.onFailure { syncFailure = it } }
        try {
            setter.start()
            assertTrue("setter never stored the flag", flagStored.await(5, TimeUnit.SECONDS))
            syncThread.start()
            assertTrue("sync never reached its read", readReached.await(5, TimeUnit.SECONDS))
            // Wait until the sync has either finished inside the window or is blocked on the lock.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (syncThread.state != Thread.State.TERMINATED && syncThread.state != Thread.State.BLOCKED) {
                assertTrue("sync neither finished nor blocked", System.nanoTime() < deadline)
                Thread.sleep(1)
            }
        } finally {
            resume.countDown()
            setter.join(5_000)
            syncThread.join(5_000)
        }
        assertFalse(setter.isAlive)
        assertFalse(syncThread.isAlive)
        setterFailure?.let { throw it }
        syncFailure?.let { throw it }
    }

    // MARK: - Raw choice on the write-through (TRUST-3074)

    @Test
    fun `write-through after a login adopt under a denied signal leaves the record's raw opt-in untouched`() =
        runTest {
            recordIs(record(ccpaOptout = false))
            sut.setUserIdentifier(userA, apiKey, trackingSignal = TrackingSignal.DENIED)
            // Local state is the suppressed view; the record still holds marketing=true.
            assertEquals(choice(marketing = false), storedPreferences)

            sut.setCcpaOptout(true)

            val written = writtenPreferences().single()
            assertEquals("CCPA-only: the record's raw opt-in is untouched", emptyMap<String, Boolean>(), written.cookieOptions)
            assertEquals(listOf(true), writtenCcpa())
        }

    @Test
    fun `write-through after adopting an opted-out record sends no category, not all-off`() =
        runTest {
            // A web DNSMPI opt-out: ccpa_optout plus the record's stored gpc, which suppresses locally.
            recordIs(record(ccpaOptout = true).copy(gpc = true))
            sut.setUserIdentifier(userA, apiKey)
            assertTrue(sut.getCcpaOptout())
            assertEquals(choice(marketing = false), storedPreferences)

            sut.setCcpaOptout(false)

            val written = writtenPreferences().single()
            assertEquals("CCPA-only, never the all-off view", emptyMap<String, Boolean>(), written.cookieOptions)
            assertEquals(listOf(false), writtenCcpa())
        }

    @Test
    fun `write-through after a user save is CCPA-only and leaves the save to the re-sync`() =
        runTest {
            recordIs(record(ccpaOptout = false))
            sut.setUserIdentifier(userA, apiKey, trackingSignal = TrackingSignal.DENIED)
            // The user saves the same values the suppressed view shows: still their own choice now.
            sut.savePreferences(choice(marketing = false)) {}

            sut.setCcpaOptout(true)

            // TRUST-2591 decision (2026-10-26): a CCPA change never resends the category map.
            assertEquals(emptyMap<String, Boolean>(), writtenPreferences().single().cookieOptions)
            assertEquals(listOf(true), writtenCcpa())
        }

    @Test
    fun `same-identity re-sync with nothing changed writes nothing, not the suppressed view`() =
        runTest {
            recordIs(record(ccpaOptout = false))
            sut.setUserIdentifier(userA, apiKey, trackingSignal = TrackingSignal.DENIED)
            verifyNoWrite()

            sut.setUserIdentifier(userA, apiKey, trackingSignal = TrackingSignal.DENIED)

            // Nothing changed on this device, so the record is adopted and nothing is re-sent.
            verifyNoWrite()
            assertEquals(choice(marketing = false), storedPreferences)
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
            .saveUniversalConsent(any(), any(), any(), eq(apiKey), captor.capture(), anyOrNull(), anyOrNull(), any())
        return captor.allValues
    }

    private suspend fun writtenPreferences(): List<UniversalConsentPreferences> {
        val captor = argumentCaptor<UniversalConsentPreferences>()
        verify(mockConsentService, org.mockito.Mockito.atLeastOnce())
            .saveUniversalConsent(any(), any(), captor.capture(), eq(apiKey), any(), anyOrNull(), anyOrNull(), any())
        return captor.allValues
    }

    private suspend fun verifyNoWrite() {
        verify(mockConsentService, never()).saveUniversalConsent(any(), any(), any(), any(), any(), anyOrNull(), anyOrNull(), any())
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
            // Older than any choice a test makes, so a later local change is newer (TRUST-3224).
            updatedAt = RECORD_UPDATED_AT,
            consentPreferences =
                UniversalConsentPreferences(
                    isCustomised = true,
                    cookieOptions = mapOf("category_essential" to true, "category_marketing" to true),
                ),
            ccpaOptout = ccpaOptout,
        )

    private fun config(syncOptout: Boolean): ConsentConfig = universalConsentTestConfig(syncOptout)
}
