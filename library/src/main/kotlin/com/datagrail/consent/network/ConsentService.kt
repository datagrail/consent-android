package com.datagrail.consent.network

import android.os.Build
import com.datagrail.consent.BuildConfig
import com.datagrail.consent.models.ConsentConfig
import com.datagrail.consent.models.ConsentException
import com.datagrail.consent.models.ConsentPreferences
import com.datagrail.consent.models.SignatureProvider
import com.datagrail.consent.models.UniversalConsentPreferences
import com.datagrail.consent.models.UniversalConsentRecord
import com.datagrail.consent.models.UniversalConsentSigningPayload
import com.datagrail.consent.storage.ConsentStorage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
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
    // Dispatcher the customer's getSignature callback runs on. Defaults to Dispatchers.IO so a
    // blocking signer never touches the main thread; injectable so tests can drive the bounded
    // wait on their virtual-time scheduler instead of a real background thread.
    private val signingDispatcher: CoroutineDispatcher = Dispatchers.IO,
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
        @SerialName("customer_id") val customerId: String,
        @SerialName("user_hash") val userHash: String,
        @SerialName("consent_preferences") val consentPreferences: UniversalConsentPreferences,
        @SerialName("consent_mode") val consentMode: String,
        @SerialName("ccpa_optout") val ccpaOptout: Boolean,
        val platform: String,
        @SerialName("policy_name") val policyName: String,
        @SerialName("config_version") val configVersion: String,
    )

    companion object {
        private const val MAX_PENDING_EVENTS = 100
        private const val PLATFORM = "android"

        /**
         * The consent-schema version this SDK's models are written against. This SDK mirrors the
         * v1 wire format (byte-equivalent legacy shape) and doesn't consume consent-schema's
         * generated types, so this is a build-time constant rather than anything derived from the
         * wire format or fetched config — bump it only when this SDK's models are rewritten
         * against a newer schema version.
         */
        private const val SCHEMA_VERSION = "v1"

        /**
         * Ceiling on how long a universal-consent write waits for the customer's `getSignature`
         * callback to return. Signing round-trips to the customer's own backend, so this is set
         * suitably high — but not forever. Past this deadline the write fails with
         * [ConsentException.SignatureTimeout] rather than leaving the caller's [ConsentCallback]
         * suspended indefinitely behind an unresponsive signer.
         */
        internal const val SIGNATURE_TIMEOUT_MS = 30_000L

        /** Lowercase-hex encode a byte array. Single source for both the user_hash and nonce. */
        private fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

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
            return toHex(hashBytes)
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
        return toHex(bytes)
    }

    private fun encodeParam(value: String): String = URLEncoder.encode(value, "UTF-8")

    /**
     * Universal Consent base URL. This endpoint is a CloudFront behavior, NOT a Rails route, so
     * it has NO `/api/v1/` prefix.
     */
    private fun universalConsentUrl(): String = "https://$privacyDomain/universal_consent"

    /**
     * The consent project id, which every universal-consent write/read hashes the user against.
     * `ConsentManager` gates callers on [ConsentConfig.universalConsentReady] first, so by the time
     * a request reaches here a non-null id is a guaranteed precondition; this is the single
     * defensive check for it rather than an inline `?:` repeated in every method.
     */
    private fun requireProjectId(config: ConsentConfig): String =
        config.consentProjectId
            ?: throw ConsentException.ValidationError("consentProjectId is required for universal consent")

    /**
     * Read a user's universal consent record for cross-device rehydration.
     *
     * GET /universal_consent?customer_id=..&user_hash=.. with an X-DG-Api-Key header.
     * The returned record is RAW and UNRECONCILED — callers must apply signal reconciliation
     * before acting on it (see [SignalReconciliation]).
     *
     * @return the parsed record, or null when the server responds `{ "status": "not_found" }`.
     * @throws ConsentException.NetworkError on network/parse failure, or on any status that is
     *   neither `found` nor `not_found` — an unrecognized status is an ambiguous read, not a clean
     *   miss, and treating it as a miss would let the read-then-write path overwrite a record we
     *   could not actually read (the TRUST-2491 corruption class). A read failure must block the write.
     */
    suspend fun getUniversalConsent(
        config: ConsentConfig,
        identifier: String,
        apiKey: String,
    ): UniversalConsentRecord? {
        val projectId = requireProjectId(config)

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
            if (record.isFound) return record
            // Only the documented "not_found" is a genuine miss. Any other status (a degraded/error
            // string, or a future value this SDK version predates) is an ambiguous read: returning
            // null here would let the caller adopt-and-write over a record it never read. Fail loud
            // so the read failure blocks the write instead.
            if (record.status == "not_found") return null
            throw ConsentException.NetworkError("Unexpected universal consent status: ${record.status}")
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
        val projectId = requireProjectId(config)

        val userHash = computeUserHash(config.dgCustomerId, projectId, identifier)

        val requestBody =
            UniversalSaveRequest(
                customerId = config.dgCustomerId,
                userHash = userHash,
                consentPreferences = preferences,
                consentMode = config.consentMode,
                // The user's actual CCPA/US opt-out value, only synced when the
                // universalConsent.syncOptout feature flag is enabled (otherwise false).
                // syncOptout is a feature gate, NOT the opt-out value itself.
                ccpaOptout = (config.universalConsent?.syncOptout == true) && ccpaOptout,
                platform = PLATFORM,
                policyName = config.consentPolicy.name,
                configVersion = config.version,
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
                        // The RAW identifier, so the backend can bind signing to its authenticated
                        // session and recompute the hash itself rather than blindly signing a
                        // client-supplied userHash for whoever the caller named.
                        identifier = identifier,
                        userHash = userHash,
                        timestamp = timestamp,
                        nonce = nonce,
                    )
                // Ask the customer's backend to sign. Secret never leaves their backend. Run on
                // Dispatchers.IO: the public entry points launch on Dispatchers.Main, and a customer
                // implementation that does a blocking network call inside getSignature would throw
                // NetworkOnMainThreadException on Main. This honors SignatureProviderCallback's "may
                // be called from a background thread" contract.
                //
                // Bound the wait with SIGNATURE_TIMEOUT_MS: signing round-trips to a customer
                // backend, so the ceiling is suitably high, but a signer that never returns must
                // not leave the caller's ConsentCallback suspended forever. On timeout the write
                // fails with ConsentException.SignatureTimeout. The resume-once contract in
                // asSignatureProvider still guards against a provider that resumes more than once.
                val sig =
                    try {
                        withTimeout(SIGNATURE_TIMEOUT_MS) {
                            withContext(signingDispatcher) { getSignature(payload) }
                        }
                    } catch (e: TimeoutCancellationException) {
                        throw ConsentException.SignatureTimeout(
                            "getSignature did not return within $SIGNATURE_TIMEOUT_MS ms",
                        )
                    }
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
                "&library_version=${encodeParam(BuildConfig.LIBRARY_VERSION)}" +
                "&os_version=${encodeParam(Build.VERSION.RELEASE ?: "")}" +
                "&schema_version=${encodeParam(SCHEMA_VERSION)}" +
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
