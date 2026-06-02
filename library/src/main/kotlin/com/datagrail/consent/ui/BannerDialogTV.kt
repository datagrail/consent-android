package com.datagrail.consent.ui

import android.app.Dialog
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import com.datagrail.consent.models.ConsentConfig
import com.datagrail.consent.models.ConsentPreferences

/**
 * DialogFragment for Android TV consent banner with D-pad navigation support
 * This is a stub implementation for feat-010; full D-pad UI will be added in feat-011
 */
class BannerDialogTV : DialogFragment() {
    private var config: ConsentConfig? = null
    private var preferences: ConsentPreferences? = null
    private var currentLayerKey: String? = null
    private var onDismissListener: ((ConsentPreferences?) -> Unit)? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return Dialog(requireContext(), android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
            window?.apply {
                setBackgroundDrawableResource(android.R.color.transparent)
                setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                )
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val rootView = createRootView()
        currentLayerKey?.let { renderLayer(it) }
        return rootView
    }

    private fun createRootView(): ViewGroup {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams =
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            setBackgroundColor(ContextCompat.getColor(context, com.datagrail.consent.R.color.consent_background))
            setPadding(48, 64, 48, 48)
            gravity = Gravity.CENTER
        }
    }

    private fun renderLayer(layerKey: String) {
        val cfg = config ?: return
        val layer = cfg.layout.consentLayers[layerKey] ?: return
        val rootLayout = view as? LinearLayout ?: return

        rootLayout.removeAllViews()

        // Simple text element showing first layer text
        val firstTextElement = layer.elements.firstOrNull { element ->
            element.type.contains("Text", ignoreCase = true)
        }

        if (firstTextElement != null) {
            val translation = firstTextElement.translations?.get("en")
            val text = translation?.value ?: translation?.text ?: "Consent Required"

            val textView =
                TextView(requireContext()).apply {
                    this.text = text
                    textSize = 18f
                    setTextColor(ContextCompat.getColor(context, com.datagrail.consent.R.color.consent_text_primary))
                    gravity = Gravity.CENTER
                    layoutParams =
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).apply {
                            bottomMargin = 32
                        }
                }
            rootLayout.addView(textView)
        }

        // Accept All button
        val acceptButton =
            Button(requireContext()).apply {
                text = "Accept All"
                textSize = 16f
                isFocusable = true
                isFocusableInTouchMode = true
                setBackgroundColor(ContextCompat.getColor(context, com.datagrail.consent.R.color.consent_button_background))
                setTextColor(ContextCompat.getColor(context, com.datagrail.consent.R.color.consent_button_text))
                layoutParams =
                    LinearLayout.LayoutParams(
                        (300 * resources.displayMetrics.density).toInt(),
                        (56 * resources.displayMetrics.density).toInt(),
                    ).apply {
                        bottomMargin = 16
                    }
                setOnClickListener { handleAcceptAll() }
            }
        rootLayout.addView(acceptButton)

        // Reject All button
        val rejectButton =
            Button(requireContext()).apply {
                text = "Reject All"
                textSize = 16f
                isFocusable = true
                isFocusableInTouchMode = true
                setBackgroundColor(ContextCompat.getColor(context, com.datagrail.consent.R.color.consent_button_background))
                setTextColor(ContextCompat.getColor(context, com.datagrail.consent.R.color.consent_button_text))
                layoutParams =
                    LinearLayout.LayoutParams(
                        (300 * resources.displayMetrics.density).toInt(),
                        (56 * resources.displayMetrics.density).toInt(),
                    )
                setOnClickListener { handleRejectAll() }
            }
        rootLayout.addView(rejectButton)

        // Request focus on first button
        acceptButton.post { acceptButton.requestFocus() }
    }

    private fun handleAcceptAll() {
        val cfg = config ?: run {
            dismiss()
            return
        }

        // Build preferences with all categories enabled
        val allCategories = getAllCategoryKeys(cfg)
        val cookieOptions =
            allCategories.map { gtmKey ->
                com.datagrail.consent.models.CategoryConsent(gtmKey = gtmKey, isEnabled = true)
            }

        val updatedPrefs =
            ConsentPreferences(
                isCustomised = false,
                cookieOptions = cookieOptions,
            )
        dismissWithPreferences(updatedPrefs)
    }

    private fun handleRejectAll() {
        val cfg = config ?: run {
            dismiss()
            return
        }

        // Build preferences with only essential/always-on categories enabled
        val allCategories = getAllCategoryKeys(cfg)
        val essentialCategories = getEssentialCategoryKeys(cfg)

        val cookieOptions =
            allCategories.map { gtmKey ->
                com.datagrail.consent.models.CategoryConsent(
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

    private fun dismissWithPreferences(prefs: ConsentPreferences) {
        onDismissListener?.invoke(prefs)
        onDismissListener = null
        dismiss()
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
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
            }
        }
    }
}
