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
import com.datagrail.consent.models.essentialCategoryKeys
import com.datagrail.consent.network.ConfigService
import com.datagrail.consent.network.ConsentService
import com.datagrail.consent.storage.ConsentStorage
import com.datagrail.consent.storage.UniversalConsentSyncState
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages consent state and coordinates between storage, network, and configuration
 */
internal class ConsentManager(
    private val storage: ConsentStorage,
    private val configService: ConfigService,
    private val consentService: ConsentService,
) {
    internal var currentConfig: ConsentConfig? = null

    // Serializes identity transitions against each other. setUserIdentifier suspends on the
    // network read/write, and clearUserIdentifier()/reset() run synchronously on the caller's
    // thread; without this, a logout that lands while a setUserIdentifier is in flight would be
    // undone when that call's continuation resumes and rebinds the old identity (and rehydrates
    // its preferences). Each logout/reset supersedes any in-flight setUserIdentifier, which then
    // aborts its remaining storage mutations. Mirrors the initGeneration guard in DataGrailConsent.
    private val identityOperationGeneration = AtomicInteger(0)

    // Serializes setUserIdentifier calls against EACH OTHER. The generation token above only lets a
    // logout/reset supersede an in-flight login; two overlapping setUserIdentifier calls (a fast
    // account switch, a retry overlapping a still-in-flight call) would otherwise each read the
    // bound hash and local choice from the same stale snapshot and interleave their writes at the
    // network suspension points, leaving the device bound to one identity while holding the other's
    // adopted/replaced preferences. The lock (fair, FIFO) makes them run to completion in call order.
    private val setUserIdentifierMutex = Mutex()

    // Bumped on every host CCPA choice (setCcpaOptout). setUserIdentifier holds the mutex across its
    // network read, but the setter persists the flag before it waits for the lock, so a setter call
    // can land mid-login. A login captures this at entry and, if it changed, leaves the newer choice
    // alone instead of adopting or clearing over it (TRUST-2591).
    private val ccpaChoiceRevision = AtomicInteger(0)

    // Bumped on every user consent choice saved through savePreferences, which also clears the
    // persisted sync state. A sync records what it left as synced only if this has not moved since
    // the sync last wrote local state (or since it started, when it wrote none): a save made while
    // the read or the write was in flight is still a user change the next re-sync writes
    // (TRUST-2902, TRUST-3074).
    private val preferencesSaveRevision = AtomicInteger(0)

    // Held across a user save (its storage write, the revision bump and the sync-state clear) and
    // across a sync's or rehydrate's local persist (and the revision it reads right after), so the two
    // never interleave: the persist either precedes the save or follows it. Also held across a host
    // CCPA choice (the flag, the ccpaChoiceRevision bump and the pending marker, see persistCcpaChoice)
    // and across every revision check that adopts, clears or un-marks the flag on a sync's behalf, so
    // the check and the write it guards see the setter's call either entirely before or entirely
    // after. Never held across a suspension point or a host callback.
    private val localChoiceLock = Any()

    init {
        // The persisted sync state only describes the identity it was recorded for.
        val state = storage.loadSyncState()
        if (state != null && state.userHash != storage.loadBoundUserHash()) {
            storage.clearSyncState()
        }
    }

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
     * Default preferences for a SPECIFIC config snapshot. The universal-consent write path never
     * seeds these (TRUST-2902: only an explicit local choice is written); they are the local
     * fallback read by [getDefaultPreferences] and the backfill for categories a login-adopted
     * record omits ([adoptOnLogin]).
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
            // Save locally. The user's own choice now replaces what the last sync left: the next
            // re-sync writes it, and a setCcpaOptout write-through sends it rather than the record's
            // raw choice.
            synchronized(localChoiceLock) {
                storage.savePreferences(preferences)
                storage.saveConfigVersion(config.version)
                preferencesSaveRevision.incrementAndGet()
                storage.clearSyncState()
            }

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
     * Delegates to [ConsentConfig.essentialCategoryKeys] — the single definition of "essential"
     * shared with [com.datagrail.consent.ui.BannerDialog] so the two never disagree about which
     * categories are always-on/hidden versus reconciled as suppressible.
     *
     * The universal-consent read paths pass the config snapshot they captured before their
     * suspending network call, rather than re-reading [currentConfig]. A concurrent [loadConfig]
     * replacing [currentConfig] while the network GET is suspended would otherwise let
     * reconciliation read one config's essential set against another config's record — a torn read.
     */
    private fun essentialCategories(config: ConsentConfig): List<String> = config.essentialCategoryKeys().toList()

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
     * Resolve the edge API key for a Universal Consent call (TRUST-2603): an explicit value passed
     * by the host wins (existing integrations behave exactly as before); otherwise fall back to
     * `universalConsent.apiKey` from config.json, which lets the key rotate server-side with no
     * client release. An empty string counts as absent at either source. Throws
     * [ConsentException.ValidationError] when neither is present, so the write is never attempted
     * with a key the edge cannot resolve.
     */
    private fun resolveUniversalConsentApiKey(config: ConsentConfig, explicit: String?): String {
        val resolved = explicit?.takeIf { it.isNotEmpty() }
            ?: config.universalConsent?.apiKey?.takeIf { it.isNotEmpty() }
        return resolved ?: throw ConsentException.ValidationError(
            "A Universal Consent API key is required: pass apiKey or set universalConsent.apiKey in config.json",
        )
    }

    /**
     * The single source of truth for signal reconciliation on a fetched record.
     *
     * Both [fetchUniversalConsent] (which hands the reconciled record back to a caller) and
     * [rehydrate] (which persists the reconciled view to local storage)
     * MUST produce a byte-identical reconciled map for the same record — one drives what a caller
     * acts on directly, the other drives [isCategoryEnabled] / [needsConsent]. Keeping the suppress
     * predicate (`record.gpc || trackingSignal.suppressesNonEssential`) and the essential-key set in
     * one place stops the two paths from silently disagreeing about the effective consent state.
     *
     * @param cookieOptions The map to reconcile; defaults to the record's own. The login-replace
     *   path passes the record's map layered over the config defaults (see [adoptOnLogin]) so the
     *   backfilled categories go through the same suppress predicate.
     */
    private fun reconciledCookieOptions(
        record: UniversalConsentRecord,
        trackingSignal: TrackingSignal,
        config: ConsentConfig,
        cookieOptions: Map<String, Boolean> = record.consentPreferences?.cookieOptions ?: emptyMap(),
    ): Map<String, Boolean> {
        // Essential-key backfill (a config's always-on category absent from — or stored false in —
        // the cross-device record must still read enabled) is owned by SignalReconciliation.reconcile
        // itself, so every caller of reconcile inherits it and the guarantee cannot drift a layer away.
        return SignalReconciliation.reconcile(
            cookieOptions = cookieOptions,
            // Either signal suppresses. The stored `gpc` came from the web, the tracking signal
            // from this device; the most privacy-protective of the two wins, and neither can
            // re-enable what the other suppressed.
            suppress = record.gpc || trackingSignal.suppressesNonEssential,
            essentialKeys = config.essentialCategoryKeys(),
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
        apiKey: String?,
        trackingSignal: TrackingSignal = TrackingSignal.NOT_DETERMINED,
    ): UniversalConsentRecord? {
        val config = requireUniversalConsentReady()
        val resolvedApiKey = resolveUniversalConsentApiKey(config, apiKey)
        val record = consentService.getUniversalConsent(config, identifier, resolvedApiKey) ?: return null

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
     * @param apiKey The customer's edge API key, or `null` to use `universalConsent.apiKey` from config.json (TRUST-2603); an explicit value wins.
     * When [identifier] is the identity the device is bound to, the persisted sync state is updated
     * the same way a [setUserIdentifier] adopt updates it: the stored view becomes what the last sync
     * left, and the record's RAW choice what the record holds. So a later re-sync or [setCcpaOptout]
     * never mistakes the GPC/ad-tracking/`ccpa_optout`-suppressed view this stores for a user change
     * and writes it over the user's cross-device opt-ins. For any other identity the sync state is
     * cleared. A setter call made during the read is newer than the record and is kept.
     *
     * @param trackingSignal This device's live signal. Read from the OS by the caller (the public
     *   API does this for you) so this method never blocks on a binder call.
     * @return true when local state was rehydrated from a stored record, false on a miss.
     */
    suspend fun rehydrateFromUniversalConsent(
        identifier: String,
        apiKey: String?,
        trackingSignal: TrackingSignal = TrackingSignal.NOT_DETERMINED,
    ): Boolean {
        // A clearUserIdentifier()/reset() that lands during the read must not have the previous
        // identity's state (its CCPA opt-out included) written back onto the logged-out device.
        val opGeneration = identityOperationGeneration.get()
        // A setCcpaOptout made during the read is newer than the record's value.
        val ccpaRevision = ccpaChoiceRevision.get()
        val config = requireUniversalConsentReady()
        val resolvedApiKey = resolveUniversalConsentApiKey(config, apiKey)
        return rehydrate(
            identifier,
            resolvedApiKey,
            trackingSignal,
            config,
            opGeneration = opGeneration,
            ccpaRevision = ccpaRevision,
            recordsSyncState = true,
        ) != null
    }

    /**
     * What [rehydrate] persisted: the record's RAW choice, and the user-save revision read right
     * after the reconciled view was stored (the baseline a sync checks for a later user save).
     */
    private class Rehydrated(
        val record: UniversalConsentRecord,
        val raw: ConsentPreferences,
        val saveRevision: Int?,
    )

    /**
     * Rehydrate local state from the stored record, and hand back the record's RAW preferences with
     * the save revision read right after the persist.
     *
     * Rehydration deliberately persists the RECONCILED view locally, so a write that sourced its
     * payload from [getCategories] afterwards would read that suppression back and store it in the
     * universal record as though the user had chosen it. Returning the raw preferences lets the
     * caller record and write what the user actually consented to.
     *
     * @param config The config snapshot to validate/read against. [setUserIdentifier] passes the
     *   snapshot it captured at entry so the READ here and the WRITE that follows use ONE config.
     * @param adoptCcpaOptout Whether a found record's `ccpa_optout` replaces the local flag
     *   (TRUST-2591: the record is authoritative for the stored choice), applied only when
     *   `universalConsent.syncOptout` is on. [setUserIdentifier] passes false on a re-sync that is
     *   about to write the local flag through, so a failed write cannot drop the user's newer local
     *   choice. The adopt is also skipped while a bound CCPA choice has not reached the record yet
     *   ([ConsentStorage.loadCcpaWritePending]), or when [ccpaRevision] is given and a setter call
     *   landed since it was captured.
     * @param opGeneration When given, the identity-operation generation captured before the read. If
     *   a logout/reset bumped it during the read, nothing is persisted and this returns null.
     * @param ccpaRevision When given, the CCPA choice revision captured before the read.
     * @param persistsLocal False when the caller will write its own local choice through right after:
     *   that choice is newer, so the record's view is not stored over it (and no save revision is
     *   reported). The caller stores the view of its own choice once the write lands.
     * @param recordsSyncState For the standalone rehydrate, which has no later bind step: record the
     *   sync state for the bound identity itself, see [rehydrateFromUniversalConsent].
     * @return the record's raw preferences, or null on a miss.
     */
    private suspend fun rehydrate(
        identifier: String,
        apiKey: String,
        trackingSignal: TrackingSignal,
        config: ConsentConfig,
        adoptCcpaOptout: Boolean = true,
        opGeneration: Int? = null,
        ccpaRevision: Int? = null,
        persistsLocal: Boolean = true,
        recordsSyncState: Boolean = false,
    ): Rehydrated? {

        // Goes to the service directly rather than through fetchUniversalConsent, which returns an
        // already-reconciled record. Both views are needed here: the reconciled one to persist
        // locally, the raw one to hand back for the write.
        val record = consentService.getUniversalConsent(config, identifier, apiKey) ?: return null
        // Superseded by a logout/reset during the read: persist nothing for the identity just left.
        if (opGeneration != null && identityOperationGeneration.get() != opGeneration) return null
        // ABSENT consent_preferences is signal-only, a miss. A PRESENT block is an answered choice
        // even when its cookieOptions map is empty (essential-only): TRUST-2961 — only a null block,
        // not an empty map, is "no choice". The predicate is the block itself (not cookieOptions,
        // which is a non-null Map defaulting to {} and so can only be null when the block is null),
        // the SAME check the login `when` branch uses, so the two absent-vs-present-empty call sites
        // read as one check. The empty-map case falls through and rehydrates (isCustomised is forced
        // true locally below, which is what stops the banner re-prompting a user who already answered
        // elsewhere).

        // Adopt the record's CCPA choice the moment a record is found — BEFORE the empty-preferences
        // early return below. A signal-only record (ccpa_optout set, no consent_preferences, e.g. a
        // web DNSMPI opt-out made before any category choice) still carries an authoritative opt-out
        // that must replace the local flag, matching the LOGIN path (adoptOnLogin's signal-only
        // branch) and this function's documented invariant ("a found record's ccpa_optout replaces
        // the local flag"). The early return only governs whether there are raw category preferences
        // to hand back for the WRITE; it must not suppress the opt-out adopt. This is the RE-SYNC (and
        // standalone rehydrate) adopt, so it is gated: on the adoptCcpaOptout flag and on syncOptout,
        // since with the gate off the SDK never puts the choice on the record and adopting it would
        // erase a local-only setCcpaOptout(true). The LOGIN adopt in setUserIdentifier is ungated.
        // Same rule as the web/iOS/React Native SDKs.
        // A bound choice that never reached the record (no category choice to write with, or a failed
        // write), or one made during this read, is newer than the record's value and is kept.
        // Checked and adopted under the lock a setter call holds, so a setter that has stored its flag
        // but not yet bumped the revision cannot have that flag overwritten here.
        // A standalone rehydrate may read an identity the device is not bound to; that identity's flag
        // would otherwise be sent by the bound identity's next write. (A re-sync is bound by definition.)
        val standaloneUserHash = if (recordsSyncState) userHashOrNull(config, identifier) else null
        synchronized(localChoiceLock) {
            val keepsLocalCcpa =
                storage.loadCcpaWritePending() || (ccpaRevision != null && ccpaChoiceRevision.get() != ccpaRevision)
            val boundHash = storage.loadBoundUserHash()
            val readsBoundOrUnbound = !recordsSyncState || boundHash == null || boundHash == standaloneUserHash
            if (adoptCcpaOptout && !keepsLocalCcpa && readsBoundOrUnbound &&
                config.universalConsent?.syncOptout == true
            ) {
                storage.saveCcpaOptout(record.ccpaOptout)
            }
        }

        val raw = rawPreferences(record) ?: return null
        if (!persistsLocal) return Rehydrated(record, raw, saveRevision = null)

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
        val effective =
            ConsentPreferences(
                isCustomised = true,
                cookieOptions = reconciled.map { (gtmKey, isEnabled) -> CategoryConsent(gtmKey, isEnabled) },
            )
        val saveRevision =
            synchronized(localChoiceLock) {
                storage.savePreferences(effective)
                // Stamp the version of the config snapshot this request was validated against — not a
                // fresh read of currentConfig, which a concurrent loadConfig() could have swapped while
                // the network GET was suspended. This marks the rehydrated consent as current for the
                // config this app is running, which is what needsConsent() compares against; carrying
                // over a stale version from the writing device would re-prompt immediately and undo
                // the rehydration.
                storage.saveConfigVersion(config.version)
                if (recordsSyncState) recordStandaloneSyncState(standaloneUserHash, effective, raw)
                preferencesSaveRevision.get()
            }
        // (The record's CCPA choice was already adopted above, before the empty-preferences early
        // return, so a signal-only record is covered too.)
        return Rehydrated(record, raw, saveRevision)
    }

    /**
     * The standalone rehydrate of the identity hashed to [userHash] (null when it could not be
     * hashed) just stored [syncedLocal], the reconciled view of a record whose RAW choice is
     * [syncedRaw]. For the bound identity that is exactly what an adopt by a sync leaves,
     * so it is recorded the same way; otherwise the stored view no longer describes the bound
     * identity's last sync, so its state is dropped. Runs under [localChoiceLock].
     */
    private fun recordStandaloneSyncState(
        userHash: String?,
        syncedLocal: ConsentPreferences,
        syncedRaw: ConsentPreferences,
    ) {
        if (userHash != null && userHash == storage.loadBoundUserHash()) {
            storage.saveSyncState(UniversalConsentSyncState(userHash, syncedLocal, syncedRaw))
        } else {
            storage.clearSyncState()
        }
    }

    /** The user hash for [identifier] under [config], or null when it cannot be computed. */
    private fun userHashOrNull(
        config: ConsentConfig,
        identifier: String,
    ): String? =
        runCatching {
            ConsentService.computeUserHash(config.dgCustomerId, requireNotNull(config.consentProjectId), identifier)
        }.getOrNull()

    /**
     * The record's RAW category choice, or null when it carries none (signal-only). This is the one
     * place a record's raw choice is derived; both [rehydrate] and the
     * [setUserIdentifier] login adopt use it, and it is what a UC session holds as `syncedRaw` for a
     * later [setCcpaOptout] write-through (TRUST-3074).
     *
     * It carries the record's OWN isCustomised flag verbatim, because a write POSTs it straight back:
     * forcing it true would flip a record written unbannered (isCustomised=false, e.g. a default state
     * synced from web) to true on every device that opens the app, even though the user made no new
     * choice. The local-storage forcing the adopt paths apply must not leak onto the wire.
     */
    private fun rawPreferences(record: UniversalConsentRecord): ConsentPreferences? =
        record.consentPreferences?.let { prefs ->
            ConsentPreferences(
                isCustomised = prefs.isCustomised,
                cookieOptions = prefs.cookieOptions.map { (gtmKey, isEnabled) -> CategoryConsent(gtmKey, isEnabled) },
            )
        }

    /**
     * Associate a user identifier with their cross-device consent, READING then WRITING the
     * universal consent store in one coordinated operation. This is the headline invariant of the
     * feature and it lives here, in the coordinator, not in the public singleton adapter.
     *
     * READ first: [rehydrate] pulls any existing record and applies it to
     * local state, so a choice the same person made on the web or another device is honored here.
     * A read FAILURE THROWS and blocks any write — the server never merges, so overwriting a record
     * we could not read would silently erase the user's real cross-device choice (the TRUST-2491
     * corruption class). The caller can retry, which re-reads first. Coroutine cancellation
     * propagates so structured concurrency is preserved.
     *
     * Any write carries the user's RAW local choice — captured from storage BEFORE the rehydrate
     * persists the reconciled view — and NEVER re-POSTs the record it just fetched. Capturing first
     * keeps any ATT/GPC-suppressed view out of the write body: the store holds raw choices and the
     * server never merges, so suppressing before a write would persist this device's transient
     * signal for every device on this identifier. Suppression stays a read-time view (see
     * [SignalReconciliation]).
     *
     * LOGIN vs RE-SYNC (TRUST-2902). The device persists the user hash (never the raw identifier) of
     * the identity it is bound to; it is set only here, only after the call succeeds, and cleared by
     * [clearUserIdentifier] and [reset].
     * - An EXPLICIT local choice is a stored record ([ConsentStorage.loadPreferences] non-null — the
     *   same read [hasUserConsent] keys off; initialize() never seeds storage, only a user save or a
     *   rehydrate writes it) on a device NOT bound to a different identity. When another identity
     *   is still bound (the host skipped logout), the stored state is that user's — possibly their
     *   rehydrated record — and is not explicit for this one.
     * - LOGIN (unbound, or bound to a different identity): a FOUND record wins and nothing is
     *   written, even over an explicit local choice. A record carrying a choice REPLACES local state
     *   (never merges): each category it carries takes its signal-reconciled value, every other
     *   category takes the config default (the same neutral value [clearUserIdentifier] gives it,
     *   never the prior local value), and essential stays on — see [adoptOnLogin]. A found record
     *   with NO choice — an ABSENT (null) consent_preferences block, signal-only — returns local
     *   state to NEUTRAL if anything is stored, else is a no-op. A PRESENT block whose cookieOptions
     *   map is empty is NOT no-choice: it is an answered essential-only choice and is adopted via the
     *   else branch (TRUST-2961); the null-vs-present distinction the decode preserves is what tells
     *   the two apart. On a genuine MISS only an explicit local choice is written (attached to the new
     *   identity). Otherwise nothing is written; if a different identity was bound and local state
     *   exists, it returns to NEUTRAL (the same local operation as [clearUserIdentifier], minus
     *   clearing the binding) so that user's state does not linger.
     * - RE-SYNC (bound to this identity): sync-on-change, decided on-device. With NO local change
     *   since the last sync (storage still holds what that sync left and no [savePreferences] since,
     *   as recorded in the persisted [UniversalConsentSyncState]) a found record is adopted and
     *   NOTHING is written, found, missing or signal-only alike: the edge stamps every write with its
     *   write time, so re-sending an unchanged choice would beat a newer one another device made.
     *   That state is persisted, so this holds after a process restart too, and a signal-suppressed
     *   view is never written back. After a local change the stored choice is written through; on a
     *   miss only an explicit local choice is written; config defaults are never seeded.
     * "Nothing written" still returns normally. The SDK cannot tell whether a pre-login choice was
     * made by the person now logging in or a previous user of a shared device (an explicit choice
     * on an unbound device is attached on a no-record login by design), does no heuristic
     * shared-device/shared-account detection, and cannot detect two people sharing one account.
     *
     * CCPA opt-out (TRUST-2591). Any write carries the RAW local [getCcpaOptout] flag, captured
     * before any adopt (or the newer value when the host set it while this call was suspended); the
     * service writes it only when `universalConsent.syncOptout` is on. A LOGIN that finds a record
     * (with or without a category choice) sets the local flag to the record's `ccpa_optout` whatever
     * the gate, dropping a pre-login local value, as the web and iOS SDKs do; a LOGIN away from a different identity with no
     * record clears it whatever the gate (it was that user's). Neither happens over a setter call
     * that landed while the read was in flight. A RE-SYNC that writes the local choice through keeps the
     * local flag (it is what the record now holds); one that only adopts takes the record's value
     * when `syncOptout` is on (with the gate off the record never carries the choice) unless a bound
     * choice is still pending. A ccpa-only local change is not an explicit category choice, so on its
     * own it never triggers a write.
     *
     * The raw identifier is kept in memory only, in the session [setCcpaOptout] writes through
     * with, and is cleared by [clearUserIdentifier] and [reset]; only its hash is persisted.
     * Subsequent operations (e.g. [fetchUniversalConsent]) must still pass it again. Requires universal consent to be enabled AND
     * configured; throws [ConsentException.ValidationError]/[ConsentException.NotInitialized]
     * otherwise, before any request reaches the service.
     *
     * @param identifier The user identifier. Normalized (Unicode NFC → trim → lowercase)
     *   before hashing, per the canonical cross-SDK contract.
     * @param apiKey The customer's edge API key, or `null` to use `universalConsent.apiKey` from config.json (TRUST-2603); an explicit value wins.
     * @param trackingSignal This device's live signal, applied only to the LOCAL read/rehydration.
     *   Read from the OS by the public adapter; defaults to [TrackingSignal.NOT_DETERMINED].
     * @param getSignature Customer-provided signature provider (calls their backend), or null for
     *   a limited-mode (API-key-only) write with no signature/timestamp/nonce headers.
     * @param onRehydrated Invoked with the effective local preferences exactly when local state was
     *   rewritten (a record adopted/replaced, or a return to neutral), never on a no-op, so the
     *   adapter can notify its consent-changed listener.
     */
    suspend fun setUserIdentifier(
        identifier: String,
        apiKey: String?,
        trackingSignal: TrackingSignal = TrackingSignal.NOT_DETERMINED,
        getSignature: SignatureProvider? = null,
        onRehydrated: ((ConsentPreferences) -> Unit)? = null,
    ) {
        // Token for this transition, captured BEFORE queueing on the lock: a clearUserIdentifier()/
        // reset() that lands while this call waits behind another setUserIdentifier, or while the
        // reads and writes below are suspended, bumps it, so the checks after each suspension abort
        // before rewriting local state or rebinding — a logout is never silently undone by a stale
        // login, including one that was still queued when the logout happened.
        val opGeneration = identityOperationGeneration.get()
        setUserIdentifierMutex.withLock {
            setUserIdentifierLocked(identifier, apiKey, trackingSignal, getSignature, onRehydrated, opGeneration)
        }
    }

    private suspend fun setUserIdentifierLocked(
        identifier: String,
        apiKey: String?,
        trackingSignal: TrackingSignal,
        getSignature: SignatureProvider?,
        onRehydrated: ((ConsentPreferences) -> Unit)?,
        opGeneration: Int,
    ) {
        // Superseded by a logout/reset while queued behind another setUserIdentifier.
        if (identityOperationGeneration.get() != opGeneration) return
        val config = requireUniversalConsentReady()
        val resolvedApiKey = resolveUniversalConsentApiKey(config, apiKey)

        // The same hash the service computes for the read/write (it also rejects an identifier that
        // is empty after normalization, before any request). Only the hash is ever persisted.
        val userHash =
            ConsentService.computeUserHash(
                config.dgCustomerId,
                requireNotNull(config.consentProjectId),
                identifier,
            )
        val boundHash = storage.loadBoundUserHash()
        val isResync = boundHash == userHash
        val boundToOther = boundHash != null && !isResync

        // Capture the user's RAW local choice BEFORE the rehydrate below persists the
        // signal-reconciled view, so no ATT/GPC-suppressed view reaches the write body. It is
        // EXPLICIT only when no other identity is bound (see the KDoc above).
        val localChoice = storage.loadPreferences()
        val explicitChoice = if (boundToOther) null else localChoice
        // The RAW local CCPA flag, captured before any adopt below can replace it. Never derived.
        val localCcpaOptout = storage.loadCcpaOptout()
        // A setCcpaOptout that lands while this call is suspended is newer than both the record and
        // the capture above: nothing below adopts or clears over it, and a write carries it.
        val ccpaRevisionAtEntry = ccpaChoiceRevision.get()
        val ccpaChangedMidFlight = { ccpaChoiceRevision.get() != ccpaRevisionAtEntry }
        // What the last sync for this identity left, read once for this call.
        val syncStateAtEntry = if (isResync) syncState(userHash) else null
        // The user changed nothing on this device since the last sync for this identity: the record
        // is adopted and nothing is written (TRUST-2902).
        val unchangedSinceSync = unchangedSinceSync(localChoice, syncStateAtEntry)
        // The RAW choice the record holds once this call is done: what it wrote, or the record's own
        // choice it adopted. Null when neither (a miss with nothing written, a signal-only record).
        var syncedRaw: ConsentPreferences? = null
        // The user-save revision this call last saw local state at: captured here, at entry, and
        // moved to the one right after each local persist below (any save before a persist was
        // overwritten by it). A save after it, during the read or the write, is a user change.
        var saveRevisionBaseline = preferencesSaveRevision.get()
        // The record a re-sync read before writing a local change through, for that change's local view.
        var writeThroughRecord: UniversalConsentRecord? = null

        // READ then WRITE. A read FAILURE THROWS and blocks the write — overwriting a record we
        // could not read would silently erase the user's real cross-device choice (the TRUST-2491
        // corruption class). Coroutine cancellation propagates for the same reason. Every read is
        // against the captured `config` snapshot so the READ and the WRITE that follows are
        // validated and hashed against ONE config — a concurrent loadConfig() cannot make the read's
        // consentProjectId/version disagree with the write's (the torn-read hazard this file guards
        // against elsewhere via essentialCategories(config)/reconciledCookieOptions).
        val toWrite: ConsentPreferences? =
            if (isResync) {
                // With no local change since the last sync, the stored choice is that sync's view
                // (after an adopt, the SIGNAL-RECONCILED one), not something the user chose, so it is
                // never written. Otherwise the user changed it and it is written through.
                val resyncChoice = localChoice?.takeUnless { unchangedSinceSync }
                // RE-SYNC. Rehydrate adopts any found record (a record without a choice counts as a
                // miss here, as before). The local CCPA flag takes the record's value only when
                // nothing will be written back (and, as in rehydrate, only with the syncOptout gate on).
                val rehydrated =
                    rehydrate(
                        identifier,
                        resolvedApiKey,
                        trackingSignal,
                        config,
                        adoptCcpaOptout = resyncChoice == null,
                        opGeneration = opGeneration,
                        ccpaRevision = ccpaRevisionAtEntry,
                        // A local change is written through below, so the device keeps showing it
                        // rather than the record's older view.
                        persistsLocal = resyncChoice == null,
                    )
                rehydrated?.saveRevision?.let { saveRevisionBaseline = it }
                if (resyncChoice != null) writeThroughRecord = rehydrated?.record
                val rawFromRecord = rehydrated?.raw
                // A logout landed during the read: rehydrate persisted nothing, and anything stored
                // since (a save or a setter call after the logout) is newer, so leave it alone.
                if (identityOperationGeneration.get() != opGeneration) return
                if (rawFromRecord != null && resyncChoice == null) {
                    getCategories()?.let { onRehydrated?.invoke(it) }
                }
                // Nothing to write: the record's raw choice (if any) is what was just adopted. An
                // unchanged re-sync that read none (a miss, a signal-only record) keeps the raw choice
                // the last sync recorded, since local state is still the view built from it.
                if (resyncChoice == null) {
                    syncedRaw = rawFromRecord ?: syncStateAtEntry?.syncedRaw?.takeIf { unchangedSinceSync }
                }
                // A bound CCPA choice that has not reached the record yet (e.g. a setter call after a
                // restart, with no session to write through) is a local change too: write it with the
                // record's own raw choice just read, so the categories another device set stay as
                // they are.
                val ccpaPending =
                    resyncChoice == null && localChoice != null && syncsCcpaOptout(config) &&
                        storage.loadCcpaWritePending()
                resyncChoice ?: rawFromRecord?.takeIf { ccpaPending }
            } else {
                // LOGIN. Reads the record directly so a found record without a choice is told apart
                // from a genuine miss (rehydrate folds the two together).
                val record = consentService.getUniversalConsent(config, identifier, resolvedApiKey)
                // A logout landed during the read: leave its neutral state in place rather than
                // adopting/replacing local state for an identity the device is no longer bound to.
                if (identityOperationGeneration.get() != opGeneration) return
                // The other identity's sync state never describes this one.
                if (boundToOther) storage.clearSyncState()
                // A setter call made while the read was in flight is newer than the record and is
                // kept. The check and the writes it guards run under the lock the setter holds, so a
                // setter that has stored its flag but not yet bumped the revision is not overwritten.
                synchronized(localChoiceLock) {
                    if (!ccpaChangedMidFlight()) {
                        // A login replaces local state, so a pending marker from before it is dropped.
                        storage.saveCcpaWritePending(false)
                        if (record != null) {
                            // A LOGIN that finds a record (with or without a category choice) adopts
                            // the record's CCPA flag whatever the syncOptout gate, the same as the web
                            // and iOS SDKs: a login is a full record-wins reset, so a pre-login local
                            // value is dropped exactly like the categories (with the gate off the
                            // record holds false, the same neutral clearUserIdentifier() leaves). Only
                            // a RE-SYNC adopt is gated (see rehydrate).
                            storage.saveCcpaOptout(record.ccpaOptout)
                        } else if (boundToOther) {
                            // The previous identity's flag must not carry over to this one, whatever
                            // the gate: the flag is identity-scoped, as clearUserIdentifier() treats it.
                            storage.clearCcpaOptout()
                        }
                    }
                }
                when {
                    record == null -> {
                        // Genuine MISS: only an explicit local choice is attached. A different
                        // identity's leftover state returns to neutral so it does not linger.
                        // (That only happens when bound to another identity, whose flag was already
                        // cleared above unless a setter call made during the read replaced it.)
                        if (explicitChoice == null && localChoice != null) {
                            returnToNeutral(onRehydrated, clearCcpaOptout = false)
                        }
                        explicitChoice
                    }
                    record.consentPreferences == null -> {
                        // FOUND but consent_preferences ABSENT — signal-only, the user made no
                        // choice (TRUST-2961: a PRESENT block with an empty cookieOptions map is an
                        // answered essential-only choice and is adopted in the else branch below,
                        // NOT treated as neutral here). Drop local state (neutral) if anything is
                        // stored. The CCPA flag already took the record's value above and keeps it.
                        if (localChoice != null) {
                            returnToNeutral(onRehydrated, clearCcpaOptout = false)
                        }
                        null
                    }
                    else -> {
                        // FOUND with a choice: the record wins and REPLACES local state. No write.
                        syncedRaw = rawPreferences(record)
                        saveRevisionBaseline =
                            synchronized(localChoiceLock) {
                                adoptOnLogin(record, trackingSignal, config)
                                preferencesSaveRevision.get()
                            }
                        getCategories()?.let { onRehydrated?.invoke(it) }
                        null
                    }
                }
            }

        // What this sync leaves, captured BEFORE any write is dispatched. It is recorded only if no
        // save landed since saveRevisionBaseline, so a save made during the read or while the write
        // is in flight still reads as a user change on the next re-sync.
        var syncedLocal = storage.loadPreferences()
        // The CCPA flag a write carried; null when nothing was written.
        var writtenCcpa: Boolean? = null
        if (toWrite != null) {
            syncedRaw = toWrite
            val carriedCcpa = if (ccpaChangedMidFlight()) storage.loadCcpaOptout() else localCcpaOptout
            writtenCcpa = carriedCcpa
            val universalPrefs =
                UniversalConsentPreferences(
                    isCustomised = toWrite.isCustomised,
                    cookieOptions = toWrite.cookieOptions.associate { it.gtmKey to it.isEnabled },
                )

            consentService.saveUniversalConsent(
                config = config,
                identifier = identifier,
                preferences = universalPrefs,
                apiKey = resolvedApiKey,
                // The user's explicit DNSMPI choice from setCcpaOptout, RAW. NEVER derived from the
                // tracking signal or any category: the ad-tracking signal is a narrower
                // ad-personalization signal, and treating one as the other would write a legal
                // opt-out the user never made. The service gates it on `syncOptout`. A setter call
                // made while this call was suspended is the newest choice, so it is sent instead.
                ccpaOptout = carriedCcpa,
                getSignature = getSignature,
            )
            // The record now holds the flag (when the gate is on), so nothing is pending.
            if (config.universalConsent?.syncOptout == true) resolveCcpaWritePending(carriedCcpa)
            // A re-sync that wrote a local change through stores that change's signal-reconciled view,
            // as an adopt stores the record's, now that the record holds it (a failed write leaves
            // the raw choice in place to be written again). Not over a save made since, nor after a
            // logout.
            val record = writeThroughRecord
            if (record != null && identityOperationGeneration.get() == opGeneration) {
                val viewChanged =
                    synchronized(localChoiceLock) {
                        if (preferencesSaveRevision.get() == saveRevisionBaseline) {
                            val written = toWrite.cookieOptions.associate { it.gtmKey to it.isEnabled }
                            val reconciled = reconciledCookieOptions(record, trackingSignal, config, written)
                            val view =
                                ConsentPreferences(
                                    isCustomised = true,
                                    cookieOptions = reconciled.map { (key, enabled) -> CategoryConsent(key, enabled) },
                                )
                            storage.savePreferences(view)
                            storage.saveConfigVersion(config.version)
                            syncedLocal = view
                            view != localChoice
                        } else {
                            false
                        }
                    }
                if (viewChanged) getCategories()?.let { onRehydrated?.invoke(it) }
            }
        }
        // A logout landed while the read/write was suspended: don't rebind the identity the device
        // just left. The call still reports success — the logout's neutral state is what stands.
        if (identityOperationGeneration.get() != opGeneration) return
        // Bind only after the call succeeds: a failed read or write throws above and leaves the
        // binding untouched, so a retry is still recognised as a login.
        storage.saveBoundUserHash(userHash)
        // A setCcpaOptout made while this call was in flight that no write carried is still waiting
        // for the record. On a first login it found the device unbound, so it was neither written
        // through nor marked pending; mark it now, or the next re-sync or rehydrate would adopt the
        // record's older value over it.
        if (syncsCcpaOptout(config)) {
            synchronized(localChoiceLock) {
                if (ccpaChangedMidFlight() && storage.loadCcpaOptout() != writtenCcpa) {
                    storage.saveCcpaWritePending(true)
                }
            }
        }
        if (preferencesSaveRevision.get() == saveRevisionBaseline) {
            storage.saveSyncState(UniversalConsentSyncState(userHash, syncedLocal, syncedRaw))
        } else {
            // The user saved a choice since the capture: a change the next re-sync must write.
            storage.clearSyncState()
        }
        universalConsentSession =
            UniversalConsentSession(
                userHash = userHash,
                identifier = identifier,
                explicitApiKey = apiKey,
                getSignature = getSignature,
            )
    }

    /** The persisted sync state for [userHash], or null when none is stored for it. */
    private fun syncState(userHash: String): UniversalConsentSyncState? =
        storage.loadSyncState()?.takeIf { it.userHash == userHash }

    /**
     * Whether the user changed nothing on this device since the last sync [state] records: storage
     * still holds exactly what that sync left and no save happened since (a save clears the state).
     */
    private fun unchangedSinceSync(
        stored: ConsentPreferences?,
        state: UniversalConsentSyncState?,
    ): Boolean {
        val syncedLocal = state?.syncedLocal ?: return false
        return stored != null && stored == syncedLocal
    }

    /**
     * The choice a [setCcpaOptout] write-through sends. While local state is still what the last sync
     * left, that is the record's RAW choice, so a suppressed view never overwrites the user's real
     * opt-ins for every device. Once the user has changed local state since, it is sent as stored.
     */
    private fun rawChoice(
        stored: ConsentPreferences,
        state: UniversalConsentSyncState?,
    ): ConsentPreferences {
        if (!unchangedSinceSync(stored, state)) return stored
        return state?.syncedRaw ?: stored
    }

    /**
     * LOGIN adopt (TRUST-2902 rev 2.1): REPLACE local state with a found record, never merge.
     *
     * For a PARTIAL answer, each category the record carries takes the record's value; every category
     * it does not mention takes the config default ([defaultPreferences] — the same neutral value
     * [clearUserIdentifier] leaves it at; a config category outside the initial set is absent there and
     * reads false, its default), never the prior local value. A PRESENT but EMPTY cookieOptions map is
     * an answered essential-only choice (TRUST-2961), NOT a partial answer: it is reconciled raw with
     * no config-default layering, so only essential keys backfill on and every non-essential category
     * reads off — even one that is default-on in initialCategories — which is the SAME derivation the
     * rehydrate/re-sync path applies to the identical shape (the two must not disagree about what
     * "essential-only" means). The resulting map then goes through the shared [reconciledCookieOptions],
     * so a stored `gpc` or this device's signal suppresses backfilled non-essential defaults as well,
     * and essential keys stay on. Stored isCustomised=true and the running config version, exactly as
     * the existing rehydrate adopt path does.
     */
    private fun adoptOnLogin(
        record: UniversalConsentRecord,
        trackingSignal: TrackingSignal,
        config: ConsentConfig,
    ) {
        val recordCookieOptions = record.consentPreferences?.cookieOptions ?: emptyMap()
        // A PRESENT but EMPTY cookieOptions map is an answered essential-only choice (TRUST-2961), not
        // a partial answer to layer config defaults under. Reconcile the raw empty map directly — the
        // SAME derivation the rehydrate/re-sync path uses for this shape — so SignalReconciliation
        // backfills only the essential keys and every non-essential category reads off. Layering
        // defaultPreferences here would re-enable any non-essential category that is default-on in
        // initialCategories.initial (a normal opt-out/default-on config), silently re-consenting the
        // user to it and making LOGIN disagree with REHYDRATE about the identical empty-map record.
        val layered =
            if (recordCookieOptions.isEmpty()) {
                recordCookieOptions
            } else {
                // A PARTIAL answer: each category the record carries wins; every category it does not
                // mention takes the config default (TRUST-2902 login-replace semantics).
                val neutral = defaultPreferences(config).cookieOptions.associate { it.gtmKey to it.isEnabled }
                neutral + recordCookieOptions
            }
        val reconciled = reconciledCookieOptions(record, trackingSignal, config, layered)
        storage.savePreferences(
            ConsentPreferences(
                isCustomised = true,
                cookieOptions = reconciled.map { (gtmKey, isEnabled) -> CategoryConsent(gtmKey, isEnabled) },
            ),
        )
        storage.saveConfigVersion(config.version)
    }

    /**
     * Logout / return-to-neutral (TRUST-2902). Clears the persisted identity binding and returns
     * local consent to NEUTRAL: the stored explicit choice and the CCPA opt-out flag (TRUST-2591)
     * are removed, so [getCcpaOptout] reads false and category reads fall back to the
     * config's defaults exactly as on a fresh install ([needsConsent] true, [getUserPreferences]
     * null), and [onNeutral] fires with those now-effective defaults.
     *
     * NON-destructive, unlike [reset]: no network call, the server-side universal consent record is
     * untouched, and the unique id, config cache, config version, locale, pending event queue and
     * loaded config all stay in place. Idempotent and safe to call when not bound.
     *
     * @param onNeutral Invoked with the now-effective (default) preferences so the adapter can
     *   notify its consent-changed listener.
     */
    fun clearUserIdentifier(onNeutral: ((ConsentPreferences) -> Unit)? = null) {
        // Supersede any in-flight setUserIdentifier so its continuation cannot rebind this identity
        // or rehydrate its preferences after we return the device to neutral below.
        identityOperationGeneration.incrementAndGet()
        storage.clearBoundUserHash()
        universalConsentSession = null
        returnToNeutral(onNeutral)
    }

    /**
     * The single "return local state to neutral" operation shared by [clearUserIdentifier] and the
     * login-transition branch of [setUserIdentifier]: remove the stored explicit choice and reuse
     * the existing default path ([getCategories] falls back to [getDefaultPreferences]) rather than
     * reimplementing defaults. Also clears the CCPA opt-out flag unless [clearCcpaOptout] is false
     * (a login onto a found record has already set it to the record's value).
     */
    private fun returnToNeutral(
        onNeutral: ((ConsentPreferences) -> Unit)?,
        clearCcpaOptout: Boolean = true,
    ) {
        storage.clearPreferences()
        if (clearCcpaOptout) {
            storage.clearCcpaOptout()
            storage.saveCcpaWritePending(false)
        }
        getCategories()?.let { onNeutral?.invoke(it) }
    }

    // MARK: - CCPA opt-out (TRUST-2591)

    /**
     * The identity and credentials of the last successful [setUserIdentifier], held in memory only
     * (the signature provider cannot be persisted) so [setCcpaOptout] can write through for the
     * bound user. After a process restart the setter stays local until the host calls
     * [setUserIdentifier] again.
     *
     * [explicitApiKey] is the key the host PASSED to [setUserIdentifier] (null when it relied on
     * `config.json`), NOT the resolved value: [setCcpaOptout] re-resolves it against the live config
     * on every write (TRUST-2603) so a key rotated server-side via `config.json` is picked up without
     * a client release — exactly like every other Universal Consent call path. Caching the resolved
     * key here would pin a stale key for the life of the process.
     *
     * What that sync left (the synced stored and raw choices) is persisted instead, see
     * [UniversalConsentSyncState].
     */
    private data class UniversalConsentSession(
        val userHash: String,
        val identifier: String,
        val explicitApiKey: String?,
        val getSignature: SignatureProvider?,
    ) {
        // Never let the identifier or an explicit API key reach a log through string interpolation.
        override fun toString(): String = "UniversalConsentSession(userHash=$userHash)"
    }

    @Volatile
    private var universalConsentSession: UniversalConsentSession? = null

    /**
     * Snapshot of the identity-operation generation. The public adapter captures this SYNCHRONOUSLY,
     * before it launches [setCcpaOptout]'s write-through on a coroutine scope, so a
     * clearUserIdentifier()/reset() that lands between the host's call and the launched coroutine
     * actually running is detected — otherwise the coroutine would re-persist the opt-out the logout
     * just cleared (TRUST-2591).
     */
    internal fun identityOperationGenerationSnapshot(): Int = identityOperationGeneration.get()

    /**
     * Persist the user's explicit CCPA/CPRA "Do Not Sell or Share" choice for this device, then write
     * it through to the bound identity's universal consent record when that applies. Changes no
     * category and fires no listener.
     *
     * The write — the user's RAW choice plus the new flag, the same write [setUserIdentifier]
     * makes — happens only when universal consent is enabled, `universalConsent.syncOptout` is on,
     * the device is bound to the identity a [setUserIdentifier] call succeeded for in this process,
     * and an explicit local choice is stored (config defaults are never seeded). Otherwise the flag
     * stays local and rides the next universal consent write. A failed write throws; the local flag
     * is kept. Until the choice reaches the record, a re-sync or rehydrate does not replace it with
     * the record's value ([ConsentStorage.loadCcpaWritePending]); a choice made during a first login,
     * before the device is bound, is marked pending when that login binds without writing it. While the stored choice is still the
     * possibly-suppressed view the last sync left, the RAW choice is the record's own (see
     * [rawChoice]), never that view itself (TRUST-3074).
     */
    suspend fun setCcpaOptout(
        optedOut: Boolean,
        opGeneration: Int = identityOperationGeneration.get(),
        persistsLocally: Boolean = true,
    ) {
        // Token for this write. The public adapter persists the flag synchronously and then LAUNCHES
        // this coroutine, so a clearUserIdentifier()/reset() can land before it even starts; the
        // adapter captures the generation up front and passes it here so a logout the launch raced is
        // seen. If it changed, the logout already returned the device to neutral and cleared the flag,
        // so bail BEFORE re-persisting it locally — re-saving here would resurrect an opt-out on a
        // logged-out device. Direct callers (and the unit tests) capture the generation at entry via
        // the default argument. Same stale-write corruption guard (TRUST-2491/TRUST-2902) every other
        // identity-scoped operation in this file carries.
        if (identityOperationGeneration.get() != opGeneration) return
        // The adapter already persisted the choice synchronously ([saveCcpaOptout]) and passes false:
        // persisting the captured value again here could overwrite a newer toggle made since.
        if (persistsLocally) persistCcpaChoice(optedOut)
        // Fast path only; everything below is re-read under the lock.
        if (!syncsCcpaOptout(currentConfig)) return
        if (universalConsentSession == null) return
        // Serialize the write-through with the identity operations (setUserIdentifier/logout) that
        // take this same mutex. Without it a setUserIdentifier that rebinds to a different identity
        // could land between the checks and the suspended POST, and two rapid toggles could complete
        // out of order and leave the server with the older flag. Under the lock we re-read the
        // config, the session, the binding, the generation and the stored flag so the write reflects
        // the current identity, gate and key and the LATEST value, and completion order can never
        // reverse the user's intent. (The wait can be a whole setUserIdentifier round-trip, during
        // which a loadConfig or re-login can change any of them.)
        setUserIdentifierMutex.withLock {
            if (identityOperationGeneration.get() != opGeneration) return@withLock
            val config = currentConfig ?: return@withLock
            if (!syncsCcpaOptout(config)) return@withLock
            val session = universalConsentSession ?: return@withLock
            // The bound hash moved (a logout, or a login to another identity) — the captured session
            // is no longer the bound one, so don't POST to it.
            if (session.userHash != storage.loadBoundUserHash()) return@withLock
            // The stored choice and the save revision it was read at, together, so a save that lands
            // during the POST is not recorded as synced below.
            val (stored, saveRevision) =
                synchronized(localChoiceLock) { storage.loadPreferences() to preferencesSaveRevision.get() }
            if (stored == null) return@withLock
            val localChoice = rawChoice(stored, syncState(session.userHash))
            // A logout/reset landed while preparing the write (including during the reads above):
            // don't write through to the now-unbound (or re-bound) identity.
            if (identityOperationGeneration.get() != opGeneration) return@withLock
            // Re-resolve the API key against the LIVE config at write time (TRUST-2603): an explicit
            // key the host passed to setUserIdentifier still wins, but when it relied on config.json
            // a key rotated server-side is picked up here instead of reusing the value captured at
            // bind time. Throws ValidationError if neither source has a key now, exactly like the
            // other call paths.
            val apiKey = resolveUniversalConsentApiKey(config, session.explicitApiKey)
            val writtenCcpa = storage.loadCcpaOptout()
            consentService.saveUniversalConsent(
                config = config,
                identifier = session.identifier,
                preferences =
                    UniversalConsentPreferences(
                        isCustomised = localChoice.isCustomised,
                        cookieOptions = localChoice.cookieOptions.associate { it.gtmKey to it.isEnabled },
                    ),
                apiKey = apiKey,
                // The LATEST stored flag, not the captured parameter: two rapid toggles that complete
                // out of order then both land the final value instead of reversing the user's intent.
                ccpaOptout = writtenCcpa,
                getSignature = session.getSignature,
            )
            // Reached the record. A newer toggle made during the POST stays pending for its own write.
            resolveCcpaWritePending(writtenCcpa)
            // The record now holds this choice, so the next re-sync must not send it again: the edge
            // stamps every write with its write time, so a repeat could beat a newer choice another
            // device made meanwhile (TRUST-2902). Not after a logout, nor over a save made since.
            if (identityOperationGeneration.get() != opGeneration) return@withLock
            synchronized(localChoiceLock) {
                if (preferencesSaveRevision.get() == saveRevision) {
                    storage.saveSyncState(UniversalConsentSyncState(session.userHash, stored, localChoice))
                }
            }
        }
    }

    /** Persist the CCPA opt-out flag locally only (the adapter's synchronous half of [setCcpaOptout]). */
    fun saveCcpaOptout(optedOut: Boolean) = persistCcpaChoice(optedOut)

    /**
     * Store a host CCPA choice. When the device is bound, the choice is marked pending until a
     * Universal Consent write carries it, so a later re-sync or rehydrate cannot replace it with the
     * record's older value. It is marked whatever the gate says (the config may not have loaded yet
     * at app start); every reader of the marker checks the `syncOptout` gate itself.
     */
    private fun persistCcpaChoice(optedOut: Boolean) {
        // One critical section, as consent-ios's IdentityGate does it: a sync's revision check never
        // sees the new flag without the bumped revision (and the pending marker) that protect it.
        synchronized(localChoiceLock) {
            storage.saveCcpaOptout(optedOut)
            ccpaChoiceRevision.incrementAndGet()
            if (storage.loadBoundUserHash() != null) {
                storage.saveCcpaWritePending(true)
            }
        }
    }

    /**
     * A Universal Consent write carried [writtenCcpa], so the record holds it and it is no longer
     * pending, unless a setter call stored a different choice since; that one stays pending for its
     * own write. Under [localChoiceLock] so the setter cannot land between the check and the clear.
     */
    private fun resolveCcpaWritePending(writtenCcpa: Boolean) {
        synchronized(localChoiceLock) {
            if (storage.loadCcpaOptout() == writtenCcpa) {
                storage.saveCcpaWritePending(false)
            }
        }
    }

    private fun syncsCcpaOptout(config: ConsentConfig?): Boolean =
        config?.universalConsent?.let { it.enabled && it.syncOptout } == true

    /** The stored CCPA opt-out choice; false when none is stored. */
    fun getCcpaOptout(): Boolean = storage.loadCcpaOptout()

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
     * Clear all consent data, including the identity binding (destructive; see
     * [clearUserIdentifier] for the non-destructive logout)
     */
    fun reset() {
        // Like clearUserIdentifier(), supersede any in-flight setUserIdentifier so a stale
        // continuation cannot rebind an identity or restore preferences after the wipe.
        identityOperationGeneration.incrementAndGet()
        storage.clearAll()
        currentConfig = null
        universalConsentSession = null
    }

    /**
     * Reset the unique tracking identifier
     */
    fun resetIdentifier() {
        storage.resetIdentifier()
    }
}
