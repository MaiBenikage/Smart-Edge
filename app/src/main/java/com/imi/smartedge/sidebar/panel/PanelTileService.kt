package com.imi.smartedge.sidebar.panel

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log

class PanelTileService : TileService() {
    
    companion object {
        // @Volatile: this flag is checked/written by onClick (main thread) and
        // cleared from the background Thread + postDelayed in onClick below.
        @Volatile private var isProcessingToggle = false
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val prefs = PanelPreferences(this)
        val isEnabled = prefs.serviceEnabled
        val isAccessibilityEnabled = isAccessibilityServiceEnabled()
        updateTileInternal(isEnabled, isAccessibilityEnabled)
    }

    private fun updateTileOptimistic(newState: Boolean) {
        val isAccessibilityEnabled = isAccessibilityServiceEnabled()
        updateTileInternal(newState, isAccessibilityEnabled)
    }

    private fun updateTileInternal(isEnabled: Boolean, isAccessibilityEnabled: Boolean) {
        val tile = qsTile ?: return
        if (isEnabled && isAccessibilityEnabled) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = "Sidebar"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = "Service Active"
            }
        } else if (isEnabled && !isAccessibilityEnabled) {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "Sidebar"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = "Accessibility Missing"
            }
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "Sidebar"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = "Service Stopped"
            }
        }
        tile.updateTile()
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        if (PanelAccessibilityService.isRunning) return true
        val enabledServices = android.provider.Settings.Secure.getString(
            contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val colonSplitter = android.text.TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServices)
        while (colonSplitter.hasNext()) {
            val componentName = colonSplitter.next()
            if (componentName.equals("$packageName/${PanelAccessibilityService::class.java.name}", ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    private fun hasOverlayPermission(): Boolean =
        android.provider.Settings.canDrawOverlays(this)

    override fun onClick() {
        super.onClick()
        
        // 1. Debounce rapid clicks
        if (isProcessingToggle) return
        isProcessingToggle = true

        val prefs = PanelPreferences(this)
        val isEnabled = prefs.serviceEnabled

        // 2. Immediate Haptic Feedback
        triggerHapticFeedback()

        // 3. Permission Check (Instant)
        if (!hasOverlayPermission() || !isAccessibilityServiceEnabled()) {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startAction(intent)
            isProcessingToggle = false
            return
        }

        // 4. Optimistic UI Update (Near Instant)
        val targetState = !isEnabled
        updateTileOptimistic(targetState)

        // 5. Centralized Toggle Logic — must run on the MAIN thread because
        //    startForegroundService throws ForegroundServiceStartNotAllowedException
        //    on Android 12+ when invoked from a non-main Thread. We previously
        //    ran this inside a raw Thread { ... }.start() which crashed.
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        mainHandler.post {
            try {
                prefs.toggleService(this@PanelTileService, forcedState = targetState)
                mainHandler.postDelayed({
                    updateTile()
                    isProcessingToggle = false
                }, 800)
            } catch (e: Exception) {
                // background-foreground policy violations, etc. — fall back to
                // plain toggle without animating optimistic UI back.
                Log.e("PanelTileService", "toggle failed", e)
                updateTile()
                isProcessingToggle = false
            }
        }

        // No shade collapse here, making the toggle seamless for the user!
    }

    private fun startAction(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // For Android 14+, use the official way to collapse and start activity
            startActivityAndCollapse(intent)
        } else {
            // Legacy collapse
            @Suppress("DEPRECATION")
            sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
            startActivity(intent)
        }
    }

    private fun triggerHapticFeedback() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(android.content.Context.VIBRATOR_SERVICE) as android.os.Vibrator
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Short light tap for tactile feel
                vibrator.vibrate(android.os.VibrationEffect.createOneShot(10, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(10)
            }
        } catch (e: Exception) {
            // Ignore if vibrator fails
        }
    }
}
