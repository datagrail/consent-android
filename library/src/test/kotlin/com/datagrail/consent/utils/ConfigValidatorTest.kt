package com.datagrail.consent.utils

import com.datagrail.consent.models.*
import org.junit.Assert.*
import org.junit.Test

class ConfigValidatorTest {
    @Test
    fun testValidConfigPasses() {
        val config = createValidConfig()

        // Should not throw
        ConfigValidator.validate(config)
    }

    @Test
    fun testMissingVersionFails() {
        val config = createValidConfig().copy(version = "")

        try {
            ConfigValidator.validate(config)
            fail("Expected ValidationError")
        } catch (e: ConsentException.ValidationError) {
            assertTrue(e.message?.contains("version") == true)
        }
    }

    @Test
    fun testInvalidConsentModeFails() {
        val config = createValidConfig().copy(consentMode = "invalid")

        try {
            ConfigValidator.validate(config)
            fail("Expected ValidationError")
        } catch (e: ConsentException.ValidationError) {
            assertTrue(e.message?.contains("consentMode") == true)
        }
    }

    @Test
    fun testEmptyLayersFails() {
        val config =
            createValidConfig().copy(
                layout = createValidConfig().layout.copy(consentLayers = emptyMap()),
            )

        try {
            ConfigValidator.validate(config)
            fail("Expected ValidationError")
        } catch (e: ConsentException.ValidationError) {
            assertTrue(e.message?.contains("No consent layers") == true)
        }
    }

    @Test
    fun testInvalidFirstLayerIdFails() {
        val config =
            createValidConfig().copy(
                layout = createValidConfig().layout.copy(firstLayerId = "nonexistent"),
            )

        try {
            ConfigValidator.validate(config)
            fail("Expected ValidationError")
        } catch (e: ConsentException.ValidationError) {
            assertTrue(e.message?.contains("firstLayerId") == true)
        }
    }

    // MARK: - Helper Methods

    private fun createValidConfig(): ConsentConfig {
        val element =
            ConsentLayerElement(
                id = "elem1",
                order = 1,
                type = "ConsentLayerTextElement",
                style = null,
                buttonAction = null,
                targetConsentLayer = null,
                categories = null,
                links = null,
                consentLayerCategories = null,
                showTrackingDetailsLink = null,
                consentLayerCategoriesConfigId = null,
                trackingDetailsLinkTranslations = null,
                translations =
                    mapOf(
                        "en" to ElementTranslation("t1", "en", "Test", null, null),
                    ),
            )

        val layer =
            ConsentLayer(
                id = "layer1",
                name = "First Layer",
                theme = "neutral",
                position = "bottom",
                showCloseButton = true,
                bannerApiId = "first",
                elements = listOf(element),
            )

        val layout =
            Layout(
                id = "layout1",
                name = "Test Layout",
                description = null,
                status = "published",
                defaultLayout = true,
                collapsedOnMobile = false,
                firstLayerId = "layer1",
                consentLayers = mapOf("layer1" to layer),
            )

        return ConsentConfig(
            version = "1.0.0",
            consentContainerVersionId = "container1",
            dgCustomerId = "customer1",
            p = 0,
            dch = "categorize",
            dc = "dg-category-essential",
            privacyDomain = "consent.datagrail.io",
            plugins =
                Plugins(
                    scriptControl = false,
                    allCookieSubdomains = false,
                    cookieBlocking = false,
                    localStorageBlocking = false,
                    syncOTConsent = false,
                ),
            testMode = false,
            ignoreDoNotTrack = false,
            trackingDetailsUrl = "https://example.com/tracking",
            consentMode = "optin",
            showBanner = true,
            consentPolicy = ConsentPolicy("GDPR", true),
            gppUsNat = false,
            initialCategories =
                InitialCategories(
                    respectGpc = false,
                    respectDnt = false,
                    respectOptout = false,
                    initial = listOf("dg-category-essential"),
                    gpc = emptyList(),
                    optout = emptyList(),
                ),
            layout = layout,
        )
    }
}
