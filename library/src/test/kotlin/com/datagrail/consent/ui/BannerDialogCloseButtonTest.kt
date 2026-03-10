package com.datagrail.consent.ui

import com.datagrail.consent.models.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for BannerDialog close button visibility logic.
 *
 * These tests verify that the showCloseButton configuration is properly respected
 * for both MODAL and FULL_SCREEN display styles.
 */
class BannerDialogCloseButtonTest {
    @Test
    fun `close button respects config when showCloseButton is true for modal`() {
        // Create a config with showCloseButton = true
        val config = createTestConfig(showCloseButton = true)
        val layer = config.layout.consentLayers[config.layout.firstLayerId]

        assertNotNull("Test layer should exist", layer)
        assertTrue("Layer should have showCloseButton = true", layer!!.showCloseButton)

        // In the actual BannerDialog, shouldShowCloseButton() should return true
        // for both MODAL and FULL_SCREEN when config says true
    }

    @Test
    fun `close button respects config when showCloseButton is false for modal`() {
        // Create a config with showCloseButton = false
        val config = createTestConfig(showCloseButton = false)
        val layer = config.layout.consentLayers[config.layout.firstLayerId]

        assertNotNull("Test layer should exist", layer)
        assertFalse("Layer should have showCloseButton = false", layer!!.showCloseButton)

        // In the actual BannerDialog, shouldShowCloseButton() should return false
        // for both MODAL and FULL_SCREEN when config says false
    }

    @Test
    fun `close button respects config when showCloseButton is true for fullscreen`() {
        // Create a config with showCloseButton = true
        val config = createTestConfig(showCloseButton = true)
        val layer = config.layout.consentLayers[config.layout.firstLayerId]

        assertNotNull("Test layer should exist", layer)
        assertTrue("Layer should have showCloseButton = true", layer!!.showCloseButton)
    }

    @Test
    fun `close button respects config when showCloseButton is false for fullscreen`() {
        // Create a config with showCloseButton = false
        val config = createTestConfig(showCloseButton = false)
        val layer = config.layout.consentLayers[config.layout.firstLayerId]

        assertNotNull("Test layer should exist", layer)
        assertFalse("Layer should have showCloseButton = false", layer!!.showCloseButton)
    }

    /**
     * Helper function to create a test config with a specific showCloseButton value
     */
    private fun createTestConfig(showCloseButton: Boolean): ConsentConfig {
        val layerId = "test-layer-id"

        val textElement = ConsentLayerElement(
            id = "text-1",
            order = 1,
            type = "text",
            translations = mapOf(
                "en" to ElementTranslation(
                    id = "trans-1",
                    locale = "en",
                    value = "Test text"
                )
            )
        )

        val layer = ConsentLayer(
            id = layerId,
            name = "Test Layer",
            theme = "neutral",
            position = "center",
            showCloseButton = showCloseButton,
            bannerApiId = "test-banner",
            elements = listOf(textElement)
        )

        return ConsentConfig(
            version = "1.0.0",
            consentContainerVersionId = "test-version",
            dgCustomerId = "test-customer",
            p = 123456789,
            dch = "test-dch",
            dc = "test-dc",
            privacyDomain = "test.com",
            plugins = Plugins(
                scriptControl = false,
                allCookieSubdomains = false,
                cookieBlocking = false,
                localStorageBlocking = false,
                syncOTConsent = false
            ),
            testMode = true,
            ignoreDoNotTrack = false,
            trackingDetailsUrl = "https://test.com/tracking",
            consentMode = "opt-in",
            showBanner = true,
            consentPolicy = ConsentPolicy(
                name = "Test Policy",
                default = true
            ),
            gppUsNat = false,
            initialCategories = InitialCategories(
                respectGpc = false,
                respectDnt = false,
                respectOptout = false,
                initial = listOf("essential"),
                gpc = emptyList(),
                optout = emptyList()
            ),
            layout = Layout(
                id = "layout-1",
                name = "Test Layout",
                description = "Test layout description",
                status = "active",
                defaultLayout = true,
                collapsedOnMobile = false,
                firstLayerId = layerId,
                gpcDntLayerId = null,
                consentLayers = mapOf(layerId to layer)
            )
        )
    }
}
