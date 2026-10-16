package com.datagrail.consent.models

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * The point of modeling the ad-tracking signal as four states rather than a boolean is that only
 * two of them are opt-outs. These tests pin that mapping, because collapsing it is the exact
 * mistake that opts out every user on a Play-less device, or grants marketing consent to everyone
 * whose advertising ID happens to be readable.
 */
class TrackingSignalTest {
    @Test
    fun `denied and restricted suppress`() {
        assertTrue(TrackingSignal.DENIED.suppressesNonEssential)
        assertTrue(TrackingSignal.RESTRICTED.suppressesNonEssential)
    }

    @Test
    fun `not determined does not suppress`() {
        // An unreadable signal is not a refusal. Treating it as one would opt out every user on a
        // device without Google Play, or any user whose lookup transiently failed.
        assertFalse(TrackingSignal.NOT_DETERMINED.suppressesNonEssential)
    }

    @Test
    fun `authorized does not suppress`() {
        assertFalse(TrackingSignal.AUTHORIZED.suppressesNonEssential)
    }

    @Test
    fun `reader degrades to not determined without a context`() {
        assertEquals(TrackingSignal.NOT_DETERMINED, TrackingSignalReader.read(null))
    }

    @Test
    fun `reader degrades to not determined when play services is absent`() {
        // play-services-ads-identifier is deliberately not a dependency of this library, so
        // AdvertisingIdClient is absent from this classpath — the same situation as an app that
        // ships without Play Services. It must degrade, not throw, and must not report an opt-out.
        val context = mock<Context>()

        assertEquals(TrackingSignal.NOT_DETERMINED, TrackingSignalReader.read(context))
    }
}
