package com.datagrail.consent.network

import com.datagrail.consent.models.ConsentConfig
import com.datagrail.consent.models.ConsentException
import com.datagrail.consent.storage.ConsentStorage
import kotlinx.serialization.json.Json

/**
 * Service for fetching and managing consent configuration
 */
class ConfigService(
    private val networkClient: NetworkClient,
    private val storage: ConsentStorage,
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
        }

    /**
     * Fetch configuration from URL
     * @param url The configuration URL
     * @return The parsed configuration
     * @throws ConsentException if fetch or parse fails
     */
    suspend fun fetchConfig(url: String): ConsentConfig {
        return try {
            // Try to fetch from network
            val response = networkClient.request(url, HTTPMethod.GET)
            val config = json.decodeFromString<ConsentConfig>(response)

            // Cache the configuration
            storage.saveConfigCache(config)

            config
        } catch (e: ConsentException.NetworkError) {
            // If network fails, try cached config
            storage.loadConfigCache()
                ?: throw e
        } catch (e: Exception) {
            // Parse error or other error
            storage.loadConfigCache()
                ?: throw ConsentException.ParseError(e.message ?: "Failed to parse config", e)
        }
    }

    /**
     * Fetch configuration with retry logic
     * @param url The configuration URL
     * @return The parsed configuration
     * @throws ConsentException if all retries fail
     */
    suspend fun fetchConfigWithRetry(url: String): ConsentConfig {
        return networkClient.retryWithBackoff {
            fetchConfig(url)
        }
    }
}
