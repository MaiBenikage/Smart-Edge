package com.imi.smartedge.sidebar.panel

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM unit tests for [LocaleHelper.resolveEffectiveLanguage] — the
 * precedence that decides which language the app runs in.
 *
 * Audit (4th) TEST — the locale order: the system's per-app locale wins
 * (it is what the system per-app picker sets, and on API 33+ it is read
 * from LocaleManager directly, which works from any Context), then the
 * stored in-app preference, then the system default. Only "en"/"es" are
 * supported; anything else falls through to the next source.
 */
class LocaleOrderTest {

    @Test
    fun `system per-app locale wins over the stored preference`() {
        // The regression H1 fixed: the old in-app choice overrode the
        // system picker on the first screen and in the Service.
        assertEquals("es", LocaleHelper.resolveEffectiveLanguage("es", "en", "en"))
        assertEquals("en", LocaleHelper.resolveEffectiveLanguage("en", "es", "es"))
    }

    @Test
    fun `stored preference is the fallback when the system has none`() {
        assertEquals("es", LocaleHelper.resolveEffectiveLanguage(null, "es", "en"))
        assertEquals("en", LocaleHelper.resolveEffectiveLanguage(null, "en", "es"))
    }

    @Test
    fun `system default is used when neither source has a language`() {
        assertEquals("fr", LocaleHelper.resolveEffectiveLanguage(null, null, "fr"))
        assertEquals("en", LocaleHelper.resolveEffectiveLanguage(null, null, "en"))
    }

    @Test
    fun `unsupported system locale falls through to the stored preference`() {
        assertEquals("es", LocaleHelper.resolveEffectiveLanguage("fr", "es", "fr"))
        assertEquals("en", LocaleHelper.resolveEffectiveLanguage("de", "en", "de"))
    }

    @Test
    fun `unsupported system locale falls through to the default when nothing is stored`() {
        assertEquals("fr", LocaleHelper.resolveEffectiveLanguage("fr", null, "fr"))
    }

    @Test
    fun `blank stored preference falls through to the default`() {
        assertEquals("fr", LocaleHelper.resolveEffectiveLanguage(null, "", "fr"))
        assertEquals("fr", LocaleHelper.resolveEffectiveLanguage(null, "   ", "fr"))
    }

    @Test
    fun `blank default falls back to English`() {
        assertEquals("en", LocaleHelper.resolveEffectiveLanguage(null, null, ""))
    }
}
