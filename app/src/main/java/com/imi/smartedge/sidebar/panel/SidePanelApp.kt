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

        // No AppCompat per-app-locales call here: setApplicationLocales
        // only notifies Activities whose AppCompatDelegate already
        // exists, and none does in Application.onCreate, so on API 33+
        // it is a no-op (and without a declared localeConfig the system
        // side is undeclared too). The language is applied by the
        // LocaleHelper.onAttach context wrap on every Activity/Service,
        // and the two Settings activities re-apply it after an import
        // or a reset, when a delegate exists — so nothing is lost.
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