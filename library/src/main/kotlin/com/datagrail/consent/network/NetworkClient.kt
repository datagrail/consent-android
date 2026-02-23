package com.datagrail.consent.network

import android.util.Log
import com.datagrail.consent.models.ConsentException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.pow

private const val TAG = "DataGrailNetwork"

/**
 * HTTP methods supported by the network client
 */
enum class HTTPMethod(val value: String) {
    GET("GET"),
    POST("POST"),
    PUT("PUT"),
    DELETE("DELETE"),
}

/**
 * Network client for making HTTP requests with retry support
 */
class NetworkClient {
    /**
     * Make an HTTP request
     * @param url The URL to request
     * @param method The HTTP method
     * @param body Optional request body string
     * @param headers Optional HTTP headers
     * @return Response body as string
     * @throws ConsentException.NetworkError if the request fails
     */
    suspend fun request(
        url: String,
        method: HTTPMethod = HTTPMethod.GET,
        body: String? = null,
        headers: Map<String, String>? = null,
    ): String =
        withContext(Dispatchers.IO) {
            Log.d(TAG, "Making ${method.value} request to: $url")
            try {
                val connection = URL(url).openConnection() as HttpURLConnection

                connection.requestMethod = method.value
                connection.connectTimeout = 30000
                connection.readTimeout = 30000

                // Set headers
                headers?.forEach { (key, value) ->
                    connection.setRequestProperty(key, value)
                }

                // Set default content type for POST/PUT
                if (body != null && connection.getRequestProperty("Content-Type") == null) {
                    connection.setRequestProperty("Content-Type", "application/json")
                }

                // Write body if present
                if (body != null) {
                    connection.doOutput = true
                    connection.outputStream.use { outputStream ->
                        outputStream.write(body.toByteArray(Charsets.UTF_8))
                    }
                }

                val responseCode = connection.responseCode
                Log.d(TAG, "Response code: $responseCode")

                if (responseCode !in 200..299) {
                    val errorBody =
                        try {
                            connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                        } catch (e: Exception) {
                            ""
                        }
                    Log.e(TAG, "HTTP error $responseCode: $errorBody")
                    throw ConsentException.NetworkError("HTTP $responseCode")
                }

                val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
                Log.d(TAG, "Response received: ${responseBody.take(200)}...")
                responseBody
            } catch (e: ConsentException) {
                Log.e(TAG, "ConsentException: ${e.message}", e)
                throw e
            } catch (e: IOException) {
                Log.e(TAG, "IOException: ${e.message}", e)
                throw ConsentException.NetworkError(e.message ?: "Network error", e)
            } catch (e: Exception) {
                Log.e(TAG, "Exception: ${e.message}", e)
                throw ConsentException.NetworkError(e.message ?: "Unknown error", e)
            }
        }

    /**
     * Retry an operation with exponential backoff
     * @param maxAttempts Maximum number of retry attempts (default: 5)
     * @param baseDelayMs Base delay in milliseconds (default: 250)
     * @param operation The suspend operation to retry
     * @return The result of the operation
     * @throws The last error if all attempts fail
     */
    suspend fun <T> retryWithBackoff(
        maxAttempts: Int = 5,
        baseDelayMs: Long = 250,
        operation: suspend () -> T,
    ): T {
        var attempt = 1
        var lastException: Exception? = null

        while (attempt <= maxAttempts) {
            try {
                return operation()
            } catch (e: Exception) {
                lastException = e

                if (attempt >= maxAttempts) {
                    throw e
                }

                val delayMs = (baseDelayMs * 2.0.pow(attempt.toDouble())).toLong()
                delay(delayMs)
                attempt++
            }
        }

        throw lastException ?: ConsentException.NetworkError("Retry failed")
    }
}
