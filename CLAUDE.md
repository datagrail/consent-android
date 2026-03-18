# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

DataGrail Consent Android SDK — a native Android library for displaying consent banners and managing user privacy preferences. Published to Maven Central as `io.datagrail:consent`. Written in Kotlin with full Java interoperability.

## Build Commands

```bash
# Run unit tests
./gradlew :library:test

# Lint (ktlint)
./gradlew :library:ktlintCheck

# Auto-fix lint issues
./gradlew :library:ktlintFormat

# Build release AAR
./gradlew :library:assembleRelease

# Build and install demo app
./gradlew :demo:assembleDebug :demo:installDebug
```

Requires JDK 17. CI runs test, lint, library build, and demo build as separate jobs.

## Architecture

Two Gradle modules: `library` (the SDK) and `demo` (sample app that depends on `library`).

### Library module (`library/src/main/kotlin/com/datagrail/consent/`)

- **`DataGrailConsent`** — Public singleton entry point (`getInstance()`). All public API lives here. Every async method has two overloads: a Kotlin-idiomatic `(Result<T>) -> Unit` lambda version and a Java-friendly callback interface version (e.g., `ConsentCallback`, `PreferencesCallback`).
- **`ConsentManager`** — Internal coordinator between storage, config, and network. Holds `currentConfig` state. Determines if banner should display (`needsConsent()`), manages essential vs. non-essential categories.
- **`models/ConsentConfig.kt`** — Kotlinx Serialization data classes for the remote JSON config. The config has a nested structure: `ConsentConfig` → `Layout` → `ConsentLayer` (map keyed by layer ID) → `ConsentLayerElement` → `ConsentLayerCategory`. Includes a custom serializer (`TrackingDetailsLinkTranslationsSerializer`) that handles both array and dictionary JSON formats.
- **`models/ConsentPreferences.kt`** — User's consent choices (`CategoryConsent` per GTM key).
- **`network/`** — `NetworkClient` (HTTP), `ConfigService` (fetches/caches config), `ConsentService` (posts consent decisions, retry queue).
- **`storage/ConsentStorage`** — Persists preferences and config version in `EncryptedSharedPreferences`.
- **`ui/BannerDialog`** — `DialogFragment` rendering the consent banner. Supports `MODAL` and `FULL_SCREEN` display styles. Uses view binding. Supports light/dark mode via color resources (`res/values/colors.xml` and `res/values-night/colors.xml`).

### Key patterns

- Singleton access: `DataGrailConsent.getInstance()`
- Async operations use `CoroutineScope(Dispatchers.Main)` with suspend functions in the manager/service layer
- Config is fetched from a remote HTTPS URL and deserialized with kotlinx.serialization
- Consent state is versioned — banner re-shows when remote config `version` changes
- Failed API calls are queued for retry via `ConsentService`

## Testing

Tests are in `library/src/test/` using JUnit 4 + Mockito-Kotlin + kotlinx-coroutines-test. Run a single test class:

```bash
./gradlew :library:test --tests "com.datagrail.consent.ConsentManagerTests"
```
