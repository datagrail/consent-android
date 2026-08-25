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

    class NetworkError(message: String, cause: Throwable? = null) : ConsentException(
        "Network error: $message",
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
}
