package com.datagrail.consent.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Signature material returned by the customer-provided `getSignature` callback.
 *
 * The DataGrail SDK never computes the HMAC itself — the shared secret lives only on the
 * customer's backend and at the DataGrail edge. The customer's backend computes
 * `HMAC-SHA256(secret, "{customerId}:{userHash}:{timestamp}")` and returns the resulting
 * [signature] together with the [keyId] (identifies which secret was used, for rotation) and
 * the [timestamp] (unix seconds) that was signed over. The SDK attaches these as request
 * headers on the universal-consent write.
 *
 * @property signature Hex-encoded HMAC-SHA256 signature computed by the customer backend.
 * @property keyId Identifier of the HMAC secret used (supports key rotation).
 * @property timestamp Unix timestamp in seconds that the signature was computed over.
 */
data class UniversalConsentSignature(
    val signature: String,
    val keyId: String,
    val timestamp: Long,
)

/**
 * Customer-provided signature provider. Invoked by the SDK immediately before a universal
 * consent write. It is a suspend function so the customer can call their own backend on an IO
 * dispatcher. The SDK passes the [customerId] and computed [userHash] so the customer backend
 * can build the exact string-to-sign the edge will recompute.
 */
typealias SignatureProvider = suspend (customerId: String, userHash: String) -> UniversalConsentSignature

/**
 * Consent preferences as stored in / returned by the universal consent API. Unlike
 * [ConsentPreferences] (which uses a list of [CategoryConsent]), the universal consent wire
 * format uses a MAP of `{ categoryKey: Boolean }` for `cookieOptions`.
 */
@Serializable
data class UniversalConsentPreferences(
    @SerialName("isCustomised")
    val isCustomised: Boolean = false,
    @SerialName("cookieOptions")
    val cookieOptions: Map<String, Boolean> = emptyMap(),
)

/**
 * A universal consent record returned by `GET /universal_consent`.
 *
 * IMPORTANT: this data is RAW and UNRECONCILED. The server never computes an "effective"
 * consent state — it returns the stored [consentPreferences] plus the IAB signals
 * ([gpc], [tcfString], [gppString]) exactly as written. Clients MUST reconcile GPC locally
 * before acting on consent (see [GpcReconciliation]).
 */
@Serializable
data class UniversalConsentRecord(
    val status: String,
    @SerialName("consent_preferences")
    val consentPreferences: UniversalConsentPreferences? = null,
    @SerialName("consent_mode")
    val consentMode: String? = null,
    @SerialName("ccpa_optout")
    val ccpaOptout: Boolean = false,
    val platform: String? = null,
    @SerialName("policy_name")
    val policyName: String? = null,
    @SerialName("config_version")
    val configVersion: String? = null,
    @SerialName("updated_at")
    val updatedAt: String? = null,
    // Raw IAB signals — passed through by the server, reconciled by the client.
    val gpc: Boolean = false,
    @SerialName("tcf_string")
    val tcfString: String? = null,
    @SerialName("gpp_string")
    val gppString: String? = null,
) {
    val isFound: Boolean get() = status == "found"
}

/**
 * Mandatory client-side GPC reconciliation.
 *
 * The Universal Consent API returns raw, unreconciled data. When a record (or a live OS/browser
 * signal) has `gpc == true`, every SDK MUST suppress non-essential categories locally,
 * regardless of what the stored map says — the stored map may still show `marketing: true`.
 * A client that naively trusts the stored booleans would fire marketing tags for a GPC opt-out
 * user, which is a compliance failure.
 *
 * Effective state = stored preferences ∘ GPC reconciliation, computed on-device.
 */
object GpcReconciliation {
    /**
     * Apply GPC reconciliation to a cookie-options map.
     *
     * @param cookieOptions The raw stored `{ categoryKey: Boolean }` map.
     * @param gpc The effective GPC signal (true = user has opted out via GPC).
     * @param essentialKeys The set of category keys that are essential / always-on and therefore
     *   never suppressed by GPC.
     * @return A new map where, when [gpc] is true, every non-essential category is forced to
     *   `false`. When [gpc] is false the input map is returned unchanged.
     */
    fun reconcile(
        cookieOptions: Map<String, Boolean>,
        gpc: Boolean,
        essentialKeys: Set<String>,
    ): Map<String, Boolean> {
        if (!gpc) return cookieOptions
        return cookieOptions.mapValues { (key, enabled) ->
            if (essentialKeys.contains(key)) enabled else false
        }
    }
}
