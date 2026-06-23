package com.datagrail.consent.utils

import android.content.Context
import android.provider.Settings
import java.security.MessageDigest

/**
 * Generates a user hash for pairing consent preferences across devices
 * The hash must byte-match iOS/web implementations for cross-platform compatibility
 */
object UserHashGenerator {
    /**
     * Generate a user hash from customer ID, consent project ID, and device ID
     * Formula: SHA-256("{customerId}:{consentProjectId}:{deviceId}") as lowercase hex
     *
     * @param customerId The DataGrail customer ID
     * @param consentProjectId The consent project identifier
     * @param deviceId The device-specific identifier (defaults to ANDROID_ID)
     * @return 64-character lowercase hex SHA-256 hash
     */
    fun generateUserHash(
        customerId: String,
        consentProjectId: String,
        deviceId: String,
    ): String {
        val input = "$customerId:$consentProjectId:$deviceId"
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Get the default device identifier (ANDROID_ID)
     *
     * @param context Android context
     * @return The device's ANDROID_ID
     */
    fun getDefaultDeviceId(context: Context): String {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "unknown"
    }
}
