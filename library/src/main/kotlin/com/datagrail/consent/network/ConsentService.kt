package com.datagrail.consent.network

import com.datagrail.consent.models.ConsentConfig
import com.datagrail.consent.models.ConsentException
import com.datagrail.consent.models.ConsentPreferences
import com.datagrail.consent.models.SignatureProvider
import com.datagrail.consent.models.UniversalConsentPreferences
import com.datagrail.consent.models.UniversalConsentRecord
import com.datagrail.consent.models.UniversalConsentSigningPayload
import com.datagrail.consent.storage.ConsentStorage
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Locale
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

    // Request DTO for the Universal Consent API. cookieOptions is a MAP on the wire; the nested
    // consent_preferences reuses the public UniversalConsentPreferences model (single source of
    // truth for the wire shape, shared with the read path) so the two paths cannot drift.
    @Serializable
    private data class UniversalSaveRequest(
        val customer_id: String,
        val user_hash: String,
        val consent_preferences: UniversalConsentPreferences,
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
         * Normalize a user identifier before hashing: Unicode NFC → trim → lowercase.
         *
         * CANONICAL CONTRACT (TRUST-1843 — identical across all repos, do not deviate):
         * every site that computes a user_hash MUST apply these three steps in this order, so
         * the same person yields the same hash from web, iOS, Android, and the customer's own
         * backend helper. Skipping this silently splits one user into multiple records and
         * their consent stops following them across devices.
         *
         * The edge handler that validates these hashes computes "SHA-256 over the normalized
         * user identifier" the same way; see the TRUST-1843 design for the full derivation.
         *
         * Lowercasing is pinned to [Locale.ROOT]: the default-locale overload would map
         * "I" to the dotless "ı" on a Turkish device, so the same identifier would hash
         * differently depending on the user's phone settings.
         */
        fun normalizeUserIdentifier(identifier: String): String =
            Normalizer.normalize(identifier, Normalizer.Form.NFC).trim().lowercase(Locale.ROOT)

        /**
         * Compute the universal-consent user hash: SHA-256 of
         * "{dgCustomerId}:{consentProjectId}:{normalizedIdentifier}" as lowercase hex
         * (64 chars). The identifier is normalized first via [normalizeUserIdentifier] —
         * see the contract note there.
         */
        fun computeUserHash(
            dgCustomerId: String,
            consentProjectId: String,
            identifier: String,
        ): String {
            val normalized = normalizeUserIdentifier(identifier)
            // Reject an identifier that is empty AFTER normalizing. SHA-256 over a bare
            // "{customerId}:{projectId}:" prefix is a valid-looking hash that every
            // empty-or-whitespace caller in the tenant shares, collapsing unrelated users
            // onto a single consent record. Checking the raw string is not enough — "   "
            // trims away to nothing.
            if (normalized.isEmpty()) {
                throw ConsentException.ValidationError(
                    "identifier must not be empty after normalization",
                )
            }
            val input = "$dgCustomerId:$consentProjectId:$normalized"
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(input.toByteArray(Charsets.UTF_8))
            return hashBytes.joinToString("") { "%02x".format(it) }
        }
    }

    // encodeDefaults is REQUIRED on the write path. kotlinx.serialization omits properties that
    // still hold their declared default, so a default-constructed UniversalConsentPreferences
    // would serialize as `"consent_preferences": {}` — dropping both `isCustomised` and the
    // `cookieOptions` map the edge expects. That is not hypothetical: an unbannered user's
    // preferences legitimately carry isCustomised=false and an empty map.
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    // CSPRNG for the per-write universal-consent nonce. SecureRandom is thread-safe, so a single
    // shared instance is fine. Not UUID.randomUUID(): that yields a 36-char hyphenated string,
    // while the canonical contract requires a 128-bit value as 32 lowercase hex.
    private val secureRandom = SecureRandom()

    /**
     * Generate the 128-bit universal-consent nonce as 32 lowercase hex characters, per the
     * canonical cross-SDK write contract (TRUST-1843). 16 CSPRNG bytes -> hex.
     */
    private fun generateNonce(): String {
        val bytes = ByteArray(16)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

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
            throw ConsentException.NetworkError("Failed to read universal consent: ${e.message}", e)
        }
    }

    /**
     * Write a user's universal consent preferences for cross-device retrieval.
     *
     * POST /universal_consent. The SDK does NOT compute the HMAC — it builds the canonical
     * string-to-sign, generates the per-write timestamp (device unix seconds) and nonce (128-bit
     * CSPRNG value as 32 lowercase hex) ITSELF, and hands them to the customer's [getSignature]
     * provider inside a [UniversalConsentSigningPayload]. The provider (which calls the
     * customer's own backend) returns just `{ signature, keyId }`; the SDK attaches those plus
     * its own timestamp and nonce as the X-DG-Signature / X-DG-Timestamp / X-DG-Nonce /
     * X-DG-Key-Id headers. The value the backend signs over is exactly the value the SDK sends,
     * so the edge can verify it. The shared secret never touches the device.
     *
     * Limited mode: when [getSignature] is null no signing callback is configured, so the write
     * goes out with X-DG-Api-Key ONLY — no signature, timestamp, or nonce headers.
     *
     * @param ccpaOptout the user's CCPA/US do-not-sell choice. Only written to the record when
     *   the `universalConsent.syncOptout` feature flag is enabled; otherwise `false`. This is
     *   NOT derived from the device's ad-tracking signal — that signal is narrower than a
     *   do-not-sell choice, so Android currently has no source for this value and passes `false`.
     * @param getSignature the customer signing callback, or null for a limited-mode
     *   (API-key-only) write.
     * @throws ConsentException.NetworkError on failure (also queues nothing — universal writes
     *   are user-identity-scoped and not part of the anonymous pending-events retry queue).
     */
    suspend fun saveUniversalConsent(
        config: ConsentConfig,
        identifier: String,
        preferences: UniversalConsentPreferences,
        apiKey: String,
        ccpaOptout: Boolean,
        getSignature: SignatureProvider?,
    ) {
        val projectId =
            config.consentProjectId
                ?: throw ConsentException.ValidationError("consentProjectId is required for universal consent")

        val userHash = computeUserHash(config.dgCustomerId, projectId, identifier)

        val requestBody =
            UniversalSaveRequest(
                customer_id = config.dgCustomerId,
                user_hash = userHash,
                consent_preferences = preferences,
                consent_mode = config.consentMode,
                // The user's actual CCPA/US opt-out value, only synced when the
                // universalConsent.syncOptout feature flag is enabled (otherwise false).
                // syncOptout is a feature gate, NOT the opt-out value itself.
                ccpa_optout = (config.universalConsent?.syncOptout == true) && ccpaOptout,
                platform = PLATFORM,
                policy_name = config.consentPolicy.name,
                config_version = config.version,
            )

        val headers =
            if (getSignature == null) {
                // Limited mode: no signing callback configured -> API-key-only write.
                mapOf("X-DG-Api-Key" to apiKey)
            } else {
                // The SDK owns the timestamp and nonce and binds them into the string the
                // customer backend signs, so the value the edge verifies is byte-for-byte the
                // value we send. The nonce is 128-bit / 32 lowercase hex from a CSPRNG.
                val timestamp = System.currentTimeMillis() / 1000
                val nonce = generateNonce()
                val stringToSign = "${config.dgCustomerId}:$userHash:$timestamp:$nonce"
                val payload =
                    UniversalConsentSigningPayload(
                        stringToSign = stringToSign,
                        customerId = config.dgCustomerId,
                        userHash = userHash,
                        timestamp = timestamp,
                        nonce = nonce,
                    )
                // Ask the customer's backend to sign. Secret never leaves their backend.
                val sig = getSignature(payload)
                mapOf(
                    "X-DG-Api-Key" to apiKey,
                    "X-DG-Signature" to sig.signature,
                    "X-DG-Timestamp" to timestamp.toString(),
                    "X-DG-Nonce" to nonce,
                    "X-DG-Key-Id" to sig.keyId,
                )
            }

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
            throw ConsentException.NetworkError("Failed to save universal consent: ${e.message}", e)
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
