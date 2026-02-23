package com.datagrail.consent.network

import com.datagrail.consent.models.ConsentConfig
import com.datagrail.consent.models.ConsentException
import com.datagrail.consent.models.ConsentPreferences
import com.datagrail.consent.storage.ConsentStorage
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Service for sending consent data to backend
 */
class ConsentService(
    private val networkClient: NetworkClient,
    private val storage: ConsentStorage,
    private val privacyDomain: String,
) {
    @Serializable
    private data class SavePreferencesRequest(
        val consentPolicy: String,
        val customerId: String,
        val isCustomised: Boolean,
        val cookieOptions: Map<String, Boolean>,
        val sessionId: String,
        val uniqueId: String,
    )

    /**
     * Save consent preferences to backend
     * @param preferences The consent preferences to save
     * @param config The consent configuration
     * @throws ConsentException on failure
     */
    suspend fun savePreferences(
        preferences: ConsentPreferences,
        config: ConsentConfig,
    ) {
        val uniqueId = storage.getOrCreateUniqueId()
        val sessionId = UUID.randomUUID().toString()

        // Build cookie options map
        val cookieOptionsMap =
            preferences.cookieOptions.associate {
                it.gtmKey to it.isEnabled
            }

        val requestBody =
            SavePreferencesRequest(
                consentPolicy = "ConsentPolicy",
                customerId = config.dgCustomerId,
                isCustomised = preferences.isCustomised,
                cookieOptions = cookieOptionsMap,
                sessionId = sessionId,
                uniqueId = uniqueId,
            )

        val url = "https://$privacyDomain/api/v1/save_preferences"
        val jsonBody = Json.encodeToString(requestBody)

        try {
            networkClient.request(
                url = url,
                method = HTTPMethod.POST,
                body = jsonBody,
            )

            // Save locally after successful backend save
            storage.savePreferences(preferences)
            storage.saveConfigVersion(config.version)
        } catch (e: Exception) {
            // Queue for retry on failure
            val eventJson =
                Json.encodeToString(
                    mapOf(
                        "type" to "save_preferences",
                        "url" to url,
                        "body" to jsonBody,
                        "timestamp" to System.currentTimeMillis().toString(),
                    ),
                )

            val existingEvents = storage.loadPendingEvents().toMutableList()
            existingEvents.add(eventJson)
            storage.savePendingEvents(existingEvents)

            // Still save locally
            storage.savePreferences(preferences)
            storage.saveConfigVersion(config.version)

            throw ConsentException.NetworkError("Failed to save preferences: ${e.message}")
        }
    }

    /**
     * Save banner open event to backend
     * @param config The consent configuration
     * @throws ConsentException on failure
     */
    suspend fun saveOpen(config: ConsentConfig) {
        val uniqueId = storage.getOrCreateUniqueId()
        val sessionId = UUID.randomUUID().toString()

        val url =
            "https://$privacyDomain/api/v1/save_open" +
                "?customerId=${config.dgCustomerId}" +
                "&sessionId=$sessionId" +
                "&uniqueId=$uniqueId" +
                "&consentPolicy=ConsentPolicy"

        try {
            networkClient.request(url = url, method = HTTPMethod.GET)
        } catch (e: Exception) {
            // Queue for retry on failure
            val eventJson =
                Json.encodeToString(
                    mapOf(
                        "type" to "save_open",
                        "url" to url,
                        "timestamp" to System.currentTimeMillis().toString(),
                    ),
                )

            val existingEvents = storage.loadPendingEvents().toMutableList()
            existingEvents.add(eventJson)
            storage.savePendingEvents(existingEvents)

            // Don't throw - saveOpen is fire-and-forget analytics
        }
    }

    /**
     * Retry any pending requests that failed previously
     * @return Pair of (successCount, failureCount)
     */
    suspend fun retryPendingRequests(): Pair<Int, Int> {
        val pendingEvents = storage.loadPendingEvents()
        if (pendingEvents.isEmpty()) {
            return Pair(0, 0)
        }

        var successCount = 0
        var failureCount = 0
        val remainingEvents = mutableListOf<String>()

        for (eventJson in pendingEvents) {
            try {
                val event = Json.decodeFromString<Map<String, String>>(eventJson)
                val type = event["type"] ?: continue
                val url = event["url"] ?: continue

                when (type) {
                    "save_preferences" -> {
                        val body = event["body"] ?: continue
                        networkClient.request(url = url, method = HTTPMethod.POST, body = body)
                        successCount++
                    }
                    "save_open" -> {
                        networkClient.request(url = url, method = HTTPMethod.GET)
                        successCount++
                    }
                }
            } catch (e: Exception) {
                failureCount++
                // Keep failed events for next retry
                remainingEvents.add(eventJson)
            }
        }

        // Update pending events (remove successful ones)
        storage.savePendingEvents(remainingEvents)

        return Pair(successCount, failureCount)
    }
}
