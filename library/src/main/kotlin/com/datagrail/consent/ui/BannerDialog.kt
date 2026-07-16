package com.datagrail.consent.ui

import android.app.Dialog
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.Html
import android.text.method.LinkMovementMethod
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import com.datagrail.consent.models.CategoryConsent
import com.datagrail.consent.models.ConsentConfig
import com.datagrail.consent.models.ConsentLayerCategory
import com.datagrail.consent.models.ConsentLayerElement
import com.datagrail.consent.models.ConsentPreferences
import java.util.Locale

/**
 * Font size and style configuration for text elements in the consent banner.
 * Override individual sizes/styles while keeping the rest at their defaults.
 *
 * @param titleTextSizeSp Text size in SP for `dg-title` elements (e.g. banner headline).
 * @param titleTypefaceStyle Typeface style for `dg-title` elements.
 * @param headerTextSizeSp Text size in SP for `dg-header` elements (e.g. section headings).
 * @param headerTypefaceStyle Typeface style for `dg-header` elements.
 * @param bodyTextSizeSp Text size in SP for `dg-main-content-explanation` and all other text elements.
 * @param bodyTypefaceStyle Typeface style for body text elements.
 */
data class BannerTextStyleConfig(
    val titleTextSizeSp: Float = 22f,
    val titleTypefaceStyle: Int = android.graphics.Typeface.BOLD,
    val headerTextSizeSp: Float = 18f,
    val headerTypefaceStyle: Int = android.graphics.Typeface.BOLD,
    val bodyTextSizeSp: Float = 16f,
    val bodyTypefaceStyle: Int = android.graphics.Typeface.NORMAL,
)

/**
 * DialogFragment that displays the consent banner with configurable layers and elements
 */
class BannerDialog : DialogFragment() {
    private var config: ConsentConfig? = null
    private var preferences: ConsentPreferences? = null
    private var currentLayerKey: String? = null
    private var onDismissListener: ((ConsentPreferences?) -> Unit)? = null
    private var displayStyle: BannerDisplayStyle = BannerDisplayStyle.MODAL
    private var textStyleConfig: BannerTextStyleConfig = BannerTextStyleConfig()

    private lateinit var cardFrame: FrameLayout
    private lateinit var scrollView: ScrollView
    private lateinit var contentLayout: LinearLayout
    private var closeButton: ImageButton? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return Dialog(requireContext(), getDialogTheme()).apply {
            window?.apply {
                setBackgroundDrawableResource(android.R.color.transparent)
                when (displayStyle) {
                    BannerDisplayStyle.FULL_SCREEN -> {
                        setLayout(
                            WindowManager.LayoutParams.MATCH_PARENT,
                            WindowManager.LayoutParams.MATCH_PARENT,
                        )
                    }
                    BannerDisplayStyle.MODAL -> {
                        setLayout(
                            WindowManager.LayoutParams.MATCH_PARENT,
                            WindowManager.LayoutParams.WRAP_CONTENT,
                        )
                        setGravity(Gravity.CENTER)
                    }
                }
            }
        }
    }

    private fun getDialogTheme(): Int {
        return when (displayStyle) {
            BannerDisplayStyle.FULL_SCREEN -> android.R.style.Theme_Black_NoTitleBar_Fullscreen
            BannerDisplayStyle.MODAL -> android.R.style.Theme_DeviceDefault_Dialog_NoActionBar
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val rootView = createRootView()

        // Always create close button (added last so it appears on top)
        // Visibility will be updated by renderLayer() based on the current layer's config
        closeButton = createCloseButton()
        cardFrame.addView(closeButton)

        currentLayerKey?.let { renderLayer(it) }

        return rootView
    }

    private fun createRootView(): ViewGroup {
        val outerFrame =
            FrameLayout(requireContext()).apply {
                layoutParams =
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
            }

        // Card-sized frame so the close button can be positioned relative to the
        // visible card (not the full screen) — this matters for MODAL, where the
        // card is centered and inset rather than filling the screen.
        cardFrame =
            FrameLayout(requireContext()).apply {
                when (displayStyle) {
                    BannerDisplayStyle.FULL_SCREEN -> {
                        layoutParams =
                            FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.MATCH_PARENT,
                            )
                    }
                    BannerDisplayStyle.MODAL -> {
                        val displayMetrics = resources.displayMetrics
                        val screenHeight = displayMetrics.heightPixels
                        val modalHeight = (screenHeight * 0.9).toInt()

                        layoutParams =
                            FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                modalHeight,
                            ).apply {
                                gravity = Gravity.CENTER
                                leftMargin = 32
                                rightMargin = 32
                            }
                    }
                }
            }

        val contentContainer =
            LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams =
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    )
                // Reserve room at the top so content never sits under the close button,
                // which is positioned relative to cardFrame rather than this container.
                val density = resources.displayMetrics.density
                val closeButtonReservedSpace =
                    ((CLOSE_BUTTON_SIZE_DP + CLOSE_BUTTON_MARGIN_DP) * density).toInt()
                // Density-scale the side/bottom padding too, so every argument to setPadding is
                // in consistent dp units rather than mixing scaled values with raw px literals.
                val sidePadding = (CONTENT_PADDING_DP * density).toInt()
                when (displayStyle) {
                    BannerDisplayStyle.FULL_SCREEN -> {
                        setBackgroundColor(getColor(com.datagrail.consent.R.color.consent_background))
                        setPadding(sidePadding, closeButtonReservedSpace, sidePadding, sidePadding)
                    }
                    BannerDisplayStyle.MODAL -> {
                        // Rounded corners with shadow
                        val shape =
                            GradientDrawable().apply {
                                setColor(getColor(com.datagrail.consent.R.color.consent_background))
                                cornerRadius = 24f
                            }
                        background = shape
                        elevation = CONTENT_ELEVATION
                        setPadding(sidePadding, closeButtonReservedSpace, sidePadding, sidePadding)
                    }
                }
            }

        scrollView =
            ScrollView(requireContext()).apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        0,
                        1f,
                    )
            }

        contentLayout =
            LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
            }

        scrollView.addView(contentLayout)
        contentContainer.addView(scrollView)
        cardFrame.addView(contentContainer)
        outerFrame.addView(cardFrame)

        return outerFrame
    }

    @androidx.annotation.VisibleForTesting
    internal fun shouldShowCloseButton(): Boolean {
        val cfg = config ?: return true
        val layer = cfg.layout.consentLayers[currentLayerKey] ?: return true

        return layer.showCloseButton
    }

    private fun createCloseButton(): ImageButton {
        return ImageButton(requireContext()).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(getColor(com.datagrail.consent.R.color.consent_text_primary))
            setBackgroundResource(android.R.color.transparent)
            contentDescription = "Close"
            elevation = CLOSE_BUTTON_ELEVATION  // Ensure button appears above content

            val size = (CLOSE_BUTTON_SIZE_DP * resources.displayMetrics.density).toInt()
            // contentContainer reserves (size + margin) at its top for this button. Split that
            // leftover margin evenly above and below the button so it sits centered in that
            // space rather than flush against its bottom edge.
            val verticalMargin = (CLOSE_BUTTON_MARGIN_DP / 2 * resources.displayMetrics.density).toInt()
            layoutParams =
                FrameLayout.LayoutParams(size, size).apply {
                    gravity = Gravity.TOP or Gravity.END
                    topMargin = verticalMargin
                    rightMargin = (CLOSE_BUTTON_MARGIN_DP * resources.displayMetrics.density).toInt()
                }

            setOnClickListener { dismiss() }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            when (displayStyle) {
                BannerDisplayStyle.FULL_SCREEN -> {
                    setLayout(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
                BannerDisplayStyle.MODAL -> {
                    setLayout(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    setBackgroundDrawableResource(android.R.color.transparent)
                }
            }
        }
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        onDismissListener?.invoke(null)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // When UI mode changes (light/dark mode), reapply all colors to avoid stale color references
        reapplyColors()
    }

    /**
     * Reapplies all color resources to existing views.
     * Called when configuration changes (e.g., dark mode toggle) to prevent stale colors.
     */
    private fun reapplyColors() {
        // Reapply colors to the root content container
        view?.findViewById<ViewGroup>(android.R.id.content)?.let { root ->
            reapplyColorsRecursively(root)
        }

        // Reapply close button color
        closeButton?.setColorFilter(getColor(com.datagrail.consent.R.color.consent_text_primary))

        // Re-render the current layer to refresh all element colors
        currentLayerKey?.let { renderLayer(it) }
    }

    /**
     * Recursively reapplies colors to all views in the hierarchy.
     */
    private fun reapplyColorsRecursively(viewGroup: ViewGroup) {
        for (i in 0 until viewGroup.childCount) {
            val child = viewGroup.getChildAt(i)
            when (child) {
                is LinearLayout -> {
                    // Check if this is a content container or category container
                    val background = child.background
                    if (background is GradientDrawable) {
                        background.setColor(getColor(com.datagrail.consent.R.color.consent_background))
                    } else if (child.id != android.R.id.content) {
                        // Reapply surface color for category containers
                        val currentColor = (child.background as? android.graphics.drawable.ColorDrawable)?.color
                        if (currentColor != null) {
                            child.setBackgroundColor(getColor(com.datagrail.consent.R.color.consent_surface))
                        }
                    }
                    reapplyColorsRecursively(child)
                }
                is ViewGroup -> reapplyColorsRecursively(child)
            }
        }
    }

    private fun renderLayer(layerKey: String) {
        val cfg = config ?: return
        val layer = cfg.layout.consentLayers[layerKey] ?: return

        contentLayout.removeAllViews()

        layer.elements.sortedBy { it.order }.forEach { element ->
            val elementView = createElementView(element)
            contentLayout.addView(elementView)

            contentLayout.addView(
                View(requireContext()).apply {
                    layoutParams =
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            24,
                        )
                },
            )
        }

        // Update close button visibility for this layer
        closeButton?.visibility = if (shouldShowCloseButton()) View.VISIBLE else View.GONE
    }

    /**
     * Renders text as HTML if it contains markup, otherwise sets it as plain text.
     * Makes links clickable when HTML anchor tags are present.
     * Non-http/https URL spans are stripped for security.
     */
    private fun renderRichText(text: String, textView: TextView) {
        if (text.containsHtmlTags()) {
            val spanned = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Html.fromHtml(text, Html.FROM_HTML_MODE_COMPACT)
            } else {
                @Suppress("DEPRECATION") // Required for API 23 support
                Html.fromHtml(text)
            }
            val spannable = android.text.SpannableString(spanned)
            for (span in spannable.getSpans(0, spannable.length, android.text.style.URLSpan::class.java)) {
                val lowerUrl = span.url?.lowercase() ?: ""
                if (!lowerUrl.startsWith("http://") && !lowerUrl.startsWith("https://")) {
                    spannable.removeSpan(span)
                }
            }
            textView.text = spannable
            textView.movementMethod = LinkMovementMethod.getInstance()
        } else {
            textView.text = text
        }
    }

    /**
     * Checks whether a string contains actual HTML tags (e.g. <b>, <a href="...">) rather than
     * incidental less-than characters (e.g. "Value < 100").
     */
    private fun String.containsHtmlTags(): Boolean {
        return contains(Regex("<[a-zA-Z][^>]*>"))
    }

    /**
     * Helper to get color resource with proper context
     */
    private fun getColor(@androidx.annotation.ColorRes colorRes: Int): Int {
        return ContextCompat.getColor(requireContext(), colorRes)
    }

    /**
     * Normalizes element type from config format to standard format.
     * The config may use types like "ConsentLayerTextElement" which should map to "text".
     */
    private fun normalizeElementType(type: String): String {
        return when {
            type.contains("BrowserSignalNotice", ignoreCase = true) -> "browser_signal_notice"
            type.contains("LanguagePicker", ignoreCase = true) -> "language_picker"
            type.contains("Text", ignoreCase = true) -> "text"
            type.contains("Button", ignoreCase = true) -> "button"
            type.contains("Link", ignoreCase = true) -> "link"
            type.contains("Category", ignoreCase = true) -> "category"
            type.contains("Tracking", ignoreCase = true) -> "tracking_details"
            else -> type.lowercase()
        }
    }

    /**
     * Gets the preferred locale code based on device language, falling back to "en".
     */
    private fun getPreferredLocale(): String {
        val deviceLocale = Locale.getDefault().language
        return deviceLocale.ifEmpty { "en" }
    }

    /**
     * Gets translation from a map with proper locale fallback.
     * Priority: device locale -> "en" -> first available
     */
    private fun <T> getTranslationWithFallback(translations: Map<String, T>?): T? {
        if (translations.isNullOrEmpty()) return null
        val preferredLocale = getPreferredLocale()
        return translations[preferredLocale]
            ?: translations["en"]
            ?: translations.values.firstOrNull()
    }

    /**
     * Gets the translation text with locale fallback, preferring 'value' over 'text' field.
     */
    private fun getTranslationText(element: ConsentLayerElement): String {
        val translation = getTranslationWithFallback(element.translations)
        return translation?.value ?: translation?.text ?: ""
    }

    private fun createElementView(element: ConsentLayerElement): View {
        val normalizedType = normalizeElementType(element.type)
        return when (normalizedType) {
            "text" -> createTextView(element)
            "button" -> createButtonView(element)
            "link" -> createLinkView(element)
            "category" -> createCategoryView(element)
            "tracking_details" -> createTrackingDetailsView(element)
            "browser_signal_notice" -> {
                // GPC/DNT are web browser signals that don't apply to mobile apps
                // Return an empty view instead of showing the notice
                View(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 0)
                    visibility = View.GONE
                }
            }
            "language_picker" -> {
                // Language picker is not supported in the Android SDK
                // Return an empty view to avoid showing an error message
                View(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 0)
                    visibility = View.GONE
                }
            }
            else ->
                TextView(requireContext()).apply {
                    text = "Unknown type: ${element.type}"
                    setTextColor(getColor(com.datagrail.consent.R.color.consent_text_secondary))
                    textSize = 12f
                }
        }
    }

    /**
     * Maps a config style string to (textSizeSp, typefaceStyle) using the current textStyleConfig.
     * Supported values: "dg-title", "dg-header", "dg-main-content-explanation".
     * Unknown or null values fall back to body style.
     */
    internal fun textSizeAndStyleFor(style: String?): Pair<Float, Int> {
        return when (style) {
            "dg-title" -> Pair(textStyleConfig.titleTextSizeSp, textStyleConfig.titleTypefaceStyle)
            "dg-header" -> Pair(textStyleConfig.headerTextSizeSp, textStyleConfig.headerTypefaceStyle)
            else -> Pair(textStyleConfig.bodyTextSizeSp, textStyleConfig.bodyTypefaceStyle)
        }
    }

    private fun createTextView(element: ConsentLayerElement): TextView {
        val textContent = getTranslationText(element)
        val (sizeSp, typefaceStyle) = textSizeAndStyleFor(element.style)
        return TextView(requireContext()).apply {
            renderRichText(textContent, this)
            textSize = sizeSp
            setTypeface(typeface, typefaceStyle)
            setTextColor(getColor(com.datagrail.consent.R.color.consent_text_primary))
            layoutParams =
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
    }

    private fun createButtonView(element: ConsentLayerElement): Button {
        return Button(requireContext()).apply {
            val buttonText = getTranslationText(element).ifEmpty { "Button" }
            val action = element.buttonAction ?: ""
            text = buttonText
            isAllCaps = false
            textSize = 16f
            setBackgroundColor(getColor(com.datagrail.consent.R.color.consent_button_background))
            setTextColor(getColor(com.datagrail.consent.R.color.consent_button_text))
            layoutParams =
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    setPadding(0, 16, 0, 16)
                }

            // Accessibility
            contentDescription =
                when (action) {
                    "accept_all" -> "$buttonText - Accept all consent categories"
                    "reject_all" -> "$buttonText - Reject all non-essential categories"
                    "accept_some" -> "$buttonText - Save your selected preferences"
                    "save_preferences", "save", "custom" -> "$buttonText - Save your consent preferences"
                    "dismiss", "close", "noop" -> "$buttonText - Close without saving"
                    "navigate", "open_layer" -> "$buttonText - View more options"
                    else -> buttonText
                }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES

            setOnClickListener {
                when (element.buttonAction) {
                    "accept_all" -> handleAcceptAll()
                    "reject_all" -> handleRejectAll()
                    "accept_some" -> handleSavePreferences()
                    "save_preferences", "save", "custom" -> handleSavePreferences()
                    "navigate", "open_layer" -> element.targetConsentLayer?.let { navigateToLayer(it) }
                    "dismiss", "close", "noop" -> dismiss()
                    else -> dismiss() // Default to dismiss for unknown actions
                }
            }
        }
    }

    private fun createLinkView(element: ConsentLayerElement): LinearLayout {
        val container =
            LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
            }

        element.links?.forEach { linkItem ->
            val translation = getTranslationWithFallback(linkItem.translations)
            val linkView =
                TextView(requireContext()).apply {
                    val linkText = translation?.text ?: translation?.value ?: "Link"
                    text = linkText
                    textSize = 14f
                    setTextColor(getColor(com.datagrail.consent.R.color.consent_link))
                    paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG

                    // Accessibility
                    contentDescription = "$linkText - Opens link in browser"
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES

                    setOnClickListener {
                        translation?.url?.let { url ->
                            openUrl(url)
                        }
                    }
                }
            container.addView(linkView)
        }

        return container
    }

    private fun createCategoryView(element: ConsentLayerElement): LinearLayout {
        val container =
            LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
            }

        element.consentLayerCategories?.forEach { category ->
            val categoryView = createSingleCategoryToggle(category)
            container.addView(categoryView)

            container.addView(
                View(requireContext()).apply {
                    layoutParams =
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            12,
                        )
                },
            )
        }

        return container
    }

    private fun createSingleCategoryToggle(category: ConsentLayerCategory): LinearLayout {
        val container =
            LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
                setPadding(24, 24, 24, 24)
                setBackgroundColor(getColor(com.datagrail.consent.R.color.consent_surface))
            }

        val categoryTranslation = getTranslationWithFallback(category.translations)
        val label =
            TextView(requireContext()).apply {
                text = categoryTranslation?.name ?: "Category"
                textSize = 15f
                setTextColor(getColor(com.datagrail.consent.R.color.consent_text_primary))
                layoutParams =
                    LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f,
                    )
            }

        val categoryName = categoryTranslation?.name ?: "Category"

        val trailingView: View =
            if (category.alwaysOn) {
                TextView(requireContext()).apply {
                    text = categoryTranslation?.essentialLabel ?: "Always On"
                    textSize = 13f
                    setTextColor(getColor(com.datagrail.consent.R.color.consent_text_secondary))
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    // Match SwitchCompat's default min height so the row doesn't shrink for always-on categories.
                    minimumHeight = (48 * resources.displayMetrics.density).toInt()
                    layoutParams =
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        )
                    contentDescription = "$categoryName consent - Always enabled, required for functionality"
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                }
            } else {
                androidx.appcompat.widget.SwitchCompat(requireContext()).apply {
                    val checked = preferences?.cookieOptions?.find { it.gtmKey == category.gtmKey }?.isEnabled ?: false
                    isChecked = checked
                    layoutParams =
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        )

                    // Accessibility
                    val statusText = if (checked) "Enabled" else "Disabled"
                    contentDescription = "$categoryName consent - $statusText"
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES

                    setOnCheckedChangeListener { _, isChecked ->
                        val prefs = preferences ?: return@setOnCheckedChangeListener
                        val updatedOptions =
                            prefs.cookieOptions.map { consent ->
                                if (consent.gtmKey == category.gtmKey) {
                                    consent.copy(isEnabled = isChecked)
                                } else {
                                    consent
                                }
                            }
                        preferences = prefs.copy(cookieOptions = updatedOptions)
                    }
                }
            }

        container.addView(label)
        container.addView(trailingView)

        return container
    }

    private fun createTrackingDetailsView(element: ConsentLayerElement): LinearLayout {
        val container =
            LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
            }

        val title =
            TextView(requireContext()).apply {
                text = getTranslationText(element).ifEmpty { "Tracking Technologies" }
                textSize = 16f
                setTextColor(getColor(com.datagrail.consent.R.color.consent_text_primary))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
            }
        container.addView(title)

        container.addView(
            View(requireContext()).apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        16,
                    )
            },
        )

        element.consentLayerCategories?.forEach { category ->
            val detailView = createCategoryDetailView(category)
            container.addView(detailView)

            container.addView(
                View(requireContext()).apply {
                    layoutParams =
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            12,
                        )
                },
            )
        }

        return container
    }

    private fun createCategoryDetailView(category: ConsentLayerCategory): LinearLayout {
        val container =
            LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
                setPadding(16, 16, 16, 16)
                setBackgroundColor(getColor(com.datagrail.consent.R.color.consent_surface))
            }

        val translation = getTranslationWithFallback(category.translations)

        val nameView =
            TextView(requireContext()).apply {
                text = translation?.name ?: "Category"
                textSize = 14f
                setTextColor(getColor(com.datagrail.consent.R.color.consent_text_primary))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
            }
        container.addView(nameView)

        translation?.description?.let { desc ->
            val descView =
                TextView(requireContext()).apply {
                    renderRichText(desc, this)
                    textSize = 12f
                    setTextColor(getColor(com.datagrail.consent.R.color.consent_text_secondary))
                    layoutParams =
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).apply {
                            topMargin = 8
                        }
                }
            container.addView(descView)
        }

        return container
    }

    private fun handleAcceptAll() {
        val cfg =
            config ?: run {
                dismiss()
                return
            }

        // Build preferences with all categories enabled
        val allCategories = getAllCategoryKeys(cfg)
        val cookieOptions =
            allCategories.map { gtmKey ->
                CategoryConsent(gtmKey = gtmKey, isEnabled = true)
            }

        val updatedPrefs =
            ConsentPreferences(
                isCustomised = false,
                cookieOptions = cookieOptions,
            )
        dismissWithPreferences(updatedPrefs)
    }

    private fun handleRejectAll() {
        val cfg =
            config ?: run {
                dismiss()
                return
            }

        // Build preferences with only essential/always-on categories enabled
        val allCategories = getAllCategoryKeys(cfg)
        val essentialCategories = getEssentialCategoryKeys(cfg)

        val cookieOptions =
            allCategories.map { gtmKey ->
                CategoryConsent(
                    gtmKey = gtmKey,
                    isEnabled = essentialCategories.contains(gtmKey),
                )
            }

        val updatedPrefs =
            ConsentPreferences(
                isCustomised = false,
                cookieOptions = cookieOptions,
            )
        dismissWithPreferences(updatedPrefs)
    }

    private fun handleSavePreferences() {
        val prefs = preferences
        if (prefs != null) {
            val updatedPrefs = prefs.copy(isCustomised = true)
            dismissWithPreferences(updatedPrefs)
        } else {
            // If no preferences, build from config with current toggle states
            val cfg =
                config ?: run {
                    dismiss()
                    return
                }
            val allCategories = getAllCategoryKeys(cfg)
            val cookieOptions =
                allCategories.map { gtmKey ->
                    CategoryConsent(gtmKey = gtmKey, isEnabled = true) // Default to enabled
                }
            val updatedPrefs =
                ConsentPreferences(
                    isCustomised = true,
                    cookieOptions = cookieOptions,
                )
            dismissWithPreferences(updatedPrefs)
        }
    }

    /**
     * Get all category GTM keys from the config
     */
    private fun getAllCategoryKeys(cfg: ConsentConfig): List<String> {
        val categories = mutableSetOf<String>()

        // Add categories from initialCategories
        categories.addAll(cfg.initialCategories.initial)

        // Also scan consent layers for any additional categories
        for (layer in cfg.layout.consentLayers.values) {
            for (element in layer.elements) {
                element.consentLayerCategories?.forEach { category ->
                    categories.add(category.gtmKey)
                }
            }
        }

        return categories.toList()
    }

    /**
     * Get essential/always-on category GTM keys from the config
     */
    private fun getEssentialCategoryKeys(cfg: ConsentConfig): Set<String> {
        val essentialKeys = mutableSetOf<String>()

        // Scan consent layers for always-on categories
        for (layer in cfg.layout.consentLayers.values) {
            for (element in layer.elements) {
                element.consentLayerCategories?.forEach { category ->
                    if (category.alwaysOn) {
                        essentialKeys.add(category.gtmKey)
                    }
                }
            }
        }

        // Also check for categories with "essential" in the name as fallback
        for (layer in cfg.layout.consentLayers.values) {
            for (element in layer.elements) {
                element.consentLayerCategories?.forEach { category ->
                    if (category.gtmKey.contains("essential", ignoreCase = true)) {
                        essentialKeys.add(category.gtmKey)
                    }
                }
            }
        }

        return essentialKeys
    }

    private fun navigateToLayer(layerKey: String) {
        currentLayerKey = layerKey
        renderLayer(layerKey)
    }

    private fun openUrl(url: String) {
        try {
            val uri = android.net.Uri.parse(url)
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") {
                com.datagrail.consent.utils.ConsentLogger.e("Blocked URL with disallowed scheme: $scheme")
                return
            }
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Could not open URL", Toast.LENGTH_SHORT).show()
        }
    }

    private fun dismissWithPreferences(prefs: ConsentPreferences) {
        onDismissListener?.invoke(prefs)
        onDismissListener = null  // Clear listener to prevent double invocation in onDismiss()
        dismiss()
    }

    companion object {
        private const val CONTENT_ELEVATION = 16f
        private const val CLOSE_BUTTON_ELEVATION = 24f
        private const val CLOSE_BUTTON_SIZE_DP = 48f
        private const val CLOSE_BUTTON_MARGIN_DP = 16f
        private const val CONTENT_PADDING_DP = 16f

        fun newInstance(
            config: ConsentConfig,
            preferences: ConsentPreferences?,
            displayStyle: BannerDisplayStyle = BannerDisplayStyle.MODAL,
            textStyleConfig: BannerTextStyleConfig = BannerTextStyleConfig(),
            onDismiss: (ConsentPreferences?) -> Unit,
        ): BannerDialog {
            return BannerDialog().apply {
                this.config = config
                this.preferences = preferences
                this.currentLayerKey = config.layout.firstLayerId
                this.onDismissListener = onDismiss
                this.displayStyle = displayStyle
                this.textStyleConfig = textStyleConfig
            }
        }
    }
}
