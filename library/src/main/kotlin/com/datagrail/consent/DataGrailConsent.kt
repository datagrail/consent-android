package com.datagrail.consent

import android.content.Context
import com.datagrail.consent.models.CategoryConsent
import com.datagrail.consent.models.ConsentConfig
import com.datagrail.consent.models.ConsentException
import com.datagrail.consent.models.ConsentPreferences
import com.datagrail.consent.models.SignatureProvider
import com.datagrail.consent.models.TrackingSignal
import com.datagrail.consent.models.TrackingSignalReader
import com.datagrail.consent.models.UniversalConsentRecord
import com.datagrail.consent.models.UniversalConsentSignature
import com.datagrail.consent.network.ConfigService
import com.datagrail.consent.network.ConsentService
import com.datagrail.consent.network.NetworkClient
import com.datagrail.consent.storage.ConsentStorage
import com.datagrail.consent.ui.BannerDisplayStyle
import com.datagrail.consent.ui.BannerTextStyleConfig
import com.datagrail.consent.utils.ConsentLogger
import com.datagrail.consent.utils.LogLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Main entry point for DataGrail Consent SDK
 *
 * ## Java Interoperability
 *
 * This SDK provides two versions of each async method:
 * - **Kotlin version**: Uses lambda callbacks with `Result<T>` (e.g., `callback: (Result<Unit>) -> Unit`)
 * - **Java version**: Uses callback interfaces with explicit success/failure methods (e.g., `ConsentCallback`)
 *
 * Kotlin developers should use the lambda-based methods as usual.
 * Java developers should use the callback interface methods for clearer, more idiomatic code.
 *
 * Both versions are functionally identical - the callback interface methods are thin adapters
 * over the lambda-based implementations. See [JAVA_INTEGRATION.md] for Java usage examples.
 */
class DataGrailConsent private constructor() {
    // Assigned from a background coroutine during initialize() and read from public API
    // methods on arbitrary threads; @Volatile guarantees the assignment is visible across
    // threads once initialization completes.
    @Volatile
    private var manager: ConsentManager? = null

    @Volatile
    private var configUrl: String? = null

    // Application context, retained from initialize() so the SDK can read the device's live
    // ad-tracking signal on the caller's behalf. Application context only — never an Activity,
    // which would leak.
    @Volatile
    private var appContext: Context? = null
    private var onConsentChangedCallback: ((ConsentPreferences) -> Unit)? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    // Monotonic token bumped on each initialize() call. A background init coroutine only
    // commits its manager/configUrl if its token is still current, so overlapping/re-entrant
    // initialize() calls resolve deterministically to the last caller ("last wins") instead of
    // whichever coroutine happens to finish last.
    private val initGeneration = AtomicInteger(0)

    companion object {
        @Volatile
        private var instance: DataGrailConsent? = null

        /**
         * How long to wait for the device ad-tracking signal before giving up on it. Generous
         * enough for a cold Play Services binder connection, short enough that a wedged one does
         * not hold a consent call open indefinitely.
         */
        private const val TRACKING_SIGNAL_TIMEOUT_MS = 3_000L

        /**
         * Set the SDK log level. Default is NONE (no logging).
         * @param level The desired log level
         */
        @JvmStatic
        fun setLogLevel(level: LogLevel) {
            ConsentLogger.level = level
        }

        /**
         * Get the shared singleton instance
         */
        @JvmStatic
        fun getInstance(): DataGrailConsent {
            return instance ?: synchronized(this) {
                instance ?: DataGrailConsent().also { instance = it }
            }
        }

        /**
         * Convert a Result callback to a ConsentCallback invocation.
         * Maps non-ConsentException errors to ConsentException.NetworkError.
         */
        /**
         * Bridge the Java-friendly [SignatureProviderCallback] onto the `suspend`
         * [SignatureProvider] the universal-consent write expects.
         *
         * Both arms of [SignatureResult] must resume the continuation. Without the failure arm a
         * signing request that fails has no way to report back, the write stays suspended
         * forever, and the caller's [ConsentCallback] never fires.
         */
        internal fun asSignatureProvider(callback: SignatureProviderCallback): SignatureProvider =
            { payload ->
                suspendCancellableCoroutine { continuation ->
                    // A customer signing implementation may invoke onSignature and onFailure from
                    // two different threads at nearly the same time (a success racing a timeout
                    // handler). A check-then-act on continuation.isActive is not atomic across the
                    // two arms, so both could observe active and the second resume would throw
                    // IllegalStateException on its thread. This CAS lets exactly one arm resume; the
                    // loser is silently ignored, which is what the "resumed at most once" contract
                    // promises — for concurrent duplicates, not just sequential ones.
                    val settled = AtomicBoolean(false)
                    callback.getSignature(
                        payload,
                        object : SignatureResult {
                            override fun onSignature(signature: UniversalConsentSignature) {
                                if (settled.compareAndSet(false, true)) continuation.resume(signature)
                            }

                            override fun onFailure(error: ConsentException) {
                                if (settled.compareAndSet(false, true)) continuation.resumeWithException(error)
                            }
                        },
                    )
                }
            }

        internal fun adaptResult(
            result: Result<Unit>,
            callback: ConsentCallback,
        ) {
            result.fold(
                onSuccess = { callback.onSuccess() },
                onFailure = { error ->
                    callback.onFailure(
                        if (error is ConsentException) error else ConsentException.NetworkError(error.message ?: "Unknown error", error),
                    )
                },
            )
        }
    }

    // MARK: - Initialization

    /**
     * Initialize the DataGrail Consent SDK (Java-friendly)
     * @param context Android application context
     * @param configUrl URL to fetch consent configuration from
     * @param callback Callback interface for success/failure
     */
    fun initialize(
        context: Context,
        configUrl: String,
        callback: ConsentCallback,
    ) {
        initialize(context, configUrl) { result -> adaptResult(result, callback) }
    }

    /**
     * Initialize the DataGrail Consent SDK (Kotlin-friendly)
     * @param context Android application context
     * @param configUrl URL to fetch consent configuration from
     * @param callback Callback with result
     */
    fun initialize(
        context: Context,
        configUrl: String,
        callback: (Result<Unit>) -> Unit,
    ) {
        // Validate URL format and scheme
        val url =
            try {
                URL(configUrl)
            } catch (e: Exception) {
                scope.launch {
                    callback(
                        Result.failure(
                            ConsentException.InvalidConfiguration("Invalid config URL format: ${e.message}"),
                        ),
                    )
                }
                return
            }

        // Validate URL scheme — HTTPS required
        if (url.protocol != "https") {
            scope.launch {
                callback(
                    Result.failure(ConsentException.InvalidConfiguration("Config URL must use HTTPS")),
                )
            }
            return
        }

        // Validate URL host
        if (url.host.isNullOrEmpty()) {
            scope.launch {
                callback(Result.failure(ConsentException.InvalidConfiguration("Config URL must have a valid host")))
            }
            return
        }

        // Extract privacy domain from config URL (already validated non-empty above)
        val privacyDomain = url.host

        // Token for this init call. Any earlier in-flight init is now superseded and must not
        // commit its manager/configUrl (see the generation check after storage setup below).
        val generation = initGeneration.incrementAndGet()

        // Build storage + services and load configuration off the calling thread.
        //
        // ConsentStorage.create() creates EncryptedSharedPreferences, which on first launch
        // triggers Tink/Android Keystore keyset generation — a synchronous, potentially slow
        // operation (~hundreds of ms). Doing it on the caller's thread (typically the main
        // thread during app cold start) blocks UI and can contribute to ANRs. We move it onto
        // Dispatchers.IO so initialize() never blocks the caller; the public API is already
        // fully async via the callback, so callers see no behavior change beyond this.
        scope.launch {
            val manager =
                try {
                    withContext(Dispatchers.IO) {
                        val storage = ConsentStorage.create(context)
                        val networkClient = NetworkClient()
                        val configService = ConfigService(networkClient, storage)
                        val consentService = ConsentService(networkClient, storage, privacyDomain)
                        ConsentManager(storage, configService, consentService)
                    }
                } catch (e: CancellationException) {
                    // Never swallow cancellation — let it propagate so the coroutine unwinds
                    // normally rather than being reported as a storage-init failure.
                    throw e
                } catch (e: Exception) {
                    // Storage initialization failed (e.g. encrypted storage could not be set up
                    // even after keyset recovery). Report through the callback rather than
                    // throwing synchronously, consistent with the async contract. configUrl is
                    // intentionally left unset here since init did not complete.
                    callback(
                        Result.failure(
                            if (e is ConsentException) {
                                e
                            } else {
                                ConsentException.InvalidConfiguration(
                                    "Failed to initialize consent storage: ${e.message}",
                                    e,
                                )
                            },
                        ),
                    )
                    return@launch
                }

            // If a newer initialize() call has started, don't clobber its state with ours.
            // Still fire this caller's callback so it isn't left hanging.
            if (initGeneration.get() != generation) {
                callback(Result.success(Unit))
                return@launch
            }

            // Commit shared state only after successful storage setup and once we've confirmed
            // this is still the current init — so a failed init never leaves a stale configUrl.
            this@DataGrailConsent.manager = manager
            this@DataGrailConsent.configUrl = configUrl
            // applicationContext, not the caller's context: an Activity held here would leak for
            // the process lifetime, and the ad-id lookup only needs an application context.
            this@DataGrailConsent.appContext = context.applicationContext

            // Load configuration
            manager.loadConfig(configUrl) { result ->
                when {
                    result.isSuccess -> {
                        // Retry any pending requests on initialization
                        scope.launch {
                            manager.retryPendingRequests()
                        }
                        callback(Result.success(Unit))
                    }
                    else -> callback(Result.failure(result.exceptionOrNull()!!))
                }
            }
        }
    }

    // MARK: - Consent Status

    /**
     * Check if consent banner should be shown based on config and user state.
     * This is the recommended API for determining whether to display the banner.
     * @return true if banner should be displayed, false otherwise
     * @throws ConsentException.NotInitialized if SDK not initialized
     */
    fun shouldDisplayBanner(): Boolean {
        val mgr = manager ?: throw ConsentException.NotInitialized()
        return mgr.needsConsent()
    }

    /**
     * Check if user has previously given consent (accepted or rejected).
     * This differs from shouldDisplayBanner() - a user may have consent saved
     * but the banner could still need to be shown (e.g., config version changed).
     * @return true if user has previously made a consent decision
     * @throws ConsentException.NotInitialized if SDK not initialized
     */
    fun hasUserConsent(): Boolean {
        val mgr = manager ?: throw ConsentException.NotInitialized()
        return mgr.getUserPreferences() != null
    }

    /**
     * @deprecated Use shouldDisplayBanner() instead
     * Check if consent banner should be shown
     * @return true if consent is needed, false otherwise
     * @throws ConsentException.NotInitialized if SDK not initialized
     */
    @Deprecated("Use shouldDisplayBanner() instead", ReplaceWith("shouldDisplayBanner()"))
    fun needsConsent(): Boolean {
        return shouldDisplayBanner()
    }

    /**
     * Get the current consent configuration
     * @return The config if initialized, null otherwise
     */
    fun getConfig(): ConsentConfig? {
        return manager?.currentConfig
    }

    /**
     * Get user's saved consent preferences
     * @return Saved preferences, or null if user hasn't saved consent yet
     * @throws ConsentException.NotInitialized if SDK not initialized
     */
    fun getUserPreferences(): ConsentPreferences? {
        val mgr = manager ?: throw ConsentException.NotInitialized()
        return mgr.getUserPreferences()
    }

    /**
     * Get categories with their current consent state
     * Returns saved preferences if available, otherwise returns default preferences from initialCategories
     * Use this to always get category status regardless of whether the user has saved consent
     * @return Consent preferences representing the current category state
     * @throws ConsentException.NotInitialized if SDK not initialized
     */
    fun getCategories(): ConsentPreferences? {
        val mgr = manager ?: throw ConsentException.NotInitialized()
        return mgr.getCategories()
    }

    /**
     * Check if a specific category is enabled
     * @param category The category GTM key (e.g., "category_marketing")
     * @return true if enabled, false otherwise
     * @throws ConsentException.NotInitialized if SDK not initialized
     */
    fun isCategoryEnabled(category: String): Boolean {
        val mgr = manager ?: throw ConsentException.NotInitialized()
        return mgr.isCategoryEnabled(category)
    }

    // MARK: - Consent Management

    /**
     * Save consent preferences (Java-friendly)
     * @param preferences The preferences to save
     * @param callback Callback interface for success/failure
     */
    fun savePreferences(
        preferences: ConsentPreferences,
        callback: ConsentCallback,
    ) {
        savePreferences(preferences) { result -> adaptResult(result, callback) }
    }

    /**
     * Save consent preferences (Kotlin-friendly)
     * @param preferences The preferences to save
     * @param callback Callback with result
     */
    fun savePreferences(
        preferences: ConsentPreferences,
        callback: (Result<Unit>) -> Unit,
    ) {
        val mgr = manager
        if (mgr == null) {
            callback(Result.failure(ConsentException.NotInitialized()))
            return
        }

        scope.launch {
            mgr.savePreferences(preferences) { result ->
                if (result.isSuccess) {
                    // Notify callback
                    onConsentChangedCallback?.invoke(preferences)
                }
                callback(result)
            }
        }
    }

    /**
     * Accept all categories (Java-friendly)
     * @param callback Callback interface for success/failure
     */
    fun acceptAll(callback: ConsentCallback) {
        acceptAll { result -> adaptResult(result, callback) }
    }

    /**
     * Accept all categories (Kotlin-friendly)
     * @param callback Callback with result
     */
    fun acceptAll(callback: (Result<Unit>) -> Unit) {
        val mgr = manager
        if (mgr == null) {
            callback(Result.failure(ConsentException.NotInitialized()))
            return
        }

        val defaultPreferences = mgr.getDefaultPreferences()
        if (defaultPreferences == null) {
            callback(Result.failure(ConsentException.NotInitialized()))
            return
        }

        // Enable all categories
        val allEnabled =
            ConsentPreferences(
                isCustomised = true,
                cookieOptions =
                    defaultPreferences.cookieOptions.map {
                        CategoryConsent(gtmKey = it.gtmKey, isEnabled = true)
                    },
            )

        savePreferences(allEnabled, callback)
    }

    /**
     * Reject all non-essential categories (Java-friendly)
     * @param callback Callback interface for success/failure
     */
    fun rejectAll(callback: ConsentCallback) {
        rejectAll { result -> adaptResult(result, callback) }
    }

    /**
     * Reject all non-essential categories (Kotlin-friendly)
     * @param callback Callback with result
     */
    fun rejectAll(callback: (Result<Unit>) -> Unit) {
        val mgr = manager
        if (mgr == null) {
            callback(Result.failure(ConsentException.NotInitialized()))
            return
        }

        val defaultPreferences = mgr.getDefaultPreferences()
        if (defaultPreferences == null) {
            callback(Result.failure(ConsentException.NotInitialized()))
            return
        }

        // Only enable essential/always-on categories
        val essentialCategories = mgr.getEssentialCategories()
        val onlyEssential =
            ConsentPreferences(
                isCustomised = true,
                cookieOptions =
                    defaultPreferences.cookieOptions.map {
                        CategoryConsent(
                            gtmKey = it.gtmKey,
                            isEnabled = essentialCategories.contains(it.gtmKey),
                        )
                    },
            )

        savePreferences(onlyEssential, callback)
    }

    /**
     * Reset all consent data
     */
    fun reset() {
        manager?.reset()
    }

    /**
     * Reset the unique tracking identifier.
     * A new ID will be generated on the next API call.
     */
    fun resetIdentifier() {
        manager?.resetIdentifier()
    }

    // MARK: - Banner Display

    /**
     * Track that the banner was shown (Java-friendly)
     * @param callback Callback interface for success/failure
     */
    fun trackBannerShown(callback: ConsentCallback) {
        trackBannerShown { result -> adaptResult(result, callback) }
    }

    /**
     * Track that the banner was shown (Kotlin-friendly)
     * @param callback Callback with result
     */
    fun trackBannerShown(callback: (Result<Unit>) -> Unit) {
        val mgr = manager
        if (mgr == null) {
            callback(Result.failure(ConsentException.NotInitialized()))
            return
        }

        scope.launch {
            mgr.trackBannerOpen(callback)
        }
    }

    // MARK: - Universal Consent

    /**
     * Read this device's ad-tracking signal, bounded.
     *
     * The lookup is a binder call into Play Services, so it runs on [Dispatchers.IO] rather than
     * the main thread. It is also bounded: `getAdvertisingIdInfo` blocks on a service connection
     * that can hang indefinitely when Play Services is wedged or being updated, and every universal
     * consent call sits behind this read. A timeout falls back to [TrackingSignal.NOT_DETERMINED],
     * which is the same fallback the reader itself uses for an unreadable signal — an unknown
     * signal is not a refusal, so nothing is suppressed on its account.
     */
    private suspend fun readTrackingSignal(context: Context?): TrackingSignal =
        readTrackingSignalBounded { TrackingSignalReader.read(context) }

    /**
     * Bound a blocking tracking-signal read so a wedged Play Services cannot hang the universal
     * consent entry points forever.
     *
     * The read runs inside [runInterruptible] on [Dispatchers.IO]. `withTimeoutOrNull` enforces its
     * deadline by cancelling the coroutine, and cancellation is COOPERATIVE — a thread parked in the
     * reflective `getAdvertisingIdInfo` binder call (which has no suspension points) would never
     * observe it, so structured concurrency would keep `withContext` from returning until that
     * blocking call finished on its own and the bound would not actually fire. [runInterruptible]
     * turns the cancellation into a THREAD INTERRUPT, so the blocking call is interrupted at the
     * deadline. On timeout we fall back to [TrackingSignal.NOT_DETERMINED] — the same value an
     * unreadable signal produces — so a timeout suppresses nothing.
     *
     * Internal (not private) and timeout-parameterised so a test with a blocking reader can assert
     * the bound fires without waiting the full production timeout.
     */
    internal suspend fun readTrackingSignalBounded(
        timeoutMs: Long = TRACKING_SIGNAL_TIMEOUT_MS,
        read: () -> TrackingSignal,
    ): TrackingSignal =
        withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.IO) { runInterruptible { read() } }
        } ?: TrackingSignal.NOT_DETERMINED.also {
            ConsentLogger.w("Timed out reading the device ad-tracking signal; treating it as undetermined")
        }

    /**
     * Sync the current effective consent preferences to the DataGrail Universal Consent store
     * for the given user identifier, for cross-device retrieval (Java-friendly).
     *
     * Thin adapter over the Kotlin lambda overload. The [getSignature] provider receives a
     * [SignatureResult] sink to hand back the signature material computed by the customer backend.
     *
     * @param identifier The user identifier (e.g. email). Normalized (Unicode NFC → trim →
     *   lowercase) before hashing, per the canonical cross-SDK contract.
     * @param apiKey The customer's DataGrail API key.
     * @param getSignature Java-friendly signature provider (calls the customer's backend).
     * @param callback Callback interface for success/failure.
     */
    fun setUserIdentifier(
        identifier: String,
        apiKey: String,
        getSignature: SignatureProviderCallback,
        callback: ConsentCallback,
    ) {
        setUserIdentifier(identifier, apiKey, asSignatureProvider(getSignature)) { result ->
            adaptResult(result, callback)
        }
    }

    /**
     * Sync the current effective consent preferences to the DataGrail Universal Consent store
     * for the given user identifier, for cross-device retrieval (Kotlin-friendly).
     *
     * This READS before it WRITES. Any stored record for this identifier is rehydrated onto the
     * device first (honoring a choice made on the web or another device); the write then carries
     * the user's CURRENT LOCAL choice — sync-on-change / write-through — and never re-POSTs the
     * fetched record. A found record with no genuine local change is adopted WITHOUT a POST. A read
     * failure blocks the write and surfaces as a failure result, so a record we could not read is
     * never overwritten; retry to re-read first.
     *
     * The identifier is NOT retained as state — later calls such as [fetchUniversalConsent] and
     * [rehydrateFromUniversalConsent] require it to be passed again.
     *
     * The SDK does NOT hold or compute the HMAC secret. It builds the canonical string-to-sign
     * (with its own per-write timestamp and nonce) and invokes the customer-provided
     * [getSignature] provider — which calls the customer's own backend — attaching the returned
     * signature/keyId plus the SDK's own timestamp and nonce as request headers.
     *
     * The read applies this device's ad-tracking signal to LOCAL state; the write carries the
     * user's RAW preferences. The SDK reads the signal itself — you do not pass it in. A device
     * signal never changes what is stored cross-device: otherwise opening the app with ad tracking
     * limited would erase a marketing opt-in the user made on the web, for every device on their
     * identifier. A signal never enables a category either.
     *
     * @param identifier The user identifier (e.g. email). Normalized (Unicode NFC → trim →
     *   lowercase) before hashing, per the canonical cross-SDK contract.
     * @param apiKey The customer's DataGrail API key.
     * @param getSignature Suspend provider that signs the SDK-built payload and returns
     *   { signature, keyId }.
     * @param callback Callback with the result.
     */
    fun setUserIdentifier(
        identifier: String,
        apiKey: String,
        getSignature: SignatureProvider,
        callback: (Result<Unit>) -> Unit,
    ) {
        launchSetUserIdentifier(identifier, apiKey, getSignature, callback)
    }

    /**
     * Sync the current effective consent preferences to the DataGrail Universal Consent store
     * for the given user identifier, without a signature (limited, API-key-only) (Kotlin-friendly).
     *
     * Same READ-then-WRITE flow as the signed overload, but omits [getSignature]: the write carries
     * only the customer's API key, with no X-DG-Signature / X-DG-Timestamp / X-DG-Nonce / X-DG-Key-Id
     * headers. Per the shared cross-SDK contract, omitting getSignature performs a limited,
     * API-key-only write. Use the signed overloads when you have a signing backend; this exists for
     * customers who do not yet.
     *
     * @param identifier The user identifier (e.g. email). Normalized (Unicode NFC → trim →
     *   lowercase) before hashing, per the canonical cross-SDK contract.
     * @param apiKey The customer's DataGrail API key.
     * @param callback Callback with the result.
     */
    fun setUserIdentifier(
        identifier: String,
        apiKey: String,
        callback: (Result<Unit>) -> Unit,
    ) {
        launchSetUserIdentifier(identifier, apiKey, getSignature = null, callback = callback)
    }

    /**
     * Sync the current effective consent preferences to the DataGrail Universal Consent store
     * for the given user identifier, without a signature (limited, API-key-only) (Java-friendly).
     *
     * Thin adapter over the Kotlin lambda overload. Performs an API-key-only write with no signature
     * headers — the limited mode of the shared cross-SDK contract.
     *
     * @param identifier The user identifier (e.g. email). Normalized (Unicode NFC → trim →
     *   lowercase) before hashing, per the canonical cross-SDK contract.
     * @param apiKey The customer's DataGrail API key.
     * @param callback Callback interface for success/failure.
     */
    fun setUserIdentifier(
        identifier: String,
        apiKey: String,
        callback: ConsentCallback,
    ) {
        setUserIdentifier(identifier, apiKey) { result -> adaptResult(result, callback) }
    }

    /**
     * Shared READ-then-WRITE launcher for every [setUserIdentifier] overload. A null [getSignature]
     * selects the limited, API-key-only write (no signature headers); a non-null one signs the
     * write. Reads this device's live signal (the manager applies it only to the local rehydration),
     * then hands the whole READ-then-WRITE sequence to the manager, which owns the invariant. Stays
     * a thin scope.launch wrapper like its siblings; the compound flow and its read-failure handling
     * live in ConsentManager where they are unit-tested.
     */
    private fun launchSetUserIdentifier(
        identifier: String,
        apiKey: String,
        getSignature: SignatureProvider?,
        callback: (Result<Unit>) -> Unit,
    ) {
        val mgr = manager
        if (mgr == null) {
            callback(Result.failure(ConsentException.NotInitialized()))
            return
        }

        val context = appContext

        scope.launch {
            try {
                val trackingSignal = readTrackingSignal(context)
                mgr.setUserIdentifier(
                    identifier = identifier,
                    apiKey = apiKey,
                    trackingSignal = trackingSignal,
                    getSignature = getSignature,
                    onRehydrated = { prefs -> onConsentChangedCallback?.invoke(prefs) },
                )
                callback(Result.success(Unit))
            } catch (e: CancellationException) {
                // Never swallow cancellation — let it propagate so the coroutine unwinds normally
                // rather than being reported to the caller as a NetworkError. Matches initialize().
                throw e
            } catch (e: Exception) {
                callback(
                    Result.failure(
                        if (e is ConsentException) e else ConsentException.NetworkError(e.message ?: "Unknown error", e),
                    ),
                )
            }
        }
    }

    /**
     * Fetch the user's universal consent record for cross-device rehydration (Kotlin-friendly).
     *
     * The returned record has signal reconciliation already applied on-device: when the stored
     * record's `gpc` signal is true, or this device's ad-tracking signal indicates an opt-out,
     * non-essential categories are reported as suppressed. The SDK reads the device signal itself.
     *
     * @param identifier The user identifier. Normalized (Unicode NFC → trim → lowercase)
     *   before hashing, per the canonical cross-SDK contract.
     * @param apiKey The customer's DataGrail API key.
     * @param callback Callback with the record (null if no record exists) or a failure.
     */
    fun fetchUniversalConsent(
        identifier: String,
        apiKey: String,
        callback: (Result<UniversalConsentRecord?>) -> Unit,
    ) {
        val mgr = manager
        if (mgr == null) {
            callback(Result.failure(ConsentException.NotInitialized()))
            return
        }

        val context = appContext

        scope.launch {
            try {
                val trackingSignal = readTrackingSignal(context)
                callback(Result.success(mgr.fetchUniversalConsent(identifier, apiKey, trackingSignal)))
            } catch (e: CancellationException) {
                // Never swallow cancellation — let it propagate so the coroutine unwinds normally
                // rather than being reported to the caller as a NetworkError. Matches initialize().
                throw e
            } catch (e: Exception) {
                callback(
                    Result.failure(
                        if (e is ConsentException) e else ConsentException.NetworkError(e.message ?: "Unknown error", e),
                    ),
                )
            }
        }
    }

    /**
     * Fetch the user's universal consent record for cross-device rehydration (Java-friendly).
     *
     * Thin adapter over the Kotlin lambda overload. The record passed to
     * [UniversalConsentCallback.onSuccess] is null when no record exists.
     *
     * @param identifier The user identifier. Normalized (Unicode NFC → trim → lowercase)
     *   before hashing, per the canonical cross-SDK contract.
     * @param apiKey The customer's DataGrail API key.
     * @param callback Callback interface for success (record, possibly null) / failure.
     */
    fun fetchUniversalConsent(
        identifier: String,
        apiKey: String,
        callback: UniversalConsentCallback,
    ) {
        fetchUniversalConsent(identifier, apiKey) { result ->
            result.fold(
                onSuccess = { record -> callback.onSuccess(record) },
                onFailure = { error ->
                    callback.onFailure(
                        if (error is ConsentException) error else ConsentException.NetworkError(error.message ?: "Unknown error", error),
                    )
                },
            )
        }
    }

    /**
     * Rehydrate local consent state from the DataGrail Universal Consent store (Kotlin-friendly).
     *
     * Call this after [initialize] and BEFORE [needsConsent] when you know who the user is. Where
     * [fetchUniversalConsent] only hands the record back, this applies it: the effective state is
     * persisted locally, so [needsConsent], [getCategories] and [isCategoryEnabled] all reflect
     * the consent the user gave on another device and the banner does not re-prompt them.
     *
     * A read miss leaves local state untouched and writes nothing — "no record" is the absence of
     * a signal, not a denial, so the banner still shows.
     *
     * @param identifier The user identifier. Normalized (Unicode NFC → trim → lowercase) before
     *   hashing, per the canonical cross-SDK contract.
     * @param apiKey The customer's DataGrail API key.
     * @param callback Callback with true when local state was rehydrated from a stored record.
     */
    fun rehydrateFromUniversalConsent(
        identifier: String,
        apiKey: String,
        callback: (Result<Boolean>) -> Unit,
    ) {
        val mgr = manager
        if (mgr == null) {
            callback(Result.failure(ConsentException.NotInitialized()))
            return
        }

        val context = appContext

        scope.launch {
            try {
                val trackingSignal = readTrackingSignal(context)
                val rehydrated = mgr.rehydrateFromUniversalConsent(identifier, apiKey, trackingSignal)
                if (rehydrated) {
                    mgr.getCategories()?.let { onConsentChangedCallback?.invoke(it) }
                }
                callback(Result.success(rehydrated))
            } catch (e: CancellationException) {
                // Never swallow cancellation — let it propagate so the coroutine unwinds normally
                // rather than being reported to the caller as a NetworkError. Matches initialize().
                throw e
            } catch (e: Exception) {
                callback(
                    Result.failure(
                        if (e is ConsentException) e else ConsentException.NetworkError(e.message ?: "Unknown error", e),
                    ),
                )
            }
        }
    }

    /**
     * Rehydrate local consent state from the DataGrail Universal Consent store (Java-friendly).
     *
     * Thin adapter over the Kotlin lambda overload. [RehydrateCallback.onSuccess] receives false
     * when no record existed for the user, in which case local state is untouched.
     *
     * @param identifier The user identifier. Normalized (Unicode NFC → trim → lowercase) before
     *   hashing, per the canonical cross-SDK contract.
     * @param apiKey The customer's DataGrail API key.
     * @param callback Callback interface for success (rehydrated true/false) / failure.
     */
    fun rehydrateFromUniversalConsent(
        identifier: String,
        apiKey: String,
        callback: RehydrateCallback,
    ) {
        rehydrateFromUniversalConsent(identifier, apiKey) { result ->
            result.fold(
                onSuccess = { rehydrated -> callback.onSuccess(rehydrated) },
                onFailure = { error ->
                    callback.onFailure(
                        if (error is ConsentException) error else ConsentException.NetworkError(error.message ?: "Unknown error", error),
                    )
                },
            )
        }
    }

    // MARK: - Callbacks

    /**
     * Set callback to be notified when consent changes (Java-friendly)
     * @param listener Listener interface to invoke with new preferences
     */
    fun onConsentChanged(listener: ConsentChangeListener) {
        this.onConsentChangedCallback = { preferences ->
            listener.onConsentChanged(preferences)
        }
    }

    /**
     * Set callback to be notified when consent changes (Kotlin-friendly)
     * @param callback Callback to invoke with new preferences
     */
    fun onConsentChanged(callback: (ConsentPreferences) -> Unit) {
        this.onConsentChangedCallback = callback
    }

    // MARK: - Utility

    /**
     * Retry any pending API requests (Java-friendly)
     * @param callback Callback interface with retry results
     */
    fun retryPendingRequests(callback: RetryCallback) {
        retryPendingRequests { successCount, failureCount ->
            callback.onRetryComplete(successCount, failureCount)
        }
    }

    /**
     * Retry any pending API requests (Kotlin-friendly)
     * @param callback Callback with (successCount, failureCount)
     */
    fun retryPendingRequests(callback: (Int, Int) -> Unit) {
        val mgr = manager
        if (mgr == null) {
            callback(0, 0)
            return
        }

        scope.launch {
            val (success, failure) = mgr.retryPendingRequests()
            callback(success, failure)
        }
    }

    // MARK: - UI Methods

    /**
     * Show the consent banner dialog with MODAL style (Java-friendly convenience overload)
     * @param activity The activity to show the dialog on
     * @param callback Callback interface for banner result
     */
    fun showBanner(
        activity: androidx.fragment.app.FragmentActivity,
        callback: PreferencesCallback,
    ) {
        showBanner(activity, BannerDisplayStyle.MODAL, callback)
    }

    /**
     * Show the consent banner dialog with specified display style (Java-friendly)
     * @param activity The activity to show the dialog on
     * @param style The display style for the banner (MODAL or FULL_SCREEN)
     * @param callback Callback interface for banner result
     */
    fun showBanner(
        activity: androidx.fragment.app.FragmentActivity,
        style: BannerDisplayStyle,
        callback: PreferencesCallback,
    ) {
        showBanner(activity, style) { preferences ->
            if (preferences != null) {
                callback.onPreferencesSaved(preferences)
            } else {
                callback.onDismissed()
            }
        }
    }

    /**
     * Show the consent banner dialog with specified display style (Kotlin-friendly)
     * @param activity The activity to show the dialog on
     * @param style The display style for the banner (MODAL or FULL_SCREEN)
     * @param textStyleConfig Optional font size/style overrides for banner text elements
     * @param callback Called when the dialog is dismissed with updated preferences (null if dismissed without saving)
     */
    @JvmOverloads
    fun showBanner(
        activity: androidx.fragment.app.FragmentActivity,
        style: BannerDisplayStyle = BannerDisplayStyle.MODAL,
        textStyleConfig: BannerTextStyleConfig = BannerTextStyleConfig(),
        callback: ((ConsentPreferences?) -> Unit)? = null,
    ) {
        val mgr = manager
        if (mgr == null) {
            callback?.invoke(null)
            return
        }

        // Get current config and preferences
        val cfg = mgr.currentConfig
        if (cfg == null) {
            callback?.invoke(null)
            return
        }

        // Use getCategories() to get effective preferences (saved or default from initialCategories)
        val prefs = mgr.getCategories()

        // Create and show dialog
        val dialog =
            com.datagrail.consent.ui.BannerDialog.newInstance(
                config = cfg,
                preferences = prefs,
                displayStyle = style,
                textStyleConfig = textStyleConfig,
            ) { updatedPreferences ->
                if (updatedPreferences != null) {
                    // Save preferences if user made changes
                    savePreferences(updatedPreferences) { result ->
                        result.fold(
                            onSuccess = { callback?.invoke(updatedPreferences) },
                            onFailure = {
                                // Still report success even if network call fails,
                                // since preferences are saved locally
                                callback?.invoke(updatedPreferences)
                            },
                        )
                    }
                } else {
                    callback?.invoke(null)
                }
            }

        dialog.show(activity.supportFragmentManager, "ConsentBannerDialog")
    }
}
