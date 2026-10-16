package com.datagrail.consent.network

import com.datagrail.consent.models.CategoryConsent
import com.datagrail.consent.models.ConsentPreferences
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLEncoder

/**
 * Service for CTV QR pairing - fetches remote consent preferences from Universal Consent API
 */
class PairingService(
    private val networkClient: NetworkClient,
    private val apiBaseUrl: String,
    private val apiKey: String?,
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    /**
     * Build the QR URL for CTV pairing flow
     *
     * @param publicBaseUrl The base URL for the phone consent page
     * @param customerId The customer ID
     * @param userHash The user hash (64-char hex)
     * @param configUrl The config URL
     * @return Complete QR URL with query parameters
     */
    fun qrUrl(
        publicBaseUrl: String,
        customerId: String,
        userHash: String,
        configUrl: String,
    ): String {
        // Pure-JVM string assembly (no android.net.Uri) so this is unit-testable
        // off-device. Each value is form-encoded exactly once.
        val base = publicBaseUrl.trimEnd('/')
        val query =
            listOf(
                "customer_id" to customerId,
                "user_hash" to userHash,
                "config_url" to configUrl,
            ).joinToString("&") { (k, v) ->
                "$k=${URLEncoder.encode(v, "UTF-8")}"
            }
        return "$base/tv?$query"
    }

    /**
     * Fetch consent preferences from the Universal Consent API
     *
     * @param customerId The customer ID
     * @param userHash The user hash
     * @return PairingRead result (NotFound or Found with preferences and updated_at)
     */
    suspend fun fetchConsent(
        customerId: String,
        userHash: String,
    ): PairingRead {
        val base = apiBaseUrl.trimEnd('/')
        val query =
            listOf(
                "customer_id" to customerId,
                "user_hash" to userHash,
            ).joinToString("&") { (k, v) ->
                "$k=${URLEncoder.encode(v, "UTF-8")}"
            }
        val url = "$base/universal_consent?$query"

        val headers = mutableMapOf("Cache-Control" to "no-cache")
        apiKey?.let { headers["X-DG-Api-Key"] = it }

        val responseBody = networkClient.request(url, HTTPMethod.GET, headers = headers)

        // Parse the boundary response type that has the server MAP shape
        val response = json.decodeFromString<PairingReadResponse>(responseBody)

        return when (response.status) {
            "not_found" -> PairingRead.NotFound
            "found" -> {
                // Adapt the server MAP cookieOptions -> SDK ARRAY of CategoryConsent
                val cookieOptions =
                    response.consentPreferences?.cookieOptions?.map { (gtmKey, isEnabled) ->
                        CategoryConsent(gtmKey = gtmKey, isEnabled = isEnabled)
                    } ?: emptyList()

                val preferences =
                    ConsentPreferences(
                        isCustomised = response.consentPreferences?.isCustomised ?: false,
                        cookieOptions = cookieOptions,
                    )

                PairingRead.Found(
                    preferences = preferences,
                    updatedAt = response.updatedAt,
                )
            }
            else -> PairingRead.NotFound
        }
    }

    /**
     * Boundary response type that decodes the server's MAP cookieOptions shape
     * This adapter is critical - the Universal Consent API returns cookieOptions as a map,
     * but the SDK's ConsentPreferences models it as an array
     */
    @Serializable
    private data class PairingReadResponse(
        @SerialName("status")
        val status: String,
        @SerialName("consent_preferences")
        val consentPreferences: ServerConsentPreferences? = null,
        // The server returns updated_at as a TOP-LEVEL sibling of consent_preferences
        // (see test-server models.py to_get_response), NOT nested inside it. The
        // baseline-new-write detection in PairingCoordinator depends on this being
        // populated, so it must be decoded at this level.
        @SerialName("updated_at")
        val updatedAt: String? = null,
    )

    @Serializable
    private data class ServerConsentPreferences(
        @SerialName("isCustomised")
        val isCustomised: Boolean,
        @SerialName("cookieOptions")
        val cookieOptions: Map<String, Boolean>,
    )
}

/**
 * Result of a pairing fetch operation
 */
sealed class PairingRead {
    /** No consent record found for this user_hash */
    data object NotFound : PairingRead()

    /** Consent record found */
    data class Found(
        val preferences: ConsentPreferences,
        val updatedAt: String?,
    ) : PairingRead()
}
