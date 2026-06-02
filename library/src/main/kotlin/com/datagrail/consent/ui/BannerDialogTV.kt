package com.datagrail.consent.ui

import android.app.Dialog
import android.content.DialogInterface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.text.Html
import android.text.method.LinkMovementMethod
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import com.datagrail.consent.models.CategoryConsent
import com.datagrail.consent.models.ConsentConfig
import com.datagrail.consent.models.ConsentLayerCategory
import com.datagrail.consent.models.ConsentLayerElement
import com.datagrail.consent.models.ConsentPreferences
import java.util.Locale

/**
 * DialogFragment for Android TV consent banner with D-pad navigation support
 * Full-screen, config-driven multi-layer element model, optimized for 10-foot viewing
 */
class BannerDialogTV : DialogFragment() {
    private var config: ConsentConfig? = null
    private var preferences: ConsentPreferences? = null
    private var currentLayerKey: String? = null
    private var layerStack: MutableList<String> = mutableListOf()
    private var onDismissListener: ((ConsentPreferences?) -> Unit)? = null

    // QR pairing fields
    private var qrPairingEnabled: Boolean = false
    private var publicBaseUrl: String? = null
    private var configUrl: String? = null
    private var userIdentifier: String? = null
    private var apiKey: String? = null
    private var pairingCoordinator: com.datagrail.consent.network.PairingCoordinator? = null

    private lateinit var scrollView: ScrollView
    private lateinit var contentLayout: LinearLayout

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return Dialog(requireContext(), android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
            window?.apply {
                setBackgroundDrawableResource(android.R.color.transparent)
                setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                )
            }
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    handleBackKey()
                    true
                } else {
                    false
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val rootView = createRootView()
        currentLayerKey?.let { layerKey ->
            layerStack.add(layerKey)
            renderLayer(layerKey)
        }

        // Start QR pairing if enabled
        if (qrPairingEnabled) {
            startQRPairing()
        }

        return rootView
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Stop polling when dialog is destroyed
        pairingCoordinator?.stop()
        pairingCoordinator = null
    }

    private fun createRootView(): ViewGroup {
        val outerContainer =
            LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams =
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                setBackgroundColor(ContextCompat.getColor(context, com.datagrail.consent.R.color.consent_background))
                setPadding(
                    (48 * resources.displayMetrics.density).toInt(),
                    (64 * resources.displayMetrics.density).toInt(),
                    (48 * resources.displayMetrics.density).toInt(),
                    (48 * resources.displayMetrics.density).toInt(),
                )
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
        outerContainer.addView(scrollView)

        return outerContainer
    }

    private fun handleBackKey() {
        if (layerStack.size > 1) {
            // Navigate back to previous layer
            layerStack.removeAt(layerStack.size - 1)
            val previousLayerKey = layerStack.last()
            currentLayerKey = previousLayerKey
            renderLayer(previousLayerKey)
        } else {
            // Dismiss on first layer
            dismiss()
        }
    }

    private fun renderLayer(layerKey: String) {
        val cfg = config ?: return
        val layer = cfg.layout.consentLayers[layerKey] ?: return

        contentLayout.removeAllViews()

        var firstFocusableView: View? = null

        layer.elements.sortedBy { it.order }.forEach { element ->
            val elementView = createElementView(element)
            contentLayout.addView(elementView)

            // Track first focusable view
            if (firstFocusableView == null && elementView.isFocusable) {
                firstFocusableView = elementView
            } else if (firstFocusableView == null && elementView is ViewGroup) {
                // Check children for focusable views
                val focusableChild = findFirstFocusableChild(elementView)
                if (focusableChild != null) {
                    firstFocusableView = focusableChild
                }
            }

            // Add spacing between elements
            contentLayout.addView(
                View(requireContext()).apply {
                    layoutParams =
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            (24 * resources.displayMetrics.density).toInt(),
                        )
                },
            )
        }

        // Request focus on first focusable element
        firstFocusableView?.post { firstFocusableView?.requestFocus() }
    }

    private fun findFirstFocusableChild(viewGroup: ViewGroup): View? {
        for (i in 0 until viewGroup.childCount) {
            val child = viewGroup.getChildAt(i)
            if (child.isFocusable) {
                return child
            } else if (child is ViewGroup) {
                val focusableDescendant = findFirstFocusableChild(child)
                if (focusableDescendant != null) {
                    return focusableDescendant
                }
            }
        }
        return null
    }

    private fun renderRichText(
        text: String,
        textView: TextView,
    ) {
        if (text.containsHtmlTags()) {
            val spanned =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    Html.fromHtml(text, Html.FROM_HTML_MODE_COMPACT)
                } else {
                    @Suppress("DEPRECATION")
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

    private fun String.containsHtmlTags(): Boolean {
        return contains(Regex("<[a-zA-Z][^>]*>"))
    }

    private fun getColor(
        @androidx.annotation.ColorRes colorRes: Int,
    ): Int {
        return ContextCompat.getColor(requireContext(), colorRes)
    }

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

    private fun getPreferredLocale(): String {
        val deviceLocale = Locale.getDefault().language
        return deviceLocale.ifEmpty { "en" }
    }

    private fun <T> getTranslationWithFallback(translations: Map<String, T>?): T? {
        if (translations.isNullOrEmpty()) return null
        val preferredLocale = getPreferredLocale()
        return translations[preferredLocale]
            ?: translations["en"]
            ?: translations.values.firstOrNull()
    }

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
            "browser_signal_notice", "language_picker" -> {
                View(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 0)
                    visibility = View.GONE
                }
            }
            else ->
                TextView(requireContext()).apply {
                    text = "Unknown type: ${element.type}"
                    setTextColor(getColor(com.datagrail.consent.R.color.consent_text_secondary))
                    textSize = 14f
                }
        }
    }

    private fun createTextView(element: ConsentLayerElement): TextView {
        val textContent = getTranslationText(element)
        return TextView(requireContext()).apply {
            renderRichText(textContent, this)
            textSize = 18f
            setTextColor(getColor(com.datagrail.consent.R.color.consent_text_primary))
            layoutParams =
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
        }
    }

    private fun createButtonView(element: ConsentLayerElement): Button {
        return Button(requireContext()).apply {
            val buttonText = getTranslationText(element).ifEmpty { "Button" }
            text = buttonText
            textSize = 18f
            isFocusable = true
            isFocusableInTouchMode = true
            minHeight = (56 * resources.displayMetrics.density).toInt()
            setTextColor(getColor(com.datagrail.consent.R.color.consent_button_text))
            layoutParams =
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    setPadding(0, 16, 0, 16)
                }

            background = createFocusableButtonDrawable()

            setOnClickListener {
                when (element.buttonAction) {
                    "accept_all" -> handleAcceptAll()
                    "reject_all" -> handleRejectAll()
                    "accept_some" -> handleSavePreferences()
                    "save_preferences", "save", "custom" -> handleSavePreferences()
                    "navigate", "open_layer" -> element.targetConsentLayer?.let { navigateToLayer(it) }
                    "dismiss", "close", "noop" -> dismiss()
                    else -> dismiss()
                }
            }
        }
    }

    private fun createFocusableButtonDrawable(): StateListDrawable {
        val focusedDrawable =
            GradientDrawable().apply {
                setColor(getColor(com.datagrail.consent.R.color.consent_button_background))
                setStroke(
                    (4 * resources.displayMetrics.density).toInt(),
                    getColor(com.datagrail.consent.R.color.consent_link),
                )
                cornerRadius = 8f * resources.displayMetrics.density
            }

        val normalDrawable =
            GradientDrawable().apply {
                setColor(getColor(com.datagrail.consent.R.color.consent_button_background))
                setStroke(
                    (2 * resources.displayMetrics.density).toInt(),
                    getColor(com.datagrail.consent.R.color.consent_text_secondary),
                )
                cornerRadius = 8f * resources.displayMetrics.density
            }

        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focusedDrawable)
            addState(intArrayOf(), normalDrawable)
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
            val linkButton =
                Button(requireContext()).apply {
                    val linkText = translation?.text ?: translation?.value ?: "Link"
                    text = linkText
                    textSize = 16f
                    isFocusable = true
                    isFocusableInTouchMode = true
                    setTextColor(getColor(com.datagrail.consent.R.color.consent_link))
                    background = createFocusableButtonDrawable()
                    layoutParams =
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        )

                    setOnClickListener {
                        translation?.url?.let { url ->
                            openUrl(url)
                        }
                    }
                }
            container.addView(linkButton)
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
                            (12 * resources.displayMetrics.density).toInt(),
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
                val padding = (24 * resources.displayMetrics.density).toInt()
                setPadding(padding, padding, padding, padding)
                setBackgroundColor(getColor(com.datagrail.consent.R.color.consent_surface))
                isFocusable = true
                isFocusableInTouchMode = true

                background = createFocusableRowDrawable()
            }

        val categoryTranslation = getTranslationWithFallback(category.translations)
        val label =
            TextView(requireContext()).apply {
                text = categoryTranslation?.name ?: "Category"
                textSize = 16f
                setTextColor(getColor(com.datagrail.consent.R.color.consent_text_primary))
                layoutParams =
                    LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f,
                    )
            }

        val toggle =
            SwitchCompat(requireContext()).apply {
                val checked = preferences?.cookieOptions?.find { it.gtmKey == category.gtmKey }?.isEnabled ?: false
                isChecked = checked
                isEnabled = !category.alwaysOn
                isFocusable = false
                layoutParams =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )

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

        // Handle D-pad left/right to toggle
        container.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_UP &&
                (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) &&
                !category.alwaysOn
            ) {
                toggle.isChecked = !toggle.isChecked
                true
            } else {
                false
            }
        }

        return container
    }

    private fun createFocusableRowDrawable(): StateListDrawable {
        val focusedDrawable =
            GradientDrawable().apply {
                setColor(getColor(com.datagrail.consent.R.color.consent_surface))
                setStroke(
                    (4 * resources.displayMetrics.density).toInt(),
                    getColor(com.datagrail.consent.R.color.consent_link),
                )
            }

        val normalDrawable =
            GradientDrawable().apply {
                setColor(getColor(com.datagrail.consent.R.color.consent_surface))
            }

        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focusedDrawable)
            addState(intArrayOf(), normalDrawable)
        }
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
                textSize = 20f
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
                        (16 * resources.displayMetrics.density).toInt(),
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
                            (12 * resources.displayMetrics.density).toInt(),
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
                val padding = (16 * resources.displayMetrics.density).toInt()
                setPadding(padding, padding, padding, padding)
                setBackgroundColor(getColor(com.datagrail.consent.R.color.consent_surface))
            }

        val translation = getTranslationWithFallback(category.translations)

        val nameView =
            TextView(requireContext()).apply {
                text = translation?.name ?: "Category"
                textSize = 16f
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
                    textSize = 14f
                    setTextColor(getColor(com.datagrail.consent.R.color.consent_text_secondary))
                    layoutParams =
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).apply {
                            val margin = (8 * resources.displayMetrics.density).toInt()
                            topMargin = margin
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
            val cfg =
                config ?: run {
                    dismiss()
                    return
                }
            val allCategories = getAllCategoryKeys(cfg)
            val cookieOptions =
                allCategories.map { gtmKey ->
                    CategoryConsent(gtmKey = gtmKey, isEnabled = true)
                }
            val updatedPrefs =
                ConsentPreferences(
                    isCustomised = true,
                    cookieOptions = cookieOptions,
                )
            dismissWithPreferences(updatedPrefs)
        }
    }

    private fun getAllCategoryKeys(cfg: ConsentConfig): List<String> {
        val categories = mutableSetOf<String>()
        categories.addAll(cfg.initialCategories.initial)

        for (layer in cfg.layout.consentLayers.values) {
            for (element in layer.elements) {
                element.consentLayerCategories?.forEach { category ->
                    categories.add(category.gtmKey)
                }
            }
        }

        return categories.toList()
    }

    private fun getEssentialCategoryKeys(cfg: ConsentConfig): Set<String> {
        val essentialKeys = mutableSetOf<String>()

        for (layer in cfg.layout.consentLayers.values) {
            for (element in layer.elements) {
                element.consentLayerCategories?.forEach { category ->
                    if (category.alwaysOn) {
                        essentialKeys.add(category.gtmKey)
                    }
                }
            }
        }

        return essentialKeys
    }

    private fun navigateToLayer(layerKey: String) {
        layerStack.add(layerKey)
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
            android.widget.Toast.makeText(requireContext(), "Could not open URL", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun startQRPairing() {
        val cfg = config ?: return
        val context = requireContext()

        // Generate user hash
        val deviceId = userIdentifier ?: com.datagrail.consent.utils.UserHashGenerator.getDefaultDeviceId(context)
        val userHash =
            com.datagrail.consent.utils.UserHashGenerator.generateUserHash(
                customerId = cfg.dgCustomerId,
                consentProjectId = cfg.privacyDomain,
                deviceId = deviceId,
            )

        // Extract API base URL from config's privacyDomain
        val apiBaseUrl = "https://${cfg.privacyDomain}"

        // Create PairingService
        val networkClient = com.datagrail.consent.network.NetworkClient()
        val pairingService =
            com.datagrail.consent.network.PairingService(
                networkClient = networkClient,
                apiBaseUrl = apiBaseUrl,
                apiKey = apiKey,
            )

        // Generate QR URL
        val qrUrl =
            pairingService.qrUrl(
                publicBaseUrl = publicBaseUrl ?: "",
                customerId = cfg.dgCustomerId,
                userHash = userHash,
                configUrl = configUrl ?: "",
            )

        // Render QR code at the top of the banner
        renderQRCode(qrUrl)

        // Start polling coordinator
        pairingCoordinator =
            com.datagrail.consent.network.PairingCoordinator(
                pairingService = pairingService,
                customerId = cfg.dgCustomerId,
                userHash = userHash,
                onPreferencesFound = { remotePreferences ->
                    // Remote preferences found - adopt and dismiss
                    dismissWithPreferences(remotePreferences)
                },
                onTimeout = {
                    // Timeout - just dismiss (fallback would be to show D-pad banner)
                    dismiss()
                },
            )

        pairingCoordinator?.start()
    }

    private fun renderQRCode(url: String) {
        try {
            val qrBitmap = com.datagrail.consent.utils.QrCodeGenerator.generateQrCode(url, 512)

            val qrImageView =
                android.widget.ImageView(requireContext()).apply {
                    setImageBitmap(qrBitmap)
                    layoutParams =
                        LinearLayout.LayoutParams(
                            (256 * resources.displayMetrics.density).toInt(),
                            (256 * resources.displayMetrics.density).toInt(),
                        ).apply {
                            gravity = android.view.Gravity.CENTER_HORIZONTAL
                            bottomMargin = (32 * resources.displayMetrics.density).toInt()
                        }
                }

            // Add instruction text
            val instructionText =
                TextView(requireContext()).apply {
                    text = "Scan this QR code with your phone to manage consent preferences"
                    textSize = 18f
                    setTextColor(getColor(com.datagrail.consent.R.color.consent_text_primary))
                    gravity = android.view.Gravity.CENTER
                    layoutParams =
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).apply {
                            bottomMargin = (24 * resources.displayMetrics.density).toInt()
                        }
                }

            // Insert at the top of content layout
            contentLayout.addView(instructionText, 0)
            contentLayout.addView(qrImageView, 1)
        } catch (e: Exception) {
            com.datagrail.consent.utils.ConsentLogger.e("Failed to render QR code: ${e.message}")
        }
    }

    private fun dismissWithPreferences(prefs: ConsentPreferences) {
        onDismissListener?.invoke(prefs)
        onDismissListener = null
        dismiss()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        onDismissListener?.invoke(null)
    }

    companion object {
        fun newInstance(
            config: ConsentConfig,
            preferences: ConsentPreferences?,
            onDismiss: (ConsentPreferences?) -> Unit,
        ): BannerDialogTV {
            return BannerDialogTV().apply {
                this.config = config
                this.preferences = preferences
                this.currentLayerKey = config.layout.firstLayerId
                this.onDismissListener = onDismiss
                this.qrPairingEnabled = false
            }
        }

        fun newInstanceWithQRPairing(
            config: ConsentConfig,
            preferences: ConsentPreferences?,
            publicBaseUrl: String,
            configUrl: String,
            userIdentifier: String?,
            apiKey: String?,
            onDismiss: (ConsentPreferences?) -> Unit,
        ): BannerDialogTV {
            return BannerDialogTV().apply {
                this.config = config
                this.preferences = preferences
                this.currentLayerKey = config.layout.firstLayerId
                this.onDismissListener = onDismiss
                this.qrPairingEnabled = true
                this.publicBaseUrl = publicBaseUrl
                this.configUrl = configUrl
                this.userIdentifier = userIdentifier
                this.apiKey = apiKey
            }
        }
    }
}
