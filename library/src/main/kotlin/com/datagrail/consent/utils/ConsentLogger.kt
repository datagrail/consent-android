package com.datagrail.consent.utils

import android.util.Log

/**
 * Log level for the DataGrail Consent SDK
 * Levels are cumulative - higher levels include all lower levels
 */
enum class LogLevel {
    NONE,   // No logging
    ERROR,  // Only errors
    WARN,   // Warnings + errors
    INFO,   // Info + warnings + errors
    DEBUG,  // Debug + info + warnings + errors (most verbose)
}

/**
 * Logger for the DataGrail Consent SDK.
 * Default level is NONE (no logging in production).
 * Set the level property to enable logging for debugging.
 */
internal object ConsentLogger {
    private const val TAG = "DataGrailConsent"

    @Volatile
    var level: LogLevel = LogLevel.NONE

    fun d(message: String) {
        if (level >= LogLevel.DEBUG) {
            Log.d(TAG, message)
        }
    }

    fun w(message: String) {
        if (level >= LogLevel.WARN) {
            Log.w(TAG, message)
        }
    }

    fun i(message: String) {
        if (level >= LogLevel.INFO) {
            Log.i(TAG, message)
        }
    }

    fun e(message: String) {
        if (level >= LogLevel.ERROR) {
            Log.e(TAG, message)
        }
    }
}
