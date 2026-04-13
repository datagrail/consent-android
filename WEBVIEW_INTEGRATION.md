# WebView Integration Guide

This guide shows how to inject DataGrail consent preferences into WebViews in your Android app.

## Overview

The `DataGrailWebViewHelper` allows you to synchronize consent preferences from the native Android SDK into WebViews. This ensures that:

- Web pages loaded in your app's WebViews respect the user's consent choices
- Consent state is consistent between native and web contexts
- The web banner API (`DG_BANNER_API`) receives the correct preferences

## How It Works

The helper injects JavaScript that:

1. **Stores preferences** in `window.datagrailConsent` for debugging and inspection
2. **Calls `DG_BANNER_API.setConsentPreferences()`** if the web banner API is available on the page
3. **Uses the `runPreferenceCallbacks: false` config** to avoid triggering GTM callbacks during injection

## Usage

### 1. Basic WebView Setup

Inject consent preferences when pages load:

```kotlin
import android.webkit.WebView
import android.webkit.WebViewClient
import com.datagrail.consent.webview.DataGrailWebViewHelper

class MyActivity : AppCompatActivity() {
    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = findViewById(R.id.webView)
        webView.settings.javaScriptEnabled = true

        // Inject consent when page finishes loading
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(
                view: WebView?,
                url: String?
            ) {
                super.onPageFinished(view, url)
                view?.let {
                    DataGrailWebViewHelper.injectConsentPreferences(it)
                }
            }
        }

        webView.loadUrl("https://example.com")
    }
}
```

### 2. Updating Consent Dynamically

Update preferences in an already-loaded WebView when consent changes:

```kotlin
import com.datagrail.consent.DataGrailConsent
import com.datagrail.consent.webview.DataGrailWebViewHelper

// Listen for consent changes
DataGrailConsent.getInstance().onConsentChanged { _ ->
    DataGrailWebViewHelper.updateConsentPreferences(webView) { success ->
        if (success) {
            Log.d("Consent", "WebView updated successfully")
        } else {
            Log.e("Consent", "WebView update failed")
        }
    }
}
```

### 3. Java Usage

The helper is fully compatible with Java:

```java
import android.webkit.WebView;
import android.webkit.WebViewClient;
import com.datagrail.consent.webview.DataGrailWebViewHelper;

public class MyActivity extends AppCompatActivity {
    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = findViewById(R.id.webView);
        webView.getSettings().setJavaScriptEnabled(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (view != null) {
                    DataGrailWebViewHelper.injectConsentPreferences(view);
                }
            }
        });

        webView.loadUrl("https://example.com");
    }
}
```

## API Reference

### `injectConsentPreferences(webView: WebView)`

Injects current consent preferences into a WebView.

- **When to call**: In `WebViewClient.onPageFinished()` (recommended) or `onPageStarted()` for earlier injection
- **Behavior**:
  - Gets current preferences via `DataGrailConsent.getInstance().getCategories()`
  - Injects JavaScript immediately via `evaluateJavascript()`
  - Does nothing if SDK is not initialized or no preferences exist

**Example:**
```kotlin
DataGrailWebViewHelper.injectConsentPreferences(webView)
```

### `updateConsentPreferences(webView: WebView, callback: ((Boolean) -> Unit)?)`

Updates consent preferences in an already-loaded WebView.

- **When to call**: When consent changes after the page has loaded
- **Parameters**:
  - `webView`: The WebView to update
  - `callback`: Optional callback invoked with `true` if successful, `false` otherwise
- **Behavior**: Same as `injectConsentPreferences` but includes result callback

**Example:**
```kotlin
DataGrailWebViewHelper.updateConsentPreferences(webView) { success ->
    Log.d("Consent", "Update result: $success")
}
```

## Injected JavaScript

The helper injects this JavaScript code:

```javascript
(function() {
    const preferences = {"isCustomised":true,"cookieOptions":[...]};
    const config = { "runPreferenceCallbacks": false };

    // Store preferences globally for debugging
    window.datagrailConsent = preferences;

    // If DG_BANNER_API is available, use it to set preferences
    if (window.DG_BANNER_API && typeof window.DG_BANNER_API.setConsentPreferences === 'function') {
        window.DG_BANNER_API.setConsentPreferences(preferences, config);
        console.log('[DataGrail Android SDK] Set consent preferences via DG_BANNER_API');
    } else {
        console.log('[DataGrail Android SDK] DG_BANNER_API not available, preferences stored in window.datagrailConsent');
    }
})();
```

### Verification

You can verify injection in your WebView by executing JavaScript:

```kotlin
webView.evaluateJavascript("""
    (function() {
        return JSON.stringify(window.datagrailConsent, null, 2);
    })();
""") { result ->
    Log.d("Consent", "Injected preferences: $result")
}
```

## Requirements

- **JavaScript must be enabled**: `webView.settings.javaScriptEnabled = true`
- **SDK must be initialized**: Call `DataGrailConsent.getInstance().initialize()` first
- **Internet permission**: Already required by the SDK

## Demo

See `WebViewDemoActivity` in the demo app for a complete working example with:

- URL input and navigation
- Console log capture
- Preference verification buttons
- Auto-checking injection on page load

Run the demo:

```bash
./gradlew :demo:assembleDebug :demo:installDebug
```

Then navigate to "WebView Demo" from the main screen.

## Troubleshooting

### Preferences not injecting

**Problem**: `window.datagrailConsent` is undefined

**Solutions**:
- Ensure SDK is initialized: `DataGrailConsent.getInstance().initialize()`
- Check JavaScript is enabled: `webView.settings.javaScriptEnabled = true`
- Verify injection timing: Call in `onPageFinished()` not `onPageStarted()` or earlier

### DG_BANNER_API not available

**Problem**: Console shows "DG_BANNER_API not available"

**Explanation**: This is normal if the web page doesn't have the DataGrail web banner installed. The preferences are still stored in `window.datagrailConsent` for your own use.

### Preferences not updating

**Problem**: Consent changes in native app but WebView doesn't update

**Solution**: Set up a consent change listener:

```kotlin
DataGrailConsent.getInstance().onConsentChanged { _ ->
    DataGrailWebViewHelper.updateConsentPreferences(webView)
}
```

## Best Practices

1. **Inject at the right time**: Use `onPageFinished()` to ensure the WebView is ready to execute JavaScript
2. **Handle errors gracefully**: The helper logs errors but doesn't throw exceptions
3. **Listen for changes**: Set up `onConsentChanged` to keep WebViews synchronized
4. **Test without banner**: The helper works even if `DG_BANNER_API` isn't present
5. **Enable logging**: Use `DataGrailConsent.setLogLevel(LogLevel.DEBUG)` during development

## Related Documentation

- [README.md](README.md) - SDK overview and setup
- [JAVA_INTEGRATION.md](JAVA_INTEGRATION.md) - Java usage examples
- iOS equivalent: See `consent-ios` repository's WebView integration guide
