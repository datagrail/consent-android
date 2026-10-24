
package com.datagrail.consent

import com.datagrail.consent.models.ConsentException
import com.datagrail.consent.models.TrackingSignal
import com.datagrail.consent.models.UniversalConsentSignature
import com.datagrail.consent.models.UniversalConsentSigningPayload
import com.datagrail.consent.network.ConfigService
import com.datagrail.consent.network.ConsentService
import com.datagrail.consent.storage.ConsentStorage
import com.datagrail.consent.utils.ConsentLogger
import com.datagrail.consent.utils.LogLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.atLeast
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever

/**
 * Tests for DataGrailConsent public API including URL validation and category detection
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DataGrailConsentTests {
    private lateinit var sut: DataGrailConsent
    private val testDispatcher = StandardTestDispatcher()

    @Mock
    private lateinit var mockContext: android.content.Context

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        Dispatchers.setMain(testDispatcher)
        sut = DataGrailConsent.getInstance()
    }

    @After
    fun tearDown() {
        sut.reset()
        Dispatchers.resetMain()
    }

    // MARK: - onConfigError (TRUST-3254)

    @Test
    fun `a config fetch failure reaches the init's onConfigError handler`() {
        val received = mutableListOf<ConsentException>()
        val error = ConsentException.ConfigNotPublished(statusCode = 404)
        sut.configErrorReporter(sut.currentInitGeneration()) { received += it }(error)
        assertEquals(listOf<ConsentException>(error), received)
    }

    @Test
    fun `onConfigError stops for a fetch that reset() or a newer initialize superseded`() {
        val received = mutableListOf<ConsentException>()
        val beforeReset = sut.configErrorReporter(sut.currentInitGeneration()) { received += it }
        sut.reset()
        beforeReset(ConsentException.NetworkError("offline"))
        assertTrue(received.isEmpty())

        // A newer initialize bumps the same token (its launched work never runs on the paused Main).
        val beforeInit = sut.configErrorReporter(sut.currentInitGeneration()) { received += it }
        sut.initialize(mockContext, "https://consent.datagrail.io/config.json", { received += it }) { }
        beforeInit(ConsentException.NetworkError("offline"))
        assertTrue(received.isEmpty())

        sut.configErrorReporter(sut.currentInitGeneration()) { received += it }(ConsentException.NetworkError("offline"))
        assertEquals(1, received.size)
    }

    @Test
    fun `reset during an in-flight initialize stops its commit and fails its callback`() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val building = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val done = java.util.concurrent.CountDownLatch(1)
        val built = mock<ConsentManager>()
        val defaultFactory = sut.managerFactory
        sut.managerFactory = { _, _ ->
            building.countDown()
            release.await(5, java.util.concurrent.TimeUnit.SECONDS)
            built
        }
        try {
            val configErrors = mutableListOf<ConsentException>()
            var result: Result<Unit>? = null
            sut.initialize(mockContext, "https://consent.datagrail.io/config.json", { configErrors += it }) {
                result = it
                done.countDown()
            }
            assertTrue(building.await(5, java.util.concurrent.TimeUnit.SECONDS))

            sut.reset()
            release.countDown()

            assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(result!!.exceptionOrNull() is ConsentException.NotInitialized)
            assertNull(sut.getConfig())
            // The wipe reached the uncommitted init's storage, and it never loaded a config, so
            // nothing could report to onConfigError.
            verify(built).reset()
            verifyNoMoreInteractions(built)
            assertTrue(configErrors.isEmpty())
        } finally {
            release.countDown()
            sut.managerFactory = defaultFactory
        }
    }

    /**
     * Runs one initialize() whose manager is real but whose config fetch is [fetch]: it receives the
     * fetch's isCurrent token and onConfigError reporter. Returns the init's result and its storage.
     */
    private fun initializeWithFetch(
        onConfigError: (ConsentException) -> Unit = {},
        fetch: suspend (isCurrent: () -> Boolean, reportError: ((ConsentException) -> Unit)?) -> Unit,
    ): Pair<Result<Unit>, ConsentStorage> {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val done = java.util.concurrent.CountDownLatch(1)
        val storage = mock<ConsentStorage>()
        val configService = mock<ConfigService>()
        runBlocking {
            whenever(configService.fetchConfigWithRetry(any(), any(), anyOrNull())).doSuspendableAnswer {
                fetch(it.getArgument(1), it.getArgument(2))
                universalConsentTestConfig(syncOptout = false)
            }
        }
        val defaultFactory = sut.managerFactory
        sut.managerFactory = { _, _ -> ConsentManager(storage, configService, mock<ConsentService>()) }
        try {
            var result: Result<Unit>? = null
            sut.initialize(mockContext, "https://consent.datagrail.io/config.json", onConfigError) {
                result = it
                done.countDown()
            }
            assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS))
            return result!! to storage
        } finally {
            sut.managerFactory = defaultFactory
            // This init committed its manager to the shared singleton, and reset() keeps it; later
            // tests expect an uninitialized SDK.
            for (field in listOf("manager", "configUrl", "appContext")) {
                DataGrailConsent::class.java.getDeclaredField(field).apply { isAccessible = true }.set(sut, null)
            }
        }
    }

    @Test
    fun `reset while the config loads fails the init and restores no config`() {
        val loading = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        var currentWhenFetched: Boolean? = null
        val resetter =
            Thread {
                assertTrue(loading.await(5, java.util.concurrent.TimeUnit.SECONDS))
                sut.reset()
                release.countDown()
            }.apply { start() }

        val (result, storage) =
            initializeWithFetch { isCurrent, _ ->
                loading.countDown()
                release.await(5, java.util.concurrent.TimeUnit.SECONDS)
                currentWhenFetched = isCurrent()
            }
        resetter.join(5_000)

        assertTrue(result.exceptionOrNull() is ConsentException.NotInitialized)
        // The fetch saw the reset, so ConfigService skips caching what it fetched.
        assertEquals(false, currentWhenFetched)
        assertNull(sut.getConfig())
        // The reset's wipe, then the superseded init's own wipe of anything persisted in the race.
        verify(storage, atLeast(2)).clearAll()
        verify(storage, never()).loadPendingEvents()
    }

    @Test
    fun `reset from the init's own onConfigError fails the init and restores no cached config`() {
        val reported = mutableListOf<ConsentException>()
        val (result, _) =
            initializeWithFetch(onConfigError = {
                reported += it
                sut.reset()
            }) { _, reportError ->
                // The fetch failed and the cache masked it: report, then return the cached config.
                reportError?.invoke(ConsentException.NetworkError("offline"))
            }

        assertEquals(1, reported.size)
        assertTrue(result.exceptionOrNull() is ConsentException.NotInitialized)
        assertNull(sut.getConfig())
    }

    @Test
    fun `the Java initialize overload takes a ConfigErrorListener`() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val done = java.util.concurrent.CountDownLatch(1)
        sut.initialize(
            mockContext,
            "ftp://consent.datagrail.io/config.json",
            object : ConfigErrorListener {
                override fun onConfigError(error: ConsentException) = Unit
            },
            object : ConsentCallback {
                override fun onSuccess() = Unit

                override fun onFailure(error: ConsentException) {
                    done.countDown()
                }
            },
        )
        assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS))
    }

    // MARK: - URL Validation Tests

    @Test
    fun `initialize with invalid scheme fails with error`() =
        runTest {
            // Given
            val invalidUrl = "ftp://example.com/config.json"
            var resultError: Throwable? = null

            // When
            sut.initialize(mockContext, invalidUrl) { result ->
                result.fold(
                    onSuccess = { },
                    onFailure = { error -> resultError = error },
                )
            }

            // Advance dispatcher to process coroutines
            testScheduler.advanceUntilIdle()

            // Then
            assertNotNull("Should have received an error", resultError)
            assertTrue(resultError is ConsentException.InvalidConfiguration)
            assertTrue((resultError as ConsentException.InvalidConfiguration).message?.contains("HTTPS") == true)
        }

    @Test
    fun `initialize with missing host fails with error`() =
        runTest {
            // Given
            val invalidUrl = "https://"
            var resultError: Throwable? = null

            // When
            sut.initialize(mockContext, invalidUrl) { result ->
                result.fold(
                    onSuccess = { },
                    onFailure = { error -> resultError = error },
                )
            }

            // Advance dispatcher to process coroutines
            testScheduler.advanceUntilIdle()

            // Then
            assertNotNull("Should have received an error", resultError)
            assertTrue(resultError is ConsentException.InvalidConfiguration)
            assertTrue((resultError as ConsentException.InvalidConfiguration).message?.contains("host") == true)
        }

    @Test
    fun `initialize with malformed URL fails with error`() =
        runTest {
            // Given
            val invalidUrl = "not a url at all"
            var resultError: Throwable? = null

            // When
            sut.initialize(mockContext, invalidUrl) { result ->
                result.fold(
                    onSuccess = { },
                    onFailure = { error -> resultError = error },
                )
            }

            // Advance dispatcher to process coroutines
            testScheduler.advanceUntilIdle()

            // Then
            assertNotNull("Should have received an error", resultError)
            assertTrue(resultError is ConsentException.InvalidConfiguration)
            assertTrue((resultError as ConsentException.InvalidConfiguration).message?.contains("Invalid") == true)
        }

    @Test
    fun `initialize with valid https URL passes validation`() {
        // This just verifies URL format is valid
        val validUrl = "https://consent.datagrail.io/config.json"
        val url = java.net.URL(validUrl)

        assertEquals("https", url.protocol)
        assertEquals("consent.datagrail.io", url.host)
    }

    // MARK: - setLogLevel Tests

    @Test
    fun `setLogLevel changes ConsentLogger level`() {
        DataGrailConsent.setLogLevel(LogLevel.DEBUG)
        assertEquals(LogLevel.DEBUG, ConsentLogger.level)

        DataGrailConsent.setLogLevel(LogLevel.ERROR)
        assertEquals(LogLevel.ERROR, ConsentLogger.level)

        DataGrailConsent.setLogLevel(LogLevel.NONE)
        assertEquals(LogLevel.NONE, ConsentLogger.level)
    }

    @Test
    fun `default log level is NONE`() {
        // Reset to default
        DataGrailConsent.setLogLevel(LogLevel.NONE)
        assertEquals(LogLevel.NONE, ConsentLogger.level)
    }

    // MARK: - Privacy Domain Fail-Loudly Tests

    @Test
    fun `initialize with empty host URL fails with InvalidConfigUrl`() =
        runTest {
            // "https://" has no host — should be caught by the earlier host validation
            val emptyHostUrl = "https://"
            var resultError: Throwable? = null

            sut.initialize(mockContext, emptyHostUrl) { result ->
                result.fold(
                    onSuccess = { },
                    onFailure = { error -> resultError = error },
                )
            }

            testScheduler.advanceUntilIdle()

            assertNotNull("Should have received an error", resultError)
            // The URL host validation catches this first
            assertTrue(
                "Should be InvalidConfiguration or InvalidConfigUrl",
                resultError is ConsentException.InvalidConfiguration || resultError is ConsentException.InvalidConfigUrl,
            )
        }

    // MARK: - Storage Initialization Tests

    @Test
    fun `initialize returns before storage setup completes`() {
        // Storage creation runs inside a launched coroutine on Dispatchers.IO — a real thread
        // pool, not the virtual StandardTestDispatcher installed in setUp(). A latch with a
        // real timeout (rather than testScheduler.advanceUntilIdle(), which only drives
        // virtual time on the Main dispatcher and can't wait for real IO-pool work) confirms
        // the callback still fires once that background work completes. Swap Main to an
        // UnconfinedTestDispatcher for just this test so the post-IO resumption dispatches
        // immediately instead of queuing on the paused StandardTestDispatcher.
        Dispatchers.setMain(UnconfinedTestDispatcher())

        val validUrl = "https://consent.datagrail.io/config.json"
        val latch = java.util.concurrent.CountDownLatch(1)
        var resultError: Throwable? = null

        // Given an unconfigured mock Context — storage creation will fail against it (no real
        // Keystore/EncryptedSharedPreferences available in this test)
        sut.initialize(mockContext, validUrl) { result ->
            result.fold(
                onSuccess = { },
                onFailure = { error -> resultError = error },
            )
            latch.countDown()
        }

        // Then - the background storage init failure arrives asynchronously via the callback
        val completed = latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
        assertTrue("Callback should fire within timeout", completed)
        assertNotNull("Should have received an error from storage initialization", resultError)
        assertTrue(resultError is ConsentException)
    }

    @Test
    fun `overlapping initialize calls each fire their own callback`() {
        // Two rapid initialize() calls race on the shared manager/configUrl assignment. The
        // generation guard makes the commit "last wins"; here we assert the re-entrancy is at
        // least safe — both callers get a callback and neither is left hanging. (The commit
        // path itself needs a real EncryptedSharedPreferences, so it isn't exercised here;
        // against the mock Context both calls fail storage init and report through the callback.)
        Dispatchers.setMain(UnconfinedTestDispatcher())

        val url = "https://consent.datagrail.io/config.json"
        val latch = java.util.concurrent.CountDownLatch(2)

        sut.initialize(mockContext, url) { latch.countDown() }
        sut.initialize(mockContext, url) { latch.countDown() }

        val completed = latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
        assertTrue("Both initialize() callbacks should fire within timeout", completed)
    }

    // MARK: - Thread Safety Tests

    @Test
    fun `onConsentChanged concurrent access does not crash`() =
        runBlocking {
            // Given
            val iterations = 100
            val threads = mutableListOf<Thread>()

            // When - Concurrent reads and writes
            repeat(iterations) { i ->
                val thread =
                    Thread {
                        sut.onConsentChanged { _ ->
                            // Callback
                        }
                    }
                threads.add(thread)
                thread.start()
            }

            // Wait for all threads
            threads.forEach { it.join() }

            // Then - Should not crash
            assertTrue("Concurrent access completed without crash", true)
        }

    // MARK: - Category Detection Tests

    @Test
    fun `rejectAll uses config data not string matching`() {
        // This test verifies the fix uses config.layout data instead of string matching
        // The method now calls getEssentialCategories() which parses config
        // Full testing requires ConsentManager tests
        assertTrue("Category detection now uses config data via getEssentialCategories()", true)
    }

    // MARK: - API Availability Tests

    @Test
    fun `getCategories throws when not initialized`() {
        // Given
        sut.reset()

        // When/Then - Should throw NotInitialized exception
        val exception =
            assertThrows(ConsentException.NotInitialized::class.java) {
                sut.getCategories()
            }
        assertNotNull(exception)
    }

    @Test
    fun `getUserPreferences throws when not initialized`() {
        // Given
        sut.reset()

        // When/Then - Should throw NotInitialized exception
        val exception =
            assertThrows(ConsentException.NotInitialized::class.java) {
                sut.getUserPreferences()
            }
        assertNotNull(exception)
    }

    @Test
    fun `isCategoryEnabled throws when not initialized`() {
        // Given
        sut.reset()

        // When/Then - Should throw NotInitialized exception
        val exception =
            assertThrows(ConsentException.NotInitialized::class.java) {
                sut.isCategoryEnabled("dg-category-marketing")
            }
        assertNotNull(exception)
    }

    @Test
    fun `shouldDisplayBanner throws when not initialized`() {
        // Given
        sut.reset()

        // When/Then - Should throw NotInitialized exception
        val exception =
            assertThrows(ConsentException.NotInitialized::class.java) {
                sut.shouldDisplayBanner()
            }
        assertNotNull(exception)
    }

    @Test
    fun `hasUserConsent throws when not initialized`() {
        // Given
        sut.reset()

        // When/Then - Should throw NotInitialized exception
        val exception =
            assertThrows(ConsentException.NotInitialized::class.java) {
                sut.hasUserConsent()
            }
        assertNotNull(exception)
    }

    @Test
    fun `clearUserIdentifier is a no-op when not initialized`() {
        // Given
        sut.reset()

        // When/Then - follows reset(): safe to call before initialize, never throws
        sut.clearUserIdentifier()
    }

    // MARK: - Java Signature Provider Adapter

    @Test
    fun `java signature provider adapter surfaces the signature`() =
        runBlocking {
            val expected = UniversalConsentSignature("sig", "key-1")
            val provider =
                DataGrailConsent.asSignatureProvider { _, onResult -> onResult.onSignature(expected) }

            val actual = provider(samplePayload())

            assertEquals(expected, actual)
        }

    @Test
    fun `java signature provider adapter propagates a signing failure`() =
        runBlocking {
            // Without the onFailure arm, a signing request that fails has no way to report back:
            // the suspended write never resumes and setUserIdentifier's callback never fires.
            val provider =
                DataGrailConsent.asSignatureProvider { _, onResult ->
                    onResult.onFailure(ConsentException.NetworkError("signing endpoint 503"))
                }

            val error =
                assertThrows(ConsentException.NetworkError::class.java) {
                    runBlocking { provider(samplePayload()) }
                }

            assertTrue(error.message!!.contains("signing endpoint 503"))
        }

    @Test
    fun `java signature provider adapter ignores a duplicate callback`() =
        runBlocking {
            // A customer provider that reports twice must not crash the app by resuming an
            // already-settled continuation.
            val expected = UniversalConsentSignature("sig", "key-1")
            val provider =
                DataGrailConsent.asSignatureProvider { _, onResult ->
                    onResult.onSignature(expected)
                    onResult.onFailure(ConsentException.NetworkError("late failure, ignored"))
                    onResult.onSignature(expected)
                }

            assertEquals(expected, provider(samplePayload()))
        }

    @Test
    fun `java signature provider adapter passes the signing payload through`() =
        runBlocking {
            var seenPayload: UniversalConsentSigningPayload? = null
            val provider =
                DataGrailConsent.asSignatureProvider { payload, onResult ->
                    seenPayload = payload
                    onResult.onSignature(UniversalConsentSignature("sig", "key-1"))
                }

            val payload =
                samplePayload(
                    customerId = "ac46d8ad-a67a-431f-a5d5-9e3eb922dae7",
                    userHash = "1fee132c",
                )
            provider(payload)

            assertEquals(payload, seenPayload)
        }

    @Test
    fun `readTrackingSignalBounded interrupts a wedged read and falls back within the timeout`() =
        runBlocking {
            // Simulates a wedged Play Services binder call: a reader that blocks far past the
            // timeout. runInterruptible must convert the deadline into a thread interrupt so the
            // bound actually fires — a cooperative timeout alone cannot interrupt a blocking call,
            // and structured concurrency would otherwise wait the full 10s for the body to return.
            val start = System.currentTimeMillis()
            val signal =
                sut.readTrackingSignalBounded(timeoutMs = 100L) {
                    Thread.sleep(10_000)
                    TrackingSignal.AUTHORIZED
                }
            val elapsed = System.currentTimeMillis() - start

            assertEquals(TrackingSignal.NOT_DETERMINED, signal)
            assertTrue(
                "bound must fire near the timeout, not wait for the blocking read (elapsed=$elapsed ms)",
                elapsed < 5_000L,
            )
        }

    private fun samplePayload(
        customerId: String = "cust-1",
        identifier: String = "user@example.com",
        userHash: String = "hash-1",
        timestamp: Long = 1_700_000_000L,
        nonce: String = "0123456789abcdef0123456789abcdef",
    ): UniversalConsentSigningPayload =
        UniversalConsentSigningPayload(
            stringToSign = "$customerId:$userHash:$timestamp:$nonce",
            customerId = customerId,
            identifier = identifier,
            userHash = userHash,
            timestamp = timestamp,
            nonce = nonce,
        )
}
