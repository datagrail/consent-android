# Java Interoperability Improvements

## Overview

This document describes the changes made to improve Java interoperability for the DataGrail Consent Android SDK.

## Problem Statement

The SDK was originally designed with Kotlin in mind, using:
- `Result<Unit>` return types in callbacks
- Lambda-based callbacks
- Nullable types (`ConsentPreferences?`)

These patterns work well in Kotlin but are difficult to use from Java:
1. Kotlin's `Result` type is an inline value class that requires special handling from Java
2. `Unit` provides no meaningful success information
3. Java developers cannot easily determine success/failure without understanding Kotlin's Result API
4. Lambda callbacks are less idiomatic in Java compared to callback interfaces

## Solution

We added Java-friendly overloads for all SDK methods while maintaining **100% backward compatibility** with existing Kotlin code.

### New Files

1. **`ConsentResult.kt`** - Java-friendly result wrapper
   - `ConsentResult<T>` sealed class with `Success` and `Failure` cases
   - `ConsentResponse` data class for operations that don't return data
   - Clear success/failure checking without requiring Result API knowledge

2. **`ConsentCallback.kt`** - Java-friendly callback interfaces
   - `ConsentCallback` - For operations with success/failure (initialize, save, accept, reject)
   - `PreferencesCallback` - For banner display (saved vs dismissed)
   - `ConsentChangeListener` - For consent change notifications
   - `RetryCallback` - For retry operations

3. **`JAVA_INTEGRATION.md`** - Complete Java integration guide
   - Quick start examples
   - Complete API usage from Java
   - Error handling patterns
   - Full working examples

4. **`JavaExample.java`** - Concrete Java example code
   - Application initialization
   - Banner display
   - Category management
   - Preference checking

### Modified Files

**`DataGrailConsent.kt`** - Added Java-friendly overloads:
- Each method now has TWO versions:
  - Original Kotlin version (lambda-based, uses `Result<Unit>`)
  - New Java version (interface-based, uses callback interfaces)
- All new methods marked with `@JvmOverloads` for flexibility
- Zero breaking changes to existing code

## API Changes

### Before (Kotlin only)

```kotlin
DataGrailConsent.getInstance().initialize(context, url) { result ->
    result.fold(
        onSuccess = { /* success */ },
        onFailure = { error -> /* failure */ }
    )
}
```

### After (Java-friendly)

```java
DataGrailConsent.getInstance().initialize(context, url, new ConsentCallback() {
    @Override
    public void onSuccess() {
        // Clear success indication
    }

    @Override
    public void onFailure(ConsentException error) {
        // Clear failure with specific error type
    }
});
```

### After (Kotlin still works exactly the same)

```kotlin
DataGrailConsent.getInstance().initialize(context, url) { result ->
    result.fold(
        onSuccess = { /* success */ },
        onFailure = { error -> /* failure */ }
    )
}
```

## Methods with Java Overloads

All of these methods now have Java-friendly versions:

1. `initialize()` - Uses `ConsentCallback`
2. `savePreferences()` - Uses `ConsentCallback`
3. `acceptAll()` - Uses `ConsentCallback`
4. `rejectAll()` - Uses `ConsentCallback`
5. `trackBannerShown()` - Uses `ConsentCallback`
6. `showBanner()` - Uses `PreferencesCallback`
7. `onConsentChanged()` - Uses `ConsentChangeListener`
8. `retryPendingRequests()` - Uses `RetryCallback`

## Benefits

### For Java Developers
- ✅ Clear success/failure states without Kotlin Result API
- ✅ Familiar callback interface pattern
- ✅ Compile-time type safety
- ✅ IDE auto-completion works perfectly
- ✅ No need to understand Kotlin conventions

### For Kotlin Developers
- ✅ Zero breaking changes
- ✅ Can continue using lambda syntax
- ✅ Can continue using Result types
- ✅ All existing code works unchanged

### For the SDK
- ✅ Broader adoption (Java + Kotlin apps)
- ✅ Better developer experience
- ✅ Clear documentation for both languages
- ✅ Maintains idiomatic patterns for each language

## Testing Recommendations

1. **Unit Tests**: Verify all Java callback interfaces work correctly
2. **Integration Tests**: Test from actual Java code (see `JavaExample.java`)
3. **Regression Tests**: Ensure existing Kotlin code still works
4. **Documentation**: Verify all examples compile and run

## Migration Guide (For Java Users)

If you were previously trying to use the Kotlin API from Java:

### Old (Difficult)
```java
// This was hard to use from Java
DataGrailConsent.getInstance().initialize(context, url, result -> {
    // How do you check success/failure?
    // Result API is awkward from Java
});
```

### New (Easy)
```java
// This is natural in Java
DataGrailConsent.getInstance().initialize(context, url, new ConsentCallback() {
    @Override
    public void onSuccess() {
        // Clear success path
    }

    @Override
    public void onFailure(ConsentException error) {
        // Clear error handling
    }
});
```

## Compatibility

- ✅ **Kotlin**: 100% backward compatible
- ✅ **Java 8+**: Full support via callback interfaces
- ✅ **ProGuard/R8**: Same rules apply
- ✅ **AndroidX**: No changes required

## Documentation Updates

1. **README.md** - Added Java support section with link to Java guide
2. **JAVA_INTEGRATION.md** - Complete Java integration guide
3. **JavaExample.java** - Working Java example code
4. **JAVA_INTEROP_CHANGES.md** - This document

## Future Considerations

1. Consider adding coroutine-based suspend functions for modern Kotlin
2. Consider Kotlin Flow for consent change streams
3. Consider RxJava observables if there's demand
4. Monitor Java usage patterns and add convenience methods as needed
