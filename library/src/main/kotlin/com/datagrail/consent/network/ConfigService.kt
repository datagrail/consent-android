package com.datagrail.consent.network

import com.datagrail.consent.models.ConsentConfig
import com.datagrail.consent.models.ConsentException
import com.datagrail.consent.storage.ConsentStorage
import com.datagrail.consent.utils.ConfigValidator
import com.datagrail.consent.utils.ConsentLogger
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

/**
 * Service for fetching and managing consent configuration
 */
internal class ConfigService(
    private val networkClient: NetworkClient,
    private val storage: ConsentStorage,
) {
    // The demo's ConfigUrlLoader mirrors these decode settings; keep the two in sync.
    private val json =
        Json {
            ignoreUnknownKeys = true
        }

    /**
     * Fetch configuration from URL. When the fetch fails and a cached config exists, the cached
     * config is returned instead and the failure goes to [onRefreshError] (TRUST-3254); without a
     * cache it is thrown.
     * @param url The configuration URL
     * @param notReset False once a reset() superseded the init this fetch serves: the fetched config
     *   is then not cached (a newer initialize() alone does not withhold it). The
     *   cache is one key for all config URLs, so a slow init for an older URL can still overwrite a
     *   newer init's cache
     * @param onRefreshError Receives a failure the cached config masked: exactly the exception this
     *   call would have thrown without a cache ([ConsentException.ConfigNotPublished] for a definite
     *   4xx, [ConsentException.NetworkError] for transport/timeout/5xx, [ConsentException.ParseError]
     *   for undecodable JSON, [ConsentException.ValidationError] for a config that decodes but fails
     *   validation). Called at most once per call, never on success. A handler that throws is
     *   logged and ignored: the cached config is still returned.
     * @return The parsed configuration
     * @throws ConsentException if fetch or parse fails
     */
    suspend fun fetchConfig(
        url: String,
        notReset: () -> Boolean = { true },
        onRefreshError: ((ConsentException) -> Unit)? = null,
    ): ConsentConfig {
        return try {
            // Try to fetch from network
            val response = networkClient.request(url, HTTPMethod.GET)
            val config = json.decodeFromString<ConsentConfig>(response)

            // Validate config structure
            try {
                ConfigValidator.validate(config)
            } catch (e: ConsentException.ValidationError) {
                ConsentLogger.e("Config validation failed: ${e.message}")
                return cachedOrThrow(e, onRefreshError)
            }

            // Cache the configuration, unless a reset() superseded this fetch while it was in flight: it
            // must not repopulate the wiped cache (TRUST-3254).
            if (notReset()) storage.saveConfigCache(config)

            config
        } catch (e: ConsentException.NetworkError) {
            // If network fails, try cached config
            cachedOrThrow(
                if (e is ConsentException.HttpError && e.isClientError) {
                    ConsentException.ConfigNotPublished(statusCode = e.statusCode, cause = e)
                } else {
                    e
                },
                onRefreshError,
            )
        } catch (e: ConsentException.ValidationError) {
            throw e
        } catch (e: CancellationException) {
            // Cancellation is not a fetch failure: never served from cache or reported as one.
            throw e
        } catch (e: Exception) {
            // Parse error or other error
            cachedOrThrow(ConsentException.ParseError(e.message ?: "Failed to parse config", e), onRefreshError)
        }
    }

    /**
     * The cached config, with [error] reported to [onRefreshError] as the failure it masked; or
     * [error] thrown when nothing is cached (an initial-fetch failure, which reaches the caller).
     */
    private fun cachedOrThrow(
        error: ConsentException,
        onRefreshError: ((ConsentException) -> Unit)?,
    ): ConsentConfig {
        val cached = storage.loadConfigCache() ?: throw error
        if (onRefreshError != null) {
            try {
                onRefreshError(error)
            } catch (e: Exception) {
                // A host handler must never break initialization; the cached config still stands.
                ConsentLogger.e("onConfigError handler threw: ${e.javaClass.simpleName}")
            }
        }
        return cached
    }

    /**
     * Fetch configuration with retry logic
     * @param url The configuration URL
     * @param onRefreshError See [fetchConfig]
     * @param notReset False once a reset() superseded the init: retrying stops and the fetched config
     *   is not cached. An init a newer initialize() overtook still reports and keeps its config, so
     *   it keeps retrying and caches it too
     * @return The parsed configuration
     * @throws ConsentException if all retries fail
     */
    suspend fun fetchConfigWithRetry(
        url: String,
        onRefreshError: ((ConsentException) -> Unit)? = null,
        notReset: () -> Boolean = { true },
    ): ConsentConfig {
        // A definite 4xx or ConfigNotPublished gives up at once; 408, 429, 5xx and transport failures retry.
        // An attempt that falls back to the cache returns, so a masked failure is reported once.
        // A load a reset() superseded stops retrying: its result is never used.
        // The cache follows the same reset-only rule: a config an init a newer initialize() overtook
        // fetched is still the freshest one, so it is cached; only a reset() withholds it.
        return networkClient.retryWithBackoff(shouldRetry = { notReset() && ConsentException.isRetryable(it) }) {
            fetchConfig(url, notReset, onRefreshError)
        }
    }
}
