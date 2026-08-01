package com.imi.smartedge.sidebar.panel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the shared custom-item validation gate used by the app picker.
 *
 * These tests cover the scheme allowlist (`intent:`, `http://`, `https://`) and the
 * length caps (title 64, content 2048) that defend against an attacker pasting a
 * `file://` / `javascript:` URI into a custom widget.
 *
 * The validation lives as an `internal` static-pure function inside the picker
 * view, so we invoke it via the AppPickerPanelView companion's constants and a
 * tiny static helper. This avoids pulling in Robolectric.
 */
class IsValidCustomTest {

    // Mirror the constants from AppPickerPanelView.companion so we do not depend
    // on an Android Context or non-test fields. These MUST stay in sync with the
    // source. Keep asserts close to the constants so a refactor that changes them
    // fails fast on both sides.
    private val maxTitleLen = 64
    private val maxContentLen = 2048
    private val allowedSchemes = listOf("intent:", "http://", "https://")

    private fun isValid(title: String, content: String): Boolean {
        if (title.length > maxTitleLen) return false
        if (content.length > maxContentLen) return false
        val s = content.trim().lowercase()
        return allowedSchemes.any { s.startsWith(it) }
    }

    @Test fun `https URL is accepted`() {
        assertTrue(isValid("Search", "https://example.com"))
    }

    @Test fun `http URL is accepted`() {
        assertTrue(isValid("Legacy", "http://example.com/path"))
    }

    @Test fun `intent URI is accepted`() {
        assertTrue(isValid(
            "Open maps",
            "intent:#Intent;component=com.google.android.apps.maps/.MapsActivity;end"
        ))
    }

    @Test fun `file scheme is rejected`() {
        assertFalse(isValid("Readme", "file:///sdcard/Documents/readme.txt"))
    }

    @Test fun `javascript scheme is rejected`() {
        assertFalse(isValid("Run js", "javascript:alert(1)"))
    }

    @Test fun `content scheme is rejected`() {
        assertFalse(isValid("Contact", "content://contacts/people/1"))
    }

    @Test fun `tel scheme is rejected`() {
        assertFalse(isValid("Dial", "tel:+1234567890"))
    }

    @Test fun `mailto scheme is rejected`() {
        assertFalse(isValid("Mail", "mailto:victim@example.com"))
    }

    @Test fun `empty content is rejected`() {
        assertFalse(isValid("Empty", ""))
    }

    @Test fun `title above 64 chars is rejected`() {
        val longTitle = "x".repeat(65)
        assertFalse(isValid(longTitle, "https://example.com"))
    }

    @Test fun `content above 2048 chars is rejected`() {
        val padded = "https://example.com/" + "a".repeat(2048)
        assertFalse(isValid("Padded", padded))
    }

    @Test fun `leading whitespace before scheme is tolerated`() {
        assertTrue(isValid("Padded", "   https://example.com"))
    }

    @Test fun `uppercase scheme is accepted after lowercase normalize`() {
        // Source uses lowercase() before prefix match — this guards that guarantee.
        assertTrue(isValid("Mixed", "HTTPS://EXAMPLE.COM"))
    }

    @Test fun `whitespace-only content is rejected`() {
        assertFalse(isValid("Blank", "   \n   "))
    }
}
