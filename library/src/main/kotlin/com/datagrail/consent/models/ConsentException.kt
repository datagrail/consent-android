package com.datagrail.consent.models

/**
 * Errors that can occur in the DataGrail Consent SDK
 */
sealed class ConsentException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NotInitialized : ConsentException(
        "DataGrailConsent not initialized. Call DataGrailConsent.initialize() first.",
    )

    class InvalidConfiguration(message: String, cause: Throwable? = null) : ConsentException(
        "Invalid configuration: $message",
        cause,
    )

    class InvalidConfigUrl(url: String) : ConsentException(
        "Invalid configuration URL host: ${try { java.net.URL(url).host } catch (_: Exception) { "<malformed>" }}",
    )

    open class NetworkError(message: String, cause: Throwable? = null) : ConsentException(
        "Network error: $message",
        cause,
    )

    /**
     * A non-2xx HTTP response. Subclasses [NetworkError] so existing `is NetworkError` /
     * `instanceof NetworkError` handling keeps matching; transport failures remain plain [NetworkError].
     */
    open class HttpError
        @JvmOverloads
        constructor(val statusCode: Int, message: String = "HTTP $statusCode") : NetworkError(message) {
            /**
             * A definitive 4xx the server will reject again on replay. 408 (timeout) and 429 (rate limit)
             * are transient and stay retryable, matching the iOS and web SDKs.
             */
            val isClientError: Boolean
                get() = statusCode in 400..499 && statusCode != 408 && statusCode != 429
        }

    /**
     * The consent configuration is not published at the requested location (the config fetch was
     * rejected with a definite 4xx, see [HttpError.isClientError]) and no cached configuration was
     * available. Not retried. Publish the configuration in the dashboard or check the config URL.
     */
    class ConfigNotPublished(cause: Throwable? = null) : NetworkError(
        "Configuration not published. Publish the consent configuration or check the config URL.",
        cause,
    )

    class ParseError(message: String, cause: Throwable? = null) : ConsentException(
        "Failed to parse configuration: $message",
        cause,
    )

    class StorageError(message: String, cause: Throwable? = null) : ConsentException(
        "Storage error: $message",
        cause,
    )

    class ValidationError(message: String) : ConsentException(
        "Validation error: $message",
    )

    /**
     * The customer-provided `getSignature` callback did not return within the write path's
     * signing deadline. A universal-consent write cannot proceed without a signature, so rather
     * than suspend forever on an unresponsive customer backend the write fails with this error.
     */
    class SignatureTimeout(message: String) : ConsentException(
        "Signature timeout: $message",
    )

    internal companion object {
        /** Shared retry policy for [com.datagrail.consent.network.NetworkClient.retryWithBackoff] callers. */
        fun isRetryable(error: Throwable): Boolean =
            when (error) {
                is ConfigNotPublished -> false
                is HttpError -> !error.isClientError
                else -> true
            }
    }
}
