package com.datagrail.consent

import com.datagrail.consent.models.CategoryConsent
import com.datagrail.consent.models.ConsentConfig
import com.datagrail.consent.models.ConsentException
import com.datagrail.consent.models.ConsentPreferences
import com.datagrail.consent.network.ConfigService
import com.datagrail.consent.network.ConsentService
import com.datagrail.consent.storage.ConsentStorage

/**
 * Manages consent state and coordinates between storage, network, and configuration
 */
class ConsentManager(
    private val storage: ConsentStorage,
    private val configService: ConfigService,
    private val consentService: ConsentService,
) {
    internal var currentConfig: ConsentConfig? = null

    // MARK: - Configuration

    /**
     * Load configuration from URL
     * @param configUrl URL to fetch configuration from
     * @param callback Callback with result
     */
    suspend fun loadConfig(
        configUrl: String,
        callback: (Result<ConsentConfig>) -> Unit,
    ) {
        try {
            val config = configService.fetchConfigWithRetry(configUrl)
            currentConfig = config
            callback(Result.success(config))
        } catch (e: Exception) {
            callback(Result.failure(e))
        }
    }

    // MARK: - Consent Check

    /**
     * Check if consent banner should be shown
     * @return true if consent is needed, false otherwise
     */
    fun needsConsent(): Boolean {
        val config = currentConfig ?: return false

        // Check if banner should be shown
        if (!config.showBanner) {
            return false
        }

        // Check if preferences exist
        val preferences = storage.loadPreferences()

        // If no preferences, always show
        if (preferences == null) {
            return true
        }

        // Check if config version has changed
        val storedVersion = storage.loadConfigVersion()
        if (storedVersion != config.version) {
            return true
        }

        return false
    }

    // MARK: - Preferences

    /**
     * Get user's saved consent preferences
     * @return Saved preferences, or null if user hasn't saved consent yet
     */
    fun getUserPreferences(): ConsentPreferences? {
        return storage.loadPreferences()
    }

    /**
     * Get categories with their current consent state
     * Returns saved preferences if available, otherwise returns default preferences from initialCategories
     * @return Consent preferences representing the current category state
     */
    fun getCategories(): ConsentPreferences? {
        storage.loadPreferences()?.let { return it }
        return getDefaultPreferences()
    }

    /**
     * Get default preferences based on configuration
     * @return Default preferences with initial categories enabled
     */
    fun getDefaultPreferences(): ConsentPreferences? {
        val config = currentConfig ?: return null

        val cookieOptions =
            config.initialCategories.initial.map { category ->
                CategoryConsent(gtmKey = category, isEnabled = true)
            }

        return ConsentPreferences(
            isCustomised = false,
            cookieOptions = cookieOptions,
        )
    }

    /**
     * Save consent preferences
     * @param preferences The preferences to save
     * @param callback Callback with result
     */
    suspend fun savePreferences(
        preferences: ConsentPreferences,
        callback: (Result<Unit>) -> Unit,
    ) {
        val config = currentConfig
        if (config == null) {
            callback(Result.failure(ConsentException.NotInitialized()))
            return
        }

        try {
            // Save locally
            storage.savePreferences(preferences)
            storage.saveConfigVersion(config.version)

            // Send to backend
            consentService.savePreferences(preferences, config)
            callback(Result.success(Unit))
        } catch (e: Exception) {
            callback(Result.failure(e))
        }
    }

    /**
     * Track banner open event
     * @param callback Callback with result
     */
    suspend fun trackBannerOpen(callback: (Result<Unit>) -> Unit) {
        val config = currentConfig
        if (config == null) {
            callback(Result.failure(ConsentException.NotInitialized()))
            return
        }

        try {
            consentService.saveOpen(config)
            callback(Result.success(Unit))
        } catch (e: Exception) {
            callback(Result.failure(e))
        }
    }

    /**
     * Check if a specific category is enabled
     * @param category The category GTM key to check
     * @return true if enabled, false otherwise
     */
    fun isCategoryEnabled(category: String): Boolean {
        val preferences = storage.loadPreferences()
        if (preferences == null) {
            // No preferences - check if it's in initial categories
            return currentConfig?.initialCategories?.initial?.contains(category) ?: false
        }

        return preferences.isCategoryEnabled(category)
    }

    /**
     * Get list of essential/always-on category GTM keys from config
     * @return List of GTM keys for categories that are always enabled
     */
    fun getEssentialCategories(): List<String> {
        val config = currentConfig ?: return emptyList()

        val essentialKeys = mutableListOf<String>()

        // Check all layers for categories marked as alwaysOn
        for ((_, layer) in config.layout.consentLayers) {
            for (element in layer.elements) {
                if (element.type == "ConsentLayerCategoryElement") {
                    element.consentLayerCategories?.forEach { category ->
                        if (category.alwaysOn) {
                            essentialKeys.add(category.gtmKey)
                        }
                    }
                }
            }
        }

        return essentialKeys
    }

    // MARK: - Retry

    /**
     * Retry any pending API requests
     * @return Pair of (successCount, failureCount)
     */
    suspend fun retryPendingRequests(): Pair<Int, Int> {
        return consentService.retryPendingRequests()
    }

    // MARK: - Reset

    /**
     * Clear all consent data
     */
    fun reset() {
        storage.clearAll()
        currentConfig = null
    }
}
