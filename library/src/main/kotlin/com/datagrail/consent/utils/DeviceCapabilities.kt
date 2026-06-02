package com.datagrail.consent.utils

import android.content.Context
import android.content.pm.PackageManager

/**
 * Utility to detect device capabilities, particularly for Android TV / Fire TV devices
 */
internal object DeviceCapabilities {
    /**
     * Detect if the device is an Android TV or Fire TV device
     * @param context Android context
     * @return true if the device has the Leanback feature (TV device), false otherwise
     */
    fun isTv(context: Context): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }
}
