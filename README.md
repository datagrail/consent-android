# DataGrail Consent Android SDK

[![CI](https://github.com/datagrail/consent-android/actions/workflows/ci.yml/badge.svg)](https://github.com/datagrail/consent-android/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.datagrail/consent)](https://central.sonatype.com/artifact/io.datagrail/consent)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Min SDK](https://img.shields.io/badge/minSdk-23-green.svg)](https://developer.android.com/about/versions/marshmallow)

Native Android SDK for displaying consent banners and managing user privacy preferences, powered by [DataGrail](https://www.datagrail.io/).

## Installation

Add the dependency to your app's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("io.datagrail:consent:1.5.0")
}
```

Sync your project and the SDK will be downloaded from Maven Central automatically.

## Quick Start

### 1. Initialize the SDK

```kotlin
import com.datagrail.consent.DataGrailConsent

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        DataGrailConsent.getInstance().initialize(
            context = this,
            configUrl = "https://consent.datagrail.io/config/YOUR_CONFIG.json"
        ) { result ->
            result.fold(
                onSuccess = {
                    if (DataGrailConsent.getInstance().needsConsent()) {
                        // Show consent banner (see step 2)
                    }
                },
                onFailure = { error ->
                    Log.e("Consent", "Failed to initialize: ${error.message}")
                }
            )
        }
    }
}
```

### 2. Show the Consent Banner

```kotlin
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (DataGrailConsent.getInstance().needsConsent()) {
            DataGrailConsent.getInstance().showBanner(this) { preferences ->
                if (preferences != null) {
                    applyConsent(preferences)
                }
            }
        }
    }
}
```

### 3. Listen for Changes

```kotlin
DataGrailConsent.getInstance().onConsentChanged { preferences ->
    updateTracking(preferences)
}
```

### 4. Check Category Status

```kotlin
if (DataGrailConsent.getInstance().isCategoryEnabled("category_marketing")) {
    enableMarketingTracking()
}
```

## API Reference

### Initialization

| Method | Description |
|--------|-------------|
| `initialize(context, configUrl, callback)` | Initialize SDK with config URL |
| `needsConsent() -> Boolean` | Check if user needs to provide consent |

### Banner Display

| Method | Description |
|--------|-------------|
| `showBanner(activity, callback)` | Display the consent dialog |

### Consent Management

| Method | Description |
|--------|-------------|
| `getUserPreferences() -> ConsentPreferences?` | Get saved preferences |
| `getCategories() -> ConsentPreferences?` | Get categories with current consent state |
| `savePreferences(preferences, callback)` | Save consent preferences |
| `acceptAll(callback)` | Accept all categories |
| `rejectAll(callback)` | Reject all non-essential categories |
| `isCategoryEnabled(gtmKey) -> Boolean` | Check if a category is enabled |
| `onConsentChanged(listener)` | Listen for consent changes |
| `reset()` | Clear all stored consent data |

## Requirements

- Android 6.0 (API 23) or higher
- Build tooling: JDK 17+ and Android Gradle Plugin 8.0+ (library is compiled with JVM/Java 17 bytecode)
- Calling code: Kotlin 1.9+ or Java 8+ source compatibility
- AndroidX

## Java Support

The SDK is written in Kotlin but provides **full Java interoperability** through dedicated callback interfaces. Java developers can use all SDK features with clear success/failure callbacks instead of Kotlin's `Result` type.

**[→ Java Integration Guide](JAVA_INTEGRATION.md)** - Complete examples for Java applications

## Permissions

Add to your `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

## Backup Exclusion

The SDK stores consent data in EncryptedSharedPreferences (`com.datagrail.consent.prefs`). To prevent this data from being included in cloud backups or device transfers, add backup exclusion rules to your app:

**Pre-API 31** (`fullBackupContent`):
```xml
<full-backup-content>
    <exclude domain="sharedpref" path="com.datagrail.consent.prefs.xml" />
</full-backup-content>
```

**API 31+** (`dataExtractionRules`):
```xml
<data-extraction-rules>
    <cloud-backup>
        <exclude domain="sharedpref" path="com.datagrail.consent.prefs.xml" />
    </cloud-backup>
    <device-transfer>
        <exclude domain="sharedpref" path="com.datagrail.consent.prefs.xml" />
    </device-transfer>
</data-extraction-rules>
```

See the demo app for a complete example.

## Dark Mode & Customization

The SDK automatically supports both light and dark modes, adapting to the user's system theme preference. The consent banner colors will automatically adjust for optimal readability in both modes.

### Customizing Colors

You can customize the banner colors to match your app's branding by overriding the SDK's color resources in your app's `res/values/colors.xml` and `res/values-night/colors.xml` files.

**[→ Dark Mode & Customization Guide](DARK_MODE_CUSTOMIZATION.md)** - Complete customization documentation with examples

Available customizable colors:
- `consent_background` - Dialog/banner background
- `consent_text_primary` - Main text and headings
- `consent_text_secondary` - Secondary text and descriptions
- `consent_button_background` - Button background color
- `consent_button_text` - Button text color
- `consent_link` - Link text color
- `consent_surface` - Category cards and surface elements

## ProGuard / R8

If you have ProGuard/R8 enabled, add these rules:

```proguard
-keep class com.datagrail.consent.** { *; }
-keepclassmembers class com.datagrail.consent.models.** { *; }
```

## Demo App

A complete demo app is included in [`demo/`](demo/) showcasing SDK initialization, banner display, accept/reject flows, and consent change callbacks.

```bash
./gradlew :demo:assembleDebug :demo:installDebug
```

## Development

```bash
# Run tests
./gradlew :library:test

# Lint check
./gradlew :library:ktlintCheck

# Build release AAR
./gradlew :library:assembleRelease
```

## Android TV / Fire TV

The SDK runs on Android TV and Fire TV. TV support is automatic: the same
artifact and the same `showBanner(...)` entry point are used — when the SDK
detects a TV device (`PackageManager.FEATURE_LEANBACK`) it presents a
full-screen, D-pad-navigable banner (`BannerDialogTV`) instead of the phone
banner. No separate dependency or API is required for the basic banner.

### Cross-device QR pairing (CTV)

Typing on a TV remote is painful, so the SDK can show a **QR code** the viewer
scans with their phone to manage granular consent. The phone writes consent to
the Universal Consent API; the TV polls and adopts the change automatically.

```kotlin
// TV-only. Shows the D-pad banner with a QR code and starts polling.
DataGrailConsent.getInstance().showBannerWithQRPairing(
    activity = this,
    publicBaseUrl = "https://consent.example.com", // base the PHONE opens from the QR
    configUrl = "https://consent.example.com/tv/phone-config.json", // phone page config
    userIdentifier = null,        // defaults to ANDROID_ID
    apiBaseUrl = null,            // where the TV polls; defaults to https://<config.privacyDomain>
) { preferences ->
    // preferences != null  -> adopted from the phone (also fires onConsentChanged)
    // preferences == null  -> dismissed or timed out (falls back to the D-pad banner)
}
```

How it works (no pairing-session subsystem): the QR encodes
`{publicBaseUrl}/tv?customer_id=…&user_hash=…&config_url=…`. The `user_hash` is
`SHA-256("{customerId}:{consentProjectId}:{deviceId}")` and is byte-identical
across web/iOS/Android. The TV captures a baseline `updated_at` on its first
poll and only completes when a **new** write arrives, so a pre-existing record
never auto-dismisses the banner.

> **QR generation** uses `com.google.zxing:core` (pure-Java). This is the one
> external dependency the TV path adds; Android has no built-in QR encoder.

### Manifest

Declare TV support as optional so the app stays installable on phones:

```xml
<uses-feature android:name="android.software.leanback" android:required="false" />
<uses-feature android:name="android.hardware.touchscreen" android:required="false" />
```

Add a `LEANBACK_LAUNCHER` intent-filter to whichever activity should appear on
the TV home screen (see the `:demo` module's `TvDemoActivity`).

### Running the TV demo against a local test server (emulator)

The `:demo` module ships a `TvDemoActivity` that pairs against the local
Universal Consent test server. Two configs are used, mirroring real usage:
`demo-config.json` (full SDK schema, has `privacyDomain`) for `initialize(...)`,
and `sample-config.json` (category toggles only) for the phone QR page.

1. Run the test server and expose it over HTTPS (the SDK requires HTTPS). On a
   LAN, a real phone resolves your dev host and scans the QR directly.
2. **Emulator networking.** A stock Android TV emulator image is a locked
   production build (`adb root` is refused), and its `127.0.0.1` is the emulator,
   not your Mac. Bridge the TV→host leg with a non-privileged reverse and point
   the SDK config + `apiBaseUrl` at that port:
   ```bash
   adb reverse tcp:9443 tcp:8443    # emulator localhost:9443 -> host:8443 (your HTTPS server)
   # demo SDK config + apiBaseUrl -> https://<your-host>:9443 ; QR publicBaseUrl stays :443 for the phone
   ```
3. **Dev TLS trust.** If the server uses a local CA (e.g. mkcert), the emulator
   won't trust it. The demo bundles the CA and trusts it for the dev host via a
   `network_security_config` (`res/xml/network_security_config.xml` +
   `res/raw/mkcert_ca.crt`) — **demo-only; never ship this in a real app.** On a
   physical TV with a publicly-trusted cert, none of this is needed: set all URLs
   to plain `https://<host>` (`:443`).

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.
