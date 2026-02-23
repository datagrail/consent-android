package com.datagrail.consent.models

/**
 * Errors that can occur in the DataGrail Consent SDK
 */
sealed class ConsentException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NotInitialized : ConsentException(
        "DataGrailConsent not initialized. Call DataGrailConsent.initialize() first.",
    )

    class InvalidConfiguration(message: String) : ConsentException(
        "Invalid configuration: $message",
    )

    class InvalidConfigUrl(url: String) : ConsentException(
        "Invalid configuration URL: $url",
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
}
