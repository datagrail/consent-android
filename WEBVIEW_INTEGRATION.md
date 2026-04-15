# WebView Integration Guide

This guide shows how to inject DataGrail consent preferences into WebViews in your Android app using HTTP cookies.

## Overview

The `DataGrailWebViewHelper` synchronizes consent preferences from the native Android SDK into WebViews via HTTP cookies. This ensures that:

- Consent preferences are available **before** the page loads (no timing issues)
- Web pages respect the user's consent choices from the first network request
- Consent state is consistent between native and web contexts
- Web applications can read consent from cookies

## How It Works

The helper injects three HTTP cookies **before** loading the web page:

1. **`datagrail_consent_preferences[_s]`** - Consent state per category (format: `"key1:1|key2:0"`)
2. **`datagrail_consent_id[_s]`** - User identifier (format: `"{customerId}.{uuid}"`)
3. **`datagrail_consent_version[_s]`** - Configuration version string

The `_s` suffix is added when `allCookieSubdomains` is enabled in your SDK configuration.

**Cookie domain** is automatically extracted from the target URL:
- **Cross-subdomain mode** (allCookieSubdomains=true): `.example.com` (applies to all subdomains)
- **Exact domain mode** (allCookieSubdomains=false): `www.example.com` (applies only to exact host)

## Migration from JavaScript Injection

**⚠️ Breaking Changes in v1.5.0:**

The WebView helper has been completely rewritten to use HTTP cookie injection instead of JavaScript injection.

**Removed:**
- `injectConsentPreferences(webView)` - replaced by `loadWebViewWithConsent(webView, url, callback)`
- `updateConsentPreferences(webView, callback)` - replaced by `updateConsentCookies(webView, callback)`
- JavaScript injection via `evaluateJavascript()`
- `window.datagrailConsent` global variable
- `DG_BANNER_API.setConsentPreferences()` calls

**Benefits of cookie-based approach:**
- ✅ No timing issues - consent available from first request
- ✅ No JavaScript required
- ✅ Works even if page blocks JavaScript execution
- ✅ Matches iOS SDK behavior
- ✅ More reliable and secure

## Usage

### 1. Load WebView with Consent Cookies (Recommended)

The recommended approach is to use `loadWebViewWithConsent()` which injects cookies and loads the URL in one call:

```kotlin
import android.webkit.WebView
import com.datagrail.consent.webview.DataGrailWebViewHelper

class MyActivity : AppCompatActivity() {
    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = findViewById(R.id.webView)
        webView.settings.javaScriptEnabled = true // Still recommended for web apps

        val url = "https://example.com"

        DataGrailWebViewHelper.loadWebViewWithConsent(webView, url) { result ->
            result.fold(
                onSuccess = {
                    Log.d("Consent", "Cookies injected, page loaded")
                },
                onFailure = { error ->
                    Log.e("Consent", "Failed to inject cookies: ${error.message}")
                }
            )
        }
    }
}
```

### 2. Manual Cookie Injection

If you need more control over URL loading, use `injectConsentCookies()`:

```kotlin
import com.datagrail.consent.webview.DataGrailWebViewHelper

val url = "https://example.com"

DataGrailWebViewHelper.injectConsentCookies(webView, url) { result ->
    if (result.isSuccess) {
        // Cookies injected successfully, now load URL
        webView.loadUrl(url)
    } else {
        Log.e("Consent", "Cookie injection failed: ${result.exceptionOrNull()?.message}")
    }
}
```

### 3. Update Cookies When Consent Changes

Update cookies in an already-loaded WebView when consent preferences change:

```kotlin
import com.datagrail.consent.DataGrailConsent
import com.datagrail.consent.webview.DataGrailWebViewHelper

// Listen for consent changes
DataGrailConsent.getInstance().onConsentChanged { _ ->
    DataGrailWebViewHelper.updateConsentCookies(webView) { result ->
        result.fold(
            onSuccess = { Log.d("Consent", "Cookies updated successfully") },
            onFailure = { e -> Log.e("Consent", "Cookie update failed: ${e.message}") }
        )
    }
}
```

### 4. Java Usage

The helper is fully compatible with Java:

```java
import android.webkit.WebView;
import com.datagrail.consent.webview.DataGrailWebViewHelper;
import kotlin.Unit;

public class MyActivity extends AppCompatActivity {
    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = findViewById(R.id.webView);
        webView.getSettings().setJavaScriptEnabled(true);

        String url = "https://example.com";

        DataGrailWebViewHelper.loadWebViewWithConsent(webView, url, result -> {
            if (result.isSuccess()) {
                Log.d("Consent", "Cookies injected, page loaded");
            } else {
                Throwable error = result.exceptionOrNull();
                Log.e("Consent", "Failed: " + (error != null ? error.getMessage() : "Unknown error"));
            }
            return Unit.INSTANCE;
        });
    }
}
```

## API Reference

### `loadWebViewWithConsent(webView: WebView, url: String, callback: ((Result<Unit>) -> Unit)?)`

Injects consent cookies into a WebView and loads the URL.

**Parameters:**
- `webView`: The WebView to inject cookies into and load
- `url`: The URL to load (must include protocol, e.g., "https://example.com")
- `callback`: Optional callback with Result.success or Result.failure

**Behavior:**
1. Gets current SDK configuration and preferences
2. Extracts cookie domain from target URL
3. Injects three HTTP cookies via `CookieManager`
4. Loads the specified URL
5. Invokes callback with success or error

**Example:**
```kotlin
DataGrailWebViewHelper.loadWebViewWithConsent(webView, "https://example.com") { result ->
    // Handle result
}
```

### `injectConsentCookies(webView: WebView, url: String, callback: ((Result<Unit>) -> Unit)?)`

Injects consent cookies without loading the URL.

**Parameters:**
- `webView`: The WebView to inject cookies into
- `url`: The target URL (used for cookie domain, not loaded)
- `callback`: Optional callback with Result.success or Result.failure

**Use case:** When you need to inject cookies but want to handle URL loading yourself.

**Example:**
```kotlin
DataGrailWebViewHelper.injectConsentCookies(webView, "https://example.com") { result ->
    if (result.isSuccess) {
        webView.loadUrl("https://example.com")
    }
}
```

### `updateConsentCookies(webView: WebView, callback: ((Result<Unit>) -> Unit)?)`

Updates consent cookies in an already-loaded WebView.

**Parameters:**
- `webView`: The WebView to update (must have a loaded URL)
- `callback`: Optional callback with Result.success or Result.failure

**Use case:** When consent preferences change after the page has loaded.

**Example:**
```kotlin
DataGrailWebViewHelper.updateConsentCookies(webView) { result ->
    // Handle result
}
```

## Cookie Details

### Cookie Names

**Base names:**
- `datagrail_consent_preferences`
- `datagrail_consent_id`
- `datagrail_consent_version`

**With cross-subdomain suffix (when `allCookieSubdomains` is true):**
- `datagrail_consent_preferences_s`
- `datagrail_consent_id_s`
- `datagrail_consent_version_s`

### Cookie Format

**Preferences cookie:**
```
datagrail_consent_preferences=category_marketing:1|category_analytics:0|category_essential:1
```

**ID cookie:**
```
datagrail_consent_id=8fb34a12-dfe5-41c4-99e2-b8d2fe3f4f89.a1b2c3d4-e5f6-7890-abcd-ef1234567890
```
Format: `{customerId}.{persistentUUID}`

**Version cookie:**
```
datagrail_consent_version=1.2.3
```

### Cookie Attributes

All cookies are set with:
- `Path=/` - Available across entire domain
- `Max-Age=31536000` - 1 year expiration
- `Domain=.example.com` or `Domain=www.example.com` - Based on cross-subdomain setting

### Verification

Verify cookies are set correctly using JavaScript in the WebView:

```kotlin
webView.evaluateJavascript("""
    (function() {
        return document.cookie;
    })();
""") { result ->
    Log.d("Consent", "Cookies: $result")
}
```

Or use Chrome DevTools:
1. Connect device and enable USB debugging
2. Open `chrome://inspect` in Chrome
3. Inspect your WebView
4. Open Application tab → Cookies
5. Verify DataGrail cookies are present

## Requirements

- **SDK must be initialized**: Call `DataGrailConsent.getInstance().initialize()` first
- **Valid URL**: Must include protocol (e.g., "https://example.com")
- **Internet permission**: Already required by the SDK
- **JavaScript enabled**: Recommended for web applications (not required for cookie injection)

## Demo

See `WebViewDemoActivity` in the demo app for a complete working example with:

- URL input and navigation
- Cookie injection before page load
- Cookie verification via JavaScript
- Consent change listener with automatic cookie updates
- Console log capture

Run the demo:

```bash
./gradlew :demo:assembleDebug :demo:installDebug
```

Then navigate to "WebView Demo" from the main screen.

## Troubleshooting

### Cookies not being set

**Problem**: Cookies are not visible in the WebView

**Solutions**:
- Ensure SDK is initialized: `DataGrailConsent.getInstance().initialize()`
- Check callback for errors: Look at `result.exceptionOrNull()?.message`
- Verify URL includes protocol: Use `https://example.com`, not `example.com`
- Enable logging: `DataGrailConsent.setLogLevel(LogLevel.DEBUG)`
- Check third-party cookie settings: Helper calls `setAcceptThirdPartyCookies(webView, true)`

### Wrong cookie domain

**Problem**: Cookies set for wrong domain

**Explanation**: Cookie domain is extracted from the **target URL**, not from the SDK's `privacyDomain` config field. If loading `https://www.example.com`, cookies will be set for `www.example.com` or `.example.com` (depending on cross-subdomain setting).

### Cookies not updating after consent change

**Problem**: User changes consent but WebView still shows old consent

**Solution**: Set up a consent change listener:

```kotlin
DataGrailConsent.getInstance().onConsentChanged { _ ->
    DataGrailWebViewHelper.updateConsentCookies(webView) { result ->
        // Optionally reload page to apply new consent
        if (result.isSuccess) {
            webView.reload()
        }
    }
}
```

**Note**: `updateConsentCookies()` updates cookies but does not reload the page. The web application may need to detect cookie changes, or you can call `webView.reload()` after updating.

### Cross-subdomain cookies not working

**Problem**: Cookies don't work across subdomains

**Solutions**:
- Verify `allCookieSubdomains` is true in your SDK configuration
- Check cookie domain has leading dot: `.example.com` (not `example.com`)
- Ensure cookies are set before any navigation to subdomains

## Best Practices

1. **Use `loadWebViewWithConsent()`**: This is the simplest and most reliable approach
2. **Handle errors**: Check the Result callback and log failures
3. **Listen for changes**: Set up `onConsentChanged` to keep WebViews synchronized
4. **Verify in development**: Use Chrome DevTools to inspect cookies during development
5. **Enable logging**: Use `DataGrailConsent.setLogLevel(LogLevel.DEBUG)` to see cookie injection logs
6. **Consider page reload**: After updating cookies, your web app may need to reload to apply new consent

## Related Documentation

- [README.md](README.md) - SDK overview and setup
- [JAVA_INTEGRATION.md](JAVA_INTEGRATION.md) - Java usage examples
- [REANCHOR_ANDROID_WEBVIEW_COOKIES.md](REANCHOR_ANDROID_WEBVIEW_COOKIES.md) - Technical implementation details
- iOS equivalent: See `consent-ios` repository's WebView integration guide
