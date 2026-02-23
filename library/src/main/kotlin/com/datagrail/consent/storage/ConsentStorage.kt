package com.datagrail.consent.storage

import android.content.Context
import android.content.SharedPreferences
import com.datagrail.consent.models.ConsentConfig
import com.datagrail.consent.models.ConsentException
import com.datagrail.consent.models.ConsentPreferences
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Handles local storage of consent data using SharedPreferences
 */
class ConsentStorage(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    companion object {
        private const val PREFS_NAME = "com.datagrail.consent.prefs"
        private const val KEY_PREFERENCES = "datagrail_consent_preferences"
        private const val KEY_UNIQUE_ID = "datagrail_consent_id"
        private const val KEY_VERSION = "datagrail_consent_version"
        private const val KEY_LOCALE_CODE = "datagrail_consent_locale_code"
        private const val KEY_CONFIG_CACHE = "datagrail_consent_config_cache"
        private const val KEY_PENDING_EVENTS = "datagrail_consent_pending_events"
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
