package com.imi.smartedge.sidebar.panel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the shared custom-item validation gate used by the app picker.
 *
 * These tests call the real production implementation
 * ([CustomItemValidation.isValidCustom], which `AppPickerPanelView`
 * delegates to) — they do not mirror the constants or the check locally.
 * If production changes the scheme allowlist or the caps, these tests fail.
 *
 * Coverage: the scheme allowlist (`intent:`, `http://`, `https://`) and the
 * length caps (title 64, content 2048) that defend against an attacker
 * pasting a `file://` / `javascript:` URI into a custom widget.
 */
class IsValidCustomTest {

    private fun isValid(title: String, content: String): Boolean =
        CustomItemValidation.isValidCustom(title, content)

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

    @Test fun `uppercase INTENT scheme is accepted on a Turkish locale`() {
        // The old code used locale-sensitive lowercase(): in a Turkish
        // locale "INTENT:..." lowercases to "ıntent:...", which matches
        // no allowlisted scheme and wrongly rejects a valid URI. The
        // production check uses Locale.ROOT, so it must accept it here.
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale("tr", "TR"))
            assertTrue(isValid("Maps", "INTENT:#Intent;component=com.example/.Main;end"))
            assertTrue(isValid("Search", "HTTPS://EXAMPLE.COM"))
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }

    @Test fun `blank title is rejected`() {
        assertFalse(isValid("", "https://example.com"))
        assertFalse(isValid("   ", "https://example.com"))
    }
}
