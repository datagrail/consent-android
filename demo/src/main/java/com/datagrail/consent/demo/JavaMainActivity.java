package com.datagrail.consent.demo;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.datagrail.consent.ConsentCallback;
import com.datagrail.consent.ConsentChangeListener;
import com.datagrail.consent.DataGrailConsent;
import com.datagrail.consent.PreferencesCallback;
import com.datagrail.consent.models.CategoryConsent;
import com.datagrail.consent.models.ConsentException;
import com.datagrail.consent.models.ConsentPreferences;
import com.datagrail.consent.ui.BannerDisplayStyle;

/**
 * Java-based demo activity for DataGrail Consent SDK
 *
 * This is a simplified Java version of the Kotlin MainActivity,
 * demonstrating full SDK integration from Java.
 */
public class JavaMainActivity extends AppCompatActivity {
    private static final String TAG = "JavaMainActivity";

    private static final String DEFAULT_CONFIG_URL =
        "https://api.consentjs.datagrailstaging.com/consent/" +
        "ac46d8ad-a67a-431f-a5d5-9e3eb922dae7/b17d1e73-6d35-4ae3-9199-ff2e98d8926a/config.json";

    // UI Elements
    private EditText configUrlInput;
    private TextView statusText;
    private TextView logText;
    private Button initButton;
    private Button showBannerModalButton;
    private Button showBannerFullScreenButton;
    private Button acceptAllButton;
    private Button rejectAllButton;
    private Button resetButton;

    // State
    private boolean isInitialized = false;
    private StringBuilder logBuilder = new StringBuilder();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_java_main);

        initViews();
        setupButtons();
        setupListeners();

        configUrlInput.setText(DEFAULT_CONFIG_URL);

        log("INFO", "Java Demo App launched");
        updateStatus("Ready to initialize");
    }

    private void initViews() {
        configUrlInput = findViewById(R.id.configUrlInput);
        statusText = findViewById(R.id.statusText);
        logText = findViewById(R.id.logText);
        initButton = findViewById(R.id.initButton);
        showBannerModalButton = findViewById(R.id.showBannerModalButton);
        showBannerFullScreenButton = findViewById(R.id.showBannerFullScreenButton);
        acceptAllButton = findViewById(R.id.acceptAllButton);
        rejectAllButton = findViewById(R.id.rejectAllButton);
        resetButton = findViewById(R.id.resetButton);
    }

    private void setupButtons() {
        initButton.setOnClickListener(v -> initializeSdk());
        showBannerModalButton.setOnClickListener(v -> showBanner(BannerDisplayStyle.MODAL));
        showBannerFullScreenButton.setOnClickListener(v -> showBanner(BannerDisplayStyle.FULL_SCREEN));
        acceptAllButton.setOnClickListener(v -> acceptAll());
        rejectAllButton.setOnClickListener(v -> rejectAll());
        resetButton.setOnClickListener(v -> resetSdk());

        // Initially disable banner buttons
        enableBannerButtons(false);
    }

    private void setupListeners() {
        DataGrailConsent.getInstance().onConsentChanged(new ConsentChangeListener() {
            @Override
            public void onConsentChanged(ConsentPreferences preferences) {
                log("INFO", "Consent changed - " + preferences.getCookieOptions().size() + " categories");
                displayPreferences(preferences);
            }
        });
    }

    private void initializeSdk() {
        log("INFO", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log("INFO", "Initializing SDK...");
        updateStatus("Initializing...");

        String configUrl = configUrlInput.getText().toString().trim();

        if (configUrl.isEmpty()) {
            log("ERROR", "Config URL is empty");
            updateStatus("ERROR: Empty config URL");
            return;
        }

        log("DEBUG", "Config URL: " + configUrl);

        DataGrailConsent.getInstance().initialize(
            getApplicationContext(),
            configUrl,
            new ConsentCallback() {
                @Override
                public void onSuccess() {
                    log("SUCCESS", "SDK initialized successfully");
                    isInitialized = true;
                    enableBannerButtons(true);
                    updateStatus("✅ SDK Initialized");

                    // Check initial state
                    checkStatus();
                }

                @Override
                public void onFailure(ConsentException error) {
                    log("ERROR", "Failed to initialize: " + error.getMessage());
                    isInitialized = false;
                    enableBannerButtons(false);
                    updateStatus("❌ Init Failed: " + error.getMessage());

                    // Log error type
                    if (error instanceof ConsentException.NetworkError) {
                        log("WARNING", "Network error - check connectivity");
                    } else if (error instanceof ConsentException.InvalidConfiguration) {
                        log("WARNING", "Invalid configuration - check config URL");
                    }
                }
            }
        );
    }

    private void showBanner(BannerDisplayStyle style) {
        log("INFO", "Showing banner with style: " + style);

        DataGrailConsent.getInstance().showBanner(
            this,
            style,
            new PreferencesCallback() {
                @Override
                public void onPreferencesSaved(ConsentPreferences preferences) {
                    log("SUCCESS", "User saved preferences");
                    log("INFO", "Customised: " + preferences.isCustomised());
                    log("INFO", "Categories: " + preferences.getCookieOptions().size());
                    updateStatus("✅ Preferences Saved");
                    displayPreferences(preferences);
                }

                @Override
                public void onDismissed() {
                    log("WARNING", "User dismissed banner without saving");
                    updateStatus("⚠️ Banner Dismissed");
                }
            }
        );
    }

    private void acceptAll() {
        log("INFO", "Accepting all categories...");

        DataGrailConsent.getInstance().acceptAll(new ConsentCallback() {
            @Override
            public void onSuccess() {
                log("SUCCESS", "All categories accepted");
                updateStatus("✅ All Accepted");
                checkStatus();
            }

            @Override
            public void onFailure(ConsentException error) {
                log("ERROR", "Failed to accept all: " + error.getMessage());
                updateStatus("❌ Accept Failed");
            }
        });
    }

    private void rejectAll() {
        log("INFO", "Rejecting all non-essential categories...");

        DataGrailConsent.getInstance().rejectAll(new ConsentCallback() {
            @Override
            public void onSuccess() {
                log("SUCCESS", "All non-essential categories rejected");
                updateStatus("✅ All Rejected");
                checkStatus();
            }

            @Override
            public void onFailure(ConsentException error) {
                log("ERROR", "Failed to reject all: " + error.getMessage());
                updateStatus("❌ Reject Failed");
            }
        });
    }

    private void resetSdk() {
        log("INFO", "Resetting SDK...");
        DataGrailConsent.getInstance().reset();
        log("SUCCESS", "SDK reset complete");

        isInitialized = false;
        enableBannerButtons(false);
        updateStatus("🔄 SDK Reset - Reinitialize Required");
    }

    private void checkStatus() {
        try {
            boolean shouldDisplay = DataGrailConsent.getInstance().shouldDisplayBanner();
            boolean hasConsent = DataGrailConsent.getInstance().hasUserConsent();
            ConsentPreferences preferences = DataGrailConsent.getInstance().getCategories();

            log("DEBUG", "shouldDisplayBanner: " + shouldDisplay);
            log("DEBUG", "hasUserConsent: " + hasConsent);

            if (preferences != null) {
                displayPreferences(preferences);
            }

            // Check specific categories
            boolean analyticsEnabled = DataGrailConsent.getInstance()
                .isCategoryEnabled("category_analytics");
            boolean marketingEnabled = DataGrailConsent.getInstance()
                .isCategoryEnabled("category_marketing");

            log("DEBUG", "Analytics enabled: " + analyticsEnabled);
            log("DEBUG", "Marketing enabled: " + marketingEnabled);

        } catch (Exception e) {
            log("ERROR", "Error checking status: " + e.getMessage());
        }
    }

    private void displayPreferences(ConsentPreferences preferences) {
        if (preferences == null) {
            log("DEBUG", "No preferences to display");
            return;
        }

        log("INFO", "Current Preferences:");
        log("INFO", "  Customised: " + preferences.isCustomised());

        for (CategoryConsent category : preferences.getCookieOptions()) {
            String status = category.isEnabled() ? "✅ ENABLED" : "❌ DISABLED";
            log("INFO", "  " + category.getGtmKey() + ": " + status);
        }
    }

    private void enableBannerButtons(boolean enabled) {
        runOnUiThread(() -> {
            showBannerModalButton.setEnabled(enabled);
            showBannerFullScreenButton.setEnabled(enabled);
            acceptAllButton.setEnabled(enabled);
            rejectAllButton.setEnabled(enabled);
        });
    }

    private void updateStatus(String message) {
        runOnUiThread(() -> {
            statusText.setText(message);
        });
    }

    private void log(String level, String message) {
        String logMessage = "[" + level + "] " + message;
        Log.d(TAG, logMessage);

        logBuilder.append(logMessage).append("\n");

        runOnUiThread(() -> {
            logText.setText(logBuilder.toString());
        });
    }
}
