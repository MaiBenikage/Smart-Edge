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

        // Apply the explicitly-chosen language to AppCompat's per-app
        // locales so every activity gets it from process start.
        LocaleHelper.applyPersistedLocale(this)

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