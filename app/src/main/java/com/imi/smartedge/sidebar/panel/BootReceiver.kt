package com.imi.smartedge.sidebar.panel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Listens for BOOT_COMPLETED and auto-starts FloatingPanelService
 * if the user has enabled "Auto-start on boot" in settings.
 *
 * Audit U4: `exported=true` is a REQUIREMENT here, not a smell — BOOT_COMPLETED
 * and QUICKBOOT_POWERON are protected system broadcasts; only the system can
 * fire them at us, regardless of our `exported` flag. Android requires the flag
 * to be explicit since target SDK 31. Do not "fix" this to exported=false.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON") return

        val prefs = PanelPreferences(context)
        if (!prefs.autoStart) return

        // Start only when the sidebar is actually enabled: forcing
        // serviceEnabled = true here overrode a user who turned the sidebar
        // off before rebooting. (The a11y service's ensurePanelRunning()
        // already restarts the panel when it is enabled.)
        if (!prefs.serviceEnabled) return

        // Check the engine is available BEFORE starting. Otherwise the UI
        // shows "enabled" but the service can't start — inconsistent state.
        // Accessibility or usable automation both count (see isEngineAvailable).
        if (!context.isEngineAvailable()) return

        val serviceIntent = Intent(context, FloatingPanelService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            android.util.Log.e("BootReceiver", "Failed to start panel service on boot", e)
        }
    }

}
