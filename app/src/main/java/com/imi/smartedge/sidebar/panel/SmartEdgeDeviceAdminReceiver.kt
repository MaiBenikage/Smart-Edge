package com.imi.smartedge.sidebar.panel

import android.app.admin.DeviceAdminReceiver

/**
 * Device admin receiver for the Lock Screen tool (instant lockNow()) and
 * Black Screen (display off). The no-lock-screen behaviour
 * (setKeyguardDisabled) is device-owner-only and guarded at the call
 * site — a plain device admin cannot do it.
 *
 * The receiver itself needs no logic: activation is initiated from the
 * Dashboard Tools settings screen via DevicePolicyManager#ACTION_ADD_DEVICE_ADMIN.
 * If the user deactivates device admin, Black Screen falls back to the
 * legacy dim+overlay behavior. The pref remains user-controlled.
 */
class SmartEdgeDeviceAdminReceiver : DeviceAdminReceiver() {
}
