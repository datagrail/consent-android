package com.datagrail.consent.models

/**
 * Result wrapper for SDK operations that provides clear success/failure states
 * and is Java-friendly (unlike Kotlin's Result type which requires special handling from Java)
 */
sealed class ConsentResult<out T> {
    /**
     * Operation succeeded
     * @param data The result data (if any)
     */
    data class Success<T>(val data: T) : ConsentResult<T>()

    /**
     * Operation failed
     * @param error The error that occurred
     */
    data class Failure(val error: ConsentException) : ConsentResult<Nothing>()

    /**
     * Check if the operation was successful
     */
    val isSuccess: Boolean get() = this is Success

    /**
     * Check if the operation failed
     */
    val isFailure: Boolean get() = this is Failure

    /**
     * Get the success data, or null if failed
     */
    fun getOrNull(): T? = if (this is Success) data else null

    /**
     * Get the error, or null if successful
     */
    fun errorOrNull(): ConsentException? = if (this is Failure) error else null

    /**
     * Execute a block if successful
     */
    inline fun onSuccess(block: (T) -> Unit): ConsentResult<T> {
        if (this is Success) block(data)
        return this
    }

    /**
     * Execute a block if failed
     */
    inline fun onFailure(block: (ConsentException) -> Unit): ConsentResult<T> {
        if (this is Failure) block(error)
        return this
    }

    companion object {
        /**
         * Create a success result
         */
        fun <T> success(data: T): ConsentResult<T> = Success(data)

        /**
         * Create a failure result
         */
        fun <T> failure(error: ConsentException): ConsentResult<T> = Failure(error)
    }
}

/**
 * Response object for operations that don't return data
 * Provides clear success/failure state that is easy to use from both Kotlin and Java
 */
data class ConsentResponse(
    val success: Boolean,
    val error: ConsentException? = null,
) {
    companion object {
        /**
         * Create a success response
         */
        fun success(): ConsentResponse = ConsentResponse(success = true, error = null)

        /**
         * Create a failure response
         */
        fun failure(error: ConsentException): ConsentResponse = ConsentResponse(success = false, error = error)
    }
}
