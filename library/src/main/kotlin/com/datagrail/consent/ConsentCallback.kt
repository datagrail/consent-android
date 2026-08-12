package com.datagrail.consent

import com.datagrail.consent.models.ConsentException
import com.datagrail.consent.models.ConsentPreferences
import com.datagrail.consent.models.UniversalConsentRecord
import com.datagrail.consent.models.UniversalConsentSignature

/**
 * Java Interoperability Callback Interfaces
 *
 * These interfaces provide Java-friendly alternatives to Kotlin's lambda-based callbacks.
 * Kotlin developers should continue using the lambda-based methods (e.g., `callback: (Result<Unit>) -> Unit`).
 * Java developers should use these interfaces for clearer, more idiomatic code.
 *
 * Why? Kotlin's `Result<Unit>` type requires special handling from Java and is not idiomatic.
 * These interfaces provide explicit `onSuccess()` and `onFailure()` methods that Java developers expect.
 */

/**
 * Java-friendly callback interface for consent operations
 * Use this when calling from Java instead of lambda callbacks
 *
 * Example (Java):
 * ```java
 * DataGrailConsent.getInstance().initialize(context, configUrl, new ConsentCallback() {
 *     @Override
 *     public void onSuccess() {
 *         // Handle success
 *     }
 *
 *     @Override
 *     public void onFailure(ConsentException error) {
 *         // Handle failure
 *     }
 * });
 * ```
 */
interface ConsentCallback {
    /**
     * Called when the operation succeeds
     */
    fun onSuccess()

    /**
     * Called when the operation fails
     * @param error The error that occurred
     */
    fun onFailure(error: ConsentException)
}

/**
 * Java-friendly callback interface for operations that return preferences
 * Use this when calling showBanner() from Java
 *
 * Example (Java):
 * ```java
 * DataGrailConsent.getInstance().showBanner(activity, BannerDisplayStyle.MODAL, new PreferencesCallback() {
 *     @Override
 *     public void onPreferencesSaved(ConsentPreferences preferences) {
 *         // User saved preferences
 *     }
 *
 *     @Override
 *     public void onDismissed() {
 *         // User dismissed without saving
 *     }
 * });
 * ```
 */
interface PreferencesCallback {
    /**
     * Called when user saves consent preferences
     * @param preferences The saved preferences
     */
    fun onPreferencesSaved(preferences: ConsentPreferences)

    /**
     * Called when user dismisses the banner without saving
     */
    fun onDismissed()
}

/**
 * Java-friendly callback interface for consent change listeners
 *
 * Example (Java):
 * ```java
 * DataGrailConsent.getInstance().onConsentChanged(new ConsentChangeListener() {
 *     @Override
 *     public void onConsentChanged(ConsentPreferences preferences) {
 *         // Handle consent change
 *     }
 * });
 * ```
 */
interface ConsentChangeListener {
    /**
     * Called when consent preferences change
     * @param preferences The new preferences
     */
    fun onConsentChanged(preferences: ConsentPreferences)
}

/**
 * Java-friendly callback interface for retry operations
 *
 * Example (Java):
 * ```java
 * DataGrailConsent.getInstance().retryPendingRequests(new RetryCallback() {
 *     @Override
 *     public void onRetryComplete(int successCount, int failureCount) {
 *         Log.d("Consent", "Retry: " + successCount + " succeeded, " + failureCount + " failed");
 *     }
 * });
 * ```
 */
interface RetryCallback {
    /**
     * Called when retry operation completes
     * @param successCount Number of successful retries
     * @param failureCount Number of failed retries
     */
    fun onRetryComplete(successCount: Int, failureCount: Int)
}

/**
 * Java-friendly callback interface for fetching a universal consent record.
 *
 * The record may be null when no record exists for the user, so this needs its own interface
 * (the value-returning analogue of [ConsentCallback]).
 *
 * Example (Java):
 * ```java
 * DataGrailConsent.getInstance().fetchUniversalConsent(identifier, apiKey, new UniversalConsentCallback() {
 *     @Override
 *     public void onSuccess(UniversalConsentRecord record) {
 *         // record may be null if none exists
 *     }
 *
 *     @Override
 *     public void onFailure(ConsentException error) {
 *         // Handle failure
 *     }
 * });
 * ```
 */
interface UniversalConsentCallback {
    /**
     * Called when the record is fetched successfully.
     * @param record The GPC-reconciled record, or null if none exists.
     */
    fun onSuccess(record: UniversalConsentRecord?)

    /**
     * Called when the operation fails
     * @param error The error that occurred
     */
    fun onFailure(error: ConsentException)
}

/**
 * Java-friendly callback interface for rehydrating local consent state from the universal consent
 * store.
 *
 * Distinct from [ConsentCallback] because the outcome is not just success/failure: a successful
 * read can still find no record, and "rehydrated" versus "no record stored" are different enough
 * that the caller usually branches on them (the latter means the banner still needs to show).
 *
 * Example (Java):
 * ```java
 * DataGrailConsent.getInstance().rehydrateFromUniversalConsent(identifier, apiKey, new RehydrateCallback() {
 *     @Override
 *     public void onSuccess(boolean rehydrated) {
 *         // false means no stored record — local state untouched, banner still applies
 *     }
 *
 *     @Override
 *     public void onFailure(ConsentException error) {
 *         // Handle failure
 *     }
 * });
 * ```
 */
interface RehydrateCallback {
    /**
     * Called when the read completes.
     * @param rehydrated true when local state was rehydrated from a stored record, false on a miss.
     */
    fun onSuccess(rehydrated: Boolean)

    /**
     * Called when the operation fails
     * @param error The error that occurred
     */
    fun onFailure(error: ConsentException)
}

/**
 * Java-friendly signature provider. The universal-consent write requires a signature computed by
 * the customer's backend. The Kotlin [com.datagrail.consent.models.SignatureProvider] is a
 * `suspend` function type, which Java cannot implement without dealing with Kotlin coroutine
 * `Continuation` internals. This interface exposes the same contract with a plain result callback
 * so Java callers can invoke their backend asynchronously and hand the result back.
 *
 * Example (Java):
 * ```java
 * SignatureProviderCallback provider = (customerId, userHash, onResult) -> {
 *     // call your backend, then EXACTLY ONE of:
 *     onResult.onSignature(new UniversalConsentSignature(signature, keyId, timestamp));
 *     onResult.onFailure(new ConsentException.NetworkError("signing endpoint unreachable", e));
 * };
 * ```
 */
fun interface SignatureProviderCallback {
    /**
     * Compute a signature for the given [customerId] and [userHash], then invoke [onResult] with
     * the signature material. May be called from a background thread.
     */
    fun getSignature(
        customerId: String,
        userHash: String,
        onResult: SignatureResult,
    )
}

/**
 * Result sink handed to [SignatureProviderCallback.getSignature]. Java callers invoke exactly one
 * of [onSignature] or [onFailure] once their backend returns.
 *
 * The failure path is not optional: a universal-consent write suspends until the provider reports
 * back, so a signing request that fails with no way to say so would leave the write hung forever
 * and [DataGrailConsent.setUserIdentifier] would never invoke its callback. Calling [onFailure]
 * surfaces the error through the normal `onFailure` path instead.
 */
interface SignatureResult {
    /** Report the signature material returned by your backend. */
    fun onSignature(signature: UniversalConsentSignature)

    /**
     * Report that signing failed (network error, non-2xx from your signing endpoint, missing
     * session, etc.). The universal-consent write fails with this error.
     */
    fun onFailure(error: ConsentException)
}
