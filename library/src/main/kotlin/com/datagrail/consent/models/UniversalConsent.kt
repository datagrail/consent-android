package com.datagrail.consent.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Signature material returned by the customer-provided `getSignature` callback.
 *
 * The DataGrail SDK never computes the HMAC itself — the shared secret lives only on the
 * customer's backend and at the DataGrail edge. The customer's backend computes
 * `HMAC-SHA256(rawSecretBytes, "{customerId}:{userHash}:{timestamp}:{nonce}")` and returns the
 * resulting [signature] together with the [keyId] (identifies which secret was used, for
 * rotation) and the [timestamp] (unix seconds) that was signed over. The SDK attaches these as
 * request headers on the universal-consent write.
 *
 * Two things the edge is strict about, so the backend MUST match them exactly:
 *  - Secret as raw bytes: the shared secret is 64 hex characters. Decode it to its 32 raw bytes
 *    and use THOSE as the HMAC key. Do NOT feed the 64-char hex string to the HMAC as ASCII /
 *    UTF-8 text — that is the single most common signing bug, and every signature computed that
 *    way fails to verify at the edge.
 *  - Nonce binding: the 128-bit nonce (32 lowercase hex, fresh per write) is part of the
 *    string-to-sign AND is sent in the `X-DG-Nonce` header. The value in the signed string and
 *    the value in the header MUST be identical, or verification fails.
 *
 * [signature] is lowercase hex.
 *
 * @property signature Lowercase-hex HMAC-SHA256 signature computed by the customer backend.
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
 * dispatcher. The SDK passes the [customerId] and computed [userHash]; the backend supplies the
 * [UniversalConsentSignature.timestamp] it signed over.
 *
 * NOTE: the canonical string-to-sign also includes the per-write nonce
 * (`{customerId}:{userHash}:{timestamp}:{nonce}` — see [UniversalConsentSignature]). This
 * provider does not yet carry the nonce, so the backend and the SDK cannot currently agree on
 * one nonce value; making writes verify at the edge requires plumbing the nonce through the
 * provider (see TRUST-1843 report).
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
 * Mandatory client-side signal reconciliation.
 *
 * The Universal Consent API returns raw, unreconciled data. When an opt-out signal applies —
 * either the record's stored `gpc` (recorded on the web, where GPC exists) or this device's live
 * ad-tracking signal — every SDK MUST suppress non-essential categories locally, regardless of
 * what the stored map says. The stored map may still show `marketing: true`, and a client that
 * naively trusts those booleans would fire marketing tags for an opted-out user.
 *
 * Suppression is one-directional. A signal may only turn categories OFF; it never turns one on.
 * Ad-tracking permission is not consent to marketing categories, and an unreadable signal is not
 * a choice at all, so neither state modifies the stored map.
 *
 * Effective state = stored preferences ∘ suppression, computed on-device and never persisted in
 * place of the raw stored choice.
 */
object SignalReconciliation {
    /**
     * Apply signal reconciliation to a cookie-options map.
     *
     * @param cookieOptions The raw stored `{ categoryKey: Boolean }` map.
     * @param suppress Whether an opt-out signal applies (device signal, stored GPC, or both).
     * @param essentialKeys The set of category keys that are essential / always-on and therefore
     *   never suppressed.
     * @return A new map where, when [suppress] is true, every non-essential category is forced to
     *   `false`. When [suppress] is false the input map is returned unchanged.
     */
    fun reconcile(
        cookieOptions: Map<String, Boolean>,
        suppress: Boolean,
        essentialKeys: Set<String>,
    ): Map<String, Boolean> {
        if (!suppress) return cookieOptions
        return cookieOptions.mapValues { (key, enabled) ->
            if (essentialKeys.contains(key)) enabled else false
        }
    }
}
