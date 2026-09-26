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
     * [rehydrateReturningRawPreferences] (which persists the reconciled view to local storage)
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
     * @param trackingSignal This device's live signal. Read from the OS by the caller (the public
     *   API does this for you) so this method never blocks on a binder call.
     * @return true when local state was rehydrated from a stored record, false on a miss.
     */
    suspend fun rehydrateFromUniversalConsent(
        identifier: String,
        apiKey: String?,
        trackingSignal: TrackingSignal = TrackingSignal.NOT_DETERMINED,
    ): Boolean {
        val config = requireUniversalConsentReady()
        val resolvedApiKey = resolveUniversalConsentApiKey(config, apiKey)
        return rehydrateReturningRawPreferences(identifier, resolvedApiKey, trackingSignal, config) != null
    }

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
     * @param adoptCcpaOptout Whether a found record's `ccpa_optout` replaces the local flag
     *   (TRUST-2591: the record is authoritative for the stored choice), applied only when
     *   `universalConsent.syncOptout` is on. [setUserIdentifier] passes
     *   false on a re-sync that is about to write the local flag through, so a failed write cannot
     *   drop the user's newer local choice.
     * @return the record's raw preferences, or null on a miss.
     */
    suspend fun rehydrateReturningRawPreferences(
        identifier: String,
        apiKey: String,
        trackingSignal: TrackingSignal = TrackingSignal.NOT_DETERMINED,
        config: ConsentConfig = requireUniversalConsentReady(),
        adoptCcpaOptout: Boolean = true,
    ): ConsentPreferences? {

        // Goes to the service directly rather than through fetchUniversalConsent, which returns an
        // already-reconciled record. Both views are needed here: the reconciled one to persist
        // locally, the raw one to hand back for the write.
        val record = consentService.getUniversalConsent(config, identifier, apiKey) ?: return null
        // ABSENT consent_preferences is signal-only, a miss. A PRESENT block is an answered choice
        // even when its cookieOptions map is empty (essential-only): TRUST-2961 — only a null block,
        // not an empty map, is "no choice". cookieOptions is non-null once the block is present, so
        // the empty-map case falls through and rehydrates (isCustomised is forced true locally
        // below, which is what stops the banner re-prompting a user who already answered elsewhere).
        val rawCookieOptions = record.consentPreferences?.cookieOptions
        if (rawCookieOptions == null) return null

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
        // Outside a login, adopt the record's CCPA choice only with the syncOptout gate on: with the
        // gate off the SDK never puts the choice on the record (it always says false), so adopting it
        // would erase a local-only setCcpaOptout(true). Same rule as the web/iOS/React Native SDKs.
        if (adoptCcpaOptout && config.universalConsent?.syncOptout == true) {
            storage.saveCcpaOptout(record.ccpaOptout)
        }
        return raw
    }

    /**
     * Associate a user identifier with their cross-device consent, READING then WRITING the
     * universal consent store in one coordinated operation. This is the headline invariant of the
     * feature and it lives here, in the coordinator, not in the public singleton adapter.
     *
     * READ first: [rehydrateReturningRawPreferences] pulls any existing record and applies it to
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
     *   with NO choice (signal-only / null preferences, or an empty cookieOptions map, which this
     *   model cannot tell apart) returns local state to NEUTRAL if anything is stored, else is a
     *   no-op. On a genuine MISS only an explicit local choice is written (attached to the new
     *   identity). Otherwise nothing is written; if a different identity was bound and local state
     *   exists, it returns to NEUTRAL (the same local operation as [clearUserIdentifier], minus
     *   clearing the binding) so that user's state does not linger.
     * - RE-SYNC (bound to this identity): a found record is adopted, and a local choice is written
     *   through (sync-on-change) as before. On a miss only an explicit local choice is written;
     *   config defaults are never seeded.
     * "Nothing written" still returns normally. The SDK cannot tell whether a pre-login choice was
     * made by the person now logging in or a previous user of a shared device (an explicit choice
     * on an unbound device is attached on a no-record login by design), does no heuristic
     * shared-device/shared-account detection, and cannot detect two people sharing one account.
     *
     * CCPA opt-out (TRUST-2591). Any write carries the RAW local [getCcpaOptout] flag, captured
     * before any adopt; the service writes it only when `universalConsent.syncOptout` is on. A LOGIN
     * that finds a record (with or without a category choice) sets the local flag to the record's
     * `ccpa_optout`, dropping a pre-login local value; a LOGIN away from a different identity with no
     * record clears it (it was that user's). A RE-SYNC that writes the local choice through keeps the
     * local flag (it is what the record now holds); one that only adopts takes the record's value
     * when `syncOptout` is on (with the gate off the record never carries the choice). A
     * ccpa-only local change is not an explicit category choice, so on its own it never triggers a
     * write.
     *
     * The raw identifier is NOT retained as manager state, so subsequent operations (e.g.
     * [fetchUniversalConsent]) must pass it again. Requires universal consent to be enabled AND
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

        // READ then WRITE. A read FAILURE THROWS and blocks the write — overwriting a record we
        // could not read would silently erase the user's real cross-device choice (the TRUST-2491
        // corruption class). Coroutine cancellation propagates for the same reason. Every read is
        // against the captured `config` snapshot so the READ and the WRITE that follows are
        // validated and hashed against ONE config — a concurrent loadConfig() cannot make the read's
        // consentProjectId/version disagree with the write's (the torn-read hazard this file guards
        // against elsewhere via essentialCategories(config)/reconciledCookieOptions).
        val toWrite: ConsentPreferences? =
            if (isResync) {
                // RE-SYNC: unchanged. Rehydrate adopts any found record (a record without a choice
                // counts as a miss here, as before); a local choice is written through
                // (sync-on-change), and a found record with no local change is adopted without a
                // POST. On a miss only an explicit local choice is written; defaults are never seeded.
                // A found record is written over whenever a local choice exists, so the local CCPA
                // flag takes the record's value only when nothing will be written back (and, as in
                // rehydrate, only with the syncOptout gate on).
                val rawFromRecord =
                    rehydrateReturningRawPreferences(
                        identifier,
                        resolvedApiKey,
                        trackingSignal,
                        config,
                        adoptCcpaOptout = localChoice == null,
                    )
                if (rawFromRecord != null) {
                    getCategories()?.let { onRehydrated?.invoke(it) }
                }
                if (rawFromRecord != null) localChoice else explicitChoice
            } else {
                // LOGIN. Reads the record directly so a found record without a choice is told apart
                // from a genuine miss (rehydrate folds the two together).
                val record = consentService.getUniversalConsent(config, identifier, resolvedApiKey)
                if (record != null) {
                    // The record is authoritative for the stored CCPA choice; a pre-login local
                    // value is dropped exactly like the categories.
                    storage.saveCcpaOptout(record.ccpaOptout)
                } else if (boundToOther) {
                    // The previous identity's flag must not carry over to this one.
                    storage.clearCcpaOptout()
                }
                when {
                    record == null -> {
                        // Genuine MISS: only an explicit local choice is attached. A different
                        // identity's leftover state returns to neutral so it does not linger.
                        if (explicitChoice == null && localChoice != null) {
                            returnToNeutral(onRehydrated)
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
                        adoptOnLogin(record, trackingSignal, config)
                        getCategories()?.let { onRehydrated?.invoke(it) }
                        null
                    }
                }
            }

        if (toWrite != null) {
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
                // opt-out the user never made. The service gates it on `syncOptout`.
                ccpaOptout = localCcpaOptout,
                getSignature = getSignature,
            )
        }
        // Bind only after the call succeeds: a failed read or write throws above and leaves the
        // binding untouched, so a retry is still recognised as a login.
        storage.saveBoundUserHash(userHash)
        universalConsentSession = UniversalConsentSession(userHash, identifier, resolvedApiKey, getSignature)
    }

    /**
     * LOGIN adopt (TRUST-2902 rev 2.1): REPLACE local state with a found record, never merge.
     *
     * Each category the record carries takes the record's value; every category it does not mention
     * takes the config default ([defaultPreferences] — the same neutral value [clearUserIdentifier]
     * leaves it at; a config category outside the initial set is absent there and reads false, its
     * default), never the prior local value. The layered map then goes through the shared
     * [reconciledCookieOptions], so a stored `gpc` or this device's signal suppresses backfilled
     * non-essential defaults as well, and essential keys stay on. Stored isCustomised=true and the
     * running config version, exactly as the existing rehydrate adopt path does.
     */
    private fun adoptOnLogin(
        record: UniversalConsentRecord,
        trackingSignal: TrackingSignal,
        config: ConsentConfig,
    ) {
        val neutral = defaultPreferences(config).cookieOptions.associate { it.gtmKey to it.isEnabled }
        val layered = neutral + (record.consentPreferences?.cookieOptions ?: emptyMap())
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
        }
        getCategories()?.let { onNeutral?.invoke(it) }
    }

    // MARK: - CCPA opt-out (TRUST-2591)

    /**
     * The identity and credentials of the last successful [setUserIdentifier], held in memory only
     * (the signature provider cannot be persisted) so [setCcpaOptout] can write through for the
     * bound user. After a process restart the setter stays local until the host calls
     * [setUserIdentifier] again.
     */
    private data class UniversalConsentSession(
        val userHash: String,
        val identifier: String,
        val apiKey: String,
        val getSignature: SignatureProvider?,
    )

    @Volatile
    private var universalConsentSession: UniversalConsentSession? = null

    /**
     * Persist the user's explicit CCPA/CPRA "Do Not Sell or Share" choice for this device, then write
     * it through to the bound identity's universal consent record when that applies. Changes no
     * category and fires no listener.
     *
     * The write — the stored local choice plus the new flag, the same write [setUserIdentifier]
     * makes — happens only when universal consent is enabled, `universalConsent.syncOptout` is on,
     * the device is bound to the identity a [setUserIdentifier] call succeeded for in this process,
     * and an explicit local choice is stored (config defaults are never seeded). Otherwise the flag
     * stays local and rides the next universal consent write. A failed write throws; the local flag
     * is kept.
     */
    suspend fun setCcpaOptout(optedOut: Boolean) {
        storage.saveCcpaOptout(optedOut)
        val config = currentConfig ?: return
        val universalConsent = config.universalConsent ?: return
        if (!universalConsent.enabled || !universalConsent.syncOptout) return
        val session = universalConsentSession ?: return
        if (session.userHash != storage.loadBoundUserHash()) return
        val localChoice = storage.loadPreferences() ?: return
        consentService.saveUniversalConsent(
            config = config,
            identifier = session.identifier,
            preferences =
                UniversalConsentPreferences(
                    isCustomised = localChoice.isCustomised,
                    cookieOptions = localChoice.cookieOptions.associate { it.gtmKey to it.isEnabled },
                ),
            apiKey = session.apiKey,
            ccpaOptout = optedOut,
            getSignature = session.getSignature,
        )
    }

    /** Persist the CCPA opt-out flag locally only (the adapter's synchronous half of [setCcpaOptout]). */
    fun saveCcpaOptout(optedOut: Boolean) = storage.saveCcpaOptout(optedOut)

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
