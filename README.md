# DataGrail Consent Android SDK

[![CI](https://github.com/datagrail/consent-android/actions/workflows/ci.yml/badge.svg)](https://github.com/datagrail/consent-android/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.datagrail/consent)](https://central.sonatype.com/artifact/io.datagrail/consent)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Min SDK](https://img.shields.io/badge/minSdk-21-green.svg)](https://developer.android.com/about/versions/lollipop)

Native Android SDK for displaying consent banners and managing user privacy preferences, powered by [DataGrail](https://www.datagrail.io/).

## Installation

Add the dependency to your app's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("io.datagrail:consent:1.0.0")
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

- Android 5.0 (API 21) or higher
- Kotlin 1.9+ or Java 8+
- AndroidX

## Java Support

The SDK is written in Kotlin but provides **full Java interoperability** through dedicated callback interfaces. Java developers can use all SDK features with clear success/failure callbacks instead of Kotlin's `Result` type.

**[→ Java Integration Guide](JAVA_INTEGRATION.md)** - Complete examples for Java applications

## Permissions

Add to your `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

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

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.
