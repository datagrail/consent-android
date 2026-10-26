package com.datagrail.consent.storage

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.datagrail.consent.models.ConsentConfig
import com.datagrail.consent.models.ConsentException
import com.datagrail.consent.models.ConsentPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Handles local storage of consent data using EncryptedSharedPreferences.
 * This class is internal to the SDK — consumers should not instantiate it directly.
 */
internal class ConsentStorage(private val prefs: SharedPreferences) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    companion object {
        private const val TAG = "DataGrailConsent"
        internal const val PREFS_NAME = "com.datagrail.consent.prefs"
        private const val KEY_PREFERENCES = "datagrail_consent_preferences"
        private const val KEY_UNIQUE_ID = "datagrail_consent_id"
        private const val KEY_VERSION = "datagrail_consent_version"
        private const val KEY_LOCALE_CODE = "datagrail_consent_locale_code"
        private const val KEY_CONFIG_CACHE = "datagrail_consent_config_cache"
        private const val KEY_PENDING_EVENTS = "datagrail_consent_pending_events"
        private const val KEY_BOUND_USER_HASH = "datagrail_consent_bound_user_hash"
        private const val KEY_CCPA_OPTOUT = "datagrail_consent_ccpa_optout"
        private const val KEY_CCPA_WRITE_PENDING = "datagrail_consent_ccpa_write_pending"
        private const val KEY_SYNC_STATE = "datagrail_consent_sync_state"
        private const val KEY_DECISION_TS = "datagrail_consent_decision_ts"

        /**
         * Create a ConsentStorage backed by EncryptedSharedPreferences
         * @param context Android application context
         * @return ConsentStorage instance with encrypted backing store
         * @throws ConsentException.InvalidConfiguration if encrypted storage cannot be initialized
         */
        fun create(context: Context): ConsentStorage {
            val appContext = context.applicationContext
            val rawPrefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return createWithRecovery(rawPrefs) { createEncryptedStorage(appContext) }
        }

        /**
         * Initialize encrypted storage, recovering from a corrupted Tink keyset on failure.
         *
         * The Tink keyset stored in SharedPreferences may be corrupted (e.g. from a partial
         * write during a prior crash or OS update), causing the initial attempt to throw
         * (e.g. "Protocol message contained an invalid tag (zero)"). On any failure we clear
         * the prefs file — removing the corrupted keyset — and retry with a fresh keyset.
         * Previously saved consent state is lost and the banner will re-appear, but the app
         * will no longer crash.
         *
         * Note: catching all exceptions (not just the corruption error) is intentional — any
         * init failure here, including a transient Keystore error, will wipe consent state and
         * retry. Crash-avoidance is the priority; re-showing the banner is the privacy-safe
         * failure mode.
         *
         * Extracted (and given an injectable [createStorage] factory) so the recovery flow can
         * be unit-tested without a real EncryptedSharedPreferences.
         *
         * Recovery events are logged via [Log.e] directly (not [ConsentLogger]) so they are
         * always visible in logcat. This recovery destroys saved consent state and is rare and
         * serious, so it must be observable regardless of the host app's configured log level.
         *
         * @throws ConsentException.InvalidConfiguration if the retry also fails
         */
        internal fun createWithRecovery(
            rawPrefs: SharedPreferences,
            createStorage: () -> ConsentStorage,
        ): ConsentStorage {
            return try {
                createStorage()
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "Failed to initialize encrypted storage, recovering by clearing corrupted " +
                        "prefs and retrying",
                    e,
                )
                try {
                    rawPrefs.edit().clear().apply()
                    createStorage()
                } catch (retryException: Exception) {
                    Log.e(
                        TAG,
                        "Encrypted storage recovery failed after clearing prefs",
                        retryException,
                    )
                    throw ConsentException.InvalidConfiguration(
                        "Failed to initialize encrypted storage",
                        retryException,
                    )
                }
            }
        }

        private fun createEncryptedStorage(appContext: Context): ConsentStorage {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            val encryptedPrefs =
                EncryptedSharedPreferences.create(
                    PREFS_NAME,
                    masterKeyAlias,
                    appContext,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            return ConsentStorage(encryptedPrefs)
        }
    }

    // MARK: - Preferences

    /**
     * Save a consent choice and WHEN it was made (TRUST-3224), in one edit, so the two never drift
     * apart. [decisionTs] is unix seconds: the user's own choice time, or the `updated_at` of the
     * record a Universal Consent adopt stored. Null means the time is unknown, and removes any stored
     * time rather than leaving another choice's time paired with this one. The time is sent as the
     * write's `decision_ts` and compared with the record's `updated_at` on a re-sync, so a stale
     * device cannot out-rank a newer choice made elsewhere.
     * @throws ConsentException.StorageError if encoding fails
     */
    fun savePreferences(
        preferences: ConsentPreferences,
        decisionTs: Long?,
    ) {
        val jsonString = encodePreferences(preferences)
        val edit = prefs.edit().putString(KEY_PREFERENCES, jsonString)
        if (decisionTs != null) edit.putLong(KEY_DECISION_TS, decisionTs) else edit.remove(KEY_DECISION_TS)
        edit.apply()
    }

    /**
     * Replace the stored choice with another view OF THE SAME CHOICE, keeping its decision time: the
     * reconciled view a re-sync write-through leaves. Never
     * use this for a new choice or an adopt; those decide their time through [savePreferences].
     * @throws ConsentException.StorageError if encoding fails
     */
    fun replacePreferencesKeepingDecisionTs(preferences: ConsentPreferences) {
        prefs.edit().putString(KEY_PREFERENCES, encodePreferences(preferences)).apply()
    }

    private fun encodePreferences(preferences: ConsentPreferences): String =
        try {
            json.encodeToString(preferences)
        } catch (e: Exception) {
            throw ConsentException.StorageError("Failed to encode preferences: ${e.message}", e)
        }

    /**
     * Load consent preferences from local storage
     * @return The stored preferences, or null if none exist
     */
    fun loadPreferences(): ConsentPreferences? {
        val jsonString = prefs.getString(KEY_PREFERENCES, null) ?: return null
        return try {
            json.decodeFromString<ConsentPreferences>(jsonString)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Remove the stored explicit consent choice only, returning reads to the config defaults (the
     * same state a first-time install sees: [loadPreferences] returns null, so the banner shows).
     * Unlike [clearAll] this leaves the unique id, config cache, config version, locale, pending
     * event queue and identity binding in place. The choice's decision time goes with it.
     */
    fun clearPreferences() {
        prefs.edit().remove(KEY_PREFERENCES).remove(KEY_DECISION_TS).apply()
    }

    // MARK: - Decision time (TRUST-3224)

    /** The stored decision time, unvalidated; null when none is stored (or it is unreadable). */
    fun loadDecisionTs(): Long? {
        if (!prefs.contains(KEY_DECISION_TS)) return null
        return try {
            prefs.getLong(KEY_DECISION_TS, 0L)
        } catch (e: ClassCastException) {
            null
        }
    }

    // MARK: - Identity Binding

    /**
     * Persist the universal-consent user hash of the identity this device is currently bound to.
     * Only the hash is stored, never the raw identifier. [clearAll] removes it with everything else.
     * @param userHash The user hash computed for the identifier passed to setUserIdentifier
     */
    fun saveBoundUserHash(userHash: String) {
        prefs.edit().putString(KEY_BOUND_USER_HASH, userHash).apply()
    }

    /**
     * Load the user hash of the identity this device is currently bound to
     * @return The bound user hash, or null if the device is not bound to an identity
     */
    fun loadBoundUserHash(): String? {
        return prefs.getString(KEY_BOUND_USER_HASH, null)
    }

    /**
     * Remove the identity binding (logout), and the sync state that belongs to it. Does not touch
     * any other stored data.
     */
    fun clearBoundUserHash() {
        prefs
            .edit()
            .remove(KEY_BOUND_USER_HASH)
            .remove(KEY_SYNC_STATE)
            .apply()
    }

    /**
     * Persist what the last successful universal consent sync left for the bound identity, so a
     * re-sync after a process restart can still tell whether the user changed anything since.
     * Holds consent maps and the user hash only, never the raw identifier.
     */
    fun saveSyncState(state: UniversalConsentSyncState) {
        try {
            prefs.edit().putString(KEY_SYNC_STATE, json.encodeToString(state)).apply()
        } catch (e: Exception) {
            // Without it the next re-sync treats the stored choice as a change and writes it.
            clearSyncState()
        }
    }

    /** The persisted sync state, or null when none is stored. */
    fun loadSyncState(): UniversalConsentSyncState? {
        val jsonString = prefs.getString(KEY_SYNC_STATE, null) ?: return null
        return try {
            json.decodeFromString<UniversalConsentSyncState>(jsonString)
        } catch (e: Exception) {
            null
        }
    }

    /** Forget the persisted sync state: the next re-sync treats the stored choice as a user change. */
    fun clearSyncState() {
        prefs.edit().remove(KEY_SYNC_STATE).apply()
    }

    // MARK: - CCPA opt-out (TRUST-2591)

    /**
     * Persist the user's explicit CCPA/CPRA "Do Not Sell or Share" choice for this device. Never a
     * derived value: only the public setter, a found-record adopt, and the neutral reset write it.
     * [clearAll] removes it with everything else.
     * @param optedOut true when the user opted out of sale/sharing
     */
    fun saveCcpaOptout(optedOut: Boolean) {
        prefs.edit().putBoolean(KEY_CCPA_OPTOUT, optedOut).apply()
    }

    /**
     * Load the stored CCPA opt-out choice
     * @return The stored choice, or false ("not opted out") if none is stored
     */
    fun loadCcpaOptout(): Boolean = prefs.getBoolean(KEY_CCPA_OPTOUT, false)

    /**
     * Remove the stored CCPA opt-out choice, so reads return false. Does not touch any other stored data.
     */
    fun clearCcpaOptout() {
        prefs.edit().remove(KEY_CCPA_OPTOUT).apply()
    }

    /**
     * Mark (or clear) that a CCPA choice made while bound has not reached the Universal Consent record
     * yet: the setter had no category choice to write with, or its write failed. While set, a found
     * record's `ccpa_optout` must not replace the local flag on a re-sync or rehydrate, or the device
     * would silently lose its own unsent choice.
     */
    fun saveCcpaWritePending(pending: Boolean) {
        if (pending) {
            prefs.edit().putBoolean(KEY_CCPA_WRITE_PENDING, true).apply()
        } else {
            prefs.edit().remove(KEY_CCPA_WRITE_PENDING).apply()
        }
    }

    /** Whether a bound CCPA choice is still waiting to reach the record; false when none is stored. */
    fun loadCcpaWritePending(): Boolean = prefs.getBoolean(KEY_CCPA_WRITE_PENDING, false)

    // MARK: - Unique ID

    /**
     * Get or create a unique identifier for this user
     * @return The unique identifier (UUID string)
     */
    fun getOrCreateUniqueId(): String {
        val existingId = prefs.getString(KEY_UNIQUE_ID, null)
        if (existingId != null) return existingId

        val newId = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_UNIQUE_ID, newId).apply()
        return newId
    }

    /**
     * Reset the unique identifier, removing it from storage.
     * A new ID will be generated on the next call to getOrCreateUniqueId().
     */
    fun resetIdentifier() {
        prefs.edit().remove(KEY_UNIQUE_ID).apply()
    }

    // MARK: - Configuration Version

    /**
     * Save the configuration version
     * @param version The version string to save
     */
    fun saveConfigVersion(version: String) {
        prefs.edit().putString(KEY_VERSION, version).apply()
    }

    /**
     * Load the stored configuration version
     * @return The version string, or null if none stored
     */
    fun loadConfigVersion(): String? {
        return prefs.getString(KEY_VERSION, null)
    }

    // MARK: - Locale

    /**
     * Save the current locale code
     * @param localeCode The locale code (e.g., "en", "es")
     */
    fun saveLocaleCode(localeCode: String) {
        prefs.edit().putString(KEY_LOCALE_CODE, localeCode).apply()
    }

    /**
     * Load the stored locale code
     * @return The locale code, or null if none stored
     */
    fun loadLocaleCode(): String? {
        return prefs.getString(KEY_LOCALE_CODE, null)
    }

    // MARK: - Config Cache

    /**
     * Save configuration to cache
     * @param config The configuration to cache
     * @throws ConsentException.StorageError if encoding fails
     */
    fun saveConfigCache(config: ConsentConfig) {
        try {
            val jsonString = json.encodeToString(config)
            prefs.edit().putString(KEY_CONFIG_CACHE, jsonString).apply()
        } catch (e: Exception) {
            throw ConsentException.StorageError("Failed to encode config: ${e.message}", e)
        }
    }

    /**
     * Load cached configuration
     * @return The cached config, or null if none exists
     */
    fun loadConfigCache(): ConsentConfig? {
        val jsonString = prefs.getString(KEY_CONFIG_CACHE, null) ?: return null
        return try {
            json.decodeFromString<ConsentConfig>(jsonString)
        } catch (e: Exception) {
            null
        }
    }

    // MARK: - Pending Events

    /**
     * Save pending events queue
     * @param events List of event JSON strings to save
     * @throws ConsentException.StorageError if encoding fails
     */
    fun savePendingEvents(events: List<String>) {
        try {
            val jsonString = json.encodeToString(events)
            prefs.edit().putString(KEY_PENDING_EVENTS, jsonString).apply()
        } catch (e: Exception) {
            throw ConsentException.StorageError("Failed to encode events: ${e.message}", e)
        }
    }

    /**
     * Load pending events queue
     * @return List of pending event JSON strings, or empty list if none
     */
    fun loadPendingEvents(): List<String> {
        val jsonString = prefs.getString(KEY_PENDING_EVENTS, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<String>>(jsonString)
        } catch (e: Exception) {
            emptyList()
        }
    }

    // MARK: - Clear

    /**
     * Clear all stored consent data
     */
    fun clearAll() {
        prefs.edit().clear().apply()
    }
}

/**
 * What the last successful universal consent sync left on this device (TRUST-2902), persisted next
 * to the bound user hash so it survives a process restart.
 *
 * @property userHash The bound identity this state belongs to. It is ignored for any other identity.
 * @property syncedLocal The stored local choice right after the sync. After an adopt this is the
 *   SIGNAL-RECONCILED view (GPC, the device ad-tracking signal or the record's ccpa_optout can force
 *   categories off), so it must never be written back as though the user chose it. Null when nothing
 *   was stored.
 * @property syncedRaw The RAW choice the record held after the sync: what it wrote, or the record it
 *   adopted. Null on a miss with nothing written, or a signal-only record.
 */
@Serializable
internal data class UniversalConsentSyncState(
    val userHash: String,
    val syncedLocal: ConsentPreferences?,
    val syncedRaw: ConsentPreferences?,
)
