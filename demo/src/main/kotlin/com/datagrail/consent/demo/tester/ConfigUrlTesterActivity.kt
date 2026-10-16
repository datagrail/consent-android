package com.datagrail.consent.demo.tester

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.datagrail.consent.BuildConfig
import com.datagrail.consent.demo.MainActivity
import com.datagrail.consent.demo.R
import com.datagrail.consent.models.CategoryConsent
import com.datagrail.consent.models.ConsentConfig
import com.datagrail.consent.models.ConsentPreferences
import com.datagrail.consent.ui.BannerDialog
import com.datagrail.consent.ui.BannerDisplayStyle
import kotlinx.coroutines.launch

/**
 * Lets a customer paste the pre-signed "View config" URL from the Mobile tab and preview it with the
 * SDK's own banner. Nothing is persisted and no consent is sent to the network.
 */
class ConfigUrlTesterActivity : AppCompatActivity() {
    private lateinit var urlInput: EditText
    private lateinit var loadButton: Button
    private lateinit var showModalButton: Button
    private lateinit var showFullScreenButton: Button
    private lateinit var versionText: TextView
    private lateinit var statusText: TextView
    private lateinit var errorCard: CardView
    private lateinit var errorTitle: TextView
    private lateinit var errorBody: TextView

    private val loader = ConfigUrlLoader()
    private var loadedConfig: ConsentConfig? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config_url_tester)

        // BannerDialog holds its config in fields set by newInstance, so a restored instance renders blank.
        if (savedInstanceState != null) {
            (supportFragmentManager.findFragmentByTag(BANNER_TAG) as? DialogFragment)?.dismissAllowingStateLoss()
        }

        urlInput = findViewById(R.id.configUrlInput)
        loadButton = findViewById(R.id.loadButton)
        showModalButton = findViewById(R.id.showModalButton)
        showFullScreenButton = findViewById(R.id.showFullScreenButton)
        versionText = findViewById(R.id.versionText)
        statusText = findViewById(R.id.statusText)
        errorCard = findViewById(R.id.errorCard)
        errorTitle = findViewById(R.id.errorTitle)
        errorBody = findViewById(R.id.errorBody)

        findViewById<Button>(R.id.pasteButton).setOnClickListener { pasteFromClipboard() }
        loadButton.setOnClickListener { load() }
        showModalButton.setOnClickListener { showBanner(BannerDisplayStyle.MODAL) }
        showFullScreenButton.setOnClickListener { showBanner(BannerDisplayStyle.FULL_SCREEN) }
        findViewById<Button>(R.id.openSdkDemoButton).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }

        renderVersions(metadata = null)
        statusText.setText(R.string.tester_status_idle)
    }

    private fun pasteFromClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)
        if (text.isNullOrBlank()) {
            Toast.makeText(this, R.string.tester_clipboard_empty, Toast.LENGTH_SHORT).show()
        } else {
            urlInput.setText(text.toString().trim())
        }
    }

    private fun load() {
        val raw = urlInput.text.toString()
        loadedConfig = null
        setShowButtonsEnabled(false)
        errorCard.visibility = View.GONE
        loadButton.isEnabled = false
        loadButton.setText(R.string.tester_loading)
        statusText.setText(R.string.tester_status_loading)
        renderVersions(ConfigUrlLoader.validatedUrl(raw)?.let { ConfigUrlLoader.metadata(it) })

        lifecycleScope.launch {
            when (val result = loader.load(raw)) {
                is ConfigUrlLoadResult.Loaded -> {
                    loadedConfig = result.config
                    statusText.text =
                        getString(
                            R.string.tester_status_loaded,
                            result.config.version,
                            result.config.layout.consentLayers.size,
                        )
                    setShowButtonsEnabled(true)
                }
                is ConfigUrlLoadResult.Failure -> {
                    val (title, body) = result.userFacing(this@ConfigUrlTesterActivity)
                    errorTitle.text = title
                    errorBody.text = body
                    errorCard.visibility = View.VISIBLE
                    statusText.setText(R.string.tester_status_failed)
                }
            }
            loadButton.isEnabled = true
            loadButton.setText(R.string.tester_load)
        }
    }

    private fun renderVersions(metadata: ConfigUrlMetadata?) {
        val sdk = getString(R.string.tester_sdk_versions, BuildConfig.LIBRARY_VERSION, BuildConfig.SCHEMA_VERSION)
        if (metadata == null) {
            versionText.text = sdk
            return
        }
        val schema = metadata.schemaVersion ?: getString(R.string.tester_schema_legacy)
        val expires =
            metadata.expiresAtMillis?.let { getString(R.string.tester_expiry_time, formatDeviceTime(this, it)) }
                ?: getString(R.string.tester_expiry_unknown)
        val url = getString(R.string.tester_url_metadata, schema, metadata.artifactMode.name.lowercase(), expires)
        versionText.text = "$sdk\n$url"
    }

    private fun showBanner(style: BannerDisplayStyle) {
        val config = loadedConfig ?: return
        // Non-null defaults mirror ConsentManager.defaultPreferences; BannerDialog's toggles no-op on null.
        val preferences =
            ConsentPreferences(
                isCustomised = false,
                cookieOptions = config.initialCategories.initial.map { CategoryConsent(gtmKey = it, isEnabled = true) },
            )
        BannerDialog.newInstance(config, preferences, style) { saved ->
            statusText.text =
                if (saved == null) {
                    getString(R.string.tester_status_dismissed)
                } else {
                    getString(R.string.tester_status_saved, saved.cookieOptions.count { it.isEnabled })
                }
        }.show(supportFragmentManager, BANNER_TAG)
    }

    private fun setShowButtonsEnabled(enabled: Boolean) {
        showModalButton.isEnabled = enabled
        showFullScreenButton.isEnabled = enabled
    }

    private companion object {
        const val BANNER_TAG = "config_url_tester_banner"
    }
}
