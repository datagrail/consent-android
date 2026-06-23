package com.datagrail.consent.network

import com.datagrail.consent.models.CategoryConsent
import com.datagrail.consent.models.ConsentPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

// Pure-JVM test: PairingCoordinator no longer touches android.os.*, so no Robolectric.
//
// The coordinator runs an INFINITE poll loop (while(true) { ...; delay(2000) }). Tests
// MUST advance virtual time by a bounded amount and then stop() — never advanceUntilIdle(),
// which would spin the self-rescheduling loop forever (OOM). The poll dispatcher is
// injected so the loop runs on the test scheduler that advanceTimeBy controls.
@OptIn(ExperimentalCoroutinesApi::class)
class PairingCoordinatorTest {
    @Test
    fun testPoll_notFoundContinuesPolling() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val mockPairingService = mock<PairingService>()
            var pollCount = 0
            whenever(mockPairingService.fetchConsent("cust", "hash"))
                .thenAnswer {
                    pollCount++
                    PairingRead.NotFound
                }

            var callbackInvoked = false
            val coordinator =
                PairingCoordinator(
                    pairingService = mockPairingService,
                    customerId = "cust",
                    userHash = "hash",
                    onPreferencesFound = { callbackInvoked = true },
                    onTimeout = {},
                    dispatcher = dispatcher,
                )

            coordinator.start()
            advanceTimeBy(10_000) // ~5 polls at 2s
            coordinator.stop()

            assertTrue("Should poll multiple times when not found", pollCount >= 3)
            assertTrue("Callback should not fire for not_found", !callbackInvoked)
        }

    @Test
    fun testPoll_baselineThenNewWriteCompletes() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val mockPairingService = mock<PairingService>()
            val prefs =
                ConsentPreferences(
                    isCustomised = true,
                    cookieOptions = listOf(CategoryConsent("cat1", true)),
                )

            var pollCount = 0
            whenever(mockPairingService.fetchConsent("cust", "hash"))
                .thenAnswer {
                    pollCount++
                    // Polls 1-2: same updated_at (baseline). Poll 3+: NEW write.
                    if (pollCount <= 2) {
                        PairingRead.Found(prefs, "2026-06-02T10:00:00Z")
                    } else {
                        PairingRead.Found(prefs, "2026-06-02T10:01:00Z")
                    }
                }

            var capturedPrefs: ConsentPreferences? = null
            val coordinator =
                PairingCoordinator(
                    pairingService = mockPairingService,
                    customerId = "cust",
                    userHash = "hash",
                    onPreferencesFound = { capturedPrefs = it },
                    onTimeout = {},
                    dispatcher = dispatcher,
                )

            coordinator.start()
            advanceTimeBy(8_000) // 4 polls
            coordinator.stop()

            assertEquals("Should adopt prefs on the NEW write", prefs, capturedPrefs)
        }

    @Test
    fun testPoll_preExistingRecordIsBaselineNotCompletion() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val mockPairingService = mock<PairingService>()
            val prefs =
                ConsentPreferences(
                    isCustomised = false,
                    cookieOptions = emptyList(),
                )

            // A record is ALREADY present at the first poll and never changes.
            whenever(mockPairingService.fetchConsent("cust", "hash"))
                .thenReturn(PairingRead.Found(prefs, "2026-06-02T10:00:00Z"))

            var capturedPrefs: ConsentPreferences? = null
            val coordinator =
                PairingCoordinator(
                    pairingService = mockPairingService,
                    customerId = "cust",
                    userHash = "hash",
                    onPreferencesFound = { capturedPrefs = it },
                    onTimeout = {},
                    dispatcher = dispatcher,
                )

            coordinator.start()
            advanceTimeBy(10_000)
            coordinator.stop()

            assertNull("Pre-existing record must NOT auto-complete the pairing", capturedPrefs)
        }

    @Test
    fun testPoll_notFoundThenWriteCompletes() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val mockPairingService = mock<PairingService>()
            val prefs =
                ConsentPreferences(
                    isCustomised = true,
                    cookieOptions = listOf(CategoryConsent("cat1", true)),
                )

            // The normal pairing case: nothing present (baseline=not_found), then phone writes.
            var pollCount = 0
            whenever(mockPairingService.fetchConsent("cust", "hash"))
                .thenAnswer {
                    pollCount++
                    if (pollCount <= 1) PairingRead.NotFound else PairingRead.Found(prefs, "2026-06-02T10:01:00Z")
                }

            var capturedPrefs: ConsentPreferences? = null
            val coordinator =
                PairingCoordinator(
                    pairingService = mockPairingService,
                    customerId = "cust",
                    userHash = "hash",
                    onPreferencesFound = { capturedPrefs = it },
                    onTimeout = {},
                    dispatcher = dispatcher,
                )

            coordinator.start()
            advanceTimeBy(8_000)
            coordinator.stop()

            assertEquals("not_found baseline then a write must complete", prefs, capturedPrefs)
        }

    @Test
    fun testPoll_timeoutInvokesCallback() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val mockPairingService = mock<PairingService>()
            whenever(mockPairingService.fetchConsent("cust", "hash"))
                .thenReturn(PairingRead.NotFound)

            var timeoutInvoked = false
            val coordinator =
                PairingCoordinator(
                    pairingService = mockPairingService,
                    customerId = "cust",
                    userHash = "hash",
                    onPreferencesFound = {},
                    onTimeout = { timeoutInvoked = true },
                    dispatcher = dispatcher,
                )

            coordinator.start()
            advanceTimeBy(11 * 60 * 1000L) // past the 10-minute client timeout
            coordinator.stop()

            assertTrue("Timeout callback should fire after 10 minutes", timeoutInvoked)
        }

    @Test
    fun testStop_stopsPolling() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val mockPairingService = mock<PairingService>()
            var pollCount = 0
            whenever(mockPairingService.fetchConsent("cust", "hash"))
                .thenAnswer {
                    pollCount++
                    PairingRead.NotFound
                }

            val coordinator =
                PairingCoordinator(
                    pairingService = mockPairingService,
                    customerId = "cust",
                    userHash = "hash",
                    onPreferencesFound = {},
                    onTimeout = {},
                    dispatcher = dispatcher,
                )

            coordinator.start()
            advanceTimeBy(4_000) // 2 polls
            coordinator.stop()
            val countAfterStop = pollCount

            advanceTimeBy(20_000) // should produce no further polls
            assertEquals("Polling must stop after stop()", countAfterStop, pollCount)
        }
}
