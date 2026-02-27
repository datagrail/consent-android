package com.datagrail.consent

import android.content.Context
import com.datagrail.consent.models.CategoryConsent
import com.datagrail.consent.models.ConsentConfig
import com.datagrail.consent.models.ConsentException
import com.datagrail.consent.models.ConsentPreferences
import com.datagrail.consent.models.ConsentResponse
import com.datagrail.consent.network.ConfigService
import com.datagrail.consent.network.ConsentService
import com.datagrail.consent.network.NetworkClient
import com.datagrail.consent.storage.ConsentStorage
import com.datagrail.consent.ui.BannerDisplayStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.URL

/**
 * Main entry point for DataGrail Consent SDK
 */
class DataGrailConsent private constructor() {
    private var manager: ConsentManager? = null
    private var configUrl: String? = null
    private var onConsentChangedCallback: ((ConsentPreferences) -> Unit)? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    companion object {
        @Volatile
        private var instance: DataGrailConsent? = null

        /**
         * Get the shared singleton instance
         */
        @JvmStatic
        fun getInstance(): DataGrailConsent {
            return instance ?: synchronized(this) {
                instance ?: DataGrailConsent().also { instance = it }
            }
        }
    }

    // MARK: - Initialization

    /**
     * Initialize the DataGrail Consent SDK (Java-friendly)
     * @param context Android application context
     * @param configUrl URL to fetch consent configuration from
     * @param callback Callback interface for success/failure
     */
    @JvmOverloads
    fun initialize(
        context: Context,
        configUrl: String,
        callback: ConsentCallback,
    ) {
        initialize(context, configUrl) { result ->
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

        // Validate URL scheme
        if (url.protocol != "https" && url.protocol != "http") {
            scope.launch {
                callback(
                    Result.failure(ConsentException.InvalidConfiguration("Config URL must use http or https scheme")),
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

        this.configUrl = configUrl

        val storage = ConsentStorage(context)
        val networkClient = NetworkClient()
        val configService = ConfigService(networkClient, storage)

        // Extract privacy domain from config URL
        val privacyDomain =
            try {
                URL(configUrl).host ?: "consent.datagrail.io"
            } catch (e: Exception) {
                "consent.datagrail.io"
            }

        val consentService = ConsentService(networkClient, storage, privacyDomain)

        val manager = ConsentManager(storage, configService, consentService)
        this.manager = manager

        // Load configuration
        scope.launch {
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
    @JvmOverloads
    fun savePreferences(
        preferences: ConsentPreferences,
        callback: ConsentCallback,
    ) {
        savePreferences(preferences) { result ->
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
    @JvmOverloads
    fun acceptAll(callback: ConsentCallback) {
        acceptAll { result ->
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
    @JvmOverloads
    fun rejectAll(callback: ConsentCallback) {
        rejectAll { result ->
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

    // MARK: - Banner Display

    /**
     * Track that the banner was shown (Java-friendly)
     * @param callback Callback interface for success/failure
     */
    @JvmOverloads
    fun trackBannerShown(callback: ConsentCallback) {
        trackBannerShown { result ->
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
    @JvmOverloads
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
    @JvmOverloads
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
     * Show the consent banner dialog with specified display style (Java-friendly)
     * @param activity The activity to show the dialog on
     * @param style The display style for the banner (MODAL or FULL_SCREEN)
     * @param callback Callback interface for banner result
     */
    @JvmOverloads
    fun showBanner(
        activity: androidx.fragment.app.FragmentActivity,
        style: BannerDisplayStyle = BannerDisplayStyle.MODAL,
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
     * @param callback Called when the dialog is dismissed with updated preferences (null if dismissed without saving)
     */
    @JvmOverloads
    fun showBanner(
        activity: androidx.fragment.app.FragmentActivity,
        style: BannerDisplayStyle = BannerDisplayStyle.MODAL,
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
            ) { updatedPreferences ->
                if (updatedPreferences != null) {
                    // Save preferences if user made changes
                    savePreferences(updatedPreferences) { result ->
                        result.fold(
                            onSuccess = { callback?.invoke(updatedPreferences) },
                            onFailure = { callback?.invoke(null) },
                        )
                    }
                } else {
                    callback?.invoke(null)
                }
            }

        dialog.show(activity.supportFragmentManager, "ConsentBannerDialog")
    }
}
