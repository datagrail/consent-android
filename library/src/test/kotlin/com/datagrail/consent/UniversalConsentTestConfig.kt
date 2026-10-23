package com.datagrail.consent

import com.datagrail.consent.models.*

/**
 * A universal-consent-enabled config with an always-on `category_essential` and an opt-in
 * `category_marketing`, shared by the universal consent tests.
 */
internal fun universalConsentTestConfig(syncOptout: Boolean): ConsentConfig {
    val categoryElements =
        listOf("category_essential" to true, "category_marketing" to false).map { (gtmKey, alwaysOn) ->
            ConsentLayerCategory(
                id =
                    java.util.UUID
                        .randomUUID()
                        .toString(),
                consentCategoryId =
                    java.util.UUID
                        .randomUUID()
                        .toString(),
                order = 1,
                hidden = false,
                primitive = "dg-category-essential",
                alwaysOn = alwaysOn,
                gtmKey = gtmKey,
                uuids = emptyList(),
                cookiePatterns = emptyList(),
                translations = emptyMap(),
                showTrackingDetailsLink = false,
            )
        }
    val element =
        ConsentLayerElement(
            id =
                java.util.UUID
                    .randomUUID()
                    .toString(),
            order = 1,
            type = "ConsentLayerCategoryElement",
            style = null,
            buttonAction = null,
            targetConsentLayer = null,
            categories = emptyList(),
            translations = null,
            links = null,
            consentLayerCategories = categoryElements,
            showTrackingDetailsLink = false,
            consentLayerCategoriesConfigId = null,
            trackingDetailsLinkTranslations = null,
        )
    val layer =
        ConsentLayer(
            id =
                java.util.UUID
                    .randomUUID()
                    .toString(),
            name = "Main Layer",
            position = "bottom",
            showCloseButton = true,
            bannerApiId = "main",
            elements = listOf(element),
        )
    val layout =
        Layout(
            id =
                java.util.UUID
                    .randomUUID()
                    .toString(),
            name = "Default",
            description = null,
            status = "published",
            defaultLayout = true,
            collapsedOnMobile = false,
            firstLayerId = layer.id,
            consentLayers = mapOf(layer.id to layer),
        )
    return ConsentConfig(
        version = "config-version-current",
        consentContainerVersionId =
            java.util.UUID
                .randomUUID()
                .toString(),
        dgCustomerId = "ac46d8ad-a67a-431f-a5d5-9e3eb922dae7",
        p = System.currentTimeMillis(),
        dch = "categorize",
        dc = "dg-category-essential",
        privacyDomain = "consent.datagrail.io",
        plugins =
            Plugins(
                scriptControl = true,
                allCookieSubdomains = true,
                cookieBlocking = true,
                localStorageBlocking = true,
                syncOTConsent = false,
            ),
        testMode = false,
        ignoreDoNotTrack = false,
        trackingDetailsUrl = "https://example.com/tracking",
        consentMode = "optin",
        showBanner = true,
        consentPolicy = ConsentPolicy(name = "GDPR", default = true),
        gppUsNat = false,
        initialCategories =
            InitialCategories(
                respectGpc = false,
                respectDnt = false,
                respectOptout = false,
                initial = listOf("category_essential"),
                gpc = emptyList(),
                optout = emptyList(),
            ),
        layout = layout,
        consentProjectId = "proj_abc123",
        universalConsent = UniversalConsentConfig(enabled = true, syncOptout = syncOptout),
    )
}
