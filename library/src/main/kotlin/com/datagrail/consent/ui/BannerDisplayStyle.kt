package com.datagrail.consent.ui

/**
 * Display style options for the consent banner
 */
enum class BannerDisplayStyle {
    /**
     * Modal dialog that covers approximately 90% of the screen height.
     * Has rounded corners and a shadow. Always shows a close button.
     */
    MODAL,

    /**
     * Full screen dialog covering the entire screen.
     * Close button visibility is controlled by the config's show_close_button setting.
     */
    FULL_SCREEN,
}
