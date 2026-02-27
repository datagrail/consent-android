package com.datagrail.consent.demo;

import android.app.Application;
import android.os.Bundle;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;

import com.datagrail.consent.ConsentCallback;
import com.datagrail.consent.ConsentChangeListener;
import com.datagrail.consent.DataGrailConsent;
import com.datagrail.consent.PreferencesCallback;
import com.datagrail.consent.models.CategoryConsent;
import com.datagrail.consent.models.ConsentException;
import com.datagrail.consent.models.ConsentPreferences;
import com.datagrail.consent.ui.BannerDisplayStyle;

/**
 * Example Java integration for DataGrail Consent SDK
 *
 * This demonstrates how to use the SDK from a Java application.
 * The SDK provides Java-friendly callback interfaces that clearly indicate
 * success/failure states, making it easy to integrate from Java code.
 */
public class JavaExample {
    private static final String TAG = "JavaExample";

    /**
     * Example: Initialize SDK in Application class
     */
    public static class ExampleApplication extends Application {
        @Override
        public void onCreate() {
            super.onCreate();
            initializeConsentSDK();
        }

        private void initializeConsentSDK() {
            DataGrailConsent.getInstance().initialize(
                this,
                "https://api.consentjs.datagrailstaging.com/consent/config.json",
                new ConsentCallback() {
                    @Override
                    public void onSuccess() {
                        Log.i(TAG, "✅ SDK initialized successfully");
                        setupConsentListener();
                    }

                    @Override
                    public void onFailure(ConsentException error) {
                        Log.e(TAG, "❌ Failed to initialize SDK: " + error.getMessage());
                        handleInitializationError(error);
                    }
                }
            );
        }

        private void setupConsentListener() {
            DataGrailConsent.getInstance().onConsentChanged(
                new ConsentChangeListener() {
                    @Override
                    public void onConsentChanged(ConsentPreferences preferences) {
                        Log.i(TAG, "Consent changed - applying to SDKs");
                        applyConsentPreferences(preferences);
                    }
                }
            );
        }

        private void applyConsentPreferences(ConsentPreferences preferences) {
            for (CategoryConsent category : preferences.getCookieOptions()) {
                String gtmKey = category.getGtmKey();
                boolean enabled = category.isEnabled();

                Log.d(TAG, "Category " + gtmKey + ": " + (enabled ? "ENABLED" : "DISABLED"));

                // Apply to your tracking SDKs
                switch (gtmKey) {
                    case "category_analytics":
                        if (enabled) {
                            // Enable analytics tracking
                        } else {
                            // Disable analytics tracking
                        }
                        break;
                    case "category_marketing":
                        if (enabled) {
                            // Enable marketing tracking
                        } else {
                            // Disable marketing tracking
                        }
                        break;
                }
            }
        }

        private void handleInitializationError(ConsentException error) {
            if (error instanceof ConsentException.NetworkError) {
                Log.e(TAG, "Network error - will retry later");
            } else if (error instanceof ConsentException.InvalidConfiguration) {
                Log.e(TAG, "Invalid configuration - check config URL");
            }
        }
    }

    /**
     * Example: Show banner in Activity
     */
    public static class ExampleActivity extends FragmentActivity {
        @Override
        protected void onCreate(@Nullable Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            checkAndShowConsentBanner();
        }

        private void checkAndShowConsentBanner() {
            try {
                boolean shouldDisplay = DataGrailConsent.getInstance().shouldDisplayBanner();
                boolean hasConsent = DataGrailConsent.getInstance().hasUserConsent();

                Log.d(TAG, "shouldDisplayBanner: " + shouldDisplay);
                Log.d(TAG, "hasUserConsent: " + hasConsent);

                if (shouldDisplay) {
                    showConsentBanner();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error checking consent status", e);
            }
        }

        private void showConsentBanner() {
            DataGrailConsent.getInstance().showBanner(
                this,
                BannerDisplayStyle.MODAL,
                new PreferencesCallback() {
                    @Override
                    public void onPreferencesSaved(ConsentPreferences preferences) {
                        Log.i(TAG, "✅ User saved preferences");
                        Log.i(TAG, "Customised: " + preferences.isCustomised());
                        Log.i(TAG, "Categories: " + preferences.getCookieOptions().size());
                    }

                    @Override
                    public void onDismissed() {
                        Log.i(TAG, "⚠️ User dismissed banner without saving");
                    }
                }
            );
        }

        /**
         * Example: Accept all categories programmatically
         */
        private void acceptAllCategories() {
            DataGrailConsent.getInstance().acceptAll(new ConsentCallback() {
                @Override
                public void onSuccess() {
                    Log.i(TAG, "✅ Successfully accepted all categories");
                }

                @Override
                public void onFailure(ConsentException error) {
                    Log.e(TAG, "❌ Failed to accept all: " + error.getMessage());
                }
            });
        }

        /**
         * Example: Reject all non-essential categories programmatically
         */
        private void rejectAllCategories() {
            DataGrailConsent.getInstance().rejectAll(new ConsentCallback() {
                @Override
                public void onSuccess() {
                    Log.i(TAG, "✅ Successfully rejected all non-essential categories");
                }

                @Override
                public void onFailure(ConsentException error) {
                    Log.e(TAG, "❌ Failed to reject all: " + error.getMessage());
                }
            });
        }

        /**
         * Example: Check if specific category is enabled
         */
        private void checkCategoryStatus() {
            boolean marketingEnabled = DataGrailConsent.getInstance()
                .isCategoryEnabled("category_marketing");

            if (marketingEnabled) {
                Log.d(TAG, "Marketing is ENABLED");
                // Enable marketing SDK
            } else {
                Log.d(TAG, "Marketing is DISABLED");
                // Disable marketing SDK
            }
        }

        /**
         * Example: Get current preferences
         */
        private void getCurrentPreferences() {
            // Get saved preferences (null if user hasn't saved yet)
            ConsentPreferences savedPrefs = DataGrailConsent.getInstance()
                .getUserPreferences();

            if (savedPrefs != null) {
                Log.d(TAG, "User has saved preferences");
            } else {
                Log.d(TAG, "User has not saved preferences yet");
            }

            // Get effective preferences (includes defaults)
            ConsentPreferences currentPrefs = DataGrailConsent.getInstance()
                .getCategories();

            if (currentPrefs != null) {
                for (CategoryConsent category : currentPrefs.getCookieOptions()) {
                    Log.d(TAG, category.getGtmKey() + ": " + category.isEnabled());
                }
            }
        }
    }
}
