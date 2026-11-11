package com.imi.smartedge.sidebar.panel

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log

class PanelTileService : TileService() {

    override fun attachBaseContext(base: android.content.Context) {
        // See FloatingPanelService: services are outside AppCompat's
        // per-app locales, so wrap the base context with the chosen
        // language here.
        super.attachBaseContext(LocaleHelper.onAttach(base))
    }

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
            tile.label = getString(R.string.tile_sidebar)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = getString(R.string.tile_service_active)
            }
        } else if (isEnabled && !isAccessibilityEnabled) {
            tile.state = Tile.STATE_INACTIVE
            tile.label = getString(R.string.tile_sidebar)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = getString(R.string.tile_accessibility_missing)
            }
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = getString(R.string.tile_sidebar)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = getString(R.string.tile_service_stopped)
            }
        }
        tile.updateTile()
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
        if (!hasOverlayPermission() || !isEngineAvailable()) {
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

        // 5. Centralized Toggle Logic — posted to the MAIN thread so the
        //    toggle and the delayed tile refresh stay ordered on one
        //    thread. (Foreground-service background-start rules depend on
        //    the app's process state and exemptions, not on the calling
        //    thread; the raw Thread { ... }.start() we used before crashed
        //    for other reasons.)
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        // toggleService catches its own start failures internally, so no
        // try/catch here: there is nothing left for this block to catch.
        mainHandler.post {
            prefs.toggleService(this@PanelTileService, forcedState = targetState)
            mainHandler.postDelayed({
                updateTile()
                isProcessingToggle = false
            }, 800)
        }

        // No shade collapse here, making the toggle seamless for the user!
    }

    /**
     * Collapse the Quick Settings shade and launch an activity, in one call.
     *
     * The [TileService.startActivityAndCollapse] PendingIntent overload is
     * only available since API 34 (UPSIDE_DOWN_CAKE); calling it below 34
     * throws NoSuchMethodError. The Intent overload exists since API 24
     * and is safe below 34 (it only throws UnsupportedOperationException
     * for apps targeting 34 while running on 34+), so it is used there.
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val pending = PendingIntent.getActivity(this, 0, intent, flags)
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
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
