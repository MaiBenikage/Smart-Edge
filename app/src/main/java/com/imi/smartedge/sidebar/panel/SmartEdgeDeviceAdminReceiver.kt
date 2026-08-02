package com.imi.smartedge.sidebar.panel

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/**
 * Device admin receiver used to allow Black Screen to turn the screen off
 * without showing the lock screen (setKeyguardDisabled).
 *
 * The receiver itself needs no logic: activation is initiated from the
 * Dashboard Tools settings screen via DevicePolicyManager#ACTION_ADD_DEVICE_ADMIN.
 */
class SmartEdgeDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        // If the user deactivates device admin, Black Screen falls back to the
        // legacy dim+overlay behavior. The pref remains user-controlled.
    }
}
