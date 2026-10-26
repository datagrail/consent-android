package com.datagrail.consent

import com.datagrail.consent.models.*
import com.datagrail.consent.network.ConfigService
import com.datagrail.consent.network.ConsentService
import com.datagrail.consent.storage.ConsentStorage
import com.datagrail.consent.storage.UniversalConsentSyncState
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
import org.mockito.kotlin.nullableArgumentCaptor
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * A stale device's re-sync must not undo a newer revocation (TRUST-3224, the Android port of the
 * web fix TRUST-3154; sign-off scenario P0-06). The edge merges each category as decision-time
 * last-writer-wins, so the SDK records when every choice was made, sends that as the write's
 * `decision_ts`, and writes a local change through over a found record only when it is strictly
 * newer than the record's `updated_at`. Otherwise it adopts the record.
 *
 * Storage is a stateful stub; the manager runs on a fake clock ([now]).
 */
class UniversalConsentDecisionTimeTests {
    private lateinit var sut: ConsentManager

    @Mock
    private lateinit var mockStorage: ConsentStorage

    @Mock
    private lateinit var mockConfigService: ConfigService

    @Mock
    private lateinit var mockConsentService: ConsentService

    private var storedPreferences: ConsentPreferences? = null
    private var storedBoundHash: String? = null
    private var storedSyncState: UniversalConsentSyncState? = null
    private var storedDecisionTs: Long? = null
    private var storedCcpaOptout = false
    private var storedCcpaWritePending = false
    private var storedCcpaDecisionTs: Long? = null

    // Unix seconds. 1_760_000_000 is 2025-10-09T08:53:20Z.
    private var now = 1_760_000_000L

    private val user = "opaque-user-3f9c2a"
    private val apiKey = "dg_key"

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        whenever(mockStorage.savePreferences(any(), anyOrNull())).doAnswer {
            storedPreferences = it.getArgument(0)
            storedDecisionTs = it.getArgument(1)
        }
        whenever(mockStorage.replacePreferencesKeepingDecisionTs(any())).doAnswer { storedPreferences = it.getArgument(0) }
        whenever(mockStorage.loadPreferences()).doAnswer { storedPreferences }
        whenever(mockStorage.clearPreferences()).doAnswer {
            storedPreferences = null
            storedDecisionTs = null
        }
        whenever(mockStorage.loadDecisionTs()).doAnswer { storedDecisionTs }
        whenever(mockStorage.saveBoundUserHash(any())).doAnswer { storedBoundHash = it.getArgument(0) }
        whenever(mockStorage.loadBoundUserHash()).doAnswer { storedBoundHash }
        whenever(mockStorage.clearBoundUserHash()).doAnswer {
            storedBoundHash = null
            storedSyncState = null
        }
        whenever(mockStorage.saveSyncState(any())).doAnswer { storedSyncState = it.getArgument(0) }
        whenever(mockStorage.loadSyncState()).doAnswer { storedSyncState }
        whenever(mockStorage.clearSyncState()).doAnswer { storedSyncState = null }
        whenever(mockStorage.saveCcpaOptout(any())).doAnswer {
            storedCcpaOptout = it.getArgument(0)
            storedCcpaDecisionTs = null
        }
        whenever(mockStorage.saveCcpaChoice(any(), any())).doAnswer {
            storedCcpaOptout = it.getArgument(0)
            storedCcpaDecisionTs = it.getArgument(1)
        }
        whenever(mockStorage.loadCcpaOptout()).doAnswer { storedCcpaOptout }
        whenever(mockStorage.clearCcpaOptout()).doAnswer { storedCcpaOptout = false }
        whenever(mockStorage.saveCcpaWritePending(any())).doAnswer { storedCcpaWritePending = it.getArgument(0) }
        whenever(mockStorage.loadCcpaWritePending()).doAnswer { storedCcpaWritePending }
        whenever(mockStorage.loadCcpaDecisionTs()).doAnswer { storedCcpaDecisionTs }
        sut = ConsentManager(mockStorage, mockConfigService, mockConsentService, nowSeconds = { now })
        sut.currentConfig = universalConsentTestConfig(syncOptout = true)
    }

    // MARK: - P0-06

    @Test
    fun `stale device re-sync adopts a newer revocation made elsewhere instead of undoing it`() =
        runTest {
            // This device opted in to marketing and synced.
            sut.savePreferences(choice(marketing = true)) {}
            recordIs(null)
            sut.setUserIdentifier(user, apiKey)
            assertEquals(listOf(now), writtenDecisionTs())
            val optInAt = now

            // Offline, it changes another setting at t+10 (a genuine local change, still marketing on).
            now = optInAt + 10
            sut.savePreferences(choice(marketing = true, analytics = true)) {}

            // Meanwhile, at t+60, the user revoked marketing on another device.
            recordIs(record(marketing = false, updatedAt = optInAt + 60))

            // This device comes back and re-syncs at t+600.
            now = optInAt + 600
            sut.setUserIdentifier(user, apiKey)

            assertEquals("the stale copy is not written", 1, writtenPreferences().size)
            assertEquals(
                "the revocation is adopted",
                false,
                storedPreferences?.cookieOptions?.first { it.gtmKey == "category_marketing" }?.isEnabled,
            )
            assertEquals("the local choice now carries the record's time", optInAt + 60, storedDecisionTs)

            // The next re-sync is a no-op: nothing changed here since.
            sut.setUserIdentifier(user, apiKey)
            assertEquals(1, writtenPreferences().size)
        }

    @Test
    fun `a local change newer than the record is written through with its own decision time`() =
        runTest {
            bindAndSync(record(marketing = true, updatedAt = now - 100))

            now += 50
            val changedAt = now
            sut.savePreferences(choice(marketing = false)) {}
            recordIs(record(marketing = true, updatedAt = changedAt - 30))

            now += 500
            sut.setUserIdentifier(user, apiKey)

            assertEquals(false, writtenPreferences().single().cookieOptions["category_marketing"])
            assertEquals("the time of the choice, not of the request", listOf(changedAt), writtenDecisionTs())
            // The device keeps the newer choice it wrote and its time, not the older record (TRUST-3310).
            assertEquals(choice(marketing = false), storedPreferences)
            assertEquals(changedAt, storedDecisionTs)
        }

    @Test
    fun `a same-second tie adopts the record`() =
        runTest {
            bindAndSync(record(marketing = true, updatedAt = now - 100))
            sut.savePreferences(choice(marketing = true, analytics = true)) {}
            recordIs(record(marketing = false, updatedAt = now))

            sut.setUserIdentifier(user, apiKey)

            verifyNoWrite()
            assertEquals(choice(marketing = false), storedPreferences)
        }

    @Test
    fun `a local change with no recorded time adopts the record`() =
        runTest {
            // A choice stored by an SDK that predates decision times, on a bound device with no
            // sync state: it cannot prove it is newer.
            storedBoundHash = hashFor(user)
            storedPreferences = choice(marketing = true)
            recordIs(record(marketing = false, updatedAt = now - 86_400))

            sut.setUserIdentifier(user, apiKey)

            verifyNoWrite()
            assertEquals(choice(marketing = false), storedPreferences)
            assertEquals(now - 86_400, storedDecisionTs)
        }

    @Test
    fun `a record without a usable updated_at adopts and leaves the local time unknown`() =
        runTest {
            storedBoundHash = hashFor(user)
            storedPreferences = choice(marketing = true)
            storedDecisionTs = now - 10
            recordIs(record(marketing = false, updatedAt = null))

            sut.setUserIdentifier(user, apiKey)

            verifyNoWrite()
            assertEquals(choice(marketing = false), storedPreferences)
            assertNull(storedDecisionTs)
        }

    @Test
    fun `a stored time implausibly far ahead of the clock is unknown, so the record is adopted`() =
        runTest {
            storedBoundHash = hashFor(user)
            storedPreferences = choice(marketing = true)
            storedDecisionTs = now + DecisionTime.STORED_TOLERANCE_SECONDS + 1
            recordIs(record(marketing = false, updatedAt = now - 10))

            sut.setUserIdentifier(user, apiKey)

            verifyNoWrite()
            assertEquals("the bogus stamp is repaired", now - 10, storedDecisionTs)
        }

    @Test
    fun `a local change over a signal-only record is written through as before`() =
        runTest {
            storedBoundHash = hashFor(user)
            storedPreferences = choice(marketing = true)
            storedDecisionTs = now - 1_000
            recordIs(UniversalConsentRecord(status = "found", ccpaOptout = true, updatedAt = iso(now - 10)))

            sut.setUserIdentifier(user, apiKey)

            assertEquals(true, writtenPreferences().single().cookieOptions["category_marketing"])
            assertEquals("still its own older time", listOf(now - 1_000), writtenDecisionTs())
        }

    // MARK: - setCcpaOptout write-through (TRUST-3311)

    @Test
    fun `a stale device's setCcpaOptout cannot undo a newer revocation made elsewhere`() =
        runTest {
            // Synced marketing on, recorded at the record's time.
            val syncedAt = now - 100
            bindAndSync(record(marketing = true, updatedAt = syncedAt))
            // Since then (unseen here) the user revoked marketing on another device, at `now`.
            now += 500

            sut.setCcpaOptout(true)

            // Unchanged since the sync, the write is CCPA-only (TRUST-2902): it sends no category, so
            // the edge's newer revocation stands, and the CCPA choice is stamped with its own time.
            assertEquals(emptyMap<String, Boolean>(), writtenPreferences().single().cookieOptions)
            assertEquals(listOf<Long?>(now), writtenDecisionTs())
        }

    @Test
    fun `setCcpaOptout after a local change stamps the write with that change's time`() =
        runTest {
            bindAndSync(record(marketing = true, updatedAt = now - 100))
            now += 10
            val changedAt = now
            sut.savePreferences(choice(marketing = false)) {}
            now += 300

            sut.setCcpaOptout(true)

            assertEquals(false, writtenPreferences().single().cookieOptions["category_marketing"])
            assertEquals(listOf<Long?>(changedAt), writtenDecisionTs())
        }

    @Test
    fun `setCcpaOptout after a local change with no recorded time stays CCPA-only`() =
        runTest {
            bindAndSync(record(marketing = true, updatedAt = now - 100))
            // A change stored before this version recorded decision times: stamped "now" it would
            // out-rank a newer revocation made elsewhere (P0-06), so it does not ride along.
            sut.savePreferences(choice(marketing = false)) {}
            storedDecisionTs = null
            now += 300

            sut.setCcpaOptout(true)

            assertEquals(emptyMap<String, Boolean>(), writtenPreferences().single().cookieOptions)
            assertEquals(listOf<Long?>(now), writtenDecisionTs())
        }

    @Test
    fun `two CCPA toggles in the same second get increasing decision times`() =
        runTest {
            bindAndSync(record(marketing = true, updatedAt = now - 100))

            sut.setCcpaOptout(true)
            sut.setCcpaOptout(false)

            // A tie would keep the opt-out at the edge, so the opt-in must be stamped later.
            assertEquals(listOf<Long?>(now, now + 1), writtenDecisionTs())
        }

    // MARK: - Recording the time

    @Test
    fun `a miss seeds the record with the choice's own time`() =
        runTest {
            sut.savePreferences(choice(marketing = true)) {}
            val choseAt = now
            now += 3_600
            recordIs(null)

            sut.setUserIdentifier(user, apiKey)

            assertEquals(listOf(choseAt), writtenDecisionTs())
        }

    @Test
    fun `a login adopt records the record's time and logout clears it`() =
        runTest {
            recordIs(record(marketing = true, updatedAt = now - 500))

            sut.setUserIdentifier(user, apiKey)

            verifyNoWrite()
            assertEquals(now - 500, storedDecisionTs)

            sut.clearUserIdentifier()
            assertNull(storedDecisionTs)
        }

    @Test
    fun `choices are stamped monotonically, also right after adopting a record stamped ahead`() =
        runTest {
            // The record was written by a device whose clock runs 100 s ahead of this one.
            bindAndSync(record(marketing = false, updatedAt = now + 100))
            assertEquals(now + 100, storedDecisionTs)

            sut.savePreferences(choice(marketing = true)) {}
            assertEquals("after the adopted time, so it still reads as newer", now + 101, storedDecisionTs)

            sut.savePreferences(choice(marketing = false)) {}
            assertEquals("a second choice in the same second still orders", now + 102, storedDecisionTs)
        }

    @Test
    fun `a write that only carries a pending CCPA choice sends no category and the setter's time`() =
        runTest {
            bindAndSync(record(marketing = true, updatedAt = now - 100))
            // A bound CCPA choice that never reached the record (no session after a restart), made
            // well before this re-sync finally sends it.
            storedCcpaOptout = true
            storedCcpaWritePending = true
            storedCcpaDecisionTs = now - 50

            sut.setUserIdentifier(user, apiKey)

            // CCPA-only: no category on the record can change at any stamp.
            assertEquals(emptyMap<String, Boolean>(), writtenPreferences().single().cookieOptions)
            // When the setter was called: not the record's time (it would tie the record's own
            // ccpa_optout register, and a tie keeps an opt-out), nor the send time (a late flush
            // would out-rank a newer CCPA choice made on another device meanwhile).
            assertEquals(listOf<Long?>(now - 50), writtenDecisionTs())
        }

    // MARK: - updated_at parsing

    @Test
    fun `updated_at parses as unix seconds`() {
        assertEquals(1_760_000_000L, DecisionTime.ofRecord("2025-10-09T08:53:20Z"))
        assertEquals("fraction truncated", 1_760_000_000L, DecisionTime.ofRecord("2025-10-09T08:53:20.999Z"))
        assertEquals("offset applied", 1_760_000_000L, DecisionTime.ofRecord("2025-10-09T10:53:20+02:00"))
        assertEquals("negative offset applied", 1_760_000_000L, DecisionTime.ofRecord("2025-10-09T03:53:20-05:00"))
        assertNull(DecisionTime.ofRecord(null))
        assertNull(DecisionTime.ofRecord(""))
        assertNull("no zone", DecisionTime.ofRecord("2025-10-09T08:53:20"))
        assertNull("out-of-range month", DecisionTime.ofRecord("2025-13-09T08:53:20Z"))
        assertNull("not a date", DecisionTime.ofRecord("yesterday"))
    }

    // MARK: - Helpers

    /** Bind the device to [user] by a login that adopts [found]. */
    private suspend fun bindAndSync(found: UniversalConsentRecord) {
        recordIs(found)
        sut.setUserIdentifier(user, apiKey)
        verifyNoWrite()
    }

    private suspend fun recordIs(record: UniversalConsentRecord?) {
        whenever(mockConsentService.getUniversalConsent(any(), any(), any())).thenReturn(record)
    }

    private suspend fun writtenPreferences(): List<UniversalConsentPreferences> {
        val captor = argumentCaptor<UniversalConsentPreferences>()
        verify(mockConsentService, org.mockito.Mockito.atLeastOnce())
            .saveUniversalConsent(any(), any(), captor.capture(), eq(apiKey), any(), anyOrNull(), anyOrNull())
        return captor.allValues
    }

    private suspend fun writtenDecisionTs(): List<Long?> {
        val captor = nullableArgumentCaptor<Long>()
        verify(mockConsentService, org.mockito.Mockito.atLeastOnce())
            .saveUniversalConsent(any(), any(), any(), eq(apiKey), any(), anyOrNull(), captor.capture())
        return captor.allValues
    }

    private suspend fun verifyNoWrite() {
        verify(mockConsentService, never())
            .saveUniversalConsent(any(), any(), any(), any(), any(), anyOrNull(), anyOrNull())
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
        updatedAt: Long?,
    ): UniversalConsentRecord =
        UniversalConsentRecord(
            status = "found",
            consentPreferences =
                UniversalConsentPreferences(
                    isCustomised = true,
                    cookieOptions = mapOf("category_essential" to true, "category_marketing" to marketing),
                ),
            updatedAt = updatedAt?.let { iso(it) },
        )

    /** Unix seconds as the edge's `updated_at` (ISO-8601, whole seconds, UTC). */
    private fun iso(seconds: Long): String {
        val format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
        format.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return format.format(java.util.Date(seconds * 1000))
    }
}
