package com.datagrail.consent.network

import android.os.Handler
import android.os.Looper
import com.datagrail.consent.models.ConsentPreferences
import com.datagrail.consent.utils.ConsentLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Coordinates the CTV pairing flow: polls the Universal Consent API for a consent record,
 * detects when a NEW write occurs (via updated_at baseline), and invokes a callback
 */
class PairingCoordinator(
    private val pairingService: PairingService,
    private val customerId: String,
    private val userHash: String,
    private val onPreferencesFound: (ConsentPreferences) -> Unit,
    private val onTimeout: () -> Unit,
) {
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var baselineUpdatedAt: String? = null
    private var pollJob: Job? = null

    /**
     * Start polling for consent preferences
     * Polls every 2 seconds with a 10-minute client-side timeout
     */
    fun start() {
        ConsentLogger.d("PairingCoordinator: Starting poll")
        val startTime = System.currentTimeMillis()
        val timeoutMs = 10 * 60 * 1000L // 10 minutes

        pollJob =
            scope.launch {
                while (true) {
                    // Check timeout
                    if (System.currentTimeMillis() - startTime > timeoutMs) {
                        ConsentLogger.d("PairingCoordinator: Client timeout reached")
                        Handler(Looper.getMainLooper()).post {
                            onTimeout()
                        }
                        stop()
                        return@launch
                    }

                    try {
                        val result = pairingService.fetchConsent(customerId, userHash)

                        when (result) {
                            is PairingRead.NotFound -> {
                                ConsentLogger.d("PairingCoordinator: Not found, continue polling")
                            }
                            is PairingRead.Found -> {
                                val currentUpdatedAt = result.updatedAt

                                if (baselineUpdatedAt == null) {
                                    // First poll: capture baseline but do NOT complete
                                    // This prevents a pre-existing record from auto-dismissing
                                    ConsentLogger.d("PairingCoordinator: Baseline captured: $currentUpdatedAt")
                                    baselineUpdatedAt = currentUpdatedAt
                                } else if (currentUpdatedAt != null && currentUpdatedAt != baselineUpdatedAt) {
                                    // NEW write detected (changed updated_at)
                                    ConsentLogger.d("PairingCoordinator: NEW write detected, completing")
                                    Handler(Looper.getMainLooper()).post {
                                        onPreferencesFound(result.preferences)
                                    }
                                    stop()
                                    return@launch
                                } else {
                                    ConsentLogger.d("PairingCoordinator: Found but unchanged, continue polling")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        ConsentLogger.e("PairingCoordinator: Poll error - ${e.message}")
                    }

                    delay(2000) // 2 second poll interval
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
}
