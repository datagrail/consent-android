package com.datagrail.consent.models

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ConsentConfigParserTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `test parse real config file`() {
        // Load the real config.json from test resources
        val configFile = File(javaClass.classLoader?.getResource("test-config.json")?.file ?: "")
        assertTrue("Config file should exist", configFile.exists())

        val configJson = configFile.readText()

        // Parse the config
        val config = json.decodeFromString<ConsentConfig>(configJson)

        // Verify top-level properties
        assertEquals("cc959465-747d-4c81-8bc1-5dcd34dc3756", config.version)
        assertEquals("0dd5bdf3-b55e-4d97-8a06-e14b17660b94", config.consentContainerVersionId)
        assertEquals("ac46d8ad-a67a-431f-a5d5-9e3eb922dae7", config.dgCustomerId)
        assertEquals(1765415800250L, config.p)
        assertEquals("categorize", config.dch)
        assertEquals("dg-category-marketing", config.dc)
        assertEquals("api.consentjs.datagrailstaging.com", config.privacyDomain)
        assertFalse(config.testMode)
        assertFalse(config.ignoreDoNotTrack)
        assertEquals("optout", config.consentMode)
        assertFalse(config.showBanner)
        assertTrue(config.gppUsNat)
    }

    @Test
    fun `test parse plugins`() {
        val configFile = File(javaClass.classLoader?.getResource("test-config.json")?.file ?: "")
        val configJson = configFile.readText()
        val config = json.decodeFromString<ConsentConfig>(configJson)

        // Verify plugins
        assertTrue(config.plugins.scriptControl)
        assertTrue(config.plugins.allCookieSubdomains)
        assertTrue(config.plugins.cookieBlocking)
        assertTrue(config.plugins.localStorageBlocking)
        assertFalse(config.plugins.syncOTConsent)
    }

    @Test
    fun `test parse consent policy`() {
        val configFile = File(javaClass.classLoader?.getResource("test-config.json")?.file ?: "")
        val configJson = configFile.readText()
        val config = json.decodeFromString<ConsentConfig>(configJson)

        // Verify consent policy
        assertEquals("CPRA", config.consentPolicy.name)
        assertFalse(config.consentPolicy.default)
    }

    @Test
    fun `test parse initial categories`() {
        val configFile = File(javaClass.classLoader?.getResource("test-config.json")?.file ?: "")
        val configJson = configFile.readText()
        val config = json.decodeFromString<ConsentConfig>(configJson)

        // Verify initial categories
        assertTrue(config.initialCategories.respectGpc)
        assertTrue(config.initialCategories.respectDnt)
        assertFalse(config.initialCategories.respectOptout)
        assertEquals(4, config.initialCategories.initial.size)
        assertTrue(config.initialCategories.initial.contains("dg-category-essential"))
        assertTrue(config.initialCategories.initial.contains("dg-category-marketing"))
        assertEquals(1, config.initialCategories.gpc.size)
        assertEquals("dg-category-essential", config.initialCategories.gpc.first())
    }

    @Test
    fun `test parse layout`() {
        val configFile = File(javaClass.classLoader?.getResource("test-config.json")?.file ?: "")
        val configJson = configFile.readText()
        val config = json.decodeFromString<ConsentConfig>(configJson)

        // Verify layout
        assertEquals("a788c60d-ec2c-40a9-bd3b-cfd371d62889", config.layout.id)
        assertEquals("CPRA only", config.layout.name)
        assertNull(config.layout.description)
        assertEquals("published", config.layout.status)
        assertFalse(config.layout.defaultLayout)
        assertTrue(config.layout.collapsedOnMobile)
        assertEquals("26259ccb-e5e0-4305-b696-fa2b7413c239", config.layout.firstLayerId)

        // Verify consent layers exist
        assertEquals(5, config.layout.consentLayers.size)
        assertNotNull(config.layout.consentLayers["00a6e2c3-f1d5-4d3f-bd91-7d45cc0b75c5"])
        assertNotNull(config.layout.consentLayers["26259ccb-e5e0-4305-b696-fa2b7413c239"])
        assertNotNull(config.layout.consentLayers["b0b9fc31-4ea2-4026-8aa1-25fd647aa265"])
    }

    @Test
    fun `test parse categories layer`() {
        val configFile = File(javaClass.classLoader?.getResource("test-config.json")?.file ?: "")
        val configJson = configFile.readText()
        val config = json.decodeFromString<ConsentConfig>(configJson)

        // Get the categories layer
        val categoriesLayer = config.layout.consentLayers["26259ccb-e5e0-4305-b696-fa2b7413c239"]
        assertNotNull("Categories layer should exist", categoriesLayer)

        categoriesLayer?.let { layer ->
            assertEquals("Categories Ler", layer.name)
            assertEquals("neutral", layer.theme)
            assertEquals("left", layer.position)
            assertTrue(layer.showCloseButton)
            assertEquals("categories-layer", layer.bannerApiId)

            // Verify elements
            assertTrue(layer.elements.isNotEmpty())

            // Find title element (type contains "Text")
            val titleElement =
                layer.elements.firstOrNull { element ->
                    element.type.contains("Text", ignoreCase = true) && element.style == "dg-title"
                }
            assertNotNull("Title element should exist", titleElement)
            assertEquals("Privacy Settings", titleElement?.translations?.get("en")?.value)
        }
    }

    @Test
    fun `test parse default layer`() {
        val configFile = File(javaClass.classLoader?.getResource("test-config.json")?.file ?: "")
        val configJson = configFile.readText()
        val config = json.decodeFromString<ConsentConfig>(configJson)

        // Get the default layer
        val defaultLayer = config.layout.consentLayers["b0b9fc31-4ea2-4026-8aa1-25fd647aa265"]
        assertNotNull("Default layer should exist", defaultLayer)

        defaultLayer?.let { layer ->
            assertEquals("Default Layer", layer.name)
            assertEquals("bottom", layer.position)
            assertFalse(layer.showCloseButton)
            assertEquals("default-layer", layer.bannerApiId)

            // Verify link element exists (type contains "Link")
            val linkElement = layer.elements.firstOrNull { it.type.contains("Link", ignoreCase = true) }
            assertNotNull("Link element should exist", linkElement)
            assertEquals(2, linkElement?.links?.size)

            // Verify Privacy Policy link
            val privacyLink =
                linkElement?.links?.firstOrNull {
                    it.translations["en"]?.text == "Privacy Policy"
                }
            assertNotNull("Privacy Policy link should exist", privacyLink)
            assertEquals(
                "https://www.datagrail.io/privacy-policy/",
                privacyLink?.translations?.get("en")?.url,
            )

            // Verify button element exists (type contains "Button")
            val buttonElement = layer.elements.firstOrNull { it.type.contains("Button", ignoreCase = true) }
            assertNotNull("Button element should exist", buttonElement)
            assertEquals("open_layer", buttonElement?.buttonAction)
            assertEquals("00a6e2c3-f1d5-4d3f-bd91-7d45cc0b75c5", buttonElement?.targetConsentLayer)
            assertEquals("OK", buttonElement?.translations?.get("en")?.value)
        }
    }

    @Test
    fun `test parse all layer elements`() {
        val configFile = File(javaClass.classLoader?.getResource("test-config.json")?.file ?: "")
        val configJson = configFile.readText()
        val config = json.decodeFromString<ConsentConfig>(configJson)

        // Iterate through all layers and verify all elements parse correctly
        config.layout.consentLayers.forEach { (layerId, layer) ->
            assertFalse("Layer $layerId should have an id", layer.id.isEmpty())
            assertFalse("Layer $layerId should have a name", layer.name.isEmpty())
            assertTrue("Layer $layerId should have elements", layer.elements.isNotEmpty())

            layer.elements.forEach { element ->
                assertFalse("Element should have an id", element.id.isEmpty())
                assertTrue("Element should have order > 0", element.order > 0)
                assertFalse("Element should have a type", element.type.isEmpty())

                // Verify element-specific fields based on type
                when {
                    element.type.contains("Text", ignoreCase = true) -> {
                        assertTrue(
                            "Text element should have translations",
                            element.translations?.isNotEmpty() == true,
                        )
                    }
                    element.type.contains("Button", ignoreCase = true) -> {
                        assertTrue(
                            "Button element should have translations",
                            element.translations?.isNotEmpty() == true,
                        )
                    }
                    element.type.contains("Link", ignoreCase = true) -> {
                        assertTrue(
                            "Link element should have links",
                            element.links?.isNotEmpty() == true,
                        )
                    }
                    element.type.contains("Category", ignoreCase = true) -> {
                        assertTrue(
                            "Category element should have categories",
                            element.consentLayerCategories?.isNotEmpty() == true,
                        )
                    }
                }
            }
        }
    }

    @Test(expected = Exception::class)
    fun `test parse invalid JSON`() {
        val invalidJSON = "{ invalid json }"
        json.decodeFromString<ConsentConfig>(invalidJSON)
    }

    @Test(expected = Exception::class)
    fun `test parse missing required fields`() {
        val invalidConfig = """
        {
            "version": "test",
            "dgCustomerId": "test"
        }
        """
        json.decodeFromString<ConsentConfig>(invalidConfig)
    }
}
