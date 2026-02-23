package com.datagrail.consent.models

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * Root configuration object for the consent banner
 */
@Serializable
data class ConsentConfig(
    val version: String,
    val consentContainerVersionId: String,
    val dgCustomerId: String,
    val p: Long,
    val dch: String,
    val dc: String,
    val privacyDomain: String,
    val plugins: Plugins,
    val testMode: Boolean,
    val ignoreDoNotTrack: Boolean,
    val trackingDetailsUrl: String,
    val consentMode: String,
    val showBanner: Boolean,
    val consentPolicy: ConsentPolicy,
    val gppUsNat: Boolean,
    val initialCategories: InitialCategories,
    val layout: Layout,
)

/**
 * Plugin configuration flags
 */
@Serializable
data class Plugins(
    val scriptControl: Boolean,
    val allCookieSubdomains: Boolean,
    val cookieBlocking: Boolean,
    val localStorageBlocking: Boolean,
    val syncOTConsent: Boolean,
)

/**
 * Consent policy information
 */
@Serializable
data class ConsentPolicy(
    val name: String,
    val default: Boolean,
)

/**
 * Initial category settings for different scenarios
 */
@Serializable
data class InitialCategories(
    @SerialName("respect_gpc")
    val respectGpc: Boolean,
    @SerialName("respect_dnt")
    val respectDnt: Boolean,
    @SerialName("respect_optout")
    val respectOptout: Boolean,
    val initial: List<String>,
    val gpc: List<String>,
    val optout: List<String>,
)

/**
 * Layout configuration for the consent banner
 */
@Serializable
data class Layout(
    val id: String,
    val name: String,
    val description: String? = null,
    val status: String,
    @SerialName("default_layout")
    val defaultLayout: Boolean,
    @SerialName("collapsed_on_mobile")
    val collapsedOnMobile: Boolean,
    @SerialName("first_layer_id")
    val firstLayerId: String,
    @SerialName("consent_layers")
    val consentLayers: Map<String, ConsentLayer>,
)

/**
 * A single consent layer (screen) in the banner flow
 */
@Serializable
data class ConsentLayer(
    val id: String,
    val name: String,
    val theme: String,
    val position: String,
    @SerialName("show_close_button")
    val showCloseButton: Boolean,
    @SerialName("banner_api_id")
    val bannerApiId: String,
    val elements: List<ConsentLayerElement>,
)

/**
 * A UI element within a consent layer
 */
@Serializable
data class ConsentLayerElement(
    val id: String,
    val order: Int,
    val type: String,
    // Text element fields
    val style: String? = null,
    // Button element fields
    @SerialName("button_action")
    val buttonAction: String? = null,
    @SerialName("target_consent_layer")
    val targetConsentLayer: String? = null,
    val categories: List<String>? = null,
    // Link element fields
    val links: List<LinkItem>? = null,
    // Category element fields
    @SerialName("consent_layer_categories")
    val consentLayerCategories: List<ConsentLayerCategory>? = null,
    @SerialName("show_tracking_details_link")
    val showTrackingDetailsLink: Boolean? = null,
    @SerialName("consent_layer_categories_config_id")
    val consentLayerCategoriesConfigId: String? = null,
    @Serializable(with = TrackingDetailsLinkTranslationsSerializer::class)
    @SerialName("tracking_details_link_translations")
    val trackingDetailsLinkTranslations: List<TrackingDetailsLinkTranslation>? = null,
    // Browser signal notice fields
    @SerialName("show_icon")
    val showIcon: Boolean? = null,
    @SerialName("consent_layer_browser_signal_notice_config_id")
    val consentLayerBrowserSignalNoticeConfigId: String? = null,
    @SerialName("browser_signal_notice_translations")
    val browserSignalNoticeTranslations: Map<String, BrowserSignalNoticeTranslation>? = null,
    // Common
    val translations: Map<String, ElementTranslation>? = null,
)

/**
 * Translation for an element
 */
@Serializable
data class ElementTranslation(
    val id: String,
    val locale: String,
    val value: String? = null,
    val text: String? = null,
    val url: String? = null,
)

/**
 * A link item within a link element
 */
@Serializable
data class LinkItem(
    val id: String,
    val order: Int,
    val translations: Map<String, ElementTranslation>,
)

/**
 * Tracking details link translation
 */
@Serializable
data class TrackingDetailsLinkTranslation(
    val id: String? = null,
    val locale: String = "",
    val value: String = "",
)

/**
 * Browser signal notice translation
 */
@Serializable
data class BrowserSignalNoticeTranslation(
    val id: String? = null,
    val locale: String? = null,
    val value: String? = null,
)

/**
 * A consent category within a category element
 */
@Serializable
data class ConsentLayerCategory(
    val id: String,
    @SerialName("consent_category_id")
    val consentCategoryId: String,
    val order: Int,
    val hidden: Boolean,
    val primitive: String,
    @SerialName("always_on")
    val alwaysOn: Boolean,
    @SerialName("gtm_key")
    val gtmKey: String,
    val uuids: List<String>,
    @SerialName("cookie_patterns")
    val cookiePatterns: List<String>,
    val translations: Map<String, CategoryTranslation>,
    @SerialName("show_tracking_details_link")
    val showTrackingDetailsLink: Boolean,
)

/**
 * Translation for a category
 */
@Serializable
data class CategoryTranslation(
    val id: String? = null,
    val locale: String? = null,
    val name: String? = null,
    val description: String? = null,
    @SerialName("essential_label")
    val essentialLabel: String? = null,
)

/**
 * Custom serializer for tracking_details_link_translations that handles both array and dictionary formats.
 * The config can have either:
 * - Array format: [{"id": "...", "locale": "en", "value": "..."}]
 * - Dictionary format: {"en": {"locale": "en", "value": "..."}}
 */
object TrackingDetailsLinkTranslationsSerializer : KSerializer<List<TrackingDetailsLinkTranslation>?> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("TrackingDetailsLinkTranslations")

    override fun serialize(
        encoder: Encoder,
        value: List<TrackingDetailsLinkTranslation>?,
    ) {
        if (value == null) return
        encoder.encodeSerializableValue(ListSerializer(TrackingDetailsLinkTranslation.serializer()), value)
    }

    override fun deserialize(decoder: Decoder): List<TrackingDetailsLinkTranslation>? {
        val jsonDecoder = decoder as? JsonDecoder ?: return null
        val element = jsonDecoder.decodeJsonElement()

        return when (element) {
            is JsonArray -> {
                jsonDecoder.json.decodeFromJsonElement(
                    ListSerializer(TrackingDetailsLinkTranslation.serializer()),
                    element,
                )
            }
            is JsonObject -> {
                // Convert dictionary format to list
                element.entries.mapNotNull { (locale, translationElement) ->
                    try {
                        val translation =
                            jsonDecoder.json.decodeFromJsonElement(
                                TrackingDetailsLinkTranslation.serializer(),
                                translationElement,
                            )
                        // Ensure locale is set
                        if (translation.locale.isEmpty()) {
                            translation.copy(locale = locale)
                        } else {
                            translation
                        }
                    } catch (e: Exception) {
                        null
                    }
                }
            }
            else -> null
        }
    }
}
