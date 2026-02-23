package com.datagrail.consent.ui

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.fragment.app.DialogFragment
import com.datagrail.consent.models.CategoryConsent
import com.datagrail.consent.models.ConsentConfig
import com.datagrail.consent.models.ConsentLayerCategory
import com.datagrail.consent.models.ConsentLayerElement
import com.datagrail.consent.models.ConsentPreferences
import java.util.Locale

/**
 * DialogFragment that displays the consent banner with configurable layers and elements
 */
class BannerDialog : DialogFragment() {
    private var config: ConsentConfig? = null
    private var preferences: ConsentPreferences? = null
    private var currentLayerKey: String? = null
    private var onDismissListener: ((ConsentPreferences?) -> Unit)? = null
    private var displayStyle: BannerDisplayStyle = BannerDisplayStyle.MODAL

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
            BannerDisplayStyle.MODAL -> android.R.style.Theme_DeviceDefault_Light_Dialog_NoActionBar
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val rootView = createRootView()

        // Add close button if needed
        if (shouldShowCloseButton()) {
            closeButton = createCloseButton()
            (rootView as FrameLayout).addView(closeButton)
        }

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

        val contentContainer =
            LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                when (displayStyle) {
                    BannerDisplayStyle.FULL_SCREEN -> {
                        layoutParams =
                            FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.MATCH_PARENT,
                            )
                        setBackgroundColor(Color.WHITE)
                        setPadding(32, 64, 32, 32)
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

                        // Rounded corners with shadow
                        val shape =
                            GradientDrawable().apply {
                                setColor(Color.WHITE)
                                cornerRadius = 24f
                            }
                        background = shape
                        elevation = 16f
                        setPadding(32, 48, 32, 32)
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
        outerFrame.addView(contentContainer)

        return outerFrame
    }

    private fun shouldShowCloseButton(): Boolean {
        val cfg = config ?: return true
        val layer = cfg.layout.consentLayers[currentLayerKey] ?: return true

        return when (displayStyle) {
            BannerDisplayStyle.MODAL -> true // Modal always shows close button
            BannerDisplayStyle.FULL_SCREEN -> layer.showCloseButton
        }
    }

    private fun createCloseButton(): ImageButton {
        return ImageButton(requireContext()).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setBackgroundResource(android.R.color.transparent)
            contentDescription = "Close"

            val size = (48 * resources.displayMetrics.density).toInt()
            layoutParams =
                FrameLayout.LayoutParams(size, size).apply {
                    gravity = Gravity.TOP or Gravity.END
                    topMargin = (16 * resources.displayMetrics.density).toInt()
                    rightMargin = (16 * resources.displayMetrics.density).toInt()
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
    }

    /**
     * Normalizes element type from config format to standard format.
     * The config may use types like "ConsentLayerTextElement" which should map to "text".
     */
    private fun normalizeElementType(type: String): String {
        return when {
            type.contains("BrowserSignalNotice", ignoreCase = true) -> "browser_signal_notice"
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
            else ->
                TextView(requireContext()).apply {
                    text = "Unknown type: ${element.type}"
                    setTextColor(Color.GRAY)
                    textSize = 12f
                }
        }
    }

    private fun createTextView(element: ConsentLayerElement): TextView {
        return TextView(requireContext()).apply {
            val textContent = getTranslationText(element)
            text = textContent
            textSize = 14f
            setTextColor(Color.BLACK)
            layoutParams =
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            // Accessibility
            contentDescription = textContent
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
    }

    private fun createButtonView(element: ConsentLayerElement): Button {
        return Button(requireContext()).apply {
            val buttonText = getTranslationText(element).ifEmpty { "Button" }
            val action = element.buttonAction ?: ""
            text = buttonText
            textSize = 16f
            setBackgroundColor(Color.parseColor("#2196F3"))
            setTextColor(Color.WHITE)
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
                    setTextColor(Color.parseColor("#2196F3"))
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
                setBackgroundColor(Color.parseColor("#F5F5F5"))
            }

        val categoryTranslation = getTranslationWithFallback(category.translations)
        val label =
            TextView(requireContext()).apply {
                text = categoryTranslation?.name ?: "Category"
                textSize = 15f
                layoutParams =
                    LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f,
                    )
            }

        val toggle =
            androidx.appcompat.widget.SwitchCompat(requireContext()).apply {
                val categoryName = categoryTranslation?.name ?: "Category"
                val checked = preferences?.cookieOptions?.find { it.gtmKey == category.gtmKey }?.isEnabled ?: false
                isChecked = checked
                isEnabled = !category.alwaysOn
                layoutParams =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )

                // Accessibility
                val statusText = if (checked) "Enabled" else "Disabled"
                contentDescription = "$categoryName consent - $statusText"
                if (category.alwaysOn) {
                    contentDescription = "$contentDescription - Always enabled, required for functionality"
                }
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

        container.addView(label)
        container.addView(toggle)

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
                setBackgroundColor(Color.parseColor("#F5F5F5"))
            }

        val translation = getTranslationWithFallback(category.translations)

        val nameView =
            TextView(requireContext()).apply {
                text = translation?.name ?: "Category"
                textSize = 14f
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
                    text = desc
                    textSize = 12f
                    setTextColor(Color.parseColor("#666666"))
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

    private fun createBrowserSignalNoticeView(element: ConsentLayerElement): LinearLayout {
        val container =
            LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
                setPadding(16, 16, 16, 16)
                setBackgroundColor(Color.parseColor("#FFF3E0")) // Light orange background
            }

        // Get translation with locale fallback
        val translation = getTranslationWithFallback(element.browserSignalNoticeTranslations)
        val noticeText = translation?.value ?: "Your browser's opt-out signal is being honored."

        // Icon
        if (element.showIcon == true) {
            val iconView =
                TextView(requireContext()).apply {
                    text = "\u26A0\uFE0F" // Warning emoji
                    textSize = 16f
                    layoutParams =
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).apply {
                            rightMargin = 12
                        }
                }
            container.addView(iconView)
        }

        // Notice text
        val textView =
            TextView(requireContext()).apply {
                text = noticeText
                textSize = 14f
                setTextColor(Color.parseColor("#E65100")) // Dark orange text
                layoutParams =
                    LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f,
                    )
                // Accessibility
                contentDescription = noticeText
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            }
        container.addView(textView)

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
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
            intent.data = android.net.Uri.parse(url)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Could not open URL", Toast.LENGTH_SHORT).show()
        }
    }

    private fun dismissWithPreferences(prefs: ConsentPreferences) {
        onDismissListener?.invoke(prefs)
        dismiss()
    }

    companion object {
        fun newInstance(
            config: ConsentConfig,
            preferences: ConsentPreferences?,
            displayStyle: BannerDisplayStyle = BannerDisplayStyle.MODAL,
            onDismiss: (ConsentPreferences?) -> Unit,
        ): BannerDialog {
            return BannerDialog().apply {
                this.config = config
                this.preferences = preferences
                this.currentLayerKey = config.layout.firstLayerId
                this.onDismissListener = onDismiss
                this.displayStyle = displayStyle
            }
        }
    }
}
