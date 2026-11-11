package com.imi.smartedge.sidebar.panel

/**
 * Pure validation gate for user-created custom sidebar items (bare URLs
 * and raw `intent:#Intent;...` URIs).
 *
 * Extracted from `AppPickerPanelView` so it is unit-testable on the JVM
 * (see IsValidCustomTest) — the view delegates to [isValidCustom].
 * No Android dependency.
 *
 * The scheme allowlist (`intent:`, `http://`, `https://`) and the
 * length caps (title 64, content 2048) defend against an attacker
 * pasting a `file://` / `javascript:` URI into a custom item.
 */
object CustomItemValidation {

    /** Max custom-item title length. */
    const val MAX_TITLE_LEN = 64

    /** Max custom-item content length. */
    const val MAX_CONTENT_LEN = 2048

    /** URI schemes the content may use. Anything else is rejected on save. */
    val ALLOWED_SCHEMES = listOf("intent:", "http://", "https://")

    /**
     * Scheme + length validation for a custom URL/intent the user is editing.
     * Returns true if the inputs are acceptable to persist.
     *
     * The scheme check lowercases with [Locale.ROOT]: in a Turkish locale
     * `"INTENT:..."`.lowercase() is `"ıntent:..."`, which would match no
     * allowlisted scheme and wrongly reject a valid uppercase `intent:` URI.
     */
    fun isValidCustom(title: String, content: String): Boolean {
        if (title.isBlank()) return false
        if (content.isBlank()) return false
        if (title.length > MAX_TITLE_LEN) return false
        if (content.length > MAX_CONTENT_LEN) return false
        val s = content.trim().lowercase(java.util.Locale.ROOT)
        return ALLOWED_SCHEMES.any { s.startsWith(it) }
    }
}
