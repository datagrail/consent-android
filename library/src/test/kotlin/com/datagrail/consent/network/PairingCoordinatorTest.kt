package com.datagrail.consent.network

import com.datagrail.consent.models.CategoryConsent
import com.datagrail.consent.models.ConsentPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@OptIn(ExperimentalCoroutinesApi::class)
class PairingCoordinatorTest {
    private lateinit var mockPairingService: PairingService
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        mockPairingService = mock()
        Dispatchers.setMain(testDispatcher)
    }

    @Test
    fun testPoll_notFoundContinuesPolling() =
        runTest {
            var pollCount = 0
            whenever(mockPairingService.fetchConsent("cust", "hash"))
                .thenAnswer {
                    pollCount++
                    PairingRead.NotFound
                }

            val latch = CountDownLatch(1)
            var callbackInvoked = false

            val coordinator =
                PairingCoordinator(
                    pairingService = mockPairingService,
                    customerId = "cust",
                    userHash = "hash",
                    onPreferencesFound = {
                        callbackInvoked = true
                        latch.countDown()
                    },
                    onTimeout = {
                        latch.countDown()
                    },
                )

            coordinator.start()

            // Advance time to trigger multiple polls
            advanceTimeBy(10000) // 10 seconds = ~5 polls at 2s intervals
            advanceUntilIdle()

            coordinator.stop()

            // Should have polled multiple times
            assertTrue("Should poll multiple times when not found", pollCount >= 3)
            assertTrue("Callback should not be invoked for not_found", !callbackInvoked)
        }

    @Test
    fun testPoll_baselineUpdatedAtCaptured() =
        runTest {
            val prefs =
                ConsentPreferences(
                    isCustomised = true,
                    cookieOptions = listOf(CategoryConsent("cat1", true)),
                )

            var pollCount = 0
            whenever(mockPairingService.fetchConsent("cust", "hash"))
                .thenAnswer {
                    pollCount++
                    if (pollCount <= 2) {
                        // First two polls: found with same updated_at
                        PairingRead.Found(prefs, "2026-06-02T10:00:00Z")
                    } else {
                        // Third poll: NEW updated_at (simulates phone write)
                        PairingRead.Found(prefs, "2026-06-02T10:01:00Z")
                    }
                }

            var capturedPrefs: ConsentPreferences? = null
            val latch = CountDownLatch(1)

            val coordinator =
                PairingCoordinator(
                    pairingService = mockPairingService,
                    customerId = "cust",
                    userHash = "hash",
                    onPreferencesFound = { foundPrefs ->
                        capturedPrefs = foundPrefs
                        latch.countDown()
                    },
                    onTimeout = {
                        latch.countDown()
                    },
                )

            coordinator.start()

            // Advance time to trigger 3+ polls
            advanceTimeBy(8000) // 8 seconds = 4 polls at 2s intervals
            advanceUntilIdle()

            // Wait for callback
            latch.await(1, TimeUnit.SECONDS)

            coordinator.stop()

            // Should have captured preferences on the NEW write (poll 3+)
            assertEquals(prefs, capturedPrefs)
            assertTrue("Should poll at least 3 times", pollCount >= 3)
        }

    @Test
    fun testPoll_unchangedUpdatedAtDoesNotComplete() =
        runTest {
            val prefs =
                ConsentPreferences(
                    isCustomised = false,
                    cookieOptions = emptyList(),
                )

            whenever(mockPairingService.fetchConsent("cust", "hash"))
                .thenReturn(PairingRead.Found(prefs, "2026-06-02T10:00:00Z"))

            var callbackInvoked = false
            val latch = CountDownLatch(1)

            val coordinator =
                PairingCoordinator(
                    pairingService = mockPairingService,
                    customerId = "cust",
                    userHash = "hash",
                    onPreferencesFound = {
                        callbackInvoked = true
                        latch.countDown()
                    },
                    onTimeout = {
                        latch.countDown()
                    },
                )

            coordinator.start()

            // Advance time for several polls
            advanceTimeBy(10000) // 10 seconds
            advanceUntilIdle()

            coordinator.stop()

            // Callback should NOT be invoked (no change in updated_at)
            assertTrue("Callback should not be invoked when updated_at unchanged", !callbackInvoked)
        }

    @Test
    fun testPoll_timeoutInvokesCallback() =
        runTest {
            whenever(mockPairingService.fetchConsent("cust", "hash"))
                .thenReturn(PairingRead.NotFound)

            var timeoutInvoked = false
            val latch = CountDownLatch(1)

            val coordinator =
                PairingCoordinator(
                    pairingService = mockPairingService,
                    customerId = "cust",
                    userHash = "hash",
                    onPreferencesFound = {
                        latch.countDown()
                    },
                    onTimeout = {
                        timeoutInvoked = true
                        latch.countDown()
                    },
                )

            coordinator.start()

            // Advance past 10-minute timeout
            advanceTimeBy(11 * 60 * 1000L) // 11 minutes
            advanceUntilIdle()

            // Wait for callback
            latch.await(1, TimeUnit.SECONDS)

            coordinator.stop()

            assertTrue("Timeout callback should be invoked after 10 minutes", timeoutInvoked)
        }

    @Test
    fun testStop_stopsPolling() =
        runTest {
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
                )

            coordinator.start()

            // Let it poll a few times
            advanceTimeBy(4000) // 2 polls
            advanceUntilIdle()

            val pollCountBeforeStop = pollCount

            // Stop coordinator
            coordinator.stop()

            // Advance time significantly
            advanceTimeBy(20000) // 20 seconds
            advanceUntilIdle()

            // Poll count should not have increased after stop
            assertEquals("Polling should stop after stop() is called", pollCountBeforeStop, pollCount)
        }
}
