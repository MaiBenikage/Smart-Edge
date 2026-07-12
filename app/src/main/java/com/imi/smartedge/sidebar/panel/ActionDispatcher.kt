package com.imi.smartedge.sidebar.panel

import android.content.Context
import android.content.Intent
import android.os.VibrationEffect
import android.os.Vibrator

object ActionDispatcher {

    fun performAction(
        context: Context,
        actionId: Int,
        panelPrefs: PanelPreferences,
        onTriggerPanel: (() -> Unit)? = null,
        onDragHandle: (() -> Unit)? = null
    ) {
        if (actionId == PanelPreferences.ACTION_NONE) return

        vibrateHaptic(context, panelPrefs)

        when (actionId) {
            PanelPreferences.ACTION_OPEN_LAUNCHER -> onTriggerPanel?.invoke()
            PanelPreferences.ACTION_MOVE_HANDLE -> onDragHandle?.invoke()

            // Service Actions
            PanelPreferences.ACTION_FLASHLIGHT -> triggerServiceAction(context, FloatingPanelService.ACTION_TOGGLE_FLASHLIGHT)
            PanelPreferences.ACTION_CAMERA -> triggerServiceAction(context, FloatingPanelService.ACTION_LAUNCH_CAMERA)
            PanelPreferences.ACTION_AUTO_ROTATION -> triggerServiceAction(context, FloatingPanelService.ACTION_TOGGLE_ROTATION)
            PanelPreferences.ACTION_OPEN_FAVORITE_APP -> triggerServiceAction(context, FloatingPanelService.ACTION_OPEN_FAV_APP)

            // System/Navigation Gestures
            PanelPreferences.ACTION_SCREENSHOT -> executeSystemAction(context, panelPrefs, PanelAccessibilityService.ACTION_TAKE_SCREENSHOT)
            PanelPreferences.ACTION_PREVIOUS_APP -> executeSystemAction(context, panelPrefs, PanelAccessibilityService.ACTION_PREVIOUS_APP)
            PanelPreferences.ACTION_BACK -> executeSystemAction(context, panelPrefs, PanelAccessibilityService.ACTION_BACK)
            PanelPreferences.ACTION_HOME -> executeSystemAction(context, panelPrefs, PanelAccessibilityService.ACTION_HOME)
            PanelPreferences.ACTION_RECENTS -> executeSystemAction(context, panelPrefs, PanelAccessibilityService.ACTION_RECENTS)
            PanelPreferences.ACTION_NOTIFICATIONS -> executeSystemAction(context, panelPrefs, PanelAccessibilityService.ACTION_NOTIFICATIONS)
            PanelPreferences.ACTION_QUICK_SETTINGS -> executeSystemAction(context, panelPrefs, PanelAccessibilityService.ACTION_QUICK_SETTINGS)
            PanelPreferences.ACTION_LOCK_SCREEN -> executeSystemAction(context, panelPrefs, PanelAccessibilityService.ACTION_LOCK_SCREEN)
            PanelPreferences.ACTION_POWER_MENU -> executeSystemAction(context, panelPrefs, PanelAccessibilityService.ACTION_SHOW_POWER_MENU)
        }
    }

    private fun triggerServiceAction(context: Context, action: String) {
        val intent = Intent(context, FloatingPanelService::class.java).apply {
            this.action = action
        }
        context.startService(intent)
    }

    private fun executeSystemAction(context: Context, panelPrefs: PanelPreferences, accAction: String) {
        if (panelPrefs.useAutomationForGestures && AutomationManager.isAutomationPossible()) {
            val success = when (accAction) {
                PanelAccessibilityService.ACTION_BACK -> AutomationManager.performBack()
                PanelAccessibilityService.ACTION_HOME -> AutomationManager.performHome()
                PanelAccessibilityService.ACTION_RECENTS -> AutomationManager.performRecents()
                PanelAccessibilityService.ACTION_NOTIFICATIONS -> AutomationManager.performNotifications()
                PanelAccessibilityService.ACTION_QUICK_SETTINGS -> AutomationManager.performQuickSettings()
                PanelAccessibilityService.ACTION_SPLIT_SCREEN -> AutomationManager.performSplitScreen()
                PanelAccessibilityService.ACTION_LOCK_SCREEN -> { AutomationManager.performLockScreen(); true }
                PanelAccessibilityService.ACTION_SHOW_POWER_MENU -> AutomationManager.performPowerMenu()
                PanelAccessibilityService.ACTION_TAKE_SCREENSHOT -> { AutomationManager.takeScreenshot(); true }
                PanelAccessibilityService.ACTION_PREVIOUS_APP -> { AutomationManager.performPreviousApp(); true }
                else -> false
            }
            if (success) return
        }

        val intent = Intent(context, PanelAccessibilityService::class.java).apply {
            this.action = accAction
        }
        context.startService(intent)
    }

    private fun vibrateHaptic(context: Context, panelPrefs: PanelPreferences, duration: Long = 20) {
        if (!panelPrefs.hapticEnabled) return
        try {
            // Strongly-typed getSystemService(Class) avoids the deprecated
            // Context.VIBRATOR_SERVICE String key. minSdk = 26 covers the API surface.
            val vibrator = context.getSystemService(Vibrator::class.java) ?: return
            if (!vibrator.hasVibrator()) return
            // minSdk = 26 (Android 8.0), so VibrationEffect.createOneShot(...) is
            // always available — the pre-O Vibrator.vibrate(long) overload
            // (deprecated in API 26) is unreachable under our minSdk.
            vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {
            // Ignore
        }
    }
}