package com.imi.smartedge.sidebar.panel

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

object LocaleHelper {

    /**
     * The language the app is actually running in: AppCompat's per-app
     * locale first (set by the system per-app picker or by setLocale),
     * then the stored in-app preference, then the system default.
     * AppCompat wins because the system picker writes only there — if
     * onAttach read the stored pref first, it would override the system
     * choice. The stored pref is the fallback for a user who chose a
     * language in-app before localeConfig existed (AppCompat had nothing
     * stored, because setApplicationLocales was a no-op without it).
     * Only "en"/"es" are supported; anything else falls through to the
     * next source.
     */
    fun getEffectiveLanguage(context: Context): String {
        val appCompatLang = AppCompatDelegate.getApplicationLocales().get(0)?.language
            ?.takeIf { it == "en" || it == "es" }
        if (appCompatLang != null) return appCompatLang
        // LocaleListCompat.getDefault()[0] is null-safe on API 24+ (minSdk
        // = 26), which lets us replace the deprecated `Locale.getDefault()`
        // platform call. Kotlin was emitting `locale: Locale!` on the old
        // call site.
        val defaultLang = LocaleListCompat.getDefault().get(0)?.language
            ?: Locale.ROOT.language
        return getPersistedData(context, defaultLang)?.takeIf { it.isNotBlank() } ?: "en"
    }

    /**
     * Align the stored in-app preference with AppCompat's per-app locale
     * at process start (called from SidePanelApp.onCreate). Two cases:
     * - AppCompat has a locale (user picked one in the system per-app
     *   picker, which writes only there): copy it into the stored pref,
     *   so the in-app setting and every onAttach fallback agree with it.
     * - AppCompat is empty but the stored pref has a language (user chose
     *   in-app before localeConfig existed, when setApplicationLocales
     *   was a no-op): push it into AppCompat, so the system picker shows
     *   the current language instead of "System default".
     * Does nothing when neither has a language (first run: the app follows
     * the system language, and pinning it would stop it following later
     * system-language changes). Only writes when the two disagree.
     */
    fun syncLocaleWithAppCompat(context: Context) {
        val preferences = context.getSharedPreferences(PanelPreferences.PREFS_NAME, Context.MODE_PRIVATE)
        val appCompatLang = AppCompatDelegate.getApplicationLocales().get(0)?.language
            ?.takeIf { it == "en" || it == "es" }
        if (appCompatLang != null) {
            if (preferences.getString(PanelPreferences.KEY_APP_LANGUAGE, null) != appCompatLang) {
                preferences.edit().putString(PanelPreferences.KEY_APP_LANGUAGE, appCompatLang).apply()
            }
            return
        }
        val stored = preferences.getString(PanelPreferences.KEY_APP_LANGUAGE, null)
            ?.takeIf { it == "en" || it == "es" }
        if (stored != null) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(stored))
        }
    }

    fun onAttach(context: Context): Context {
        // Round-7 U-Med: bypass setLocale() here because that path calls
        // persist() — unconditional on every Activity attach (MainActivity,
        // SettingsMainActivity, every *SettingsActivity, etc.), which forces
        // a SharedPreferences.edit().apply() on the UI thread for each
        // activity create during app startup. The value we just read is the
        // same value persist() would write back, so the disk write is pure
        // waste on every resume. updateResources only mutates Context.resources
        // (cheap). The Settings page still uses setLocale() for the explicit
        // user-driven language flip, where the write is the whole point.
        return updateResources(context, getEffectiveLanguage(context))
    }

    fun setLocale(context: Context, language: String?): Context {
        persist(context, language)
        // AppCompat per-app locales: this is what actually reaches every
        // AppCompatActivity (they all apply it in attachBaseContext) and
        // the app's resources. The context wrap below is for the caller's
        // immediate use and for services, which AppCompat does not cover.
        if (!language.isNullOrBlank()) {
            AppCompatDelegate.setApplicationLocales(
                LocaleListCompat.forLanguageTags(language)
            )
        }
        // minSdk = 26 (Build.VERSION_CODES.N), so the old < N legacy branch has
        // been removed — `updateResources` is always the supported path.
        return updateResources(context, language)
    }

    /**
     * Apply the explicitly-chosen language to AppCompat's per-app locales
     * at process start, so activities get it without each needing an
     * onAttach wrap. Does nothing when the user never chose a language —
     * pinning the locale to the system language at first run would stop
     * the app following later system-language changes.
     */
    fun applyPersistedLocale(context: Context) {
        val preferences = context.getSharedPreferences(PanelPreferences.PREFS_NAME, Context.MODE_PRIVATE)
        if (!preferences.contains(PanelPreferences.KEY_APP_LANGUAGE)) return
        val lang = preferences.getString(PanelPreferences.KEY_APP_LANGUAGE, null)
        if (!lang.isNullOrBlank()) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(lang))
        }
    }

    private fun getPersistedData(context: Context, defaultLanguage: String): String? {
        // Same file as PanelPreferences (side_panel_prefs). Used to live in a
        // separate "panel_prefs" file, which meant the language preference was
        // written to one file while PanelPreferences.appLanguage read another —
        // settings export/import (which only covers side_panel_prefs) silently
        // lost the language. Unify on the main prefs file so backups carry it.
        // The key is the shared PanelPreferences.KEY_APP_LANGUAGE const — the
        // literal used to be duplicated here and in PanelPreferences.
        val preferences = context.getSharedPreferences(PanelPreferences.PREFS_NAME, Context.MODE_PRIVATE)
        return preferences.getString(PanelPreferences.KEY_APP_LANGUAGE, defaultLanguage)
    }

    private fun persist(context: Context, language: String?) {
        val preferences = context.getSharedPreferences(PanelPreferences.PREFS_NAME, Context.MODE_PRIVATE)
        val editor = preferences.edit()
        editor.putString(PanelPreferences.KEY_APP_LANGUAGE, language)
        editor.apply()
    }

    private fun updateResources(context: Context, language: String?): Context {
        val locale = Locale(language ?: "en")
        Locale.setDefault(locale)
        // Start from an EMPTY Configuration and set only the locale and
        // its layout direction. Copying the live Configuration
        // (resources.configuration) also copies orientation and uiMode,
        // so the wrapped context freezes the values from process start:
        // the handle then reappears in landscape when it is set not to,
        // dynamic game detection never fires (it reads orientation), and
        // the overlay stops following dark mode. A fresh Configuration
        // leaves orientation/uiMode unset, so the system re-resolves
        // them from the live values for every read. A new object is
        // still used (never mutate the live one owned by the base
        // context's Resources — that is undefined behaviour and can leak
        // the locale into other contexts).
        val configuration = Configuration()
        configuration.setLocale(locale)
        configuration.setLayoutDirection(locale)

        // createConfigurationContext(...) returns a Context configured with
        // the new locale. The pre-API-17 Resources.updateConfiguration(...)
        // overload was deprecated in API 25 and is no longer called — the
        // modern wrapper above is the supported API on minSdk = 26.
        return context.createConfigurationContext(configuration)
    }
}
