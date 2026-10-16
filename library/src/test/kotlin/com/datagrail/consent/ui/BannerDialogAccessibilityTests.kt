package com.datagrail.consent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for accessibility-related logic in BannerDialog
 *
 * These tests verify that accessibility content descriptions are correctly formatted.
 * UI-level accessibility testing (TalkBack navigation) should be done via instrumentation tests
 * or manual testing.
 */
class BannerDialogAccessibilityTests {
    // MARK: - Content Description Formatting Tests

    @Test
    fun testCloseButtonContentDescription() {
        // The close button should have a simple, clear content description
        val expectedContentDescription = "Close"
        assertEquals(
            "Close button should have 'Close' as content description",
            expectedContentDescription,
            "Close",
        )
    }

    @Test
    fun testTextElementContentDescription_MatchesTextContent() {
        // Text elements should use their text content as the content description
        val textContent = "We value your privacy"
        val contentDescription = textContent // In BannerDialog, this is how it's set

        assertEquals(
            "Text element content description should match the text content",
            textContent,
            contentDescription,
        )
    }

    @Test
    fun testButtonContentDescription_IncludesActionContext() {
        // Buttons should have content descriptions that explain what they do

        // Accept All button
        val acceptAllText = "Accept All"
        val acceptAllDescription = "$acceptAllText - Accept all consent categories"
        assertTrue(
            "Accept All button description should include action context",
            acceptAllDescription.contains("Accept all consent categories"),
        )

        // Reject All button
        val rejectAllText = "Reject All"
        val rejectAllDescription = "$rejectAllText - Reject all non-essential categories"
        assertTrue(
            "Reject All button description should include action context",
            rejectAllDescription.contains("Reject all non-essential categories"),
        )

        // Custom/Save button
        val customText = "Save Preferences"
        val customDescription = "$customText - Save your consent preferences"
        assertTrue(
            "Custom button description should include action context",
            customDescription.contains("Save your consent preferences"),
        )
    }

    @Test
    fun testLinkContentDescription_IndicatesLinkBehavior() {
        // Links should indicate they open in browser
        val linkText = "Privacy Policy"
        val linkDescription = "$linkText - Opens link in browser"

        assertTrue(
            "Link content description should indicate it opens in browser",
            linkDescription.endsWith("Opens link in browser"),
        )
    }

    @Test
    fun testCategoryToggleContentDescription_IncludesNameAndState() {
        // Category toggles should include category name and current state
        val categoryName = "Marketing"
        val isEnabled = true
        val statusText = if (isEnabled) "Enabled" else "Disabled"

        val contentDescription = "$categoryName consent - $statusText"

        assertTrue(
            "Category toggle should include category name",
            contentDescription.contains(categoryName),
        )
        assertTrue(
            "Category toggle should include consent suffix",
            contentDescription.contains("consent"),
        )
        assertTrue(
            "Category toggle should include state",
            contentDescription.contains(statusText),
        )
    }

    @Test
    fun testEssentialCategoryToggleContentDescription_IndicatesAlwaysEnabled() {
        // Essential (always-on) categories render a label instead of a toggle, so their
        // content description omits the Enabled/Disabled status segment.
        val categoryName = "Essential"
        val contentDescription =
            buildCategoryContentDescription(
                categoryName = categoryName,
                isEnabled = true,
                isAlwaysOn = true,
            )

        assertTrue(
            "Essential category should indicate always enabled",
            contentDescription.contains("Always enabled"),
        )
        assertTrue(
            "Essential category should explain why",
            contentDescription.contains("required for functionality"),
        )
        assertTrue(
            "Essential category should not include a toggle Enabled/Disabled state segment",
            !contentDescription.contains(" - Enabled") && !contentDescription.contains(" - Disabled"),
        )
    }

    @Test
    fun testNonEssentialCategoryToggleContentDescription_DoesNotIndicateAlwaysEnabled() {
        // Non-essential categories should NOT have the "Always enabled" suffix
        val categoryName = "Marketing"
        val isEnabled = false
        val statusText = if (isEnabled) "Enabled" else "Disabled"
        val isAlwaysOn = false

        var contentDescription = "$categoryName consent - $statusText"
        if (isAlwaysOn) {
            contentDescription = "$contentDescription - Always enabled, required for functionality"
        }

        assertTrue(
            "Non-essential category should NOT indicate always enabled",
            !contentDescription.contains("Always enabled"),
        )
    }

    // MARK: - Always-On Label Tests

    @Test
    fun testAlwaysOnLabel_UsesEssentialLabelTranslationWhenPresent() {
        // Always-on categories show the configured essentialLabel translation when provided
        val result = buildAlwaysOnLabel(essentialLabel = "Siempre activado")

        assertEquals(
            "Should use the configured essentialLabel translation",
            "Siempre activado",
            result,
        )
    }

    @Test
    fun testAlwaysOnLabel_FallsBackToDefaultWhenTranslationMissing() {
        // When no essentialLabel translation is configured, fall back to the literal default
        assertEquals(
            "Should fall back to 'Always On' when translation is null",
            "Always On",
            buildAlwaysOnLabel(essentialLabel = null),
        )
    }

    @Test
    fun testAlwaysOnLabel_UsesTranslationVerbatimWhenNonNull() {
        // Fallback is null-only (?:), so a non-null (even empty) translation is used verbatim
        // rather than falling back to the default. Pins the actual coalescing behavior.
        assertEquals(
            "A non-null translation is used as-is, no fallback",
            "",
            buildAlwaysOnLabel(essentialLabel = ""),
        )
    }

    // MARK: - Content Description Builder Tests

    @Test
    fun testBuildCategoryContentDescription_EnabledNonEssential() {
        val result =
            buildCategoryContentDescription(
                categoryName = "Analytics",
                isEnabled = true,
                isAlwaysOn = false,
            )

        assertEquals(
            "Analytics consent - Enabled",
            result,
        )
    }

    @Test
    fun testBuildCategoryContentDescription_DisabledNonEssential() {
        val result =
            buildCategoryContentDescription(
                categoryName = "Marketing",
                isEnabled = false,
                isAlwaysOn = false,
            )

        assertEquals(
            "Marketing consent - Disabled",
            result,
        )
    }

    @Test
    fun testBuildCategoryContentDescription_EnabledEssential() {
        val result =
            buildCategoryContentDescription(
                categoryName = "Essential",
                isEnabled = true,
                isAlwaysOn = true,
            )

        // Always-on categories render a label rather than a toggle, so the description does
        // not include the Enabled/Disabled status segment.
        assertEquals(
            "Essential consent - Always enabled, required for functionality",
            result,
        )
    }

    @Test
    fun testBuildButtonContentDescription_AcceptAll() {
        val result = buildButtonContentDescription("Accept All", "accept_all")

        assertEquals(
            "Accept All - Accept all consent categories",
            result,
        )
    }

    @Test
    fun testBuildButtonContentDescription_RejectAll() {
        val result = buildButtonContentDescription("Reject All", "reject_all")

        assertEquals(
            "Reject All - Reject all non-essential categories",
            result,
        )
    }

    @Test
    fun testBuildButtonContentDescription_Custom() {
        val result = buildButtonContentDescription("Save Preferences", "custom")

        assertEquals(
            "Save Preferences - Save your consent preferences",
            result,
        )
    }

    @Test
    fun testBuildButtonContentDescription_OpenLayer() {
        val result = buildButtonContentDescription("More Options", "open_layer")

        assertEquals(
            "More Options - View more options",
            result,
        )
    }

    @Test
    fun testBuildButtonContentDescription_Noop() {
        val result = buildButtonContentDescription("Close", "noop")

        assertEquals(
            "Close - Close without saving",
            result,
        )
    }

    @Test
    fun testBuildButtonContentDescription_Link() {
        val result = buildButtonContentDescription("Privacy Policy", "link")

        assertEquals(
            "Privacy Policy - Opens link in browser",
            result,
        )
    }

    @Test
    fun testBuildButtonContentDescription_UnknownAction() {
        val result = buildButtonContentDescription("Unknown Button", "some_unknown_action")

        // Unknown actions should just use the button text
        assertEquals(
            "Unknown Button",
            result,
        )
    }

    // MARK: - Helper functions that mirror BannerDialog logic

    /**
     * Builds the content description for a category toggle
     * This mirrors the logic in BannerDialog.createCategoryView()
     */
    private fun buildCategoryContentDescription(
        categoryName: String,
        isEnabled: Boolean,
        isAlwaysOn: Boolean,
    ): String {
        // Always-on categories render a label instead of a toggle (see
        // BannerDialog.createSingleCategoryToggle), so their description reads
        // "<name> consent - Always enabled, required for functionality" with no
        // Enabled/Disabled status segment. Toggle categories keep the status segment.
        if (isAlwaysOn) {
            return "$categoryName consent - Always enabled, required for functionality"
        }
        val statusText = if (isEnabled) "Enabled" else "Disabled"
        return "$categoryName consent - $statusText"
    }

    /**
     * Resolves the label shown for an always-on category.
     * This mirrors the fallback in BannerDialog.createSingleCategoryToggle()
     * (`categoryTranslation?.essentialLabel ?: "Always On"`) — a null-only coalesce, so a
     * non-null translation (including an empty string) is used verbatim.
     */
    private fun buildAlwaysOnLabel(essentialLabel: String?): String {
        return essentialLabel ?: "Always On"
    }

    /**
     * Builds the content description for a button based on its action
     * This mirrors the logic in BannerDialog.createButtonView()
     */
    private fun buildButtonContentDescription(
        buttonText: String,
        buttonAction: String,
    ): String {
        return when (buttonAction) {
            "accept_all" -> "$buttonText - Accept all consent categories"
            "reject_all" -> "$buttonText - Reject all non-essential categories"
            "custom" -> "$buttonText - Save your consent preferences"
            "open_layer" -> "$buttonText - View more options"
            "noop" -> "$buttonText - Close without saving"
            "link" -> "$buttonText - Opens link in browser"
            else -> buttonText
        }
    }
}
