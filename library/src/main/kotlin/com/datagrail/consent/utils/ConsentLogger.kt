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
 * Logger for the DataGrail Consent SDK.
 * Default level is NONE (no logging in production).
 * Set the level property to enable logging for debugging.
 */
object ConsentLogger {
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
