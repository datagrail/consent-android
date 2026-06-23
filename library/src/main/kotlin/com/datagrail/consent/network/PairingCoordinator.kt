package com.datagrail.consent.network

import com.datagrail.consent.models.ConsentPreferences
import com.datagrail.consent.utils.ConsentLogger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Coordinates the CTV pairing flow: polls the Universal Consent API for a consent record,
 * detects when a NEW write occurs (via updated_at baseline), and invokes a callback
 *
 * @param dispatcher coroutine dispatcher for the poll loop; defaults to Main. Injectable so
 *   unit tests can drive the loop on a TestDispatcher with virtual time.
 */
class PairingCoordinator(
    private val pairingService: PairingService,
    private val customerId: String,
    private val userHash: String,
    private val onPreferencesFound: (ConsentPreferences) -> Unit,
    private val onTimeout: () -> Unit,
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
) {
    private val scope = CoroutineScope(dispatcher + Job())
    // Separate boolean sentinel (not "baselineUpdatedAt == null") so that a record
    // present at the FIRST poll whose updated_at is null — or a not_found→found
    // transition — is still treated as the baseline rather than a completion.
    private var baselineCaptured = false
    private var baselineUpdatedAt: String? = null
    private var pollJob: Job? = null

    /**
     * Start polling for consent preferences
     * Polls every 2 seconds with a 10-minute client-side timeout
     */
    fun start() {
        ConsentLogger.d("PairingCoordinator: Starting poll")
        // Track elapsed time via accumulated delay() rather than wall-clock, so the
        // timeout is driven by the (possibly virtual) coroutine clock and is
        // deterministically testable on a TestDispatcher.
        var elapsedMs = 0L

        pollJob =
            scope.launch {
                while (true) {
                    // Check timeout
                    if (elapsedMs > TIMEOUT_MS) {
                        ConsentLogger.d("PairingCoordinator: Client timeout reached")
                        // Already on the poll dispatcher; invoke directly (keeps this
                        // class free of android.os.* so it is unit-testable off-device).
                        onTimeout()
                        stop()
                        return@launch
                    }

                    try {
                        val result = pairingService.fetchConsent(customerId, userHash)

                        // The first poll only establishes the baseline (was a record
                        // already present, and at what updated_at?) without completing,
                        // so a PRE-EXISTING record can't instantly dismiss the banner.
                        // Completion requires a NEW write that arrives after this point.
                        if (!baselineCaptured) {
                            baselineCaptured = true
                            baselineUpdatedAt =
                                when (result) {
                                    is PairingRead.NotFound -> null
                                    is PairingRead.Found -> result.updatedAt
                                }
                            ConsentLogger.d("PairingCoordinator: baseline updatedAt=$baselineUpdatedAt")
                        } else {
                            when (result) {
                                is PairingRead.NotFound -> {
                                    ConsentLogger.d("PairingCoordinator: Not found, continue polling")
                                }
                                is PairingRead.Found -> {
                                    // Complete only on a NEW write: a record appeared
                                    // where the baseline had none, or updated_at changed.
                                    if (result.updatedAt != baselineUpdatedAt) {
                                        ConsentLogger.d("PairingCoordinator: NEW write detected, completing")
                                        onPreferencesFound(result.preferences)
                                        stop()
                                        return@launch
                                    } else {
                                        ConsentLogger.d("PairingCoordinator: Found but unchanged, continue polling")
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        ConsentLogger.e("PairingCoordinator: Poll error - ${e.message}")
                    }

                    delay(POLL_INTERVAL_MS)
                    elapsedMs += POLL_INTERVAL_MS
                }
            }
    }

    /**
     * Stop polling
     */
    fun stop() {
        ConsentLogger.d("PairingCoordinator: Stopping poll")
        pollJob?.cancel()
        pollJob = null
        scope.cancel()
    }

    companion object {
        private const val POLL_INTERVAL_MS = 2000L
        private const val TIMEOUT_MS = 10 * 60 * 1000L // 10 minutes
    }
}
