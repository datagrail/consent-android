package com.datagrail.consent.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Represents user's consent preferences
 */
@Serializable
data class ConsentPreferences(
    @SerialName("isCustomised")
    val isCustomised: Boolean,
    @SerialName("cookieOptions")
    val cookieOptions: List<CategoryConsent>,
) {
    /**
     * Check if a specific category is enabled
     * @param categoryKey The GTM key of the category (e.g., "category_marketing")
     * @return True if the category is enabled, false otherwise
     */
    fun isCategoryEnabled(categoryKey: String): Boolean {
        return cookieOptions.find { it.gtmKey == categoryKey }?.isEnabled ?: false
    }
}

/**
 * Represents consent status for a single category
 */
@Serializable
data class CategoryConsent(
    @SerialName("gtm_key")
    val gtmKey: String,
    @SerialName("isEnabled")
    val isEnabled: Boolean,
)
