package com.datagrail.consent

import android.content.Context
import com.datagrail.consent.models.CategoryConsent
import com.datagrail.consent.models.ConsentConfig
import com.datagrail.consent.models.ConsentException
import com.datagrail.consent.models.ConsentPreferences
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
import kotlinx.coroutines.withContext
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

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
