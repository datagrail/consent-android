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
    fun getDefaultPreferences(): ConsentPreferences? = currentConfig?.let { defaultPreferences(it) }

    /**
     * Default preferences for a SPECIFIC config snapshot. The universal-consent write path passes
     * the snapshot it captured before its suspending network call rather than re-reading live
     * [currentConfig], which a concurrent [loadConfig] could have swapped while the GET was
     * suspended — that would derive the write's cookieOptions from a different config than the
     * `config_version` stamped on the same request (a torn read).
     */
    private fun defaultPreferences(config: ConsentConfig): ConsentPreferences =
        ConsentPreferences(
            isCustomised = false,
            cookieOptions =
                config.initialCategories.initial.map { category ->
                    CategoryConsent(gtmKey = category, isEnabled = true)
                },
        )

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
    fun getEssentialCategories(): List<String> = currentConfig?.let { essentialCategories(it) } ?: emptyList()

    /**
     * Essential/always-on category keys for a SPECIFIC config.
     *
     * The universal-consent read paths pass the config snapshot they captured before their
     * suspending network call, rather than re-reading [currentConfig]. A concurrent [loadConfig]
     * replacing [currentConfig] while the network GET is suspended would otherwise let
     * reconciliation read one config's essential set against another config's record — a torn read.
     */
    private fun essentialCategories(config: ConsentConfig): List<String> {
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
     * Whether universal (cross-device) consent is enabled AND fully configured for the loaded
     * config. Delegates to the same [ConsentConfig.universalConsentReady] predicate that
     * [requireUniversalConsentReady] gates every entry point on, so this "is it usable" answer can
     * never disagree with what a subsequent setUserIdentifier/fetch will actually accept — a caller
     * (e.g. UI deciding whether to show a "link your account" affordance) that trusts this and then
     * calls in would otherwise hit a ValidationError for a missing/blank consentProjectId.
     */
    fun isUniversalConsentEnabled(): Boolean {
        return currentConfig?.universalConsentReady == true
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
        config: ConsentConfig,
    ): Map<String, Boolean> {
        // Essential-key backfill (a config's always-on category absent from — or stored false in —
        // the cross-device record must still read enabled) is owned by SignalReconciliation.reconcile
        // itself, so every caller of reconcile inherits it and the guarantee cannot drift a layer away.
        return SignalReconciliation.reconcile(
            cookieOptions = record.consentPreferences?.cookieOptions ?: emptyMap(),
            // Either signal suppresses. The stored `gpc` came from the web, the tracking signal
            // from this device; the most privacy-protective of the two wins, and neither can
            // re-enable what the other suppressed.
            suppress = record.gpc || trackingSignal.suppressesNonEssential,
            essentialKeys = essentialCategories(config).toSet(),
        )
    }

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
            consentPreferences = prefs.copy(cookieOptions = reconciledCookieOptions(record, trackingSignal, config)),
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
     * @param config Optional config snapshot to validate/read against. [setUserIdentifier] passes
     *   the snapshot it captured at entry so the READ here and the WRITE that follows use ONE
     *   config — otherwise this would re-read [currentConfig] independently and a concurrent
     *   [loadConfig] could make the read and write disagree on consentProjectId/version. Other
     *   callers omit it and get a fresh [requireUniversalConsentReady].
     * @return the record's raw preferences, or null on a miss.
     */
    suspend fun rehydrateReturningRawPreferences(
        identifier: String,
        apiKey: String,
        trackingSignal: TrackingSignal = TrackingSignal.NOT_DETERMINED,
        config: ConsentConfig = requireUniversalConsentReady(),
    ): ConsentPreferences? {

        // Goes to the service directly rather than through fetchUniversalConsent, which returns an
        // already-reconciled record. Both views are needed here: the reconciled one to persist
        // locally, the raw one to hand back for the write.
        val record = consentService.getUniversalConsent(config, identifier, apiKey) ?: return null
        val rawCookieOptions = record.consentPreferences?.cookieOptions
        if (rawCookieOptions.isNullOrEmpty()) return null

        // The RAW preferences are handed back for the WRITE, so they carry the record's OWN
        // isCustomised flag verbatim. setUserIdentifier POSTs this value straight back as the
        // record's isCustomised; forcing it true here would flip a record written unbannered
        // (isCustomised=false, e.g. a default state synced from web) to true on every device that
        // opens the app, even though the user made no new choice. The local-storage forcing below
        // must not leak onto the wire.
        val raw =
            ConsentPreferences(
                isCustomised = record.consentPreferences?.isCustomised ?: false,
                cookieOptions = rawCookieOptions.map { (gtmKey, isEnabled) -> CategoryConsent(gtmKey, isEnabled) },
            )

        // Local state gets the RECONCILED view. Shares the exact reconciliation policy with
        // fetchUniversalConsent via reconciledCookieOptions, so the persisted state and the
        // returned record can never disagree about what a signal suppresses.
        //
        // isCustomised is forced true for the LOCAL copy ONLY: a record that came back at all
        // represents an answered prompt, and needsConsent() keys off stored preferences existing —
        // a non-customised local copy would re-prompt a user who already answered elsewhere. This
        // is local-storage correctness and deliberately does not travel back to the server (see the
        // raw preferences above).
        val reconciled = reconciledCookieOptions(record, trackingSignal, config)
        storage.savePreferences(
            ConsentPreferences(
                isCustomised = true,
                cookieOptions = reconciled.map { (gtmKey, isEnabled) -> CategoryConsent(gtmKey, isEnabled) },
            ),
        )
        // Stamp the version of the config snapshot this request was validated against — not a fresh
        // read of currentConfig, which a concurrent loadConfig() could have swapped while the
        // network GET was suspended. This marks the rehydrated consent as current for the config
        // this app is running, which is what needsConsent() compares against; carrying over a stale
        // version from the writing device would re-prompt immediately and undo the rehydration.
        storage.saveConfigVersion(config.version)
        return raw
    }

    /**
     * Associate a user identifier with their cross-device consent, READING then WRITING the
     * universal consent store in one coordinated operation. This is the headline invariant of the
     * feature and it lives here, in the coordinator, not in the public singleton adapter.
     *
     * READ first: [rehydrateReturningRawPreferences] pulls any existing record and applies it to
     * local state, so a choice the same person made on the web or another device is honored here.
     *
     * WRITE-THROUGH / sync-on-change: the write carries the user's CURRENT LOCAL choice — captured
     * from storage BEFORE the rehydrate persists the reconciled view — and NEVER re-POSTs the record
     * it just fetched. Echoing the fetched record back would discard a choice the user made on THIS
     * device before associating their identity (the launch-blocking "lose an explicit opt-out" /
     * revocation-resurrection class) while only telling the edge what it already holds. When a FOUND
     * record meets no genuine local change — a fresh install that merely adopted it — the call
     * ADOPTS WITHOUT POSTING: the record is already applied to local state, so there is nothing to
     * write. Cross-device conflict resolution is the edge's job, not the SDK's.
     *
     * A genuine local choice is distinguished from initialize()'s defaults by the presence of a
     * stored record ([ConsentStorage.loadPreferences] non-null — the same read [hasUserConsent]
     * keys off); initialize() never seeds storage, so no auto-persisted default is mistaken for a
     * user choice. The choice is captured BEFORE rehydrate so no ATT/GPC-suppressed view leaks into
     * the write body — the store holds raw choices and the server never merges, so suppressing
     * before a write would persist this device's transient signal for every device on this
     * identifier. Limit-ad-tracking is an ad-personalization answer, not a marketing opt-out.
     * Suppression stays a read-time view (see [SignalReconciliation]).
     *
     * A read MISS (no record) writes: local state seeds the first cross-device record. A read
     * FAILURE, by contrast, THROWS and blocks the write — the server never merges, so overwriting a
     * record we could not read would silently erase the user's real cross-device choice (the
     * TRUST-2491 corruption class). The caller can retry, which re-reads first. Coroutine
     * cancellation propagates so structured concurrency is preserved.
     *
     * The identifier is NOT retained as manager state, so subsequent operations (e.g.
     * [fetchUniversalConsent]) must pass it again. Requires universal consent to be enabled AND
     * configured; throws [ConsentException.ValidationError]/[ConsentException.NotInitialized]
     * otherwise, before any request reaches the service.
     *
     * @param identifier The user identifier. Normalized (Unicode NFC → trim → lowercase)
     *   before hashing, per the canonical cross-SDK contract.
     * @param apiKey The customer's DataGrail API key.
     * @param trackingSignal This device's live signal, applied only to the LOCAL read/rehydration.
     *   Read from the OS by the public adapter; defaults to [TrackingSignal.NOT_DETERMINED].
     * @param getSignature Customer-provided signature provider (calls their backend), or null for
     *   a limited-mode (API-key-only) write with no signature/timestamp/nonce headers.
     * @param onRehydrated Invoked with the effective local preferences when — and only when — a
     *   record was rehydrated, so the adapter can notify its consent-changed listener.
     */
    suspend fun setUserIdentifier(
        identifier: String,
        apiKey: String,
        trackingSignal: TrackingSignal = TrackingSignal.NOT_DETERMINED,
        getSignature: SignatureProvider? = null,
        onRehydrated: ((ConsentPreferences) -> Unit)? = null,
    ) {
        val config = requireUniversalConsentReady()

        // Capture the user's RAW local choice BEFORE the rehydrate below persists the
        // signal-reconciled view. null means no genuine local choice yet: initialize() never seeds
        // storage (the same read [hasUserConsent] keys off), so a non-null value here is an explicit
        // choice, and capturing it now keeps any ATT/GPC-suppressed view out of the write body.
        val localChoice = storage.loadPreferences()

        // READ then WRITE. Rehydrate applies any found record to local state first (honoring a
        // choice made on the web or another device). A genuine MISS returns null and the write
        // below seeds the first record; a read FAILURE THROWS and blocks the write — overwriting a
        // record we could not read would silently erase the user's real cross-device choice
        // (the TRUST-2491 corruption class). Coroutine cancellation propagates for the same reason.
        // Pass the captured `config` snapshot so the READ below and the WRITE that follows are
        // validated and hashed against ONE config — a concurrent loadConfig() cannot make the read's
        // consentProjectId/version disagree with the write's (the torn-read hazard this file guards
        // against elsewhere via essentialCategories(config)/reconciledCookieOptions).
        val rawFromRecord = rehydrateReturningRawPreferences(identifier, apiKey, trackingSignal, config)
        if (rawFromRecord != null) {
            getCategories()?.let { onRehydrated?.invoke(it) }
        }

        // Adopt-without-POST: a FOUND record with no genuine local change is already applied to
        // local state by the rehydrate above. Re-POSTing it would only echo state the edge already
        // holds. Conflict resolution across devices is the edge's job, not the SDK's.
        if (rawFromRecord != null && localChoice == null) {
            return
        }

        // Write-through the user's CURRENT LOCAL choice (sync-on-change) — NEVER the fetched
        // record, which would discard a choice the user made on this device before associating
        // their identity. The choice was captured raw, before rehydrate, so no device signal leaks
        // onto the wire. On a genuine miss with no explicit choice this seeds the first record from
        // the `config` SNAPSHOT's defaults — not getCategories()/getDefaultPreferences(), which
        // re-read live currentConfig (a torn read against a concurrent loadConfig, and a stale
        // signal-suppressed local map would leak into the write) and whose extra fallback tier was
        // dead anyway (getCategories() already falls back to the defaults when storage is empty).
        val current = localChoice ?: defaultPreferences(config)
        val rawMap = current.cookieOptions.associate { it.gtmKey to it.isEnabled }

        val universalPrefs =
            UniversalConsentPreferences(
                isCustomised = current.isCustomised,
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
