package com.datagrail.consent.demo

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import com.datagrail.consent.DataGrailConsent
import com.datagrail.consent.ui.BannerDisplayStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    // UI Elements
    private lateinit var configUrlInput: EditText
    private lateinit var statusText: TextView
    private lateinit var initButton: Button
    private lateinit var showBannerModalButton: Button
    private lateinit var showBannerFullScreenButton: Button
    private lateinit var resetButton: Button
    private lateinit var policyCard: CardView
    private lateinit var policyContainer: LinearLayout
    private lateinit var preferencesCard: CardView
    private lateinit var preferencesContainer: LinearLayout
    private lateinit var debugLogText: TextView
    private lateinit var logScrollView: ScrollView
    private lateinit var clearLogButton: ImageButton
    private lateinit var copyLogButton: ImageButton

    // State
    private var isInitialized = false
    private val logEntries = mutableListOf<String>()

    // Default config URL
    private val defaultConfigUrl =
        "https://api.consentjs.datagrailstaging.com/consent/" +
            "ac46d8ad-a67a-431f-a5d5-9e3eb922dae7/b17d1e73-6d35-4ae3-9199-ff2e98d8926a/config.json"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupButtons()

        // Set default config URL
        configUrlInput.setText(defaultConfigUrl)

        log("INFO", "Demo app launched")
        checkStatus()
    }

    private fun initViews() {
        configUrlInput = findViewById(R.id.configUrlInput)
        statusText = findViewById(R.id.statusText)
        initButton = findViewById(R.id.initButton)
        showBannerModalButton = findViewById(R.id.showBannerModalButton)
        showBannerFullScreenButton = findViewById(R.id.showBannerFullScreenButton)
        resetButton = findViewById(R.id.resetButton)
        policyCard = findViewById(R.id.policyCard)
        policyContainer = findViewById(R.id.policyContainer)
        preferencesCard = findViewById(R.id.preferencesCard)
        preferencesContainer = findViewById(R.id.preferencesContainer)
        debugLogText = findViewById(R.id.debugLogText)
        logScrollView = findViewById(R.id.logScrollView)
        clearLogButton = findViewById(R.id.clearLogButton)
        copyLogButton = findViewById(R.id.copyLogButton)
    }

    private fun setupButtons() {
        initButton.setOnClickListener { initializeSdk() }
        showBannerModalButton.setOnClickListener { showBanner(BannerDisplayStyle.MODAL) }
        showBannerFullScreenButton.setOnClickListener { showBanner(BannerDisplayStyle.FULL_SCREEN) }
        resetButton.setOnClickListener { resetSdk() }
        clearLogButton.setOnClickListener { clearLogs() }
        copyLogButton.setOnClickListener { copyLogs() }
    }

    private fun initializeSdk() {
        log("INFO", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        log("INFO", "Initialize SDK requested")
        updateStatus("Initializing SDK...")

        val configUrl = configUrlInput.text.toString().trim()
        log("DEBUG", "Config URL: $configUrl")

        if (configUrl.isEmpty()) {
            log("ERROR", "Config URL is empty")
            updateStatus("ERROR: Empty config URL")
            return
        }

        log("NETWORK", "Calling DataGrailConsent.initialize()...")

        DataGrailConsent.getInstance().initialize(
            context = applicationContext,
            configUrl = configUrl,
        ) { result ->
            result.fold(
                onSuccess = {
                    log("SUCCESS", "Initialize returned SUCCESS")
                    isInitialized = true
                    enableButtons(true)
                    updateStatus("SDK initialized successfully")

                    // Log config info
                    DataGrailConsent.getInstance().getConfig()?.let { config ->
                        log("INFO", "Config loaded:")
                        log(if (config.showBanner) "INFO" else "WARNING", "  showBanner = ${config.showBanner}")
                        log("INFO", "  consentMode = ${config.consentMode}")
                        log("DEBUG", "  version = ${config.version}")
                        log("DEBUG", "  layers = ${config.layout.consentLayers.size}")

                        if (!config.showBanner) {
                            log("WARNING", "showBanner=false - shouldDisplayBanner() will return false")
                            log("INFO", "But banner buttons will still work for testing")
                        }
                    }

                    // Update policy UI
                    updatePolicyUI()

                    // Check state after initialization
                    try {
                        val shouldDisplay = DataGrailConsent.getInstance().shouldDisplayBanner()
                        val hasConsent = DataGrailConsent.getInstance().hasUserConsent()
                        log("DEBUG", "shouldDisplayBanner() = $shouldDisplay")
                        log("DEBUG", "hasUserConsent() = $hasConsent")

                        DataGrailConsent.getInstance().getUserPreferences()?.let { prefs ->
                            log("DEBUG", "getUserPreferences() returned ${prefs.cookieOptions.size} categories")
                            prefs.cookieOptions.forEach { opt ->
                                log("DEBUG", "  - ${opt.gtmKey}: ${if (opt.isEnabled) "enabled" else "disabled"}")
                            }
                        } ?: log("DEBUG", "getUserPreferences() returned null")
                    } catch (e: Exception) {
                        log("ERROR", "Error checking state: ${e.message}")
                    }

                    checkStatus()
                },
                onFailure = { error ->
                    log("ERROR", "Initialize returned FAILURE")
                    log("ERROR", "Error: ${error.message}")
                    isInitialized = false
                    enableButtons(false)

                    val errorMessage = error.message ?: error.toString()
                    when {
                        errorMessage.contains("parse", ignoreCase = true) -> {
                            log("WARNING", "This appears to be a JSON parsing error")
                            log("WARNING", "The config.json format may not match the expected model")
                        }
                        errorMessage.contains("network", ignoreCase = true) ||
                            errorMessage.contains("connect", ignoreCase = true) -> {
                            log("WARNING", "This appears to be a network error")
                            log("WARNING", "Check the URL and network connectivity")
                        }
                    }

                    updateStatus("Init failure: ${error.message}")
                },
            )
        }

        // Set up consent change listener
        DataGrailConsent.getInstance().onConsentChanged { preferences ->
            log("INFO", "Consent changed: ${preferences.cookieOptions.count { it.isEnabled }} categories enabled")
            runOnUiThread { updatePreferencesUI() }
        }
    }

    private fun showBanner(style: BannerDisplayStyle) {
        log("INFO", "Show banner requested (style: $style)")

        // Log config details before showing banner
        DataGrailConsent.getInstance().getConfig()?.let { config ->
            log("DEBUG", "Config firstLayerId: ${config.layout.firstLayerId}")
            log("DEBUG", "Config consentLayers keys: ${config.layout.consentLayers.keys}")
            config.layout.consentLayers[config.layout.firstLayerId]?.let { layer ->
                log("DEBUG", "First layer has ${layer.elements.size} elements")
                log("DEBUG", "First layer showCloseButton: ${layer.showCloseButton}")
                layer.elements.take(3).forEach { element ->
                    log("DEBUG", "  Element type: ${element.type}")
                }
            } ?: log("ERROR", "First layer NOT FOUND!")
        }

        log("NETWORK", "Calling showBanner(style: $style)...")

        DataGrailConsent.getInstance().showBanner(this, style) { preferences ->
            if (preferences != null) {
                log("SUCCESS", "Banner completed with preferences")
                log("DEBUG", "Categories: ${preferences.cookieOptions.size}")
                updateStatus("Preferences saved")
            } else {
                log("WARNING", "Banner dismissed without saving")
                updateStatus("Banner dismissed")
            }
            checkStatus()
        }
    }

    private fun checkStatus() {
        log("DEBUG", "Checking status...")

        try {
            val shouldDisplay = DataGrailConsent.getInstance().shouldDisplayBanner()
            val hasConsent = DataGrailConsent.getInstance().hasUserConsent()
            val preferences = DataGrailConsent.getInstance().getCategories()

            log("DEBUG", "shouldDisplayBanner = $shouldDisplay")
            log("DEBUG", "hasUserConsent = $hasConsent")
            log("DEBUG", "preferences = ${if (preferences != null) "present" else "nil"}")

            when {
                shouldDisplay -> updateStatus("Should display banner")
                preferences != null -> updateStatus("Consent: ${preferences.cookieOptions.size} categories")
                isInitialized -> updateStatus("Initialized (user hasn't consented yet or showBanner=false)")
                else -> updateStatus("Not initialized")
            }

            updatePreferencesUI()
        } catch (e: Exception) {
            log("ERROR", "Error checking status: ${e.message}")
            updateStatus("Not initialized")
        }
    }

    private fun resetSdk() {
        log("INFO", "Reset SDK requested")
        DataGrailConsent.getInstance().reset()
        log("SUCCESS", "SDK reset complete")
        isInitialized = false
        enableButtons(false)
        updateStatus("SDK reset - reinitialize")
        policyCard.visibility = View.GONE
        preferencesCard.visibility = View.GONE
    }

    private fun enableButtons(enabled: Boolean) {
        showBannerModalButton.isEnabled = enabled
        showBannerFullScreenButton.isEnabled = enabled
    }

    private fun updateStatus(message: String) {
        runOnUiThread {
            statusText.text = message
        }
    }

    private fun updatePolicyUI() {
        runOnUiThread {
            try {
                val config = DataGrailConsent.getInstance().getConfig()
                if (config != null) {
                    policyCard.visibility = View.VISIBLE
                    policyContainer.removeAllViews()

                    // Policy Name
                    val policyNameView =
                        LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            setPadding(0, 4, 0, 4)
                        }
                    val policyNameLabel =
                        TextView(this).apply {
                            text = "Policy Name:"
                            textSize = 12f
                            setTypeface(null, android.graphics.Typeface.BOLD)
                            layoutParams =
                                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        }
                    val policyNameValue =
                        TextView(this).apply {
                            text = config.consentPolicy.name
                            textSize = 12f
                        }
                    policyNameView.addView(policyNameLabel)
                    policyNameView.addView(policyNameValue)
                    policyContainer.addView(policyNameView)

                    // Default Policy
                    val defaultPolicyView =
                        LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            setPadding(0, 4, 0, 4)
                        }
                    val defaultPolicyLabel =
                        TextView(this).apply {
                            text = "Default Policy:"
                            textSize = 12f
                            setTypeface(null, android.graphics.Typeface.BOLD)
                            layoutParams =
                                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        }
                    val defaultPolicyValue =
                        TextView(this).apply {
                            text = if (config.consentPolicy.default) "Yes" else "No"
                            textSize = 12f
                            setTextColor(
                                if (config.consentPolicy.default) {
                                    android.graphics.Color.parseColor("#4CAF50")
                                } else {
                                    android.graphics.Color.parseColor("#FF9800")
                                },
                            )
                        }
                    defaultPolicyView.addView(defaultPolicyLabel)
                    defaultPolicyView.addView(defaultPolicyValue)
                    policyContainer.addView(defaultPolicyView)

                    // Show Banner
                    val showBannerView =
                        LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            setPadding(0, 4, 0, 4)
                        }
                    val showBannerLabel =
                        TextView(this).apply {
                            text = "Show Banner:"
                            textSize = 12f
                            setTypeface(null, android.graphics.Typeface.BOLD)
                            layoutParams =
                                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        }
                    val showBannerValue =
                        TextView(this).apply {
                            text = if (config.showBanner) "Yes" else "No"
                            textSize = 12f
                            setTextColor(
                                if (config.showBanner) {
                                    android.graphics.Color.parseColor("#4CAF50")
                                } else {
                                    android.graphics.Color.parseColor("#FF9800")
                                },
                            )
                        }
                    showBannerView.addView(showBannerLabel)
                    showBannerView.addView(showBannerValue)
                    policyContainer.addView(showBannerView)
                } else {
                    policyCard.visibility = View.GONE
                }
            } catch (e: Exception) {
                policyCard.visibility = View.GONE
            }
        }
    }

    private fun updatePreferencesUI() {
        runOnUiThread {
            try {
                val preferences = DataGrailConsent.getInstance().getCategories()
                if (preferences != null) {
                    preferencesCard.visibility = View.VISIBLE
                    preferencesContainer.removeAllViews()

                    // Add customised label
                    val customisedView =
                        TextView(this).apply {
                            text = "Customised: ${if (preferences.isCustomised) "Yes" else "No"}"
                            textSize = 12f
                        }
                    preferencesContainer.addView(customisedView)

                    // Add category count
                    val countView =
                        TextView(this).apply {
                            text = "Categories: ${preferences.cookieOptions.size}"
                            textSize = 12f
                        }
                    preferencesContainer.addView(countView)

                    // Add each category
                    preferences.cookieOptions.forEach { category ->
                        val categoryView =
                            LinearLayout(this).apply {
                                orientation = LinearLayout.HORIZONTAL
                                setPadding(0, 8, 0, 8)
                            }

                        val icon =
                            ImageView(this).apply {
                                setImageResource(
                                    if (category.isEnabled) {
                                        android.R.drawable.presence_online
                                    } else {
                                        android.R.drawable.presence_offline
                                    },
                                )
                                layoutParams =
                                    LinearLayout.LayoutParams(32, 32).apply {
                                        rightMargin = 8
                                    }
                            }

                        val label =
                            TextView(this).apply {
                                text = category.gtmKey
                                textSize = 11f
                                layoutParams =
                                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                            }

                        val statusLabel =
                            TextView(this).apply {
                                text = if (category.isEnabled) "Enabled" else "Disabled"
                                textSize = 10f
                                setTextColor(
                                    if (category.isEnabled) {
                                        android.graphics.Color.parseColor("#4CAF50")
                                    } else {
                                        android.graphics.Color.parseColor("#F44336")
                                    },
                                )
                            }

                        categoryView.addView(icon)
                        categoryView.addView(label)
                        categoryView.addView(statusLabel)
                        preferencesContainer.addView(categoryView)
                    }
                } else {
                    preferencesCard.visibility = View.GONE
                }
            } catch (e: Exception) {
                preferencesCard.visibility = View.GONE
            }
        }
    }

    // Logging functions
    private fun log(
        level: String,
        message: String,
    ) {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val emoji =
            when (level) {
                "INFO" -> "ℹ️"
                "SUCCESS" -> "✅"
                "WARNING" -> "⚠️"
                "ERROR" -> "❌"
                "NETWORK" -> "🌐"
                "DEBUG" -> "🔍"
                else -> "•"
            }
        val entry = "[$timestamp] $emoji $message"
        logEntries.add(entry)

        runOnUiThread {
            debugLogText.text = logEntries.joinToString("\n")
            // Scroll to bottom
            logScrollView.post { logScrollView.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun clearLogs() {
        logEntries.clear()
        debugLogText.text = "Logs cleared. Click Initialize SDK to start."
        log("INFO", "Logs cleared")
    }

    private fun copyLogs() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Debug Logs", logEntries.joinToString("\n"))
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
        log("INFO", "Logs copied to clipboard")
    }
}
