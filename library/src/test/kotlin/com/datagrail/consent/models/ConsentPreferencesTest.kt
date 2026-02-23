package com.datagrail.consent.models

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ConsentPreferencesTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun testPreferencesInitialization() {
        val prefs =
            ConsentPreferences(
                isCustomised = true,
                cookieOptions =
                    listOf(
                        CategoryConsent("category_marketing", true),
                        CategoryConsent("category_analytics", false),
                    ),
            )

        assertTrue(prefs.isCustomised)
        assertEquals(2, prefs.cookieOptions.size)
    }

    @Test
    fun testIsCategoryEnabled() {
        val prefs =
            ConsentPreferences(
                isCustomised = true,
                cookieOptions =
                    listOf(
                        CategoryConsent("category_marketing", true),
                        CategoryConsent("category_analytics", false),
                    ),
            )

        assertTrue(prefs.isCategoryEnabled("category_marketing"))
        assertFalse(prefs.isCategoryEnabled("category_analytics"))
        assertFalse(prefs.isCategoryEnabled("category_nonexistent"))
    }

    @Test
    fun testSerializable() {
        val original =
            ConsentPreferences(
                isCustomised = true,
                cookieOptions =
                    listOf(
                        CategoryConsent("category_marketing", true),
                    ),
            )

        val jsonString = json.encodeToString(original)
        val decoded = json.decodeFromString<ConsentPreferences>(jsonString)

        assertEquals(original, decoded)
    }
}
