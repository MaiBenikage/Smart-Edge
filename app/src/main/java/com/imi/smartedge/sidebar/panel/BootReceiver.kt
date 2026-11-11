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

        // Check AccessibilityService availability BEFORE flipping serviceEnabled.
        // Otherwise the UI shows "enabled" but the service can't start — inconsistent state.
        if (!context.isAccessibilityServiceEnabled()) return
        prefs.serviceEnabled = true

        val serviceIntent = Intent(context, FloatingPanelService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }

}
