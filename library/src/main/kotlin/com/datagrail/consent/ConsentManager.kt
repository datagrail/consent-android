package com.datagrail.consent

import com.datagrail.consent.models.CategoryConsent
import com.datagrail.consent.models.ConsentConfig
import com.datagrail.consent.models.ConsentException
import com.datagrail.consent.models.ConsentPreferences
import com.datagrail.consent.models.SignalReconciliation
import com.datagrail.consent.models.SignatureProvider
import com.datagrail.consent.models.TrackingSignal
import com.datagrail.consent.models.UniversalConsentPreferences
import com.datagrail.consent.models.UniversalConsentRecord
import com.datagrail.consent.network.ConfigService
import com.datagrail.consent.network.ConsentService
import com.datagrail.consent.storage.ConsentStorage

/**
 * Manages consent state and coordinates between storage, network, and configuration
 */
internal class ConsentManager(
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

    // MARK: - Universal Consent

    /**
     * Whether universal (cross-device) consent is enabled for the loaded config.
     */
    fun isUniversalConsentEnabled(): Boolean {
        return currentConfig?.universalConsent?.enabled == true
    }

    /**
     * Gate every universal-consent entry point on one predicate. Fails fast with
     * [ConsentException.NotInitialized] when there is no config and
     * [ConsentException.ValidationError] when universal consent is not both enabled and configured
     * (see [ConsentConfig.universalConsentReady]), so the network layer never has to re-discover a
     * missing `consentProjectId` mid-write.
     */
    private fun requireUniversalConsentReady(): ConsentConfig {
        val config = currentConfig ?: throw ConsentException.NotInitialized()
        if (!config.universalConsentReady) {
            throw ConsentException.ValidationError("Universal consent is not enabled for this configuration")
        }
        return config
    }

    /**
     * The single source of truth for signal reconciliation on a fetched record.
     *
     * Both [fetchUniversalConsent] (which hands the reconciled record back to a caller) and
     * [rehydrateReturningRawPreferences] (which persists the reconciled view to local storage)
     * MUST produce a byte-identical reconciled map for the same record — one drives what a caller
     * acts on directly, the other drives [isCategoryEnabled] / [needsConsent]. Keeping the suppress
     * predicate (`record.gpc || trackingSignal.suppressesNonEssential`) and the essential-key set in
     * one place stops the two paths from silently disagreeing about the effective consent state.
     */
    private fun reconciledCookieOptions(
        record: UniversalConsentRecord,
        trackingSignal: TrackingSignal,
    ): Map<String, Boolean> =
        SignalReconciliation.reconcile(
            cookieOptions = record.consentPreferences?.cookieOptions ?: emptyMap(),
            // Either signal suppresses. The stored `gpc` came from the web, the tracking signal
            // from this device; the most privacy-protective of the two wins, and neither can
            // re-enable what the other suppressed.
            suppress = record.gpc || trackingSignal.suppressesNonEssential,
            essentialKeys = getEssentialCategories().toSet(),
        )

    /**
     * Fetch the user's universal consent record and reconcile opt-out signals on-device.
     *
     * The server returns raw, unreconciled data. This method applies mandatory client-side
     * reconciliation before returning: when an opt-out signal applies, every non-essential
     * category in the cookieOptions map is forced to `false` regardless of the stored value.
     * Essential/always-on categories are preserved.
     *
     * Two signals are considered, and the more privacy-protective wins:
     * - the record's stored `gpc`, recorded on the web where GPC exists; and
     * - [trackingSignal], this device's live ad-tracking signal.
     *
     * Requires universal consent to be enabled in the loaded config; throws
     * [ConsentException.ValidationError] otherwise.
     *
     * @param trackingSignal This device's live signal. Read from the OS by the caller (the
     *   public API does this for you) so this method never blocks on a binder call.
     * @return the record with reconciled cookieOptions, or null if none exists.
     */
    suspend fun fetchUniversalConsent(
        identifier: String,
        apiKey: String,
        trackingSignal: TrackingSignal = TrackingSignal.NOT_DETERMINED,
    ): UniversalConsentRecord? {
        val config = requireUniversalConsentReady()
        val record = consentService.getUniversalConsent(config, identifier, apiKey) ?: return null

        val prefs = record.consentPreferences ?: return record
        return record.copy(
            consentPreferences = prefs.copy(cookieOptions = reconciledCookieOptions(record, trackingSignal)),
        )
    }

    /**
     * Rehydrate local consent state from the universal consent store.
     *
     * [fetchUniversalConsent] reconciles a record and hands it back, but returning it is not the
     * same as applying it: nothing else in the SDK reads that return value, so on its own the
     * stored consent stays invisible to [needsConsent], [getCategories] and [isCategoryEnabled].
     * This method persists the effective state, which is what makes a web opt-in actually stop
     * the banner from re-prompting a user who already answered on another device.
     *
     * A read MISS writes nothing. "No record" is the absence of a signal, not a denial, so
     * persisting an empty record would both fabricate a choice the user never made and suppress
     * the banner that should collect it.
     *
     * @param identifier The user identifier. Normalized (Unicode NFC → trim → lowercase) before
     *   hashing, per the canonical cross-SDK contract.
     * @param apiKey The customer's DataGrail API key.
     * @param trackingSignal This device's live signal. Read from the OS by the caller (the public
     *   API does this for you) so this method never blocks on a binder call.
     * @return true when local state was rehydrated from a stored record, false on a miss.
     */
    suspend fun rehydrateFromUniversalConsent(
        identifier: String,
        apiKey: String,
        trackingSignal: TrackingSignal = TrackingSignal.NOT_DETERMINED,
    ): Boolean = rehydrateReturningRawPreferences(identifier, apiKey, trackingSignal) != null

    /**
     * Rehydrate, and hand back the RAW preferences from the stored record.
     *
     * Same behaviour as [rehydrateFromUniversalConsent], except the return value carries the
     * record's raw preferences (or null on a miss) rather than a Boolean. The read-then-write entry
     * point needs this: rehydration deliberately persists the RECONCILED view locally, so a write
     * that sourced its payload from [getCategories] afterwards would read that suppression back and
     * store it in the universal record as though the user had chosen it. Returning the raw
     * preferences lets the write carry what the user actually consented to.
     *
     * @return the record's raw preferences, or null on a miss.
     */
    suspend fun rehydrateReturningRawPreferences(
        identifier: String,
        apiKey: String,
        trackingSignal: TrackingSignal = TrackingSignal.NOT_DETERMINED,
    ): ConsentPreferences? {
        val config = requireUniversalConsentReady()

        // Goes to the service directly rather than through fetchUniversalConsent, which returns an
        // already-reconciled record. Both views are needed here: the reconciled one to persist
        // locally, the raw one to hand back for the write.
        val record = consentService.getUniversalConsent(config, identifier, apiKey) ?: return null
        val rawCookieOptions = record.consentPreferences?.cookieOptions
        if (rawCookieOptions.isNullOrEmpty()) return null

        // A record that came back at all represents an answered prompt, so the rehydrated state is
        // customised even if the writer left the flag false. needsConsent() keys off stored
        // preferences existing, and a non-customised record would re-prompt a user who already
        // answered.
        val raw =
            ConsentPreferences(
                isCustomised = true,
                cookieOptions = rawCookieOptions.map { (gtmKey, isEnabled) -> CategoryConsent(gtmKey, isEnabled) },
            )

        // Local state gets the RECONCILED view. Shares the exact reconciliation policy with
        // fetchUniversalConsent via reconciledCookieOptions, so the persisted state and the
        // returned record can never disagree about what a signal suppresses.
        val reconciled = reconciledCookieOptions(record, trackingSignal)
        storage.savePreferences(
            ConsentPreferences(
                isCustomised = true,
                cookieOptions = reconciled.map { (gtmKey, isEnabled) -> CategoryConsent(gtmKey, isEnabled) },
            ),
        )
        // Stamp the CURRENT config version, not the record's. This marks the rehydrated consent
        // as current for the config this app is running, which is what needsConsent() compares
        // against; carrying over a stale version from the writing device would re-prompt
        // immediately and undo the rehydration we just did.
        currentConfig?.version?.let { storage.saveConfigVersion(it) }
        return raw
    }

    /**
     * Write the current effective consent preferences to the universal consent store for the
     * given user identifier. This performs an immediate one-shot write; the identifier is NOT
     * retained as manager state, so subsequent operations (e.g. [fetchUniversalConsent]) must
     * pass the identifier again.
     *
     * Writes the RAW preferences. The tracking signal is deliberately NOT applied here: the store
     * holds raw choices and the server never merges, so suppressing before a write would persist
     * this device's transient signal as the user's choice — permanently, for every device on this
     * identifier. Limit-ad-tracking is an ad-personalization answer, not a marketing opt-out:
     * someone who opted in on the web and then opens the app with ad tracking limited must not have
     * that opt-in erased. Suppression is a read-time view (see [SignalReconciliation], applied by
     * [fetchUniversalConsent] and [rehydrateReturningRawPreferences]).
     *
     * Requires universal consent to be enabled in the loaded config; throws
     * [ConsentException.ValidationError] otherwise.
     *
     * @param identifier The user identifier. Normalized (Unicode NFC → trim → lowercase)
     *   before hashing, per the canonical cross-SDK contract.
     * @param apiKey The customer's DataGrail API key.
     * @param preferences The preferences to write; defaults to the current stored preferences.
     *   Callers that just rehydrated MUST pass the raw record explicitly, since rehydration
     *   persists the reconciled view locally.
     * @param getSignature Customer-provided signature provider (calls their backend), or null for
     *   a limited-mode (API-key-only) write with no signature/timestamp/nonce headers.
     */
    suspend fun setUserIdentifier(
        identifier: String,
        apiKey: String,
        preferences: ConsentPreferences? = null,
        getSignature: SignatureProvider? = null,
    ) {
        val config = requireUniversalConsentReady()

        val current = preferences ?: getCategories() ?: getDefaultPreferences()
        val rawMap =
            current?.cookieOptions?.associate { it.gtmKey to it.isEnabled } ?: emptyMap()

        val universalPrefs =
            UniversalConsentPreferences(
                isCustomised = current?.isCustomised ?: false,
                cookieOptions = rawMap,
            )

        consentService.saveUniversalConsent(
            config = config,
            identifier = identifier,
            preferences = universalPrefs,
            apiKey = apiKey,
            // NOT derived from the tracking signal. `ccpa_optout` records a CCPA/US do-not-sell
            // choice; the device ad-tracking signal is a narrower ad-personalization signal, and
            // treating one as the other would write a legal opt-out the user never made. Until
            // the mobile signal-to-opt-out rule is ratified, Android has no source for this
            // value, so it stays false and `syncOptout` writes nothing.
            ccpaOptout = false,
            getSignature = getSignature,
        )
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

    /**
     * Reset the unique tracking identifier
     */
    fun resetIdentifier() {
        storage.resetIdentifier()
    }
}
