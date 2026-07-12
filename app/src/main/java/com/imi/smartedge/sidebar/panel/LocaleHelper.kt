package com.imi.smartedge.sidebar.panel

import android.content.Context
import androidx.core.os.LocaleListCompat
import java.util.Locale

object LocaleHelper {
    private const val SELECTED_LANGUAGE = "Locale.Helper.Selected.Language"

    fun onAttach(context: Context): Context {
        // LocaleListCompat.getDefault()[0] is null-safe on API 24+ (minSdk = 26),
        // which lets us replace the deprecated `Locale.getDefault()` platform
        // call. Kotlin was emitting `locale: Locale!` on the old call site.
        val defaultLang = LocaleListCompat.getDefault().get(0)?.language
            ?: Locale.ROOT.language
        val lang = getPersistedData(context, defaultLang)
        // Round-7 U-Med: bypass setLocale() here because that path calls
        // persist() — unconditional on every Activity attach (MainActivity,
        // SettingsMainActivity, every *SettingsActivity, etc.), which forces
        // a SharedPreferences.edit().apply() on the UI thread for each
        // activity create during app startup. The value we just read is the
        // same value persist() would write back, so the disk write is pure
        // waste on every resume. updateResources only mutates Context.resources
        // (cheap). The Settings page still uses setLocale() for the explicit
        // user-driven language flip, where the write is the whole point.
        return updateResources(context, lang)
    }

    fun setLocale(context: Context, language: String?): Context {
        persist(context, language)
        // minSdk = 26 (Build.VERSION_CODES.N), so the old < N legacy branch has
        // been removed — `updateResources` is always the supported path.
        return updateResources(context, language)
    }

    private fun getPersistedData(context: Context, defaultLanguage: String): String? {
        val preferences = context.getSharedPreferences("panel_prefs", Context.MODE_PRIVATE)
        return preferences.getString(SELECTED_LANGUAGE, defaultLanguage)
    }

    private fun persist(context: Context, language: String?) {
        val preferences = context.getSharedPreferences("panel_prefs", Context.MODE_PRIVATE)
        val editor = preferences.edit()
        editor.putString(SELECTED_LANGUAGE, language)
        editor.apply()
    }

    private fun updateResources(context: Context, language: String?): Context {
        val locale = Locale(language ?: "en")
        Locale.setDefault(locale)
        val configuration = context.resources.configuration
        configuration.setLocale(locale)
        configuration.setLayoutDirection(locale)

        // createConfigurationContext(...) returns a Context configured with
        // the new locale. The pre-API-17 Resources.updateConfiguration(...)
        // overload was deprecated in API 25 and is no longer called — the
        // modern wrapper above is the supported API on minSdk = 26.
        return context.createConfigurationContext(configuration)
    }
}
