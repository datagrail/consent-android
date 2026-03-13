package com.datagrail.consent.ui

/**
 * Display style options for the consent banner
 */
enum class BannerDisplayStyle {
    /**
     * Modal dialog that covers approximately 90% of the screen height.
     * Has rounded corners and a shadow.
     * Close button visibility is controlled by the show_close_button setting
     * of the currently active consent layer (based on currentLayerKey).
     */
    MODAL,

    /**
     * Full screen dialog covering the entire screen.
     * Close button visibility is controlled by the show_close_button setting
     * of the currently active consent layer (based on currentLayerKey).
     */
    FULL_SCREEN,
}
