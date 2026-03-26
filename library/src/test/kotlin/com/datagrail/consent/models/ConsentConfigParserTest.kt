package com.datagrail.consent.models

import com.datagrail.consent.utils.ConfigValidator
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

    @Test
    fun `test parse config without syncOTConsent defaults to false`() {
        val configFile = File(javaClass.classLoader?.getResource("config-no-sync-ot.json")?.file ?: "")
        assertTrue("Config file should exist", configFile.exists())

        val config = json.decodeFromString<ConsentConfig>(configFile.readText())

        // syncOTConsent is absent from this config and should default to false
        assertFalse(config.plugins.syncOTConsent)

        // Verify the rest of plugins parsed correctly
        assertTrue(config.plugins.scriptControl)
        assertTrue(config.plugins.allCookieSubdomains)
        assertTrue(config.plugins.cookieBlocking)
        assertTrue(config.plugins.localStorageBlocking)
    }

    @Test
    fun `test parse config with gpcDntLayerId present in no-sync-ot config`() {
        val configFile = File(javaClass.classLoader?.getResource("config-no-sync-ot.json")?.file ?: "")
        val config = json.decodeFromString<ConsentConfig>(configFile.readText())

        // This config has a non-null gpc_dnt_layer_id
        assertEquals("4de8260b-d70c-4601-9232-6d9631e49622", config.layout.gpcDntLayerId)

        // Verify layout basics still parse
        assertEquals("0c74436b-1c80-4078-89f8-e0840903252a", config.layout.id)
        assertEquals("CPRA only", config.layout.name)
        assertFalse(config.layout.defaultLayout)
        assertEquals(5, config.layout.consentLayers.size)
    }

    @Test
    fun `test parse config with gpcDntLayerId present`() {
        val configFile = File(javaClass.classLoader?.getResource("config-bys.json")?.file ?: "")
        val config = json.decodeFromString<ConsentConfig>(configFile.readText())

        // config-bys.json has a non-null gpc_dnt_layer_id
        assertEquals("19fcd340-6fb6-4363-bc9b-df10af839800", config.layout.gpcDntLayerId)
    }

    @Test
    fun `test parse CPRA config`() {
        val configFile = File(javaClass.classLoader?.getResource("config-cpra.json")?.file ?: "")
        assertTrue("Config file should exist", configFile.exists())

        val config = json.decodeFromString<ConsentConfig>(configFile.readText())

        // Verify top-level properties
        assertEquals("f697b4ac-341a-4e5a-9794-d09e23148771", config.version)
        assertEquals("72c28510-a607-4bfc-8eb9-512273b9c625", config.consentContainerVersionId)
        assertEquals("c30be0d2-795f-40af-9f70-502b83f7bb68", config.dgCustomerId)
        assertEquals("bradleyy.dg-dev.com", config.privacyDomain)
        assertFalse(config.testMode)
        assertFalse(config.ignoreDoNotTrack)
        assertEquals("optout", config.consentMode)
        assertTrue(config.showBanner)
        assertFalse(config.gppUsNat)

        // Verify plugins
        assertTrue(config.plugins.scriptControl)
        assertTrue(config.plugins.cookieBlocking)
        assertTrue(config.plugins.localStorageBlocking)
        assertTrue(config.plugins.syncOTConsent)

        // Verify consent policy
        assertEquals("CPRA", config.consentPolicy.name)
        assertFalse(config.consentPolicy.default)

        // Verify initial categories — optout mode has all 4 categories initially on
        assertEquals(4, config.initialCategories.initial.size)
        assertTrue(config.initialCategories.initial.contains("dg-category-essential"))
        assertTrue(config.initialCategories.initial.contains("dg-category-performance"))
        assertTrue(config.initialCategories.initial.contains("dg-category-functional"))
        assertTrue(config.initialCategories.initial.contains("dg-category-marketing"))
        assertEquals(1, config.initialCategories.gpc.size)
        assertEquals("dg-category-essential", config.initialCategories.gpc.first())

        // Verify layout
        assertEquals("d5e6b99f-90bc-4124-9954-8ae4880599cd", config.layout.id)
        assertEquals("Global Layout", config.layout.name)
        assertNull(config.layout.gpcDntLayerId)
        assertEquals(2, config.layout.consentLayers.size)

        // Verify first layer
        assertEquals("1b4c5952-ab0b-4f6d-82f4-c28a430e9d59", config.layout.firstLayerId)
        val defaultLayer = config.layout.consentLayers[config.layout.firstLayerId]
        assertNotNull(defaultLayer)
        assertEquals("Default Layer", defaultLayer?.name)
        assertEquals("left", defaultLayer?.position)

        // Verify categories layer
        val categoriesLayer = config.layout.consentLayers["33e8abaf-f967-44d8-8ad4-56c9f18028f6"]
        assertNotNull(categoriesLayer)
        assertEquals("Categories Layer", categoriesLayer?.name)
        assertEquals("modal", categoriesLayer?.position)

        // Validate config
        ConfigValidator.validate(config)
    }

    @Test
    fun `test parse GDPR config`() {
        val configFile = File(javaClass.classLoader?.getResource("config-gdpr.json")?.file ?: "")
        assertTrue("Config file should exist", configFile.exists())

        val config = json.decodeFromString<ConsentConfig>(configFile.readText())

        // Verify top-level properties
        assertEquals("f697b4ac-341a-4e5a-9794-d09e23148771", config.version)
        assertEquals("72c28510-a607-4bfc-8eb9-512273b9c625", config.consentContainerVersionId)
        assertEquals("c30be0d2-795f-40af-9f70-502b83f7bb68", config.dgCustomerId)
        assertEquals("bradleyy.dg-dev.com", config.privacyDomain)
        assertFalse(config.testMode)
        assertFalse(config.ignoreDoNotTrack)
        assertEquals("optin", config.consentMode)
        assertTrue(config.showBanner)
        assertFalse(config.gppUsNat)

        // Verify plugins
        assertTrue(config.plugins.scriptControl)
        assertTrue(config.plugins.cookieBlocking)
        assertTrue(config.plugins.localStorageBlocking)
        assertTrue(config.plugins.syncOTConsent)

        // Verify consent policy
        assertEquals("GDPR", config.consentPolicy.name)
        assertFalse(config.consentPolicy.default)

        // Verify initial categories — optin mode has only essential initially on
        assertEquals(1, config.initialCategories.initial.size)
        assertEquals("dg-category-essential", config.initialCategories.initial.first())
        assertEquals(1, config.initialCategories.gpc.size)
        assertEquals("dg-category-essential", config.initialCategories.gpc.first())

        // Verify layout — same layout as CPRA, different policy
        assertEquals("d5e6b99f-90bc-4124-9954-8ae4880599cd", config.layout.id)
        assertEquals("Global Layout", config.layout.name)
        assertNull(config.layout.gpcDntLayerId)
        assertEquals(2, config.layout.consentLayers.size)

        // Verify first layer
        assertEquals("1b4c5952-ab0b-4f6d-82f4-c28a430e9d59", config.layout.firstLayerId)
        val defaultLayer = config.layout.consentLayers[config.layout.firstLayerId]
        assertNotNull(defaultLayer)
        assertEquals("Default Layer", defaultLayer?.name)

        // Verify categories layer
        val categoriesLayer = config.layout.consentLayers["33e8abaf-f967-44d8-8ad4-56c9f18028f6"]
        assertNotNull(categoriesLayer)
        assertEquals("Categories Layer", categoriesLayer?.name)

        // Validate config
        ConfigValidator.validate(config)
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

    @Test
    fun `test validate parsed config`() {
        val configFile = File(javaClass.classLoader?.getResource("test-config.json")?.file ?: "")
        assertTrue("Config file should exist", configFile.exists())

        val configJson = configFile.readText()
        val config = json.decodeFromString<ConsentConfig>(configJson)

        // This should not throw any exceptions
        ConfigValidator.validate(config)
    }

    @Test
    fun `test validate config without syncOTConsent`() {
        val configFile = File(javaClass.classLoader?.getResource("config-no-sync-ot.json")?.file ?: "")
        assertTrue("Config file should exist", configFile.exists())

        val config = json.decodeFromString<ConsentConfig>(configFile.readText())

        // This should not throw any exceptions
        ConfigValidator.validate(config)
    }
}
