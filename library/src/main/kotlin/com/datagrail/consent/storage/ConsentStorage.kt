package com.datagrail.consent.storage

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.datagrail.consent.models.ConsentConfig
import com.datagrail.consent.models.ConsentException
import com.datagrail.consent.models.ConsentPreferences
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
     * Save consent preferences to local storage
     * @param preferences The consent preferences to save
     * @throws ConsentException.StorageError if encoding fails
     */
    fun savePreferences(preferences: ConsentPreferences) {
        try {
            val jsonString = json.encodeToString(preferences)
            prefs.edit().putString(KEY_PREFERENCES, jsonString).apply()
        } catch (e: Exception) {
            throw ConsentException.StorageError("Failed to encode preferences: ${e.message}", e)
        }
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
