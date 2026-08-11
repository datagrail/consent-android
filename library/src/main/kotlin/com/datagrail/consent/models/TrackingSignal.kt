package com.datagrail.consent.models

import android.content.Context
import com.datagrail.consent.utils.ConsentLogger

/**
 * The device's live opt-out signal, as read from the operating system.
 *
 * **There is no GPC on Android.** GPC (`navigator.globalPrivacyControl` / `Sec-GPC`) is a
 * web-browser signal; a native app has no equivalent. Android's OS-level signal is the
 * advertising-ID opt-out ("Delete advertising ID" / limit ad tracking), which is narrower in
 * scope than GPC — it concerns ad personalization and the GAID, not do-not-sell — and, unlike
 * GPC, may be unreadable on a given device. Modeling it as `gpc: Boolean` erased both facts:
 * "the user opted out of ad tracking" and "we could not determine anything" collapsed into the
 * same `false`, and only one of them is a signal at all.
 *
 * The state names mirror iOS's App Tracking Transparency vocabulary so the two SDKs and the
 * React Native wrapper describe the same four cases with the same words, even though the
 * underlying platform mechanisms differ.
 *
 * A live signal is not a stored choice. It belongs to this device, is re-read per session, and
 * is never something the user picked in a consent banner.
 */
enum class TrackingSignal {
    /**
     * The signal could not be determined — Play Services is absent, unavailable, or the lookup
     * failed. **Not** an opt-out: an unreadable signal is not a refusal, and treating it as one
     * would opt out every user on a device without Google Play.
     */
    NOT_DETERMINED,

    /**
     * Tracking is restricted at the device level. Included for parity with iOS, where MDM or
     * parental controls produce this state; Android does not currently report it distinctly.
     * Treated as an opt-out.
     */
    RESTRICTED,

    /** The user opted out of ad personalization, or the advertising ID has been deleted. */
    DENIED,

    /**
     * Ad tracking is permitted. This is permission to track — it is NOT consent to marketing
     * categories, and it never turns a stored opt-out into an opt-in. Signals may only suppress.
     */
    AUTHORIZED,

    ;

    /**
     * Whether this signal asserts an opt-out, i.e. whether it must suppress non-essential
     * categories.
     *
     * [AUTHORIZED] does not suppress, but it also does not grant. [NOT_DETERMINED] deliberately
     * does not suppress: an unreadable signal says nothing about what the user wants.
     */
    val suppressesNonEssential: Boolean
        get() =
            when (this) {
                DENIED, RESTRICTED -> true
                AUTHORIZED, NOT_DETERMINED -> false
            }
}

/**
 * Reads the device's live tracking signal from the OS.
 *
 * The SDK reads this itself so integrators do not have to: passing the wrong value — or leaving
 * it at `false` — silently disables the suppression the signal exists to enforce.
 *
 * `AdvertisingIdClient` lives in `play-services-ads-identifier`, which this library deliberately
 * does not depend on: forcing Play Services onto every consumer (including apps shipping to
 * Play-less devices) to read one boolean is not a trade we want to make on their behalf. The
 * lookup therefore goes through reflection and degrades to [TrackingSignal.NOT_DETERMINED] when
 * the class is absent. Apps that already ship the dependency get the real signal for free.
 */
object TrackingSignalReader {
    private const val ADVERTISING_ID_CLIENT = "com.google.android.gms.ads.identifier.AdvertisingIdClient"

    /** A deleted advertising ID is reported as all zeroes rather than as an error. */
    private const val ZEROED_AD_ID = "00000000-0000-0000-0000-000000000000"

    /**
     * Read the current ad-tracking signal.
     *
     * **Blocking.** `getAdvertisingIdInfo` performs a binder call and must not run on the main
     * thread; call this from `Dispatchers.IO` (the SDK does).
     *
     * @param context Any context; only the application context is retained by the lookup.
     * @return the signal, or [TrackingSignal.NOT_DETERMINED] when it cannot be determined.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun read(context: Context?): TrackingSignal {
        if (context == null) return TrackingSignal.NOT_DETERMINED

        return try {
            val clientClass = Class.forName(ADVERTISING_ID_CLIENT)
            val info =
                clientClass
                    .getMethod("getAdvertisingIdInfo", Context::class.java)
                    .invoke(null, context)
                    ?: return TrackingSignal.NOT_DETERMINED

            val limitAdTracking =
                info.javaClass.getMethod("isLimitAdTrackingEnabled").invoke(info) as? Boolean ?: false
            val adId = info.javaClass.getMethod("getId").invoke(info) as? String

            when {
                limitAdTracking -> TrackingSignal.DENIED
                // Newer Android versions zero the ID instead of setting the limit flag, so the
                // flag alone is not sufficient to detect an opt-out.
                adId == ZEROED_AD_ID -> TrackingSignal.DENIED
                adId.isNullOrEmpty() -> TrackingSignal.NOT_DETERMINED
                else -> TrackingSignal.AUTHORIZED
            }
        } catch (e: ClassNotFoundException) {
            // The app does not ship play-services-ads-identifier. Expected, not an error.
            TrackingSignal.NOT_DETERMINED
        } catch (e: Exception) {
            // Play Services missing/repairable, IO failure, or a reflection mismatch after a
            // Play Services API change. None of these tell us the user opted out, so the signal
            // stays undetermined rather than becoming a refusal the user never expressed.
            ConsentLogger.d("Could not read the device ad-tracking signal: ${e.message}")
            TrackingSignal.NOT_DETERMINED
        }
    }
}
