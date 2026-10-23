package com.datagrail.consent.demo

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.datagrail.consent.DataGrailConsent
import com.datagrail.consent.utils.LogLevel

/**
 * Android TV demo: initializes the SDK against the local Universal Consent test
 * server, shows the D-pad banner with QR pairing, and renders a live consent
 * status panel that updates when the phone writes consent (TV adopts via polling).
 *
 * This talks to the LOCAL test server. Both URLs must be reachable:
 *  - configUrl   : the consent config JSON (SDK requires HTTPS) — use an https tunnel
 *  - publicBaseUrl: the base the PHONE will open from the QR (LAN IP or tunnel)
 * See the README "Android TV" section for the LAN / cloudflared run book.
 *
 * Built as a fully programmatic, D-pad-navigable screen (no XML) so it runs on a
 * bare Android TV image without extra resources.
 */
class TvDemoActivity : AppCompatActivity() {
    private lateinit var configUrlInput: EditText
    private lateinit var apiKeyInput: EditText
    private lateinit var publicBaseUrlInput: EditText
    private lateinit var apiBaseUrlInput: EditText
    private lateinit var initButton: Button
    private lateinit var showBannerButton: Button
    private lateinit var resetButton: Button
    private lateinit var statusText: TextView
    private lateinit var categoryStatusText: TextView

    private var isInitialized = false

    // Endpoints come from BuildConfig (set via the dgTvHost / dgTvPublicHost / dgTvApiKey
    // gradle properties — see demo/build.gradle.kts). Defaults are generic placeholders;
    // override them locally rather than committing your host. Two distinct configs,
    // mirroring the iOS demo:
    //  - SDK config (demo-config.json): full SDK schema incl. privacyDomain + layout —
    //    this is what initialize() consumes.
    //  - phone QR config (sample-config.json): just category toggles for the phone page.
    // TV_HOST is what the TV uses for config + polling (on an emulator, include the
    // adb-reverse port); TV_PUBLIC_HOST is what the QR encodes for the phone (usually :443).
    private val host = BuildConfig.TV_HOST.trimEnd('/')
    private val publicHost = BuildConfig.TV_PUBLIC_HOST.trimEnd('/')
    private val defaultConfigUrl = "$host/tv/demo-config.json"
    private val defaultPhoneConfigUrl = "$publicHost/tv/sample-config.json"
    private val defaultPublicBaseUrl = publicHost
    private val defaultApiBaseUrl = host
    private val defaultApiKey = BuildConfig.TV_API_KEY

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DataGrailConsent.setLogLevel(LogLevel.DEBUG)
        setContentView(buildUi())

        DataGrailConsent.getInstance().onConsentChanged { _ ->
            runOnUiThread {
                logLine("onConsentChanged fired — TV adopted remote write")
                refreshCategoryStatus()
                renderStatus("Consent changed (adopted from phone).")
            }
        }

        initButton.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        // Keep the category panel live whenever we return to this screen (e.g. after
        // the banner dismisses on a successful pairing), mirroring the tvOS demo.
        refreshCategoryStatus()
    }

    private fun buildUi(): View {
        val pad = (24 * resources.displayMetrics.density).toInt()
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
                setBackgroundColor(0xFF101418.toInt())
            }

        root.addView(
            TextView(this).apply {
                text = "DataGrail Consent — Android TV Demo"
                textSize = 26f
                setTextColor(0xFFFFFFFF.toInt())
            },
        )

        // Always-visible category status panel (mirrors the tvOS demo). Shows the
        // current consent state and updates after init / pairing / reset.
        root.addView(
            TextView(this).apply {
                text = "Current Consent"
                textSize = 18f
                setTextColor(0xFFFFFFFF.toInt())
                setPadding(0, pad, 0, 8)
            },
        )
        categoryStatusText =
            TextView(this).apply {
                textSize = 16f
                setTextColor(0xFFB0BEC5.toInt())
                setPadding(24, 16, 24, 16)
                setBackgroundColor(0xFF1A2128.toInt())
                text = "Not initialized."
            }
        root.addView(categoryStatusText)

        // Action buttons FIRST so D-pad navigation flows cleanly through them on a
        // TV remote (the config fields below are pre-filled and rarely edited on TV;
        // putting EditTexts between the buttons makes the remote land on a text field
        // and pop the soft keyboard).
        initButton =
            tvButton(root, "1) Initialize") {
                doInitialize()
            }
        showBannerButton =
            tvButton(root, "2) Show TV Banner + QR") {
                doShowBanner()
            }
        resetButton =
            tvButton(root, "Reset consent") {
                DataGrailConsent.getInstance().reset()
                refreshCategoryStatus()
                renderStatus("Consent reset.")
            }

        configUrlInput = field(root, "SDK config URL (https, full schema)", defaultConfigUrl)
        publicBaseUrlInput = field(root, "Public base URL (phone-reachable, for QR)", defaultPublicBaseUrl)
        apiBaseUrlInput = field(root, "API base URL (where the TV polls)", defaultApiBaseUrl)
        apiKeyInput = field(root, "API key (reads)", defaultApiKey)

        statusText =
            TextView(this).apply {
                textSize = 16f
                setTextColor(0xFFB0BEC5.toInt())
                setPadding(0, pad, 0, 0)
                text = "Not initialized."
            }
        val scroll =
            ScrollView(this).apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1f,
                    )
                addView(statusText)
            }
        root.addView(scroll)
        return root
    }

    private fun field(
        parent: LinearLayout,
        label: String,
        default: String,
    ): EditText {
        parent.addView(
            TextView(this).apply {
                text = label
                textSize = 13f
                setTextColor(0xFF78909C.toInt())
                setPadding(0, 16, 0, 0)
            },
        )
        val input =
            EditText(this).apply {
                setText(default)
                textSize = 15f
                setTextColor(0xFFFFFFFF.toInt())
                isFocusable = true
                isFocusableInTouchMode = true
            }
        parent.addView(input)
        return input
    }

    private fun tvButton(
        parent: LinearLayout,
        label: String,
        onClick: () -> Unit,
    ): Button {
        val button =
            Button(this).apply {
                text = label
                textSize = 18f
                isFocusable = true
                isFocusableInTouchMode = true
                minHeight = (56 * resources.displayMetrics.density).toInt()
                gravity = Gravity.CENTER
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = 16 }
                setOnClickListener { onClick() }
            }
        parent.addView(button)
        return button
    }

    private fun doInitialize() {
        val configUrl = configUrlInput.text.toString().trim()
        val apiKey = apiKeyInput.text.toString().trim().ifEmpty { null }
        renderStatus("Initializing against:\n$configUrl")
        DataGrailConsent.getInstance().initialize(this, configUrl, apiKey) { result ->
            runOnUiThread {
                result.fold(
                    onSuccess = {
                        isInitialized = true
                        refreshCategoryStatus()
                        renderStatus("Initialized.")
                        showBannerButton.requestFocus()
                    },
                    onFailure = { e ->
                        renderStatus("Initialize FAILED:\n${e.message}")
                    },
                )
            }
        }
    }

    private fun doShowBanner() {
        if (!isInitialized) {
            renderStatus("Initialize first.")
            return
        }
        val publicBaseUrl = publicBaseUrlInput.text.toString().trim()
        val apiBaseUrl = apiBaseUrlInput.text.toString().trim().ifEmpty { null }
        // The phone QR points at the lightweight category-toggle config, not the SDK config.
        val phoneConfigUrl = defaultPhoneConfigUrl
        // Log the exact user_hash this TV will poll (demo aid; matches the QR + server key).
        val cfg = DataGrailConsent.getInstance().getConfig()
        if (cfg != null) {
            val deviceId = com.datagrail.consent.utils.UserHashGenerator.getDefaultDeviceId(this)
            val uh =
                com.datagrail.consent.utils.UserHashGenerator.generateUserHash(
                    customerId = cfg.dgCustomerId,
                    consentProjectId = cfg.privacyDomain,
                    deviceId = deviceId,
                )
            android.util.Log.i("TvDemo", "user_hash=$uh deviceId=$deviceId customerId=${cfg.dgCustomerId} project=${cfg.privacyDomain}")
            logLine("user_hash=${uh.take(12)}…")
        }
        logLine("Showing TV banner with QR pairing; phone opens $publicBaseUrl/tv ...")
        DataGrailConsent.getInstance().showBannerWithQRPairing(
            activity = this,
            publicBaseUrl = publicBaseUrl,
            configUrl = phoneConfigUrl,
            userIdentifier = null,
            apiBaseUrl = apiBaseUrl,
        ) { prefs ->
            runOnUiThread {
                refreshCategoryStatus()
                if (prefs != null) {
                    renderStatus("Pairing complete — consent adopted.")
                } else {
                    renderStatus("Banner dismissed / pairing timed out.")
                }
            }
        }
    }

    /** Refresh the always-visible category panel from current SDK state. */
    private fun refreshCategoryStatus() {
        if (!isInitialized) {
            categoryStatusText.text = "Not initialized."
            return
        }
        val prefs = DataGrailConsent.getInstance().getCategories()
        if (prefs == null || prefs.cookieOptions.isEmpty()) {
            categoryStatusText.text = "(no categories)"
            return
        }
        categoryStatusText.text =
            prefs.cookieOptions.joinToString("\n") { c ->
                "${if (c.isEnabled) "✅" else "❌"}  ${c.gtmKey}"
            }
    }

    private val logLines = StringBuilder()

    private fun logLine(line: String) {
        logLines.append("• ").append(line).append('\n')
    }

    private fun renderStatus(header: String) {
        statusText.text = header + "\n\n" + logLines.toString()
    }
}
