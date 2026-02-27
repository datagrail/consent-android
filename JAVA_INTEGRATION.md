# Java Integration Guide

The DataGrail Consent Android SDK is written in Kotlin but provides full Java interoperability through dedicated callback interfaces.

## Quick Start (Java)

### 1. Initialize the SDK

```java
import com.datagrail.consent.DataGrailConsent;
import com.datagrail.consent.ConsentCallback;
import com.datagrail.consent.models.ConsentException;

public class MyApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();

        DataGrailConsent.getInstance().initialize(
            this,
            "https://consent.datagrail.io/config/YOUR_CONFIG.json",
            new ConsentCallback() {
                @Override
                public void onSuccess() {
                    Log.d("Consent", "SDK initialized successfully");

                    if (DataGrailConsent.getInstance().shouldDisplayBanner()) {
                        // Show banner in your MainActivity
                    }
                }

                @Override
                public void onFailure(ConsentException error) {
                    Log.e("Consent", "Failed to initialize: " + error.getMessage());
                }
            }
        );
    }
}
```

### 2. Show the Consent Banner

```java
import com.datagrail.consent.DataGrailConsent;
import com.datagrail.consent.PreferencesCallback;
import com.datagrail.consent.models.ConsentPreferences;
import com.datagrail.consent.ui.BannerDisplayStyle;

public class MainActivity extends FragmentActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (DataGrailConsent.getInstance().shouldDisplayBanner()) {
            DataGrailConsent.getInstance().showBanner(
                this,
                BannerDisplayStyle.MODAL,
                new PreferencesCallback() {
                    @Override
                    public void onPreferencesSaved(ConsentPreferences preferences) {
                        Log.d("Consent", "User saved preferences");
                        applyConsent(preferences);
                    }

                    @Override
                    public void onDismissed() {
                        Log.d("Consent", "User dismissed banner");
                    }
                }
            );
        }
    }

    private void applyConsent(ConsentPreferences preferences) {
        // Apply user's consent choices to your tracking SDKs
        for (CategoryConsent category : preferences.getCookieOptions()) {
            Log.d("Consent", category.getGtmKey() + ": " + category.isEnabled());
        }
    }
}
```

### 3. Listen for Consent Changes

```java
import com.datagrail.consent.ConsentChangeListener;

DataGrailConsent.getInstance().onConsentChanged(new ConsentChangeListener() {
    @Override
    public void onConsentChanged(ConsentPreferences preferences) {
        Log.d("Consent", "Consent changed - updating tracking");
        updateTracking(preferences);
    }
});
```

### 4. Accept/Reject All Categories

```java
// Accept all categories
DataGrailConsent.getInstance().acceptAll(new ConsentCallback() {
    @Override
    public void onSuccess() {
        Log.d("Consent", "Accepted all categories");
    }

    @Override
    public void onFailure(ConsentException error) {
        Log.e("Consent", "Failed to accept all: " + error.getMessage());
    }
});

// Reject all non-essential categories
DataGrailConsent.getInstance().rejectAll(new ConsentCallback() {
    @Override
    public void onSuccess() {
        Log.d("Consent", "Rejected all non-essential categories");
    }

    @Override
    public void onFailure(ConsentException error) {
        Log.e("Consent", "Failed to reject all: " + error.getMessage());
    }
});
```

### 5. Save Custom Preferences

```java
import com.datagrail.consent.models.CategoryConsent;
import com.datagrail.consent.models.ConsentPreferences;
import java.util.Arrays;

// Create custom preferences
ConsentPreferences customPreferences = new ConsentPreferences(
    true, // isCustomised
    Arrays.asList(
        new CategoryConsent("category_functional", true),
        new CategoryConsent("category_marketing", false),
        new CategoryConsent("category_analytics", true)
    )
);

DataGrailConsent.getInstance().savePreferences(
    customPreferences,
    new ConsentCallback() {
        @Override
        public void onSuccess() {
            Log.d("Consent", "Custom preferences saved");
        }

        @Override
        public void onFailure(ConsentException error) {
            Log.e("Consent", "Failed to save: " + error.getMessage());
        }
    }
);
```

### 6. Check Category Status

```java
// Check if a specific category is enabled
boolean isMarketingEnabled = DataGrailConsent.getInstance()
    .isCategoryEnabled("category_marketing");

if (isMarketingEnabled) {
    // Enable marketing tracking
    enableMarketingSDK();
}
```

### 7. Get User Preferences

```java
// Get saved preferences (returns null if user hasn't saved consent yet)
ConsentPreferences savedPrefs = DataGrailConsent.getInstance()
    .getUserPreferences();

if (savedPrefs != null) {
    for (CategoryConsent category : savedPrefs.getCookieOptions()) {
        Log.d("Consent",
            category.getGtmKey() + ": " +
            (category.isEnabled() ? "enabled" : "disabled")
        );
    }
}

// Get categories with current state (includes defaults if user hasn't saved)
ConsentPreferences currentState = DataGrailConsent.getInstance()
    .getCategories();
```

### 8. Retry Failed Requests

```java
import com.datagrail.consent.RetryCallback;

DataGrailConsent.getInstance().retryPendingRequests(new RetryCallback() {
    @Override
    public void onRetryComplete(int successCount, int failureCount) {
        Log.d("Consent", "Retry complete: " + successCount +
            " succeeded, " + failureCount + " failed");
    }
});
```

## Error Handling

All error types extend `ConsentException`:

```java
@Override
public void onFailure(ConsentException error) {
    if (error instanceof ConsentException.NotInitialized) {
        Log.e("Consent", "SDK not initialized");
    } else if (error instanceof ConsentException.NetworkError) {
        Log.e("Consent", "Network error: " + error.getMessage());
    } else if (error instanceof ConsentException.InvalidConfiguration) {
        Log.e("Consent", "Invalid config: " + error.getMessage());
    } else {
        Log.e("Consent", "Error: " + error.getMessage());
    }
}
```

## Complete Example

```java
package com.example.myapp;

import android.app.Application;
import android.os.Bundle;
import android.util.Log;
import androidx.fragment.app.FragmentActivity;
import com.datagrail.consent.DataGrailConsent;
import com.datagrail.consent.ConsentCallback;
import com.datagrail.consent.ConsentChangeListener;
import com.datagrail.consent.PreferencesCallback;
import com.datagrail.consent.models.ConsentException;
import com.datagrail.consent.models.ConsentPreferences;
import com.datagrail.consent.models.CategoryConsent;
import com.datagrail.consent.ui.BannerDisplayStyle;

public class MyApplication extends Application {
    private static final String TAG = "ConsentSDK";

    @Override
    public void onCreate() {
        super.onCreate();
        initializeConsentSDK();
    }

    private void initializeConsentSDK() {
        DataGrailConsent.getInstance().initialize(
            this,
            "https://consent.datagrail.io/config/YOUR_CONFIG.json",
            new ConsentCallback() {
                @Override
                public void onSuccess() {
                    Log.i(TAG, "SDK initialized successfully");
                    setupConsentListener();
                }

                @Override
                public void onFailure(ConsentException error) {
                    Log.e(TAG, "Failed to initialize SDK", error);
                }
            }
        );
    }

    private void setupConsentListener() {
        DataGrailConsent.getInstance().onConsentChanged(
            new ConsentChangeListener() {
                @Override
                public void onConsentChanged(ConsentPreferences preferences) {
                    Log.i(TAG, "Consent changed");
                    applyConsentToSDKs(preferences);
                }
            }
        );
    }

    private void applyConsentToSDKs(ConsentPreferences preferences) {
        for (CategoryConsent category : preferences.getCookieOptions()) {
            String gtmKey = category.getGtmKey();
            boolean enabled = category.isEnabled();

            if ("category_analytics".equals(gtmKey)) {
                if (enabled) {
                    // Enable analytics SDK
                } else {
                    // Disable analytics SDK
                }
            } else if ("category_marketing".equals(gtmKey)) {
                if (enabled) {
                    // Enable marketing SDK
                } else {
                    // Disable marketing SDK
                }
            }
        }
    }
}

public class MainActivity extends FragmentActivity {
    private static final String TAG = "MainActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        checkAndShowConsentBanner();
    }

    private void checkAndShowConsentBanner() {
        try {
            if (DataGrailConsent.getInstance().shouldDisplayBanner()) {
                showConsentBanner();
            } else {
                Log.d(TAG, "Banner not needed");
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
                    Log.i(TAG, "User saved consent preferences");
                    // Preferences are automatically applied via ConsentChangeListener
                }

                @Override
                public void onDismissed() {
                    Log.i(TAG, "User dismissed banner");
                }
            }
        );
    }
}
```

## Key Differences from Kotlin

| Kotlin | Java |
|--------|------|
| Lambda callbacks | Callback interfaces |
| `Result<Unit>` | `ConsentCallback` with `onSuccess()`/`onFailure()` |
| Nullable `ConsentPreferences?` | `PreferencesCallback` with separate methods |
| Lambda for changes | `ConsentChangeListener` interface |

## Additional Resources

- [Main README](README.md) - SDK overview and features
- [Kotlin Examples](README.md#quick-start) - Kotlin usage examples
- [API Reference](README.md#api-reference) - Complete API documentation
