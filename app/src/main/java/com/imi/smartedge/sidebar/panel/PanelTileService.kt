package com.imi.smartedge.sidebar.panel

import android.app.PendingIntent
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
        // qsTile is non-null only while the system has this tile pinned into
        // the shade — bail out cleanly when it's been unbound. The downstream
        // updateTileInternal reads qsTile directly, so the unused local `tile`
        // is gone.
        qsTile ?: return
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

    /**
     * Collapse the Quick Settings shade and launch an activity, in one call.
     *
     * Uses the modern [TileService.startActivityAndCollapse] PendingIntent overload
     * (available since API 26). This replaces two deprecated paths that were
     * previously forked by API level:
     *   - [TileService.startActivityAndCollapse] taking an [Intent] (deprecated API 26)
     *   - `sendBroadcast(ACTION_CLOSE_SYSTEM_DIALOGS)` to collapse the shade
     *     (deprecated since Android N; the system no longer honors it for non-system apps)
     * [PendingIntent.FLAG_IMMUTABLE] is required by API 31+ for any PIs we create.
     *
     * Audit L-Low: on Android 14+ the strict background-activity-start policy
     * can treat an unbounded PI launch as background and silently buffer it.
     * Tile clicks already have system-granted background-launch privileges,
     * so the 1-arg overload is correct everywhere; the 2-arg overload
     * (which adds MODE_BACKGROUND_ACTIVITY_START_ALLOWED) is intentionally
     * NOT used here because compileSdk = 34's TileService API surface does
     * not yet expose it in this toolchain. The single-arg form below
     * preserves the proven shipping path while keeping this reasoning
     * documented for the next maintenance pass.
     */
    private fun startAction(intent: Intent) {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val pending = PendingIntent.getActivity(this, 0, intent, flags)
        startActivityAndCollapse(pending)
    }

    private fun triggerHapticFeedback() {
        try {
            // VibratorManager is API 31+ (S). Pre-S devices (Android 8-11) still
            // exist in the wild, so the legacy strongly-typed lookup stays below.
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager
                vibratorManager.defaultVibrator
            } else {
                // Strongly-typed getSystemService(Class) avoids the deprecated
                // Context.VIBRATOR_SERVICE String key.
                getSystemService(android.os.Vibrator::class.java) ?: return
            }

            // minSdk = 26, so VibrationEffect.createOneShot(...) is always available —
            // the pre-O Vibrator.vibrate(long) overload (deprecated in API 26) is
            // unreachable under our minSdk.
            vibrator.vibrate(android.os.VibrationEffect.createOneShot(10, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {
            // Ignore if vibrator fails
        }
    }
}
