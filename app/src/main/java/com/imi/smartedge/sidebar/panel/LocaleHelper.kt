package com.imi.smartedge.sidebar.panel

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

object LocaleHelper {

    /**
     * The app's per-app language as the system sees it, or null if none
     * is set. On API 33+ this reads LocaleManager directly (via any
     * Context) instead of AppCompatDelegate.getApplicationLocales(),
     * which only works while an Activity delegate is alive: it iterates
     * sActivityDelegates, so in Application.onCreate and in onAttach
     * (called as an argument to super.attachBaseContext, before the
     * delegate exists) it returns empty. That made the stored pref win
     * over the system picker on the first screen and in the Service.
     * On older versions AppCompat is the only mechanism, so it is used
     * there. Only "en"/"es" are supported; anything else returns null
     * and falls through to the next source.
     */
    private fun getPerAppLanguage(context: Context): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val localeManager = context.getSystemService(LocaleManager::class.java)
            val locales = localeManager?.applicationLocales
            if (locales != null && !locales.isEmpty) {
                return locales.get(0).language.takeIf { it == "en" || it == "es" }
            }
            return null
        }
        return AppCompatDelegate.getApplicationLocales().get(0)?.language
            ?.takeIf { it == "en" || it == "es" }
    }

    /**
     * Write the app's per-app language. On API 33+ this writes
     * LocaleManager directly (works from any Context, including
     * Application.onCreate); on older versions it goes through
     * AppCompatDelegate. The stored pref is updated separately by the
     * caller (setLocale / syncLocaleWithAppCompat), so the two stay in
     * sync.
     */
    private fun setPerAppLanguage(context: Context, language: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val localeManager = context.getSystemService(LocaleManager::class.java)
            localeManager?.setApplicationLocales(LocaleList.forLanguageTags(language))
        } else {
            AppCompatDelegate.setApplicationLocales(
                LocaleListCompat.forLanguageTags(language)
            )
        }
    }

    /**
     * Pure precedence for the effective language, extracted so it is
     * unit-testable (see LocaleOrderTest): the system's per-app locale
     * first (only "en"/"es" are supported — anything else falls through),
     * then the stored in-app preference (blank falls through), then the
     * system default. [perAppLang] is the raw first language from the
     * system's per-app locales (or null when none is set); [storedLang]
     * is the raw stored preference (or null when never set).
     */
    fun resolveEffectiveLanguage(
        perAppLang: String?,
        storedLang: String?,
        defaultLang: String
    ): String {
        val perApp = perAppLang?.takeIf { it == "en" || it == "es" }
        if (perApp != null) return perApp
        val stored = storedLang?.takeIf { it.isNotBlank() }
        if (stored != null) return stored
        return defaultLang.takeIf { it.isNotBlank() } ?: "en"
    }

    /**
     * The language the app is actually running in: the system's per-app
     * locale first (set by the system per-app picker or by setLocale),
     * then the stored in-app preference, then the system default.
     * The system locale wins because the system picker writes only
     * there — if onAttach read the stored pref first, it would override
     * the system choice. The stored pref is the fallback for a user who
     * chose a language in-app before localeConfig existed (the system
     * had nothing stored, because setApplicationLocales was a no-op
     * without it). Only "en"/"es" are supported; anything else falls
     * through to the next source.
     */
    fun getEffectiveLanguage(context: Context): String {
        val perAppLang = getPerAppLanguage(context)
        // LocaleListCompat.getDefault()[0] is null-safe on API 24+ (minSdk
        // = 26), which lets us replace the deprecated `Locale.getDefault()`
        // platform call. Kotlin was emitting `locale: Locale!` on the old
        // call site.
        val defaultLang = LocaleListCompat.getDefault().get(0)?.language
            ?: Locale.ROOT.language
        // Raw stored preference (null when never set) — the pure
        // resolveEffectiveLanguage below decides the fallback, so the
        // default is not substituted here.
        val storedLang = context.getSharedPreferences(PanelPreferences.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PanelPreferences.KEY_APP_LANGUAGE, null)
        return resolveEffectiveLanguage(perAppLang, storedLang, defaultLang)
    }

    /**
     * Align the stored in-app preference with the system's per-app locale
     * at process start (called from SidePanelApp.onCreate). Two cases:
     * - The system has a locale (user picked one in the system per-app
     *   picker, which writes only there): copy it into the stored pref,
     *   so the in-app setting and every onAttach fallback agree with it.
     * - The system is empty but the stored pref has a language (user chose
     *   in-app before localeConfig existed, when setApplicationLocales
     *   was a no-op): push it into the system, so the system picker shows
     *   the current language instead of "System default".
     * Does nothing when neither has a language (first run: the app follows
     * the system language, and pinning it would stop it following later
     * system-language changes). Only writes when the two disagree.
     * On API 33+ this reads/writes LocaleManager directly, which works
     * from Application.onCreate (AppCompatDelegate's static getters only
     * work while an Activity delegate is alive, so the old version of
     * this sync was a no-op here).
     */
    fun syncLocaleWithAppCompat(context: Context) {
        val preferences = context.getSharedPreferences(PanelPreferences.PREFS_NAME, Context.MODE_PRIVATE)
        val perAppLang = getPerAppLanguage(context)
        if (perAppLang != null) {
            if (preferences.getString(PanelPreferences.KEY_APP_LANGUAGE, null) != perAppLang) {
                preferences.edit().putString(PanelPreferences.KEY_APP_LANGUAGE, perAppLang).apply()
            }
            return
        }
        val stored = preferences.getString(PanelPreferences.KEY_APP_LANGUAGE, null)
            ?.takeIf { it == "en" || it == "es" }
        if (stored != null) {
            setPerAppLanguage(context, stored)
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
        // Per-app locale: this is what actually reaches every
        // AppCompatActivity (they all apply it in attachBaseContext) and
        // the app's resources. On API 33+ it writes LocaleManager
        // directly, so the system picker shows the in-app choice and the
        // in-app picker can switch back to a language it thinks is
        // already set. The context wrap below is for the caller's
        // immediate use and for services, which AppCompat does not cover.
        if (!language.isNullOrBlank()) {
            setPerAppLanguage(context, language)
        }
        // minSdk = 26 (Build.VERSION_CODES.N), so the old < N legacy branch has
        // been removed — `updateResources` is always the supported path.
        return updateResources(context, language)
    }

    /**
     * Apply the explicitly-chosen language to the per-app locale
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
            setPerAppLanguage(context, lang)
        }
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
