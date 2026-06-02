package com.datagrail.consent.network

import android.net.Uri
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
        val encodedConfigUrl = URLEncoder.encode(configUrl, "UTF-8")
        return Uri
            .parse(publicBaseUrl.trimEnd('/'))
            .buildUpon()
            .appendPath("tv")
            .appendQueryParameter("customer_id", customerId)
            .appendQueryParameter("user_hash", userHash)
            .appendQueryParameter("config_url", encodedConfigUrl)
            .build()
            .toString()
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
        val url =
            Uri
                .parse(apiBaseUrl.trimEnd('/'))
                .buildUpon()
                .appendPath("universal_consent")
                .appendQueryParameter("customer_id", customerId)
                .appendQueryParameter("user_hash", userHash)
                .build()
                .toString()

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
                    updatedAt = response.consentPreferences?.updatedAt,
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
    )

    @Serializable
    private data class ServerConsentPreferences(
        @SerialName("isCustomised")
        val isCustomised: Boolean,
        @SerialName("cookieOptions")
        val cookieOptions: Map<String, Boolean>,
        @SerialName("updated_at")
        val updatedAt: String? = null,
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
