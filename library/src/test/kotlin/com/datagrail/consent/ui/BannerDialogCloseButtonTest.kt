package com.datagrail.consent.ui

import com.datagrail.consent.models.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for BannerDialog close button visibility logic.
 *
 * These tests verify that shouldShowCloseButton() properly respects the showCloseButton
 * configuration for both MODAL and FULL_SCREEN display styles, ensuring the fix
 * prevents regression of the TRUST-1695 bug.
 */
class BannerDialogCloseButtonTest {
    @Test
    fun `shouldShowCloseButton returns true when config is true for MODAL`() {
        val config = createTestConfig(showCloseButton = true)
        val dialog = BannerDialog.newInstance(
            config = config,
            preferences = null,
            displayStyle = BannerDisplayStyle.MODAL,
            onDismiss = {}
        )

        assertTrue(
            "shouldShowCloseButton() should return true for MODAL when config is true",
            dialog.shouldShowCloseButton()
        )
    }

    @Test
    fun `shouldShowCloseButton returns false when config is false for MODAL`() {
        val config = createTestConfig(showCloseButton = false)
        val dialog = BannerDialog.newInstance(
            config = config,
            preferences = null,
            displayStyle = BannerDisplayStyle.MODAL,
            onDismiss = {}
        )

        assertFalse(
            "shouldShowCloseButton() should return false for MODAL when config is false",
            dialog.shouldShowCloseButton()
        )
    }

    @Test
    fun `shouldShowCloseButton returns true when config is true for FULL_SCREEN`() {
        val config = createTestConfig(showCloseButton = true)
        val dialog = BannerDialog.newInstance(
            config = config,
            preferences = null,
            displayStyle = BannerDisplayStyle.FULL_SCREEN,
            onDismiss = {}
        )

        assertTrue(
            "shouldShowCloseButton() should return true for FULL_SCREEN when config is true",
            dialog.shouldShowCloseButton()
        )
    }

    @Test
    fun `shouldShowCloseButton returns false when config is false for FULL_SCREEN`() {
        val config = createTestConfig(showCloseButton = false)
        val dialog = BannerDialog.newInstance(
            config = config,
            preferences = null,
            displayStyle = BannerDisplayStyle.FULL_SCREEN,
            onDismiss = {}
        )

        assertFalse(
            "shouldShowCloseButton() should return false for FULL_SCREEN when config is false",
            dialog.shouldShowCloseButton()
        )
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
