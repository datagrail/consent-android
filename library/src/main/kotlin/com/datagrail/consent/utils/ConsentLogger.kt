package com.datagrail.consent.utils

import android.util.Log

/**
 * Log level for the DataGrail Consent SDK
 */
enum class LogLevel {
    NONE,
    ERROR,
    WARN,
    DEBUG,
}

/**
 * Internal logger that respects the configured log level.
 * Default level is NONE (no logging in production).
 */
internal object ConsentLogger {
    private const val TAG = "DataGrailConsent"

    @Volatile
    var level: LogLevel = LogLevel.NONE

    fun d(message: String) {
        if (level == LogLevel.DEBUG) {
            Log.d(TAG, message)
        }
    }

    fun w(message: String) {
        if (level == LogLevel.WARN || level == LogLevel.DEBUG) {
            Log.w(TAG, message)
        }
    }

    fun e(message: String) {
        if (level == LogLevel.ERROR || level == LogLevel.WARN || level == LogLevel.DEBUG) {
            Log.e(TAG, message)
        }
    }
}
