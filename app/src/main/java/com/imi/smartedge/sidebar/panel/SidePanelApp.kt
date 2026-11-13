package com.imi.smartedge.sidebar.panel

import android.app.Application
import android.graphics.drawable.Drawable
import android.util.Log
import com.bumptech.glide.Glide

class SidePanelApp : Application() {
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(LocaleHelper.onAttach(base))
    }

    override fun onCreate() {
        super.onCreate()

        // Sync the stored in-app language with AppCompat's per-app locale
        // (see LocaleHelper.syncLocaleWithAppCompat). With localeConfig
        // declared, setApplicationLocales persists to the system on API 33+,
        // so this is no longer the no-op the old comment here described —
        // that comment was written when localeConfig was absent and the
        // system side was undeclared. The sync runs once per process start
        // (not on every Activity attach) and only writes when the two
        // disagree, so it does not add a per-resume disk write. The language
        // itself is still applied by the LocaleHelper.onAttach context wrap
        // on every Activity/Service, and the two Settings activities re-apply
        // it after an import or a reset, when a delegate exists.
        LocaleHelper.syncLocaleWithAppCompat(this)
        // Apply the saved theme mode
        applyAppTheme(this)
        
        // Register the custom AppIconRequest loader with Glide
        Glide.get(this).registry.append(
            AppIconRequest::class.java,
            Drawable::class.java,
            AppIconModelLoader.Factory(this)
        )
    }
}