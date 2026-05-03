package com.datagrail.consent.ui

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for BannerDialog rich text rendering logic.
 *
 * These tests verify the HTML detection heuristic and the content description
 * sanitization that strips HTML tags for accessibility readers.
 * Actual Html.fromHtml rendering is an Android framework concern tested
 * via instrumentation tests.
 */
class BannerDialogRichTextTest {
    /**
     * Mirrors the HTML detection logic in BannerDialog.renderRichText().
     * Returns true if the text contains actual HTML tags.
     */
    private fun containsHtml(text: String): Boolean {
        return text.contains(Regex("<[a-zA-Z][^>]*>"))
    }

    /**
     * Mirrors the content description sanitization in BannerDialog.createTextView().
     * Strips HTML tags for screen reader accessibility.
     */
    private fun stripHtmlForAccessibility(text: String): String {
        return text.replace(Regex("<[^>]*>"), "")
    }

    // MARK: - HTML Detection

    @Test
    fun `plain text is not detected as HTML`() {
        assertFalse(containsHtml("We value your privacy"))
    }

    @Test
    fun `text with bold tag is detected as HTML`() {
        assertTrue(containsHtml("We <b>value</b> your privacy"))
    }

    @Test
    fun `text with anchor tag is detected as HTML`() {
        assertTrue(containsHtml("<a href=\"https://example.com\">Privacy Policy</a>"))
    }

    @Test
    fun `text with br tag is detected as HTML`() {
        assertTrue(containsHtml("Line one<br>Line two"))
    }

    @Test
    fun `text with paragraph tag is detected as HTML`() {
        assertTrue(containsHtml("<p>First paragraph</p><p>Second paragraph</p>"))
    }

    @Test
    fun `text with list tags is detected as HTML`() {
        assertTrue(containsHtml("<ul><li>Item one</li><li>Item two</li></ul>"))
    }

    @Test
    fun `text with emphasis tag is detected as HTML`() {
        assertTrue(containsHtml("This is <em>important</em>"))
    }

    @Test
    fun `text with strong tag is detected as HTML`() {
        assertTrue(containsHtml("This is <strong>very important</strong>"))
    }

    @Test
    fun `empty string is not detected as HTML`() {
        assertFalse(containsHtml(""))
    }

    @Test
    fun `text with less-than in math expression is not detected as HTML`() {
        assertFalse(containsHtml("Value must be < 100"))
    }

    @Test
    fun `text with less-than followed by space is not detected as HTML`() {
        assertFalse(containsHtml("if x < y then z"))
    }

    @Test
    fun `text with less-than followed by digit is not detected as HTML`() {
        assertFalse(containsHtml("count <3"))
    }

    // MARK: - Content Description Sanitization

    @Test
    fun `plain text passes through unchanged`() {
        val text = "We value your privacy"
        assertEquals("We value your privacy", stripHtmlForAccessibility(text))
    }

    @Test
    fun `bold tags are stripped`() {
        val text = "We <b>value</b> your privacy"
        assertEquals("We value your privacy", stripHtmlForAccessibility(text))
    }

    @Test
    fun `anchor tags with attributes are stripped`() {
        val text = "Read our <a href=\"https://example.com\">Privacy Policy</a>"
        assertEquals("Read our Privacy Policy", stripHtmlForAccessibility(text))
    }

    @Test
    fun `br tags are stripped`() {
        val text = "Line one<br>Line two"
        assertEquals("Line oneLine two", stripHtmlForAccessibility(text))
    }

    @Test
    fun `self-closing br tags are stripped`() {
        val text = "Line one<br/>Line two"
        assertEquals("Line oneLine two", stripHtmlForAccessibility(text))
    }

    @Test
    fun `nested tags are stripped`() {
        val text = "<p>This is <strong>very <em>important</em></strong> text</p>"
        assertEquals("This is very important text", stripHtmlForAccessibility(text))
    }

    @Test
    fun `list tags are stripped`() {
        val text = "<ul><li>Item one</li><li>Item two</li></ul>"
        assertEquals("Item oneItem two", stripHtmlForAccessibility(text))
    }

    @Test
    fun `multiple paragraphs are stripped`() {
        val text = "<p>First paragraph</p><p>Second paragraph</p>"
        assertEquals("First paragraphSecond paragraph", stripHtmlForAccessibility(text))
    }

    @Test
    fun `underline and italic tags are stripped`() {
        val text = "This is <u>underlined</u> and <i>italic</i>"
        assertEquals("This is underlined and italic", stripHtmlForAccessibility(text))
    }

    @Test
    fun `empty string returns empty`() {
        assertEquals("", stripHtmlForAccessibility(""))
    }
}
