package com.datagrail.consent.utils

import com.datagrail.consent.models.ConsentConfig
import com.datagrail.consent.models.ConsentException

/**
 * Validates consent configuration structure
 */
object ConfigValidator {
    /**
     * Validate a consent configuration
     * @param config The configuration to validate
     * @throws ConsentException.ValidationError if validation fails
     */
    fun validate(config: ConsentConfig) {
        // Validate required fields
        validateRequiredFields(config)

        // Validate layers
        validateLayers(config)

        // Validate elements
        validateElements(config)

        // Validate categories
        validateCategories(config)
    }

    private fun validateRequiredFields(config: ConsentConfig) {
        if (config.version.isEmpty()) {
            throw ConsentException.ValidationError("Missing required field: version")
        }

        if (config.dgCustomerId.isEmpty()) {
            throw ConsentException.ValidationError("Missing required field: dgCustomerId")
        }

        if (config.privacyDomain.isEmpty()) {
            throw ConsentException.ValidationError("Missing required field: privacyDomain")
        }

        if (config.consentMode !in listOf("optin", "optout")) {
            throw ConsentException.ValidationError("Invalid consentMode: ${config.consentMode}")
        }
    }

    private fun validateLayers(config: ConsentConfig) {
        if (config.layout.consentLayers.isEmpty()) {
            throw ConsentException.ValidationError("No consent layers defined")
        }

        if (config.layout.firstLayerId !in config.layout.consentLayers.keys) {
            throw ConsentException.ValidationError(
                "firstLayerId '${config.layout.firstLayerId}' does not reference an existing layer",
            )
        }

        // Validate that all layers have at least one element
        config.layout.consentLayers.forEach { (layerId, layer) ->
            if (layer.elements.isEmpty()) {
                ConsentLogger.w("Layer '$layerId' has no elements")
            }
        }
    }

    private fun validateElements(config: ConsentConfig) {
        val validElementTypes =
            setOf(
                "ConsentLayerTextElement",
                "ConsentLayerButtonElement",
                "ConsentLayerLinkElement",
                "ConsentLayerCategoryElement",
                "ConsentLayerTrackingDetailsElement",
                "ConsentLayerBrowserSignalNoticeElement",
                "ConsentLayerLanguagePickerElement"
            )

        config.layout.consentLayers.values.forEach { layer ->
            layer.elements.forEach { element ->
                // Validate element type
                if (element.type !in validElementTypes) {
                    throw ConsentException.ValidationError("Invalid element type: ${element.type}")
                }

                // Validate button actions that reference layers
                if (element.buttonAction == "open_layer") {
                    val targetId = element.targetConsentLayer
                    if (targetId == null) {
                        throw ConsentException.ValidationError(
                            "Button with action 'open_layer' must specify targetConsentLayer",
                        )
                    }
                    if (targetId !in config.layout.consentLayers.keys) {
                        throw ConsentException.ValidationError(
                            "Button target layer '$targetId' does not exist",
                        )
                    }
                }

                // Validate translations exist
                when (element.type) {
                    "ConsentLayerLinkElement" -> {
                        // Links have translations in their links array
                        if (element.links == null || element.links.isEmpty()) {
                            throw ConsentException.ValidationError(
                                "Link element '${element.id}' has no links"
                            )
                        }
                        element.links.forEach { link ->
                            if (link.translations.isEmpty()) {
                                throw ConsentException.ValidationError(
                                    "Link '${link.id}' in element '${element.id}' has no translations"
                                )
                            }
                        }
                    }
                    "ConsentLayerCategoryElement",
                    "ConsentLayerLanguagePickerElement",
                    "ConsentLayerTrackingDetailsElement",
                    "ConsentLayerBrowserSignalNoticeElement" -> {
                        // These elements don't require standard translations
                    }
                    else -> {
                        // Standard elements require translations
                        if (element.translations == null || element.translations.isEmpty()) {
                            throw ConsentException.ValidationError("Element '${element.id}' has no translations")
                        }
                    }
                }
            }
        }
    }

    private fun validateCategories(config: ConsentConfig) {
        val validPrimitives =
            setOf(
                "dg-category-essential",
                "dg-category-performance",
                "dg-category-functional",
                "dg-category-marketing",
            )

        // Find all category elements
        val categoryElements =
            config.layout.consentLayers.values
                .flatMap { it.elements }
                .filter { it.type == "ConsentLayerCategoryElement" }

        categoryElements.forEach { element ->
            val categories = element.consentLayerCategories
            if (categories == null || categories.isEmpty()) {
                throw ConsentException.ValidationError("Category element '${element.id}' has no categories")
            }

            categories.forEach { category ->
                // Validate primitive
                if (category.primitive !in validPrimitives) {
                    throw ConsentException.ValidationError("Invalid category primitive: ${category.primitive}")
                }

                // Validate gtmKey
                if (category.gtmKey.isEmpty()) {
                    throw ConsentException.ValidationError("Category '${category.id}' has empty gtmKey")
                }

                // Validate translations
                if (category.translations.isEmpty()) {
                    throw ConsentException.ValidationError("Category '${category.id}' has no translations")
                }
            }
        }
    }
}
