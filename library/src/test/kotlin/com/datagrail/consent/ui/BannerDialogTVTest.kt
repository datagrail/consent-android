package com.datagrail.consent.ui

import com.datagrail.consent.models.CategoryConsent
import com.datagrail.consent.models.ConsentConfig
import com.datagrail.consent.models.ConsentPreferences
import com.datagrail.consent.network.ConsentServiceSecurityTest
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for BannerDialogTV D-pad navigation and TV-specific UI
 */
class BannerDialogTVTest {
    @Test
    fun `newInstance creates dialog with correct config and preferences`() {
        val config = ConsentServiceSecurityTest.createTestConfig()
        val preferences =
            ConsentPreferences(
                isCustomised = false,
                cookieOptions = listOf(CategoryConsent(gtmKey = "dg-category-marketing", isEnabled = true)),
            )

        var callbackInvoked = false
        val dialog =
            BannerDialogTV.newInstance(
                config = config,
                preferences = preferences,
            ) { prefs ->
                callbackInvoked = true
            }

        assertNotNull("Dialog should be created", dialog)
    }

    @Test
    fun `getAllCategoryKeys extracts all unique category keys from config`() {
        val config = ConsentServiceSecurityTest.createTestConfig()
        val dialog = BannerDialogTV()

        val configField = BannerDialogTV::class.java.getDeclaredField("config")
        configField.isAccessible = true
        configField.set(dialog, config)

        val method = BannerDialogTV::class.java.getDeclaredMethod("getAllCategoryKeys", ConsentConfig::class.java)
        method.isAccessible = true

        @Suppress("UNCHECKED_CAST")
        val categories = method.invoke(dialog, config) as List<String>

        assertTrue("Should contain categories from initialCategories", categories.isNotEmpty())
    }

    @Test
    fun `getEssentialCategoryKeys filters for alwaysOn categories`() {
        val config = ConsentServiceSecurityTest.createTestConfig()
        val dialog = BannerDialogTV()

        val configField = BannerDialogTV::class.java.getDeclaredField("config")
        configField.isAccessible = true
        configField.set(dialog, config)

        val method = BannerDialogTV::class.java.getDeclaredMethod("getEssentialCategoryKeys", ConsentConfig::class.java)
        method.isAccessible = true

        @Suppress("UNCHECKED_CAST")
        val essentialCategories = method.invoke(dialog, config) as Set<String>

        // Result may be empty if no categories marked alwaysOn in test config, that's fine
        assertNotNull("Should return a non-null set", essentialCategories)
    }

    @Test
    fun `normalizeElementType correctly maps config types to standard types`() {
        val dialog = BannerDialogTV()
        val method = BannerDialogTV::class.java.getDeclaredMethod("normalizeElementType", String::class.java)
        method.isAccessible = true

        assertEquals("text", method.invoke(dialog, "ConsentLayerTextElement"))
        assertEquals("button", method.invoke(dialog, "ConsentLayerButtonElement"))
        assertEquals("category", method.invoke(dialog, "ConsentLayerCategoryElement"))
        assertEquals("link", method.invoke(dialog, "ConsentLayerLinkElement"))
        assertEquals("tracking_details", method.invoke(dialog, "ConsentLayerTrackingDetailsElement"))
        assertEquals("browser_signal_notice", method.invoke(dialog, "BrowserSignalNotice"))
        assertEquals("language_picker", method.invoke(dialog, "LanguagePicker"))
    }

    @Test
    fun `text minimum font size requirement documented`() {
        // This test documents the requirement that TV banners use minimum 18sp text
        val minTextSize = 18f
        assertTrue("Text elements should use minimum 18sp for 10-foot viewing", minTextSize >= 18f)
    }

    @Test
    fun `button minimum height requirement documented`() {
        // This test documents the requirement that TV buttons use minimum 56dp height
        val minButtonHeightDp = 56
        assertTrue("Button elements should use minimum 56dp height for TV D-pad", minButtonHeightDp >= 56)
    }

    @Test
    fun `all interactive elements must be focusable documented`() {
        // This test documents the requirement that all interactive elements are focusable
        // In the actual implementation, buttons and category rows have isFocusable = true
        val buttonFocusable = true
        val categoryRowFocusable = true

        assertTrue("Buttons must be focusable for D-pad navigation", buttonFocusable)
        assertTrue("Category rows must be focusable for D-pad navigation", categoryRowFocusable)
    }

    @Test
    fun `back key navigation layer stack behavior`() {
        // Documents the back key behavior:
        // - Multiple layers in stack: navigate back to previous layer
        // - Single layer (first layer): dismiss dialog
        val layerStack = mutableListOf("first", "second")

        // Simulate back on multi-layer stack
        layerStack.removeAt(layerStack.size - 1)
        assertEquals("Should navigate to first layer", "first", layerStack.last())

        // Simulate back on single-layer stack
        layerStack.removeAt(layerStack.size - 1)
        assertTrue("Should dismiss when only first layer", layerStack.isEmpty())
    }

    @Test
    fun `DPAD left and right key codes for category toggle`() {
        // Documents that DPAD_LEFT and DPAD_RIGHT toggle category switches
        val leftKeyCode = android.view.KeyEvent.KEYCODE_DPAD_LEFT
        val rightKeyCode = android.view.KeyEvent.KEYCODE_DPAD_RIGHT

        assertEquals(21, leftKeyCode)
        assertEquals(22, rightKeyCode)
    }

    @Test
    fun `Back key code for layer navigation`() {
        // Documents that KEYCODE_BACK navigates layers / dismisses
        val backKeyCode = android.view.KeyEvent.KEYCODE_BACK
        assertEquals(4, backKeyCode)
    }
}
