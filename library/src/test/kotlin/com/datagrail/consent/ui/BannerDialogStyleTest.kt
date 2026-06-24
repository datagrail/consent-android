package com.datagrail.consent.ui

import android.graphics.Typeface
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for BannerDialog style parameter handling.
 *
 * Tests verify that dg-title, dg-header, and dg-main-content-explanation style values
 * map to the correct text sizes and typeface styles from BannerTextStyleConfig.
 * These mirror the style resolution logic in BannerDialog.textSizeAndStyleFor().
 */
class BannerDialogStyleTest {
    /**
     * Mirrors the style resolution logic in BannerDialog.textSizeAndStyleFor().
     */
    private fun textSizeAndStyleFor(
        style: String?,
        config: BannerTextStyleConfig = BannerTextStyleConfig(),
    ): Pair<Float, Int> {
        return when (style) {
            "dg-title" -> Pair(config.titleTextSizeSp, config.titleTypefaceStyle)
            "dg-header" -> Pair(config.headerTextSizeSp, config.headerTypefaceStyle)
            else -> Pair(config.bodyTextSizeSp, config.bodyTypefaceStyle)
        }
    }

    // MARK: - Default config sizes

    @Test
    fun `dg-title uses 22sp bold by default`() {
        val (size, style) = textSizeAndStyleFor("dg-title")
        assertEquals(22f, size)
        assertEquals(Typeface.BOLD, style)
    }

    @Test
    fun `dg-header uses 18sp bold by default`() {
        val (size, style) = textSizeAndStyleFor("dg-header")
        assertEquals(18f, size)
        assertEquals(Typeface.BOLD, style)
    }

    @Test
    fun `dg-main-content-explanation uses 16sp normal by default`() {
        val (size, style) = textSizeAndStyleFor("dg-main-content-explanation")
        assertEquals(16f, size)
        assertEquals(Typeface.NORMAL, style)
    }

    @Test
    fun `null style falls back to body (16sp normal) by default`() {
        val (size, style) = textSizeAndStyleFor(null)
        assertEquals(16f, size)
        assertEquals(Typeface.NORMAL, style)
    }

    @Test
    fun `unknown style falls back to body (16sp normal) by default`() {
        val (size, style) = textSizeAndStyleFor("dg-unknown-element")
        assertEquals(16f, size)
        assertEquals(Typeface.NORMAL, style)
    }

    // MARK: - Custom config overrides

    @Test
    fun `custom titleTextSizeSp is used for dg-title`() {
        val config = BannerTextStyleConfig(titleTextSizeSp = 30f)
        val (size, _) = textSizeAndStyleFor("dg-title", config)
        assertEquals(30f, size)
    }

    @Test
    fun `custom headerTextSizeSp is used for dg-header`() {
        val config = BannerTextStyleConfig(headerTextSizeSp = 24f)
        val (size, _) = textSizeAndStyleFor("dg-header", config)
        assertEquals(24f, size)
    }

    @Test
    fun `custom bodyTextSizeSp is used for dg-main-content-explanation`() {
        val config = BannerTextStyleConfig(bodyTextSizeSp = 14f)
        val (size, _) = textSizeAndStyleFor("dg-main-content-explanation", config)
        assertEquals(14f, size)
    }

    @Test
    fun `custom bodyTextSizeSp is used for null style`() {
        val config = BannerTextStyleConfig(bodyTextSizeSp = 14f)
        val (size, _) = textSizeAndStyleFor(null, config)
        assertEquals(14f, size)
    }

    @Test
    fun `custom titleTypefaceStyle is used for dg-title`() {
        val config = BannerTextStyleConfig(titleTypefaceStyle = Typeface.BOLD_ITALIC)
        val (_, style) = textSizeAndStyleFor("dg-title", config)
        assertEquals(Typeface.BOLD_ITALIC, style)
    }

    @Test
    fun `custom headerTypefaceStyle is used for dg-header`() {
        val config = BannerTextStyleConfig(headerTypefaceStyle = Typeface.ITALIC)
        val (_, style) = textSizeAndStyleFor("dg-header", config)
        assertEquals(Typeface.ITALIC, style)
    }

    @Test
    fun `custom bodyTypefaceStyle is used for body elements`() {
        val config = BannerTextStyleConfig(bodyTypefaceStyle = Typeface.BOLD)
        val (_, style) = textSizeAndStyleFor(null, config)
        assertEquals(Typeface.BOLD, style)
    }

    // MARK: - BannerTextStyleConfig defaults

    @Test
    fun `BannerTextStyleConfig default values match expected sizes`() {
        val config = BannerTextStyleConfig()
        assertEquals(22f, config.titleTextSizeSp)
        assertEquals(18f, config.headerTextSizeSp)
        assertEquals(16f, config.bodyTextSizeSp)
    }

    @Test
    fun `BannerTextStyleConfig default typeface styles are correct`() {
        val config = BannerTextStyleConfig()
        assertEquals(Typeface.BOLD, config.titleTypefaceStyle)
        assertEquals(Typeface.BOLD, config.headerTypefaceStyle)
        assertEquals(Typeface.NORMAL, config.bodyTypefaceStyle)
    }
}
