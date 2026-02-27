package com.datagrail.consent

import com.datagrail.consent.models.ConsentException
import com.datagrail.consent.models.ConsentPreferences
import com.datagrail.consent.models.ConsentResponse

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
