package com.datagrail.consent.network

import com.datagrail.consent.models.ConsentConfig
import com.datagrail.consent.models.ConsentException
import com.datagrail.consent.models.ConsentPreferences
import com.datagrail.consent.models.SignatureProvider
import com.datagrail.consent.models.UniversalConsentPreferences
import com.datagrail.consent.models.UniversalConsentRecord
import com.datagrail.consent.storage.ConsentStorage
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID

/**
 * Service for sending consent data to backend
 */
internal class ConsentService(
    private val networkClient: NetworkClient,
    private val storage: ConsentStorage,
    private val privacyDomain: String,
) {
    @Serializable
    private data class SavePreferencesRequest(
        val consentPolicy: String,
        val policyUuid: String? = null,
        val customerId: String,
        val isCustomised: Boolean,
        val cookieOptions: Map<String, Boolean>,
        val sessionId: String,
        val uniqueId: String,
        val consentContainerVersionId: String,
    )

    // Request/response DTOs for the Universal Consent API. cookieOptions is a MAP on the wire.
    @Serializable
    private data class UniversalConsentInner(
        val isCustomised: Boolean,
        val cookieOptions: Map<String, Boolean>,
    )

    @Serializable
    private data class UniversalSaveRequest(
        val customer_id: String,
        val user_hash: String,
        val consent_preferences: UniversalConsentInner,
        val consent_mode: String,
        val ccpa_optout: Boolean,
        val platform: String,
        val policy_name: String,
        val config_version: String,
    )

    companion object {
        private const val MAX_PENDING_EVENTS = 100
        private const val PLATFORM = "android"

        /**
         * Compute the universal-consent user hash: SHA-256 of
         * "{dgCustomerId}:{consentProjectId}:{identifier}" as lowercase hex (64 chars).
         *
         * The identifier is used VERBATIM — do not trim, lowercase, or normalize it; the hash
         * must match what the customer's backend and other SDKs compute.
         */
        fun computeUserHash(
            dgCustomerId: String,
            consentProjectId: String,
            identifier: String,
        ): String {
            val input = "$dgCustomerId:$consentProjectId:$identifier"
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(input.toByteArray(Charsets.UTF_8))
            return hashBytes.joinToString("") { "%02x".format(it) }
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    private fun encodeParam(value: String): String = URLEncoder.encode(value, "UTF-8")

    /**
     * Universal Consent base URL. This endpoint is a CloudFront behavior, NOT a Rails route, so
     * it has NO `/api/v1/` prefix.
     */
    private fun universalConsentUrl(): String = "https://$privacyDomain/universal_consent"

    /**
     * Read a user's universal consent record for cross-device rehydration.
     *
     * GET /universal_consent?customer_id=..&user_hash=.. with an X-DG-Api-Key header.
     * The returned record is RAW and UNRECONCILED — callers must apply GPC reconciliation
     * before acting on it (see GpcReconciliation).
     *
     * @return the parsed record, or null when the server responds `{ "status": "not_found" }`.
     * @throws ConsentException.NetworkError on network/parse failure.
     */
    suspend fun getUniversalConsent(
        config: ConsentConfig,
        identifier: String,
        apiKey: String,
    ): UniversalConsentRecord? {
        val projectId =
            config.consentProjectId
                ?: throw ConsentException.ValidationError("consentProjectId is required for universal consent")

        val userHash = computeUserHash(config.dgCustomerId, projectId, identifier)
        val url =
            universalConsentUrl() +
                "?customer_id=${encodeParam(config.dgCustomerId)}" +
                "&user_hash=${encodeParam(userHash)}"

        try {
            val response =
                networkClient.request(
                    url = url,
                    method = HTTPMethod.GET,
                    headers = mapOf("X-DG-Api-Key" to apiKey),
                )
            val record = json.decodeFromString<UniversalConsentRecord>(response)
            return if (record.isFound) record else null
        } catch (e: ConsentException) {
            throw e
        } catch (e: Exception) {
            throw ConsentException.NetworkError("Failed to read universal consent: ${e.message}")
        }
    }

    /**
     * Write a user's universal consent preferences for cross-device retrieval.
     *
     * POST /universal_consent. The SDK does NOT compute the HMAC — it invokes the customer's
     * [getSignature] provider (which calls the customer's own backend) and attaches the result
     * as X-DG-Signature / X-DG-Timestamp (unix seconds) / X-DG-Key-Id headers, plus X-DG-Nonce.
     * The shared secret never touches the device.
     *
     * @throws ConsentException.NetworkError on failure (also queues nothing — universal writes
     *   are user-identity-scoped and not part of the anonymous pending-events retry queue).
     */
    suspend fun saveUniversalConsent(
        config: ConsentConfig,
        identifier: String,
        preferences: UniversalConsentPreferences,
        apiKey: String,
        getSignature: SignatureProvider,
    ) {
        val projectId =
            config.consentProjectId
                ?: throw ConsentException.ValidationError("consentProjectId is required for universal consent")

        val userHash = computeUserHash(config.dgCustomerId, projectId, identifier)

        // Ask the customer's backend to sign. Secret never leaves their backend.
        val sig = getSignature(config.dgCustomerId, userHash)

        val requestBody =
            UniversalSaveRequest(
                customer_id = config.dgCustomerId,
                user_hash = userHash,
                consent_preferences =
                    UniversalConsentInner(
                        isCustomised = preferences.isCustomised,
                        cookieOptions = preferences.cookieOptions,
                    ),
                consent_mode = config.consentMode,
                ccpa_optout = config.universalConsent?.syncOptout ?: false,
                platform = PLATFORM,
                policy_name = config.consentPolicy.name,
                config_version = config.version,
            )

        val headers =
            mapOf(
                "X-DG-Api-Key" to apiKey,
                "X-DG-Signature" to sig.signature,
                "X-DG-Timestamp" to sig.timestamp.toString(),
                "X-DG-Key-Id" to sig.keyId,
                "X-DG-Nonce" to UUID.randomUUID().toString(),
            )

        try {
            networkClient.request(
                url = universalConsentUrl(),
                method = HTTPMethod.POST,
                body = json.encodeToString(requestBody),
                headers = headers,
            )
        } catch (e: ConsentException) {
            throw e
        } catch (e: Exception) {
            throw ConsentException.NetworkError("Failed to save universal consent: ${e.message}")
        }
    }

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
                consentPolicy = config.consentPolicy.name,
                policyUuid = config.consentPolicy.uuid,
                customerId = config.dgCustomerId,
                isCustomised = preferences.isCustomised,
                cookieOptions = cookieOptionsMap,
                sessionId = sessionId,
                uniqueId = uniqueId,
                consentContainerVersionId = config.consentContainerVersionId,
            )

        val url = "https://$privacyDomain/save_preferences"
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
            if (existingEvents.size > MAX_PENDING_EVENTS) {
                existingEvents.subList(0, existingEvents.size - MAX_PENDING_EVENTS).clear()
            }
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

        val policyUuidParam = config.consentPolicy.uuid?.let { "&policy_uuid=${encodeParam(it)}" } ?: ""
        val url =
            "https://$privacyDomain/save_open" +
                "?customer=${encodeParam(config.dgCustomerId)}" +
                "&sessionId=${encodeParam(sessionId)}" +
                "&uniqueId=${encodeParam(uniqueId)}" +
                "&policy_name=${encodeParam(config.consentPolicy.name)}" +
                "&consent_container_version_id=${encodeParam(config.consentContainerVersionId)}" +
                policyUuidParam

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
            if (existingEvents.size > MAX_PENDING_EVENTS) {
                existingEvents.subList(0, existingEvents.size - MAX_PENDING_EVENTS).clear()
            }
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
