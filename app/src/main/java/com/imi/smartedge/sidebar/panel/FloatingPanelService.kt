package com.imi.smartedge.sidebar.panel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.service.quicksettings.TileService
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class FloatingPanelService : Service() {

    override fun attachBaseContext(base: android.content.Context) {
        // The overlay (sidebar, picker, tools) draws strings from this
        // service's context: wrap it with the chosen language, which
        // AppCompat's per-app locales do not cover for services.
        super.attachBaseContext(LocaleHelper.onAttach(base))
    }

    private lateinit var windowManager: WindowManager
    private var edgeHandleView: EdgeHandleView? = null
    private var notchHandleView: NotchHandleView? = null
    private var sidePanelView: SidePanelView? = null
    private var pickerPanelView: AppPickerPanelView? = null
    
    private var rootLayout: android.widget.FrameLayout? = null
    private var rootParams: WindowManager.LayoutParams? = null

    private val activeTorches = mutableSetOf<String>()
    private var isFlashlightOn = false
    private var lastManualTorchTime = 0L
    /** (cameraId -> state) of our own just-issued setTorchMode calls, so the
     *  callback can ignore exactly their echo. A blanket time window used
     *  to drop real external changes for 500 ms after any manual toggle. */
    private val lastManualTorchState = mutableMapOf<String, Boolean>()
    private var cameraManager: android.hardware.camera2.CameraManager? = null
    private val torchCallback = object : android.hardware.camera2.CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            super.onTorchModeChanged(cameraId, enabled)
            // Ignore only the echo of our own just-issued setTorchMode for
            // this camera and state (within 500 ms). A real external change
            // — another camera, the opposite state, or a later change — must
            // still be recorded.
            if (lastManualTorchState[cameraId] == enabled &&
                System.currentTimeMillis() - lastManualTorchTime <= 500) {
                return
            }
            if (enabled) activeTorches.add(cameraId) else activeTorches.remove(cameraId)
            isFlashlightOn = activeTorches.isNotEmpty()
            Log.d(TAG, "External torch change: $enabled for camera $cameraId. Master state: $isFlashlightOn")
        }
    }
    // Black Screen state
    private var blackScreenOverlay: android.widget.FrameLayout? = null
    private var blackScreenOverlayParams: WindowManager.LayoutParams? = null
    private var blackScreenWakeLock: android.os.PowerManager.WakeLock? = null
    private var screenshotHideViews: List<View>? = null
    private var screenshotPrevAlphas: List<Float>? = null
    private val screenshotRestoreRunnable = Runnable { restoreScreenshotUi() }
    private var savedBrightness: Int = 0
    private var savedBrightnessFloat: Float? = null
    private var savedBrightnessMode: Int = android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
    /** Original screen-off timeout (ms) saved before true screen-off, restored on exit. */
    private var savedScreenOffTimeout: Int = -1
    /** True when true screen-off (via device admin) is active rather than the dim overlay. */
    private var trueScreenOffActive: Boolean = false

    // Persisted Black Screen session (SharedPreferences "black_screen_session"):
    // the saved brightness/timeout live in memory only, so a process kill
    // while Black Screen is active would leave the device at a 15s timeout
    // and minimum brightness. The session is written at activation, cleared
    // at deactivation, and restored on the next service start if present.
    private fun blackScreenSessionPrefs(): android.content.SharedPreferences =
        getSharedPreferences("black_screen_session", Context.MODE_PRIVATE)

    private fun persistBlackScreenSession() {
        blackScreenSessionPrefs().edit()
            .putBoolean("active", true)
            .putInt("brightness", savedBrightness)
            .putFloat("brightness_float", savedBrightnessFloat ?: -1f)
            .putInt("brightness_mode", savedBrightnessMode)
            .putInt("screen_off_timeout", savedScreenOffTimeout)
            .putBoolean("true_screen_off", trueScreenOffActive)
            .apply()
    }

    private fun clearBlackScreenSession() {
        blackScreenSessionPrefs().edit().clear().apply()
    }

    /** Restore a session persisted by [persistBlackScreenSession] when the
     *  previous process died while Black Screen was active. Runs once. */
    private fun restorePersistedBlackScreenSession() {
        val prefs = blackScreenSessionPrefs()
        if (!prefs.getBoolean("active", false)) return
        savedBrightness = prefs.getInt("brightness", 128)
        savedBrightnessFloat = prefs.getFloat("brightness_float", -1f).takeIf { it >= 0f }
        savedBrightnessMode = prefs.getInt(
            "brightness_mode",
            android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        )
        savedScreenOffTimeout = prefs.getInt("screen_off_timeout", -1)
        trueScreenOffActive = prefs.getBoolean("true_screen_off", false)
        clearBlackScreenSession()
        restoreSavedBrightness()
        if (trueScreenOffActive) {
            trueScreenOffActive = false
            try {
                if (savedScreenOffTimeout > 0) {
                    android.provider.Settings.System.putInt(
                        contentResolver,
                        android.provider.Settings.System.SCREEN_OFF_TIMEOUT,
                        savedScreenOffTimeout
                    )
                    savedScreenOffTimeout = -1
                }
            } catch (_: Exception) {}
            reenableKeyguardIfDeviceOwner()
        }
    }

    /**
     * setKeyguardDisabled is device-owner-only: a plain device admin cannot
     * disable the keyguard, so the call throws (and was silently swallowed)
     * on every activation. Only call it when this app is the device owner.
     */
    private fun disableKeyguardIfDeviceOwner() {
        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            val cn = android.content.ComponentName(this, SmartEdgeDeviceAdminReceiver::class.java)
            if (dpm.isAdminActive(cn) && dpm.isDeviceOwnerApp(packageName)) {
                dpm.setKeyguardDisabled(cn, true)
            }
        } catch (_: Exception) {}
    }

    private fun reenableKeyguardIfDeviceOwner() {
        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            val cn = android.content.ComponentName(this, SmartEdgeDeviceAdminReceiver::class.java)
            if (dpm.isAdminActive(cn) && dpm.isDeviceOwnerApp(packageName)) {
                dpm.setKeyguardDisabled(cn, false)
            }
        } catch (_: Exception) {}
    }

    // ── Content Picker state ──────────────────────────────────────────────
    private var contentPickerOverlay: android.widget.FrameLayout? = null
    private var contentPickerOverlayParams: WindowManager.LayoutParams? = null
    private var contentPickerControls: List<ContentInfo> = emptyList()
    private var contentPickerActive = false
    private val contentPickerRefreshRunnable = Runnable { refreshContentPickerBorders() }
    private val contentPickerBorderColor = android.graphics.Color.YELLOW
    /** Consecutive empty snapshots (no accessible controls) seen so far. */
    private var contentPickerEmptySnapshots = 0
    /** Text snippets accumulated from single-tap selections. Written to the
     *  clipboard (joined with newlines) when the picker is dismissed by the
     *  two-finger gesture. */
    private val contentPickerAccumulated = mutableListOf<String>()
    /** In-overlay feedback label so the user sees "read" feedback even though
     *  the host rootLayout is removed while the picker is active. */
    private var contentPickerFeedbackText: android.widget.TextView? = null
    private val contentPickerFeedbackHideRunnable = Runnable {
        contentPickerFeedbackText?.visibility = android.view.View.GONE
    }
    /** Standalone mini-overlay used to confirm a clipboard flush after the
     *  Content Picker overlay is dismissed. Created as its own window so the
     *  confirmation is visible regardless of rootLayout/Toast/Window limits. */
    private var confirmOverlay: android.view.View? = null

    private var isPanelOpen = false
    private var isPickerOpen = false
    private var currentFolderId: String? = null
    private lateinit var panelPrefs: PanelPreferences
    // Audit L1: AppRepository is now a single per-service instance provisioned in
    // onCreate(). Previously every call to refreshApps()/getTop5Apps() allocated
    // a fresh AppRepository(this), which owned its own
    //   iconPreloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // so we leaked one SupervisorJob per refresh. With the field, refreshApps()
    // reuses the same scope and onDestroy() can call repository.clear() (Audit L2).
    private lateinit var repository: AppRepository
    private var lastPickerToggleTime = 0L
    private var notificationChangedListener: (() -> Unit)? = null
    
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private val handler = Handler(Looper.getMainLooper())

    private val packageReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action
            if (action != Intent.ACTION_PACKAGE_ADDED &&
                action != Intent.ACTION_PACKAGE_REMOVED &&
                action != Intent.ACTION_PACKAGE_REPLACED) return

            val packageName = intent.data?.schemeSpecificPart ?: return

            // Invalidate system icon cache for this app
            AppRepository.clearSystemIconCache()
            // Drop the cached version tag so Glide's icon key for this
            // package is rebuilt from the new lastUpdateTime and the
            // pre-update icon is not served from Glide's caches.
            AppIconModelLoader.invalidateVersionTag(packageName)
            // Drop any cached icon-pack mapping for this package: a pack
            // update or reinstall must re-parse its appfilter.
            IconPackManager.invalidateCache(packageName)

            // If it was removed, purge all references to this package.
            if (action == Intent.ACTION_PACKAGE_REMOVED && !intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                // One read, one write, under the shared panel-apps lock:
                // the old code read getPanelApps() twice (once for the
                // filter, once for the size check) outside the lock, so a
                // concurrent edit between the two reads could make the size
                // check pass against a stale list and the write clobber it.
                // (synchronized is re-entrant, so removeApp/setPanelApps
                // taking the same lock inside is safe.)
                synchronized(PanelPreferences.PANEL_APPS_LOCK) {
                    panelPrefs.removeApp(packageName)
                    // Drop intent-URI pins that target the uninstalled package (parseUri, not substring).
                    val current = panelPrefs.getPanelApps()
                    val remaining = current.filterNot { id ->
                        id.startsWith("intent:") && repository.intentUriTargetsPackage(id, packageName)
                    }
                    if (remaining.size != current.size) {
                        panelPrefs.setPanelApps(remaining)
                    }
                }
                panelPrefs.setGameApps(panelPrefs.getGameApps().filterNot { it == packageName })
                panelPrefs.setFullscreenWhitelist(
                    panelPrefs.getFullscreenWhitelist().filterNot { it == packageName }
                )
                panelPrefs.clearFavoriteAppIfPackage(packageName)
            }

            // Refresh lists if picker or panel is open
            if (isPanelOpen) {
                refreshApps()
            }
            if (isPickerOpen) {
                pickerPanelView?.invalidateAppList()
                pickerPanelView?.loadApps(forceRefresh = true)
            }
        }
    }

    // Intent.ACTION_CLOSE_SYSTEM_DIALOGS is deprecated since API 29 but is still
    // broadcast by the system on Home/Recents presses. There is no public
    // replacement that lets a regular 3rd-party service observe home-key, so
    // we keep the receiver (and the deprecated constant on the filter below)
    // with file-scoped suppression.
    @Suppress("DEPRECATION")
    private val systemDialogsReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_CLOSE_SYSTEM_DIALOGS) {
                val reason = intent.getStringExtra("reason")
                if (reason == "homekey" || reason == "recentapps") {
                    closePanel()
                }
            }
        }
    }

    companion object {
        const val TAG = "FloatingPanelService"
        @Volatile
        var isRunning = false
            private set
            
        const val CHANNEL_ID = "side_panel_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.imi.smartedge.sidebar.panel.STOP"
        /** Stop overlay without clearing serviceEnabled (e.g. a11y unbound). */
        const val ACTION_STOP_RUNTIME = "com.imi.smartedge.sidebar.panel.STOP_RUNTIME"
        const val ACTION_OPEN = "com.imi.smartedge.sidebar.panel.OPEN"
        const val ACTION_OPEN_HUB = "com.imi.smartedge.sidebar.panel.OPEN_HUB"
        const val ACTION_REFRESH = "com.imi.smartedge.sidebar.panel.REFRESH"
        const val ACTION_CLOSE_PANEL = "com.imi.smartedge.sidebar.panel.CLOSE_PANEL"
        const val ACTION_SHOW_TEMP = "com.imi.smartedge.sidebar.panel.SHOW_TEMP"
        const val ACTION_TOGGLE = "com.imi.smartedge.sidebar.panel.TOGGLE"
        const val ACTION_SCREENSHOT = "com.imi.smartedge.sidebar.panel.SCREENSHOT"
        const val ACTION_SCREENSHOT_UI_RESTORE = "com.imi.smartedge.sidebar.panel.SCREENSHOT_UI_RESTORE"
        /** Light foreground-switch signal: only re-checks handle visibility
         *  and game mode (see refreshForegroundState). The a11y service
         *  sends this on every foreground switch; the full ACTION_REFRESH
         *  is reserved for settings changes. */
        const val ACTION_FOREGROUND_CHANGED = "com.imi.smartedge.sidebar.panel.FOREGROUND_CHANGED"
        const val ACTION_TOGGLE_FLASHLIGHT = "com.imi.smartedge.sidebar.panel.TOGGLE_FLASHLIGHT"
        const val ACTION_LAUNCH_CAMERA = "com.imi.smartedge.sidebar.panel.LAUNCH_CAMERA"
        const val ACTION_TOGGLE_ROTATION = "com.imi.smartedge.sidebar.panel.TOGGLE_ROTATION"
        const val ACTION_OPEN_FAV_APP = "com.imi.smartedge.sidebar.panel.OPEN_FAV_APP"
        /** Which gesture slot fired the favourite-app action (the action
         *  pref key, e.g. "tap_action"); absent for legacy callers. */
        const val EXTRA_FAV_SLOT = "fav_slot"

        // Timing (ms)
        const val SCREENSHOT_TRIGGER_DELAY_MS = 200L
        const val SCREENSHOT_RESTORE_DELAY_MS = 600L
        const val CONTENT_PICKER_REFRESH_MS = 1200L
        const val BLACK_SCREEN_OVERLAY_DELAY_MS = 150L
        const val PICKER_CLOSE_COLUMNS_DELAY_MS = 250L
        const val SCREEN_OFF_TIMEOUT_MIN_MS = 1000
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true

        // Audit L5/U11/L7: probe engine once at service startup, then react if the
        // Shizuku binder dies at runtime. A localized Snackbar (with Toast fallback
        // if no anchor View exists yet) is now surfaced instead of the old plain toast.
        AutomationManager.refresh()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            TileService.requestListeningState(this, android.content.ComponentName(this, PanelTileService::class.java))
        }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        panelPrefs = PanelPreferences(this)
        // A previous process may have died while Black Screen was active:
        // restore the brightness/timeout it saved (persisted at activation).
        restorePersistedBlackScreenSession()
        // The engine-lost callback reads panelPrefs, so it must be assigned
        // AFTER panelPrefs exists: if the engine dies between service start
        // and this line, the callback would read an uninitialised lateinit
        // and crash the service instead of showing the Snackbar.
        AutomationManager.onEngineLost = {
            if (panelPrefs.useAutomationForGestures) {
                showEngineLostSnackbar()
                addEdgeHandle()
            }
        }
        // Audit L1: provision the shared repository BEFORE initSidePanel() calls
        // refreshApps() — otherwise the launch would NPE on repository.getPanelApps().
        repository = AppRepository.shared(this)
        // When the async label backfill fills legacy intent: labels, show
        // them without waiting for the next refresh cycle.
        repository.onLabelsBackfilled = {
            serviceScope.launch { refreshApps() }
        }

        try {
            cameraManager = getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            cameraManager?.registerTorchCallback(torchCallback, handler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register torch callback", e)
        }

        // One-time migration for new defaults
        if (!panelPrefs.toolsFolderMigrated) {
            panelPrefs.showTools = true
            panelPrefs.showToolsPanelButton = true
            panelPrefs.toolsFolderMigrated = true
        }

        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, buildNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        initSidePanel()
        initPickerPanel()
        
        // Force enable notch gestures for debugging if we're in a debug build
        // val isDebug = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        // if (isDebug) {
        //    panelPrefs.notchGesturesEnabled = true
        // }

        if (panelPrefs.serviceEnabled) {
            addEdgeHandle()
            addNotchHandle()
        }

        @Suppress("DEPRECATION") val filter = android.content.IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(systemDialogsReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(systemDialogsReceiver, filter)
        }

        val pkgFilter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        // Android 13+ requires explicit exported flag for receivers. ACTION_PACKAGE_*
        // are protected system broadcasts but the platform still requires the
        // RECEIVER_EXPORTED declaration to suppress strict-mode warnings and to
        // future-proof against background-receiver policy changes.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(packageReceiver, pkgFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(packageReceiver, pkgFilter)
        }

        serviceScope.launch {
            // First-run seeding, once only: without the flag, a user who
            // deliberately unpins everything gets 5 apps re-pinned on the
            // next service start, making an empty sidebar impossible.
            if (!panelPrefs.panelSeeded && panelPrefs.getPanelApps().isEmpty()) {
                val topApps = repository.getTop5Apps()
                if (topApps.isNotEmpty()) {
                    panelPrefs.setPanelApps(topApps)
                    panelPrefs.panelSeeded = true
                }
                refreshApps()
            }
        }

        notificationChangedListener = {
            if (isPanelOpen) {
                refreshApps()
            }
        }
        NotificationTrackingService.addOnNotificationsChangedListener(notificationChangedListener!!)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        
        // If service is disabled, we only allow ACTION_TOGGLE or ACTION_STOP to proceed.
        // Any other action should stop the service.
        if (!panelPrefs.serviceEnabled && action != ACTION_TOGGLE && action != ACTION_STOP && action != ACTION_STOP_RUNTIME && action != ACTION_SCREENSHOT_UI_RESTORE) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent == null || action == null) {
            if (panelPrefs.serviceEnabled) {
                addEdgeHandle()
            }
        }
        
        when (action) {
            ACTION_TOGGLE -> {
                // `intent` is non-null here: a matched non-null `action`
                // implies a non-null intent (the `if (intent == null ||
                // action == null)` guard above only handles the both-null
                // case and does not return).
                val newState = intent.getBooleanExtra("target_state", !panelPrefs.serviceEnabled)
                panelPrefs.setServiceEnabled(newState, commit = true)
                
                // Request Tile Update explicitly
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    TileService.requestListeningState(this, android.content.ComponentName(this, PanelTileService::class.java))
                }
                
                if (newState) {
                    addEdgeHandle()
                    addNotchHandle()
                } else {
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                // Explicit user stop (notification / settings): persist disabled.
                panelPrefs.setServiceEnabled(false, commit = true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    TileService.requestListeningState(this, android.content.ComponentName(this, PanelTileService::class.java))
                }
                stopSelf()
            }
            ACTION_STOP_RUNTIME -> {
                // Engine/runtime teardown only — do not flip serviceEnabled.
                stopSelf()
            }
            ACTION_OPEN -> {
                openPanel()
            }
            ACTION_OPEN_HUB -> {
                togglePicker(false)
            }
            ACTION_REFRESH -> {
                serviceScope.launch {
                    // First-run seeding, once only (see onCreate).
                    if (!panelPrefs.panelSeeded && panelPrefs.getPanelApps().isEmpty()) {
                        val topApps = repository.getTop5Apps()
                        if (topApps.isNotEmpty()) {
                            panelPrefs.setPanelApps(topApps)
                            panelPrefs.panelSeeded = true
                        }
                    }
                    refreshForegroundState()

                    sidePanelView?.updateStyles()
                    sidePanelView?.refreshIcons()
                    
                    // Update side/picker gravity in case it changed
                    val isRightSide = panelPrefs.panelSide == PanelPreferences.SIDE_RIGHT
                    sidePanelView?.let { panel ->
                        val lp = panel.layoutParams as? android.widget.FrameLayout.LayoutParams
                        if (lp != null) {
                            lp.gravity = if (isRightSide) Gravity.END or Gravity.CENTER_VERTICAL
                                         else Gravity.START or Gravity.CENTER_VERTICAL
                            panel.layoutParams = lp
                        }
                    }
                    pickerPanelView?.let { picker ->
                        val lp = picker.layoutParams as? android.widget.FrameLayout.LayoutParams
                        if (lp != null) {
                            lp.gravity = if (isRightSide) Gravity.END or Gravity.CENTER_VERTICAL
                                         else Gravity.START or Gravity.CENTER_VERTICAL
                            picker.layoutParams = lp
                        }
                    }

                    pickerPanelView?.applyTheme()
                    pickerPanelView?.clearIcons()
                    updateBlur(isPanelOpen)
                    
                    if (!isPanelOpen) {
                        isPickerOpen = false
                        pickerPanelView?.visibility = View.GONE
                        pickerPanelView?.onPickerHidden()
                        sidePanelView?.animatePickerToggle(false)
                    } else if (isPickerOpen) {
                        pickerPanelView?.loadApps() 
                    }
                    refreshApps()
                }
            }
            ACTION_FOREGROUND_CHANGED -> {
                // Light path for every foreground switch: only handle
                // visibility and game mode. The full REFRESH (styles, icons,
                // theme, blur, repository IO, recents disk read) runs only
                // for settings changes.
                serviceScope.launch {
                    refreshForegroundState()
                }
            }
            ACTION_CLOSE_PANEL -> closePanel(immediate = false)
            ACTION_SCREENSHOT -> {
                handler.postDelayed({ triggerScreenshot() }, SCREENSHOT_TRIGGER_DELAY_MS)
            }
            ACTION_SCREENSHOT_UI_RESTORE -> {
                restoreScreenshotUi()
                // The a11y service's delayed restore can arrive after the
                // user stopped the panel (or the service was recreated for
                // it): restore the UI, then stop instead of lingering in
                // the foreground while disabled.
                if (!panelPrefs.serviceEnabled) {
                    stopSelf()
                }
            }
            ACTION_SHOW_TEMP -> {
                addEdgeHandle(forceRecreate = false)
                addNotchHandle()
                edgeHandleView?.showTemporarily()
            }
            ACTION_TOGGLE_FLASHLIGHT -> toggleFlashlight()
            ACTION_LAUNCH_CAMERA -> launchCamera()
            ACTION_TOGGLE_ROTATION -> toggleAutoRotation()
            ACTION_OPEN_FAV_APP -> openFavoriteApp(intent.getStringExtra(EXTRA_FAV_SLOT))
        }
        return if (panelPrefs.serviceEnabled) START_STICKY else START_NOT_STICKY
    }

    fun triggerScreenshot() {
        // Hide overlays instantly (alpha = 0) so the system screenshot does not
        // capture the sidebar/picker/handle. Keep the panel open.
        val viewsToHide = listOfNotNull(
            rootLayout,
            sidePanelView,
            pickerPanelView,
            edgeHandleView,
            notchHandleView,
            indicatorText
        )
        // Cancel any in-flight restore from a previous capture.
        handler.removeCallbacks(screenshotRestoreRunnable)
        screenshotHideViews = viewsToHide
        screenshotPrevAlphas = viewsToHide.map { it.alpha }
        viewsToHide.forEach { view ->
            view.animate().cancel()
            view.alpha = 0f
        }

        // One frame so alpha=0 is committed, then dispatch capture.
        // Restore is driven by ACTION_SCREENSHOT_UI_RESTORE from the accessibility
        // service (preferred) with a safety timeout fallback.
        handler.post {
            if (!isAccessibilityServiceEnabled()) {
                // Accessibility is off: startService would create a plain,
                // non-connected instance whose performGlobalAction returns
                // false, so go straight to the automation fallback.
                takeScreenshotViaAutomation()
            } else {
                try {
                    val intent = Intent(this, PanelAccessibilityService::class.java).apply {
                        action = PanelAccessibilityService.ACTION_TAKE_SCREENSHOT
                    }
                    startService(intent)
                } catch (e: SecurityException) {
                    Log.e(TAG, "startService(PanelAccessibilityService) denied; falling back to AutomationManager.takeScreenshot()", e)
                    takeScreenshotViaAutomation()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to dispatch screenshot intent", e)
                }
            }
            // Safety net if accessibility never acks restore (OEM quirks).
            handler.postDelayed(screenshotRestoreRunnable, SCREENSHOT_RESTORE_DELAY_MS)
        }
    }

    /** Screenshot via the Shizuku/root automation engine, off the main thread
     *  (a shell call can take seconds on a cold engine probe). */
    private fun takeScreenshotViaAutomation() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                AutomationManager.takeScreenshot()
            } catch (e: Exception) {
                Log.e(TAG, "Automation screenshot fallback failed", e)
            }
        }
    }

    /** Lock screen via the Shizuku/root automation engine, off the main thread. */
    private fun lockScreenViaAutomation() {
        // Never block the main thread on a shell call (it can take seconds
        // on a cold engine probe); see ActionDispatcher.
        // The failure indicator must be posted back to the main thread.
        serviceScope.launch(Dispatchers.IO) {
            try {
                AutomationManager.performLockScreen()
            } catch (e: Exception) {
                Log.e(TAG, "Automation lock fallback failed", e)
                handler.post { showIndicator(getString(R.string.toast_lock_screen_failed)) }
            }
        }
    }

    private fun restoreScreenshotUi() {
        handler.removeCallbacks(screenshotRestoreRunnable)
        val views = screenshotHideViews ?: return
        val alphas = screenshotPrevAlphas
        views.forEachIndexed { index, view ->
            view.alpha = alphas?.getOrElse(index) { 1f } ?: 1f
        }
        screenshotHideViews = null
        screenshotPrevAlphas = null
    }

    private fun toggleFlashlight() {
        try {
            val manager = cameraManager ?: getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            
            val cameraId = manager.cameraIdList.firstOrNull { id ->
                val chars = manager.getCameraCharacteristics(id)
                chars.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            
            if (cameraId == null) {
                Log.e(TAG, "No flashlight-capable camera found!")
                return
            }

            // Redundant check: if our master boolean says ON, or if we have ANY active torch IDs
            val currentState = isFlashlightOn || activeTorches.isNotEmpty()
            val newState = !currentState
            
            Log.d(TAG, "toggleFlashlight: Toggling to $newState (current state: $currentState, activeTorches: $activeTorches)")
            
            lastManualTorchTime = System.currentTimeMillis()
            if (newState) {
                manager.setTorchMode(cameraId, true)
                lastManualTorchState[cameraId] = true
                // Update state AFTER the hardware call succeeds to prevent
                // desync if setTorchMode throws CameraAccessException.
                isFlashlightOn = true
                activeTorches.add(cameraId)
            } else {
                // Turn off EVERY torch that is on, not just the first
                // flash-capable camera: the torch that is actually on may
                // belong to a different camera ID (turned on elsewhere and
                // recorded by the callback). That torch would otherwise stay
                // on while the state says off.
                val ids = (activeTorches + cameraId).toList()
                for (id in ids) {
                    try {
                        manager.setTorchMode(id, false)
                        lastManualTorchState[id] = false
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to turn off torch $id", e)
                    }
                }
                isFlashlightOn = false
                activeTorches.clear()
            }
            showIndicator(if (newState) getString(R.string.indicator_flashlight_on) else getString(R.string.indicator_flashlight_off))
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle flashlight", e)
        }
    }

    private fun launchCamera() {
        try {
            val intent = Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            closePanel(immediate = true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch camera", e)
        }
    }

    private fun toggleAutoRotation() {
        try {
            if (!android.provider.Settings.System.canWrite(this)) {
                showIndicator(getString(R.string.toast_write_settings_required))
                val intent = Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = android.net.Uri.parse("package:$packageName")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                return
            }
            
            val current = android.provider.Settings.System.getInt(contentResolver, android.provider.Settings.System.ACCELEROMETER_ROTATION, 0)
            val newState = if (current == 1) 0 else 1
            android.provider.Settings.System.putInt(contentResolver, android.provider.Settings.System.ACCELEROMETER_ROTATION, newState)
            showIndicator(if (newState == 1) getString(R.string.indicator_rotation_on) else getString(R.string.indicator_rotation_off))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle rotation", e)
        }
    }

    private fun activateLockScreen() {
        try {
            // Mutual exclusion: locking during Black Screen or Content Picker is
            // meaningless (black screen already turned the screen off; the picker
            // owns a full-screen overlay). Bail out cleanly.
            if (blackScreenOverlay != null || contentPickerActive) return
            closePanel(immediate = true)
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            dpm.lockNow()
        } catch (e: SecurityException) {
            Log.e(TAG, "Device admin required for lock screen; trying fallbacks", e)
            if (isAccessibilityServiceEnabled()) {
                try {
                    val intent = Intent(this, PanelAccessibilityService::class.java).apply {
                        action = PanelAccessibilityService.ACTION_LOCK_SCREEN
                    }
                    startService(intent)
                } catch (e2: SecurityException) {
                    Log.e(TAG, "startService denied for lock; trying AutomationManager", e2)
                    lockScreenViaAutomation()
                } catch (e2: Exception) {
                    Log.e(TAG, "Accessibility lock fallback failed", e2)
                    showIndicator(getString(R.string.toast_lock_screen_failed))
                }
            } else {
                // Accessibility is off: startService would create a plain,
                // non-connected instance whose performGlobalAction returns
                // false, so skip it and go straight to automation.
                lockScreenViaAutomation()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to lock screen", e)
            showIndicator(getString(R.string.toast_lock_screen_failed))
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Content Picker — highlight interactive controls, tap to copy
    // ════════════════════════════════════════════════════════════════════

    private fun startContentPicker() {
        // Mutual exclusion: Content Picker and Black Screen both own full-screen
        // overlays. If Black Screen is active, don't stack a second overlay.
        if (contentPickerActive || blackScreenOverlay != null) return
        contentPickerActive = true
        closePanel(immediate = true)

        // Register the snapshot callback on the accessibility service BEFORE
        // requesting the first refresh so no race drops the initial frame.
        // The accessibility service runs on the main looper, but we guard with
        // postToMain anyway: if a future refactor ever calls the callback from
        // a worker thread, touching the overlay View off the main thread would
        // crash. The guard keeps the contract safe without changing behavior.
        PanelAccessibilityService.contentPickerCallback = { controls ->
            if (contentPickerActive) {
                handler.post {
                    if (!contentPickerActive) return@post
                    if (controls.isEmpty()) {
                        // No accessible controls (a11y service just restarted, or the
                        // foreground window exposes nothing). Count consecutive empty
                        // snapshots and give up after a short grace period so the user
                        // is not stuck with a full-screen overlay that does nothing.
                        contentPickerEmptySnapshots++
                        if (contentPickerEmptySnapshots >= 3) {
                            contentPickerEmptySnapshots = 0
                            showIndicator(getString(R.string.content_picker_no_controls))
                            // Flush whatever was accumulated before giving up, so no
                            // selections are lost on the a11y-unavailable path.
                            flushContentPickerToClipboard()
                            stopContentPicker()
                            return@post
                        }
                    } else {
                        contentPickerEmptySnapshots = 0
                    }
                    contentPickerControls = controls
                    drawContentPickerBorders()
                }
            }
        }

        // Full-screen dim overlay that draws yellow borders and receives taps.
        // Background #14141414 is a subtle dim so the user can tell the picker is
        // active and knows the tap target area, without fully hiding the page.
        val overlay = object : android.widget.FrameLayout(this) {
            private val borderPaint = android.graphics.Paint().apply {
                color = contentPickerBorderColor
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 3f * resources.displayMetrics.density
                isAntiAlias = true
            }
            override fun onDraw(canvas: android.graphics.Canvas) {
                super.onDraw(canvas)
                for (c in contentPickerControls) {
                    canvas.drawRect(c.bounds, borderPaint)
                }
            }
            override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_POINTER_DOWN -> {
                        // Two-finger touch anywhere dismisses the picker and returns
                        // to the app. All accumulated single-tap selections are
                        // joined with newlines and written to the clipboard first.
                        if (event.pointerCount >= 2) {
                            flushContentPickerToClipboard()
                            stopContentPicker()
                            return true
                        }
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        if (event.pointerCount <= 1) {
                            handleContentPickerTap(event.x, event.y)
                        }
                        return true
                    }
                }
                return true
            }
        }.apply {
            setBackgroundColor(android.graphics.Color.parseColor("#14141414"))
            isClickable = true
            // NOT focusable: a focusable TYPE_APPLICATION_OVERLAY becomes the
            // active window, so AccessibilityService.getRootInActiveWindow()
            // would return OUR overlay's node tree instead of the target app's.
            // Every node would then be skipped by the `pkg == packageName`
            // guard, producing zero controls (no borders drawn). Non-focusable
            // overlays still receive touch events when they sit on top of the
            // window stack, so tap-to-copy and tap-empty-to-dismiss keep working.
            isFocusable = false
            isFocusableInTouchMode = false
            // In-overlay feedback label (top-center) so feedback is visible even
            // while the picker covers the screen and the host rootLayout is gone.
            contentPickerFeedbackText = android.widget.TextView(this@FloatingPanelService).apply {
                setTextColor(android.graphics.Color.WHITE)
                textSize = 14f
                setPadding((16 * resources.displayMetrics.density).toInt(),
                           (10 * resources.displayMetrics.density).toInt(),
                           (16 * resources.displayMetrics.density).toInt(),
                           (10 * resources.displayMetrics.density).toInt())
                gravity = android.view.Gravity.CENTER
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.parseColor("#E6303030"))
                    cornerRadius = 24f * resources.displayMetrics.density
                }
                visibility = android.view.View.GONE
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
                    topMargin = (40 * resources.displayMetrics.density).toInt()
                }
            }.also { addView(it) }
        }

        contentPickerOverlay = overlay
        contentPickerOverlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        try {
            windowManager.addView(overlay, contentPickerOverlayParams)
        } catch (e: Exception) {
            contentPickerActive = false
            PanelAccessibilityService.contentPickerCallback = null
            contentPickerEmptySnapshots = 0
            Log.e(TAG, "Failed to add control picker overlay", e)
            return
        }

        // Request the first snapshot, then keep refreshing on the shared interval
        // (1200ms — frequent enough to track scrolling pages without hammering
        // the accessibility tree traversal on the main thread).
        requestContentPickerRefresh()
        handler.postDelayed(contentPickerRefreshRunnable, CONTENT_PICKER_REFRESH_MS)
        showContentPickerFeedback(getString(R.string.content_picker_hint))
    }

    private fun requestContentPickerRefresh() {
        try {
            val intent = Intent(this, PanelAccessibilityService::class.java).apply {
                action = PanelAccessibilityService.ACTION_CONTENT_PICK_REFRESH
            }
            startService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request control picker refresh", e)
        }
    }

    private fun refreshContentPickerBorders() {
        if (!contentPickerActive) return
        requestContentPickerRefresh()
        handler.postDelayed(contentPickerRefreshRunnable, CONTENT_PICKER_REFRESH_MS)
    }

    private fun drawContentPickerBorders() {
        contentPickerOverlay?.invalidate()
    }

    private fun handleContentPickerTap(x: Float, y: Float) {
        val controls = contentPickerControls
        // Find the smallest control containing the tap point (most specific hit).
        var best: ContentInfo? = null
        var bestArea = Long.MAX_VALUE
        for (c in controls) {
            if (c.bounds.contains(x.toInt(), y.toInt())) {
                val area = c.bounds.width().toLong() * c.bounds.height().toLong()
                if (area < bestArea) {
                    bestArea = area
                    best = c
                }
            }
        }
        if (best != null) {
            accumulateContentInfo(best)
        } else {
            // Single-tap on empty area dismisses the picker exactly like the
            // two-finger gesture: flush accumulated selections to the clipboard,
            // then close. (Tapping a control keeps the picker open for more picks.)
            flushContentPickerToClipboard()
            stopContentPicker()
        }
        // NOTE: tapping a control keeps the picker open so the user can select
        // multiple controls; the joined clipboard write happens on dismissal.
    }

    /** Show transient feedback inside the Content Picker overlay (top-center).
     *  Unlike Toast, this is guaranteed visible because the overlay is the top
     *  window and the host rootLayout is removed while picking. */
    private fun showContentPickerFeedback(text: String) {
        val label = contentPickerFeedbackText ?: return
        handler.removeCallbacks(contentPickerFeedbackHideRunnable)
        label.text = text
        label.visibility = android.view.View.VISIBLE
        label.alpha = 1f
        label.animate().cancel()
        handler.postDelayed(contentPickerFeedbackHideRunnable, INDICATOR_SHOW_DURATION_MS)
    }

    /** Extract copyable text from a control and append it to the accumulation
     *  list (priority: text → contentDescription → viewIdResourceName). */
    private fun accumulateContentInfo(c: ContentInfo) {
        val text: String? = when {
            !c.text.isNullOrBlank() -> c.text
            !c.contentDescription.isNullOrBlank() -> c.contentDescription
            !c.viewIdResourceName.isNullOrBlank() -> c.viewIdResourceName
            else -> null
        }
        if (text == null) return  // nothing copyable → skip
        contentPickerAccumulated.add(text)
        // Per-tap feedback inside the overlay: user sees the control content was
        // read and appended. The "Copied to clipboard" confirmation is delivered
        // when the picker flushes on dismiss.
        showContentPickerFeedback(getString(R.string.content_picker_concatenated))
    }

    /** Show a standalone confirmation overlay (bottom-center, same look and
     *  duration as the regular indicator) that survives the Content Picker
     *  overlay dismissal. The mini window is independent of rootLayout, Toast,
     *  and PopupWindow anchor constraints, so it is reliably visible. */
    private fun showConfirmOverlay(text: String) {
        confirmOverlay?.let { old ->
            try { if (old.isAttachedToWindow) windowManager.removeView(old) } catch (e: Exception) {}
        }
        val density = resources.displayMetrics.density
        val label = android.widget.TextView(this).apply {
            setTextColor(android.graphics.Color.WHITE)
            textSize = 14f
            setPadding((16 * density).toInt(), (10 * density).toInt(), (16 * density).toInt(), (10 * density).toInt())
            gravity = android.view.Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#E6303030"))
                cornerRadius = 24f * density
            }
            this.text = text
            elevation = 8f * density
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
            // WindowManager.LayoutParams has no bottomMargin; use y offset
            // (negative = upward from the BOTTOM gravity anchor).
            y = -(90 * density).toInt()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        confirmOverlay = label
        try {
            windowManager.addView(label, params)
            // Audit fix: the delayed runnable previously dereferenced the
            // shared confirmOverlay field, not the captured label — a second
            // call within 1,500ms made the first runnable remove/null the
            // second overlay early. Capture this call's label and only
            // clear the field if it still holds that label.
            handler.postDelayed({
                if (label.isAttachedToWindow) {
                    try { windowManager.removeView(label) } catch (e: Exception) {}
                }
                if (confirmOverlay === label) {
                    confirmOverlay = null
                }
            }, INDICATOR_SHOW_DURATION_MS)
        } catch (e: Exception) {
            confirmOverlay = null
            Log.e(TAG, "Failed to show confirm overlay", e)
        }
    }

    /** Join every accumulated snippet with newlines and write them to the
     *  clipboard once when the picker is dismissed via two-finger gesture. */
    private fun flushContentPickerToClipboard() {
        if (contentPickerAccumulated.isEmpty()) return
        val joined = contentPickerAccumulated.joinToString("\n")
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Content Picker", joined))
            // Confirm the flush (actual clipboard write) once via a standalone
            // mini-overlay. It outlives the picker overlay dismissal (independent
            // window), so the user reliably sees "Copied to clipboard" even when
            // system Toast from a Service context is dropped by the OS.
            showConfirmOverlay(getString(R.string.content_picker_copied))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write accumulated control info", e)
        }
    }

    private fun stopContentPicker() {
        if (!contentPickerActive) return
        contentPickerActive = false
        handler.removeCallbacks(contentPickerRefreshRunnable)
        contentPickerEmptySnapshots = 0
        contentPickerAccumulated.clear()
        PanelAccessibilityService.contentPickerCallback = null
        contentPickerControls = emptyList()
        contentPickerOverlay?.let { ov ->
            try {
                if (ov.isAttachedToWindow) windowManager.removeView(ov)
            } catch (e: Exception) {}
        }
        contentPickerOverlay = null
        contentPickerOverlayParams = null
    }

    private fun openFavoriteApp(slotKey: String? = null) {
        val pkg = if (slotKey != null) panelPrefs.favoriteAppPackageFor(slotKey) else panelPrefs.favoriteAppPackage
        if (pkg.isEmpty()) {
            showIndicator(getString(R.string.toast_fav_app_unset))
            return
        }
        try {
            val intent = packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                closePanel(immediate = true)
            } else {
                showIndicator(getString(R.string.toast_app_not_found))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open favorite app", e)
        }
    }

    private fun activateBlackScreen() {
        try {
            // Guard against re-entrant activation — if overlay already exists, ignore.
            if (blackScreenOverlay != null) return
            // Mutual exclusion with Content Picker: both own full-screen overlays.
            if (contentPickerActive) return

            closePanel()
            val resolver = contentResolver

            // 1. Save brightness state + dim to minimum via the pure helper.
            //    The lambda bridge lets us call Settings.System without making
            //    the helper depend on ContentResolver (keeps it unit-testable).
            val canWrite = android.provider.Settings.System.canWrite(this)
            val snapshot = saveAndDimBrightness(
                readAutoMode = {
                    android.provider.Settings.System.getInt(
                        resolver,
                        android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,
                        android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                    ) == android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                },
                readBrightness = {
                    android.provider.Settings.System.getInt(
                        resolver,
                        android.provider.Settings.System.SCREEN_BRIGHTNESS,
                        125
                    )
                },
                setAutoMode = { auto ->
                    if (canWrite) {
                        android.provider.Settings.System.putInt(
                            resolver,
                            android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,
                            if (auto) android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                            else android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                        )
                    }
                },
                setBrightness = { value ->
                    if (canWrite) {
                        android.provider.Settings.System.putInt(
                            resolver,
                            android.provider.Settings.System.SCREEN_BRIGHTNESS,
                            value
                        )
                    }
                },
                setFloatBrightness = if (canWrite && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {{
                    try {
                        android.provider.Settings.System.putFloat(
                            resolver,
                            "screen_brightness_float",
                            it
                        )
                    } catch (_: Exception) {}
                }} else null,
                // Capture the pre-dim float so dismiss can restore it (A2):
                // on Android 12+ the system slider follows the float, not
                // the int, so restoring only the int leaves the device dim.
                readFloatBrightness = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    {
                        val intBrightness = android.provider.Settings.System.getInt(
                            resolver,
                            android.provider.Settings.System.SCREEN_BRIGHTNESS,
                            125
                        )
                        try {
                            android.provider.Settings.System.getFloat(
                                resolver,
                                "screen_brightness_float",
                                intBrightness / 255f
                            )
                        } catch (_: Exception) {
                            intBrightness / 255f
                        }
                    }
                } else null
            )
            savedBrightnessMode = if (snapshot.autoMode)
                android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
            else
                android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            savedBrightness = snapshot.brightness
            savedBrightnessFloat = snapshot.floatBrightness

            // 4. Wake lock strategy — two modes:
            //    - True screen-off (device admin active + pref enabled):
            //      PARTIAL_WAKE_LOCK keeps the CPU running so the foreground
            //      app stays alive, while the screen itself is turned off via
            //      a minimal SCREEN_OFF_TIMEOUT and keyguard is suppressed.
            //    - Legacy dim overlay: no wake lock — the overlay window's
            //      FLAG_KEEP_SCREEN_ON keeps the screen on, which is the
            //      supported replacement for the deprecated
            //      SCREEN_BRIGHT_WAKE_LOCK.
            val useTrueScreenOff = try {
                panelPrefs.enableDeviceAdmin &&
                    (getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager)
                        .isAdminActive(android.content.ComponentName(this, SmartEdgeDeviceAdminReceiver::class.java))
            } catch (e: Exception) {
                false
            }
            trueScreenOffActive = useTrueScreenOff
            try {
                val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                if (useTrueScreenOff) {
                    // CPU stays awake; screen may be off.
                    blackScreenWakeLock = powerManager.newWakeLock(
                        android.os.PowerManager.PARTIAL_WAKE_LOCK,
                        "SmartEdge:BlackScreen"
                    )
                    blackScreenWakeLock?.acquire() // held until Black Screen is dismissed (no fixed timeout so long-running background tasks survive past 10 min)
                    // Suppress the lock screen so waking returns to the app.
                    // Device-owner-only; a plain device admin cannot do this.
                    disableKeyguardIfDeviceOwner()
                    // Drive a real screen-off with the shortest supported timeout.
                    try {
                        savedScreenOffTimeout = android.provider.Settings.System.getInt(
                            contentResolver,
                            android.provider.Settings.System.SCREEN_OFF_TIMEOUT,
                            30000
                        )
                        android.provider.Settings.System.putInt(
                            contentResolver,
                            android.provider.Settings.System.SCREEN_OFF_TIMEOUT,
                            SCREEN_OFF_TIMEOUT_MIN_MS
                        )
                    } catch (e: Exception) {}
                }
                // No wake lock in legacy dim mode: the overlay below carries
                // FLAG_KEEP_SCREEN_ON, the supported replacement for the
                // deprecated SCREEN_BRIGHT_WAKE_LOCK.
            } catch (_: Exception) {}

            // Persist the saved brightness/timeout so a process kill while
            // Black Screen is active does not leave the device dimmed with
            // a 15s timeout: the next service start restores them.
            persistBlackScreenSession()

            // 5. Create full-screen black overlay with FLAG_KEEP_SCREEN_ON
            //    (replaces deprecated FULL_WAKE_LOCK for API 17+)
            // Focusable overlay so KEYCODE_BACK is delivered here and can be
            // fully consumed (must not reach the foreground app underneath).
            blackScreenOverlay = object : android.widget.FrameLayout(this) {
                override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
                    if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                        // Consume DOWN and UP so the back is not forwarded.
                        if (event.action == android.view.KeyEvent.ACTION_UP) {
                            deactivateBlackScreen()
                        }
                        return true
                    }
                    return super.dispatchKeyEvent(event)
                }

                override fun onKeyPreIme(keyCode: Int, event: android.view.KeyEvent): Boolean {
                    if (keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                        if (event.action == android.view.KeyEvent.ACTION_UP) {
                            deactivateBlackScreen()
                        }
                        return true
                    }
                    return super.onKeyPreIme(keyCode, event)
                }
            }.apply {
                setBackgroundColor(android.graphics.Color.BLACK)
                isClickable = true
                isFocusable = true
                isFocusableInTouchMode = true
                // In true screen-off mode the screen is physically off, so there is
                // nothing to keep on; keepScreenOn would fight the screen-off timer.
                keepScreenOn = !trueScreenOffActive
                // Status & navigation bars are hidden once the overlay is
                // attached (hideSystemBarsForOverlay, called after addView)
                // via the platform WindowInsetsController on API 30+ (legacy
                // systemUiVisibility flags below that). Transient bars
                // auto-hide again after a swipe, so no re-hide listener
                // is needed.
                // Tap no longer exits — Back is the exit gesture.
                setOnFocusChangeListener { v, hasFocus ->
                    if (!hasFocus && blackScreenOverlay === v) {
                        // Reclaim focus so gesture-nav / system UI cannot steal Back.
                        v.post { if (blackScreenOverlay === v) v.requestFocus() }
                    }
                }
            }

            // FLAG_NOT_FOCUSABLE is intentionally OMITTED — the overlay MUST
            // receive touch events so Back can be delivered and consumed here. Without
            // focus the WindowManager also draws system bars on top of the
            // overlay, defeating immersive mode.
            // FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS tells WM we handle the area
            // behind status/nav bars ourselves, so our black fill covers them.
            // FLAG_LAYOUT_IN_SCREEN + FLAG_LAYOUT_NO_LIMITS extends the overlay
            // into the system-bar regions, and WindowInsetsControllerCompat
            // (applied after addView) hides the bars themselves.
            blackScreenOverlayParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                (if (trueScreenOffActive) 0 else WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) or
                WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
                PixelFormat.OPAQUE
            ).apply {
                // Extend into display cutouts (notch, hole-punch, etc.) so
                // the black fill reaches the physical screen edge, not just
                // the safe-area inset. Without this, apps that draw behind
                // cutouts leave a bright strip at the top.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }

            handler.postDelayed({
                blackScreenOverlay?.let { overlay ->
                    blackScreenOverlayParams?.let { params ->
                        try {
                            windowManager.addView(overlay, params)
                            // Ensure the overlay owns focus so Back is delivered here.
                            overlay.isFocusableInTouchMode = true
                            overlay.requestFocus()
                            hideSystemBarsForOverlay(overlay)
                        } catch (e: Exception) {
                            // addView runs on a delay, outside the outer
                            // try/catch: a failure here (e.g. the overlay
                            // permission was revoked after activation) used
                            // to leave the device dimmed at minimum
                            // brightness with a wake lock held and no
                            // overlay. Tear the session down — that restores
                            // brightness, releases the wake lock and clears
                            // the overlay — and report the failure.
                            Log.e(TAG, "Failed to add black screen overlay", e)
                            deactivateBlackScreen()
                            showIndicator(getString(R.string.toast_black_screen_failed))
                        }
                    }
                }
            }, BLACK_SCREEN_OVERLAY_DELAY_MS)

            showIndicator(getString(R.string.indicator_black_screen_on))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to activate black screen", e)
            showIndicator(getString(R.string.toast_black_screen_failed))
        }
    }

    private fun deactivateBlackScreen() {
        // Idempotent: Back DOWN/UP both return true; only the first real exit should run.
        if (blackScreenOverlay == null && blackScreenWakeLock == null) return
        try {
            // 1. FIRST: exit immersive mode — restore status & navigation bars
            blackScreenOverlay?.let { overlay ->
                showSystemBarsForOverlay(overlay)
            }

            // 2. Remove the black overlay safely (removeViewImmediate avoids WindowLeaked)
            blackScreenOverlay?.let { overlay ->
                try {
                    if (overlay.isAttachedToWindow) {
                        windowManager.removeViewImmediate(overlay)
                    }
                } catch (e: Exception) {}
            }
            blackScreenOverlay = null
            blackScreenOverlayParams = null

            // 3. Release wake lock if still held (legacy guard for old instances)
            blackScreenWakeLock?.let { wl ->
                if (wl.isHeld) wl.release()
            }
            blackScreenWakeLock = null

            // 3b. Restore the screen-off timeout + re-enable keyguard when a true
            //     screen-off session is being torn down.
            if (trueScreenOffActive) {
                trueScreenOffActive = false
                try {
                    if (savedScreenOffTimeout > 0) {
                        android.provider.Settings.System.putInt(
                            contentResolver,
                            android.provider.Settings.System.SCREEN_OFF_TIMEOUT,
                            savedScreenOffTimeout
                        )
                        savedScreenOffTimeout = -1
                    }
                } catch (e: Exception) {}
                reenableKeyguardIfDeviceOwner()
            }

            // 4. Restore brightness + auto-mode via the pure helper.
            //    The BrightnessSnapshot was constructed by activateBlackScreen
            //    and stored in savedBrightness / savedBrightnessMode fields.
            restoreSavedBrightness()
            clearBlackScreenSession()

            showIndicator(getString(R.string.indicator_black_screen_off))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deactivate black screen", e)
        }
    }

    /**
     * Restore the brightness saved by [activateBlackScreen], without any
     * UI (no indicator overlay). Used by [deactivateBlackScreen] and by
     * [onDestroy]: if the service is killed while Black Screen is active,
     * onDestroy must restore brightness too, or the device is left stuck
     * at minimum brightness.
     */
    private fun restoreSavedBrightness() {
        try {
            val resolver = contentResolver
            val canWrite = android.provider.Settings.System.canWrite(this)
            restoreBrightness(
                snapshot = BrightnessSnapshot(
                    autoMode = savedBrightnessMode == android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC,
                    brightness = savedBrightness,
                    floatBrightness = savedBrightnessFloat
                ),
                setBrightness = { value ->
                    if (canWrite) {
                        android.provider.Settings.System.putInt(
                            resolver,
                            android.provider.Settings.System.SCREEN_BRIGHTNESS,
                            value
                        )
                    }
                },
                setAutoMode = { auto ->
                    if (canWrite) {
                        android.provider.Settings.System.putInt(
                            resolver,
                            android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,
                            if (auto) android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                            else android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                        )
                    }
                },
                setFloatBrightness = if (canWrite && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { value ->
                    try {
                        android.provider.Settings.System.putFloat(
                            resolver,
                            "screen_brightness_float",
                            value
                        )
                    } catch (_: Exception) {
                        try {
                            android.provider.Settings.System.putString(
                                resolver,
                                "screen_brightness_float",
                                value.toString()
                            )
                        } catch (_: Exception) {}
                    }
                } else null
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore brightness", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        // Audit fix: restore brightness if Black Screen was active. The old
        // onDestroy only removed the overlay and released the wake lock, so
        // a kill during Black Screen left the device at minimum brightness.
        if (blackScreenOverlay != null) {
            restoreSavedBrightness()
        }
        try {
            cameraManager?.unregisterTorchCallback(torchCallback)
        } catch (e: Exception) {}

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            TileService.requestListeningState(this, android.content.ComponentName(this, PanelTileService::class.java))
        }
        notificationChangedListener?.let {
            NotificationTrackingService.removeOnNotificationsChangedListener(it)
        }
        notificationChangedListener = null
        // Audit S3: clear the AutomationManager.onEngineLost callback so the
        // single-engine-singleton doesn't keep `this` alive past the service's
        // death. Without this the Singleton → Service reference would leak the
        // entire WindowManager overlay tree across service restarts.
        AutomationManager.onEngineLost = null
        // Remove the standalone confirmation overlay if it is still attached
        // (avoids WindowLeaked when the service dies before the 1.5s timeout).
        confirmOverlay?.let { ov ->
            try { if (ov.isAttachedToWindow) windowManager.removeView(ov) } catch (e: Exception) {}
        }
        confirmOverlay = null
        // Audit L6: cancel every pending message on the foreground Handler.
        // `handler.removeCallbacksAndMessages(null)` clears the indicator-fade
        // runnable as well as anything scheduled via postDelayed above.
        handler.removeCallbacksAndMessages(null)
        // Drop screenshot-restore state so it doesn't pin Views across restarts.
        screenshotHideViews = null
        screenshotPrevAlphas = null
        // Remove the standalone indicator overlay if it is still attached
        // (avoids WindowLeaked when the service dies while it is visible).
        // Round-13 audit M2: also nil out the TextView reference — the
        // property holds a hard reference to the View object, which
        // transitively anchors the View internals (mContext, mAttachInfo,
        // mListenerInfo). Across service restarts that pile up. The
        // fade-runnable cancellation above removes the timing path; this
        // drops the memory anchor.
        indicatorText?.let { tv ->
            try { if (tv.isAttachedToWindow) windowManager.removeView(tv) } catch (e: Exception) {}
        }
        indicatorFadeRunnable = null
        indicatorText = null
        // Audit L2: cancel the AppRepository's preload scope BEFORE tearing
        // down serviceScope so its inflight icon loads cannot race the cancel.
        // Guarded because `repository` is `lateinit` and may not have been
        // initialized yet if onCreate threw before reaching the assignment.
        if (::repository.isInitialized) repository.clear()
        serviceScope.cancel()
        try {
            unregisterReceiver(systemDialogsReceiver)
            unregisterReceiver(packageReceiver)
        } catch (e: Exception) {}
        removeView(edgeHandleView)
        removeView(notchHandleView)
        removeView(rootLayout)
        // Release black screen resources if active
        val wasBlackScreenActive = blackScreenOverlay != null || trueScreenOffActive
        blackScreenWakeLock?.let { if (it.isHeld) it.release() }
        blackScreenWakeLock = null
        blackScreenOverlay?.let { overlay ->
            try {
                if (overlay.isAttachedToWindow) {
                    windowManager.removeViewImmediate(overlay)
                }
            } catch (e: Exception) {}
        }
        blackScreenOverlay = null
        blackScreenOverlayParams = null
        // Drop any active control picker overlay + callback.
        stopContentPicker()
        // Lifecycle guard: if the service dies while a Black Screen session
        // is active, restore the brightness the dim overlay lowered and
        // the timeout/keyguard a true screen-off session changed, so the
        // device is not left dimmed, unlocked, or ultra-short-timeout.
        if (wasBlackScreenActive) {
            restoreSavedBrightness()
            if (trueScreenOffActive) {
                trueScreenOffActive = false
                try {
                    if (savedScreenOffTimeout > 0) {
                        android.provider.Settings.System.putInt(
                            contentResolver,
                            android.provider.Settings.System.SCREEN_OFF_TIMEOUT,
                            savedScreenOffTimeout
                        )
                    }
                    savedScreenOffTimeout = -1
                } catch (e: Exception) {}
                reenableKeyguardIfDeviceOwner()
            }
            clearBlackScreenSession()
        }
    }

    // Audit U-Med: this service is the live overlay panel; it MUST outlive
    // any short-lived ConfigurationActivity the user swipes from Recents,
    // because that's exactly how the Android swipe-from-recents UX is
    // expected to behave for foreground services. Calling stopSelf() on
    // task removal terminated the entire sidebar the moment the user
    // cleaned up the launcher task — the most unexpected failure mode we
    // have in the wild. The service still terminates voluntarily through
    // Notification-stop / quick-tile / explicit user action or through the
    // system's own low-memory killer. There is deliberately no
    // onTaskRemoved override: the default (do nothing) is the policy.
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)

        // Audit M6: instead of slamming the panel closed on rotation, reflow its
        // layout in place via updateStyles() + updateSideLayout(). screenHeightPx
        // changes are re-read inside updateSideLayout(), so column counts, max
        // recycler height, and gravity all recompute correctly. The handle is
        // re-added below to keep WindowManager bounds in sync with the new
        // orientation.
        if (isPanelOpen) {
            sidePanelView?.updateStyles()
            sidePanelView?.updateSideLayout()
        }

        if (panelPrefs.serviceEnabled) {
            val isLandscape = newConfig.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            if (isLandscape && !panelPrefs.showInLandscape) {
                edgeHandleView?.visibility = View.GONE
            } else {
                // Re-add the handle to guarantee WindowManager bounds are perfectly mapped to the new orientation.
                addEdgeHandle()
                edgeHandleView?.visibility = if (isPanelOpen) View.GONE else View.VISIBLE
            }
        }
    }

    private fun removeView(view: View?) {
        if (view == null) return
        // Cancel any live SpringAnimator animation first: a running spring
        // keeps the view (and its window params) alive past detach.
        SpringAnimator.cancel(view)
        try {
            // More aggressive removal to ensure no 'permanent' ghosts remain
            if (view.isAttachedToWindow) {
                windowManager.removeViewImmediate(view)
            } else {
                // Try removing anyway to catch any edge cases
                windowManager.removeView(view)
            }
        } catch (e: Exception) {
            // View was likely already removed or never attached
        }
    }

    /**
     * Re-checks handle visibility and game mode for the current foreground
     * app. This is the light path run on every foreground switch
     * (ACTION_FOREGROUND_CHANGED) and as the first step of the full
     * ACTION_REFRESH. It does two PackageManager queries (via
     * isCurrentPackageLauncher) but no repository IO, no recents disk read,
     * and no style/icon/theme/blur work.
     */
    private fun refreshForegroundState() {
        val isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val shouldShowHandle = if (isLandscape && !panelPrefs.showInLandscape) false
                              else if (panelPrefs.onlyOnHome && !isCurrentPackageLauncher()) false
                              else true

        if (!shouldShowHandle) {
            edgeHandleView?.visibility = View.GONE
            // Also remove it from WM to be sure it doesn't block touches
            removeView(edgeHandleView)
            edgeHandleView = null

            // Also hide notch handle if onlyOnHome is active and not on home
            notchHandleView?.visibility = View.GONE
        } else {
            addEdgeHandle(forceRecreate = false)
            addNotchHandle()
            edgeHandleView?.visibility = if (isPanelOpen) View.GONE else View.VISIBLE
            notchHandleView?.visibility = if (isPanelOpen) View.GONE else View.VISIBLE
        }

        // Update game mode state: user-curated list OR dynamic landscape+fullscreen+non-video.
        val currentPkg = panelPrefs.currentForegroundPackage
        val isInGameList = panelPrefs.getGameApps().contains(currentPkg)
        val isDynamicGame = if (isInGameList) true else {
            val isLandscapeOrientation = resources.configuration.orientation ==
                    android.content.res.Configuration.ORIENTATION_LANDSCAPE
            isLandscapeOrientation && currentPkg.isNotBlank() &&
                    !isCurrentPackageLauncher() &&
                    !isVideoPlayerPackage(currentPkg)
        }
        edgeHandleView?.isGameActive = isDynamicGame
    }

    private fun isCurrentPackageLauncher(): Boolean {
        val currentPkg = panelPrefs.currentForegroundPackage
        if (currentPkg.isEmpty() || currentPkg == packageName) return true // Assume home if unknown or if in our own app

        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
        }
        val resolveInfo = packageManager.resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        val homePkg = resolveInfo?.activityInfo?.packageName
        
        // Also check all installed launchers as some devices have multiple or third-party ones
        val allLaunchers = packageManager.queryIntentActivities(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.packageName }
        
        return currentPkg == homePkg || allLaunchers.contains(currentPkg) || currentPkg == "com.android.systemui"
    }

    /**
     * Edge-handle window height in px. Pill visible: the configured handle
     * height. Pill hidden: 1.5x that height — the invisible edge strip is
     * still a touch target, but it must not span 60% of the screen. The
     * create path used 1.5x and the update path used 60% of screen height,
     * so the handle silently changed size after any preference edit; one
     * formula (the first-run 1.5x) is used by both paths now.
     */
    private fun handleHeightPx(isPillVisible: Boolean): Int {
        return if (isPillVisible) dpToPx(panelPrefs.handleHeight)
        else dpToPx((panelPrefs.handleHeight * 1.5f).toInt())
    }

    /**
     * Single source of truth for whether the edge handle may be shown.
     * Combines every policy that gates it: at least one trigger enabled,
     * not landscape when landscape is disabled, home-only when not on the
     * launcher, and an active engine (Accessibility or Automation).
     * Used by [addEdgeHandle]'s creation gate and by
     * [syncHandleVisibility]; previously each call site re-derived a
     * subset, so closePanel could resurrect the handle in a state where
     * addEdgeHandle would refuse to create it.
     */
    private fun shouldShowHandle(): Boolean {
        val anyTriggerEnabled = panelPrefs.gesturesEnabled ||
            panelPrefs.tapToOpen ||
            panelPrefs.doubleTapToOpen ||
            panelPrefs.tripleTapToOpen
        if (!anyTriggerEnabled) return false
        val isLandscape = resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        if (isLandscape && !panelPrefs.showInLandscape) return false
        if (panelPrefs.onlyOnHome && !isCurrentPackageLauncher()) return false
        // --- MANDATORY ENGINE CHECK ---
        // If neither Accessibility is ON nor Native Automation is POSSIBLE, we must NOT show the handle.
        return isEngineAvailable()
    }

    /**
     * Re-apply the handle policy to the existing handle view. Shows it when
     * the policy allows, removes it from WindowManager when it doesn't (a
     * merely GONE window could still intercept edge touches). Call this
     * instead of setting `edgeHandleView.visibility = VISIBLE` directly.
     */
    private fun syncHandleVisibility() {
        val handle = edgeHandleView ?: return
        if (shouldShowHandle()) {
            handle.visibility = View.VISIBLE
        } else {
            removeView(handle)
            edgeHandleView = null
        }
    }

    private fun addEdgeHandle(forceRecreate: Boolean = false) {
        if (!shouldShowHandle()) {
            removeView(edgeHandleView)
            edgeHandleView = null
            return
        }

        val isRight = panelPrefs.panelSide == PanelPreferences.SIDE_RIGHT
        val isPillVisible = panelPrefs.showPill

        if (edgeHandleView != null && !forceRecreate) {
            val params = edgeHandleView?.layoutParams as? WindowManager.LayoutParams
            if (params != null) {
                // 1. Update gravity if side changed
                val newGravity = if (isRight) Gravity.END or Gravity.CENTER_VERTICAL
                                 else Gravity.START or Gravity.CENTER_VERTICAL
                params.gravity = newGravity

                // 2. Update size and position
                val density = resources.displayMetrics.density
                val screenH = resources.displayMetrics.heightPixels
                val safeMargin = (10 * density).toInt()
                val h = cappedHandleHeightPx(handleHeightPx(isPillVisible), screenH, safeMargin)
                val maxOffset = maxHandleOffsetPx(screenH, h, safeMargin)
                val requestedOffset = (panelPrefs.handleVerticalOffset * density).toInt()

                params.width = (panelPrefs.handleWidth * density).toInt()
                params.height = h
                params.y = requestedOffset.coerceIn(-maxOffset, maxOffset)

                // Keep the handle glued to the physical screen edge even in
                // landscape when a display cutout sits on the same side —
                // otherwise the system insets the window into the cutout area.
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    params.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
                
                try {
                    edgeHandleView?.let { windowManager.updateViewLayout(it, params) }
                } catch (e: Exception) {}
            }
            
            edgeHandleView?.updateState(
                isRight, 
                isPillVisible, 
                panelPrefs.panelOpacity
            )
            return
        }

        removeView(edgeHandleView)
        edgeHandleView = null

        edgeHandleView = EdgeHandleView(this).apply {
            onTrigger = {
                refreshApps {
                    openPanel()
                }
            }
            onAdjustBrightness = { deltaPercent ->
                adjustBrightness(deltaPercent)
            }
            onAdjustVolume = { steps ->
                adjustVolume(steps)
            }
            onSideChanged = { _ ->
                // Pill was dragged to the opposite edge — sync the whole service UI
                sidePanelView?.updateSideLayout()
            }
            isRightSide = isRight
            showPill = isPillVisible
            alpha = panelPrefs.panelOpacity / 100f
        }

        val handleWidth = panelPrefs.handleWidth // Use user-defined width
        val handleHeight = cappedHandleHeightPx(
            handleHeightPx(isPillVisible),
            resources.displayMetrics.heightPixels,
            dpToPx(10)
        )

        // Fix: Use FLAG_LAYOUT_NO_LIMITS carefully or ensure GRAVITY_CENTER doesn't overflow
        val params = WindowManager.LayoutParams(
            dpToPx(handleWidth),
            handleHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = if (isRight) Gravity.END or Gravity.CENTER_VERTICAL
                      else Gravity.START or Gravity.CENTER_VERTICAL
            
            // Calculate absolute max offset to keep handle on screen
            val screenH = resources.displayMetrics.heightPixels
            val safeMargin = dpToPx(10) // Keep away from extreme top/bottom edges
            val maxOffset = maxHandleOffsetPx(screenH, handleHeight, safeMargin)
            
            val requestedOffset = dpToPx(panelPrefs.handleVerticalOffset)
            y = requestedOffset.coerceIn(-maxOffset, maxOffset)

            // Keep the handle glued to the physical screen edge even in
            // landscape when a display cutout sits on the same side —
            // otherwise the system insets the window into the cutout area.
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            
            // Log.d(TAG, "Handle Params: width=$width, height=$height, y=$y (requested=$requestedOffset, max=$maxOffset)")
        }

        // Crash fix: same BadTokenException / SecurityException family as
        // openPanel(). addEdgeHandle is called from FloatingPanelService.onCreate
        // without exception handling, so any WMS hiccup here killed the service
        // process on first launch. Catch, log, and reset the handle reference so
        // the service stays alive — the user can still access other UI in the
        // app while the permission issue is investigated.
        try {
            edgeHandleView?.let { windowManager.addView(it, params) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add edge handle overlay", e)
            try { edgeHandleView?.let { windowManager.removeView(it) } } catch (_: Exception) {}
            edgeHandleView = null
        }
    }

    private fun addNotchHandle() {
        Log.d(TAG, "addNotchHandle called. Enabled: ${panelPrefs.notchGesturesEnabled}")
        if (!panelPrefs.notchGesturesEnabled) {
            removeView(notchHandleView)
            notchHandleView = null
            return
        }

        if (notchHandleView != null) {
            Log.d(TAG, "Notch handle already exists")
            return
        }

        notchHandleView = NotchHandleView(this)

        val params = WindowManager.LayoutParams(
            dpToPx(120), // Increased width for easier debugging
            dpToPx(60),  // Increased height for easier debugging
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        try {
            Log.d(TAG, "Adding notch handle to WindowManager")
            notchHandleView?.let { windowManager.addView(it, params) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add notch handle", e)
            // Drop the reference so the next addNotchHandle() call doesn't try to
            // re-attach a partially-attached view (would throw a different
            // unchecked exception on second attempt).
            try { notchHandleView?.let { windowManager.removeView(it) } } catch (_: Exception) {}
            notchHandleView = null
        }
    }

    private fun initSidePanel() {
        // Crash fix: SidePanelView constructor inflates layout XML and creates
        // a RecyclerView (adapter + view-bindings). On older devices or with
        // corrupted resources, View.<init> can throw Resources.NotFoundException
        // or InflateException — both propagate up and kill the service. The view
        // is optional (the service still functions for foreground notification
        // duty without it), so degrade-safe rather than crash.
        try {
            sidePanelView = SidePanelView(this).apply {
                onShowIndicator = { text -> showIndicator(text) }
                onClose = { closePanel() }
                onAddClick = { isEdit -> togglePicker(isEdit) }
                onScreenshot = {
                    triggerScreenshot()
                }
                onFolderOpen = { folderId ->
                    currentFolderId = folderId
                    refreshApps()
                }
                onBackNavigation = {
                    currentFolderId = null // Simple logic for now: only 1-level folders
                    refreshApps()
                }
            onToolClick = { toolId ->
                when (toolId) {
                    "smartedge.tool.screenshot" -> triggerScreenshot()
                    "smartedge.tool.blackscreen" -> activateBlackScreen()
                    "smartedge.tool.lockscreen" -> activateLockScreen()
                    "smartedge.tool.content_picker" -> startContentPicker()
                    // Volume/Brightness taps are handled in SidePanelView / PanelAppsAdapter
                    // with the shared long-press-drag gesture. Keep these as safe fallbacks
                    // that match the dashboard semantics and do NOT close the panel.
                    "smartedge.tool.volume_up" -> {
                        try {
                            val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                            audioManager.adjustStreamVolume(
                                android.media.AudioManager.STREAM_MUSIC,
                                android.media.AudioManager.ADJUST_SAME,
                                android.media.AudioManager.FLAG_SHOW_UI
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to show volume UI", e)
                        }
                    }
                    "smartedge.tool.brightness_up" -> {
                        try {
                            if (!android.provider.Settings.System.canWrite(this@FloatingPanelService)) {
                                val intent = Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                                    data = android.net.Uri.parse("package:$packageName")
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                startActivity(intent)
                            } else {
                                val resolver = contentResolver
                                val current = android.provider.Settings.System.getInt(
                                    resolver,
                                    android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,
                                    android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                                )
                                val next = if (current == android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
                                    android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                                else
                                    android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                                android.provider.Settings.System.putInt(
                                    resolver,
                                    android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,
                                    next
                                )
                                showIndicator(
                                    if (next == android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
                                        getString(R.string.indicator_auto_brightness_on)
                                    else
                                        getString(R.string.indicator_auto_brightness_off)
                                )
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to toggle auto brightness", e)
                        }
                    }
                }
            }
            onBlackScreen = {
                activateBlackScreen()
            }
            onLockScreen = {
                activateLockScreen()
            }
                visibility = View.GONE
            }
            refreshApps()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init side panel view", e)
            sidePanelView = null
        }
    }

    private fun initPickerPanel() {
        // Crash fix (parity with initSidePanel): AppPickerPanelView construction
        // is the heavy one (RecyclerView + SearchView + TabLayout + drag handles).
        // Same degrade-safe policy — log and continue.
        try {
            initPickerPanelInternal()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init picker panel view", e)
            pickerPanelView = null
        }
    }

    private fun initPickerPanelInternal() {
        pickerPanelView = AppPickerPanelView(this).apply {
            // Audit U4 — drain any in-progress edit BEFORE tearing the picker
            // down. Without this, tapping outside or swiping-down closed the
            // picker with the EditText rows intact and a draft custom item
            // still in progress. The next session would then log an indexing
            // -1 error when the ghost item was looked up. Draining on close
            // also catches the case where the user filled a valid title/URL
            // but didn't tap DONE — without the explicit save the row was
            // silently lost.
            onClose = { pickerPanelView?.let { picker ->
    picker.commitPendingEdits()
    if (picker.isEditMode) {
        picker.setEditMode(false)
    }
    closePicker()
} }
            onEditModeChanged = { editing ->
                // While the picker's EDIT mode is on, let the user long-press a
                // sidebar item to drag-reorder it. DONE exits and disables drag.
                sidePanelView?.setEditButtonVisible(editing)
            }
            onAppLaunched = { closePanel() }
            onToggleApp = { app, isSelected ->
                if (isSelected) {
                    val added = panelPrefs.addApp(app.identifier)
                    if (!added) {
                        android.widget.Toast.makeText(
                            this@FloatingPanelService,
                            getString(R.string.panel_apps_limit_reached, PanelPreferences.MAX_PANEL_APPS),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                        pickerPanelView?.loadApps(forceRefresh = true)
                    } else {
                        // Persist the picker-facing label so sidebar refresh does not
                        // replace shortcut shortLabel with the parent app name.
                        if (app.type == AppInfo.Type.SHORTCUT ||
                            app.type == AppInfo.Type.ACTIVITY ||
                            app.identifier.startsWith("intent:")
                        ) {
                            if (app.appName.isNotBlank()) {
                                panelPrefs.setPanelItemLabel(app.identifier, app.appName)
                            }
                        }
                        refreshApps {
                            if (isPickerOpen) {
                                sidePanelView?.scrollToApp(app.identifier)
                            }
                        }
                    }
                } else {
                    panelPrefs.removeApp(app.identifier)
                    refreshApps()
                }
            }
            // Custom Intents/URLs (URLS tab) sync hooks
            onAddCustomItem = { item ->
                // Persist custom metadata only. Sidebar membership is NOT set
                // here — the user explicitly adds/removes it via the row badge
                // in edit mode (toggleCustomInPanel). Auto-pinning every new
                // custom item here was the cause of "all added items land in
                // the sidebar" regardless of badge state.
                panelPrefs.addCustomItem(item)
                refreshApps()
            }
            onUpdateCustomItem = { item ->
                panelPrefs.updateCustomItem(item)
                // Identifier unchanged; sidebar needs to re-resolve to pick up new title.
                refreshApps()
            }
            onRemoveCustomItem = { id ->
                panelPrefs.removeCustomItem(id)
                val sidebarId = PanelPreferences.CUSTOM_ID_PREFIX + id
                panelPrefs.removeApp(sidebarId)
                refreshApps()
            }
            // Round-7 U3: drag-to-reorder inside the URLS tab persists the new
            // order via resyncPanelAppsOrderFromCustomItems() (already correct),
            // but the SidePanelView won't see the new positions until the
            // service's refreshApps() runs. The picker holds the canonical
            // customItems list while it's mounted, so wire this hook so the
            // sidebar visually reflects the reorder immediately instead of
            // waiting for the next ACTION_REFRESH / panel close-open cycle.
            onCustomItemsReordered = {
                refreshApps()
            }
            visibility = View.GONE
        }
    }

    private val sideRect = android.graphics.Rect()
    private val pickerRect = android.graphics.Rect()

    private fun initRootLayout() {
        if (rootLayout != null) return

        rootLayout = object : android.widget.FrameLayout(this) {
            override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
                if (event.action == android.view.KeyEvent.ACTION_UP && event.keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                    if (isPickerOpen) {
                        val view = findFocus()
                        if (view is android.widget.EditText && view.hasFocus()) {
                            view.clearFocus()
                            this.requestFocus()
                            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                            imm.hideSoftInputFromWindow(view.windowToken, 0)
                            return true
                        }
                        closePicker()
                        return true
                    } else {
                        closePanel()
                        return true
                    }
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            isFocusable = true
            isFocusableInTouchMode = true

            // This captures the actual click
            setOnClickListener {
                val view = findFocus()
                var closedKeyboard = false
                if (view is android.widget.EditText && view.hasFocus()) {
                    view.clearFocus()
                    this.requestFocus() // take focus away from EditText
                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                    imm.hideSoftInputFromWindow(view.windowToken, 0)
                    closedKeyboard = true
                }

                if (!closedKeyboard) {
                    if (isPickerOpen) {
                        closePicker()
                    } else {
                        closePanel()
                    }
                }
            }

            // This ensures we can detect if the touch was inside or outside our children
            setOnTouchListener { _, event ->
                if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                    val x = event.x.toInt()
                    val y = event.y.toInt()

                    val insideSide = sidePanelView?.let { v ->
                        v.getHitRect(sideRect)
                        sideRect.contains(x, y)
                    } ?: false

                    val insidePicker = if (isPickerOpen) {
                        pickerPanelView?.let { v ->
                            v.getHitRect(pickerRect)
                            pickerRect.contains(x, y)
                        } ?: false
                    } else false

                    if (insideSide || insidePicker) {
                        // Let the touch pass through to the panel/picker
                        return@setOnTouchListener false
                    }
                }
                // Return false to allow setOnClickListener to handle the tap
                false
            }
        }
        rootParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        
        // Round-H regression: sidePanelView and pickerPanelView are nullable
        // because initSidePanel() / initPickerPanel() (in their Round-7 degrade-safe
        // try/catch shells) set them to null on constructor failure. ViewGroup
        // contract forbids addView(null) - it throws IllegalArgumentException:
        //   "Cannot add a null child view to a ViewGroup"
        // which propagates out of openPanel() → onStartCommand() and kills the
        // service process (the user-reported FATAL EXCEPTION we just caught).
        // Safe-call the ARGUMENT too (`sidePanelView?.also { ... }`), not just
        // the receiver, so we degrade to "panel-less" gracefully instead of
        // crashing the entire app.
        sidePanelView?.also { rootLayout?.addView(it) }
        pickerPanelView?.also { rootLayout?.addView(it) }
    }

    private fun openPanel() {
        if (isPanelOpen || !panelPrefs.serviceEnabled) return
        isPanelOpen = true
        refreshApps() // Load apps in background while panel opens
        initRootLayout()
        if (rootLayout?.parent == null) {
            // Crash fix: windowManager.addView for a TYPE_APPLICATION_OVERLAY throws
            // BadTokenException / SecurityException when the user revoked the
            // "Draw over other apps" permission mid-session, or when system_server
            // returns a transient IPC error. Without this guard the uncaught
            // exception propagates out of onStartCommand and the Service process
            // (which is the same as the App process) is killed by Android → user
            // sees the app "flash-quit" immediately on click. Failure here is
            // recoverable: roll back the open-state and continue without the panel;
            // the user can grant the permission and retry.
            try {
                rootLayout?.let { windowManager.addView(it, rootParams) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add rootLayout overlay; rolling back openPanel()", e)
                isPanelOpen = false
                rootLayout?.removeAllViews()
                rootLayout = null
                rootParams = null
                sidePanelView?.visibility = View.GONE
                return
            }
        }
        updateBlur(true)
        sidePanelView?.updateStyles() // Evaluate Game Mode columns & update layout
        sidePanelView?.let { panel ->
            val isRight = panelPrefs.panelSide == PanelPreferences.SIDE_RIGHT
            val lp = panel.layoutParams as android.widget.FrameLayout.LayoutParams
            lp.width = android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            lp.height = android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            lp.gravity = if (isRight) Gravity.END or Gravity.CENTER_VERTICAL
                         else Gravity.START or Gravity.CENTER_VERTICAL
            panel.layoutParams = lp
            panel.alpha = 0f
            panel.translationX = if (isRight) 1000f else -1000f
            panel.visibility = View.VISIBLE
            panel.post {
                val panelWidth = panel.width.toFloat()
                val stiffness = panelPrefs.animSpeed.toFloat()
                SpringAnimator.animateOpen(panel, if (isRight) panelWidth else -panelWidth, stiffness = stiffness)
            }
        }
        edgeHandleView?.visibility = View.GONE
        // Start the fling-to-close quiet period: the tail of the swipe that
        // opened the panel must not immediately close it again.
        sidePanelView?.notifyPanelOpened()
    }

    private fun updateBlur(enabled: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val shouldBlur = enabled && panelPrefs.blurEnabled
        val blurRadius = panelPrefs.blurAmount
        rootParams?.let { params ->
            if (shouldBlur) {
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                params.blurBehindRadius = blurRadius
            } else {
                params.flags = params.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
                params.blurBehindRadius = 0
            }
            // Round-H trap-fix: `if (rootLayout?.parent != null) { rootLayout.xxx }`
            // does NOT smart-cast rootLayout through the `?.` — Kotlin keeps the
            // receiver typed as FrameLayout? inside the body, so the call site
            // would dispatch to windowManager.updateViewLayout with a null
            // argument and trip IllegalArgumentException. Use takeIf + let so
            // the receiver is non-null-shadowed under `it`.
            rootLayout?.takeIf { it.parent != null }?.let { rl ->
                windowManager.updateViewLayout(rl, params)
            }
        }
    }

    fun closePanel(immediate: Boolean = false) {
        // Safety: Don't close if user is still interacting with the trigger handle
        if (edgeHandleView?.isPressed == true) return

        val wasOpen = isPanelOpen
        isPanelOpen = false
        
        if (immediate) {
            if (isPickerOpen) {
                isPickerOpen = false
                pickerPanelView?.visibility = View.GONE
                pickerPanelView?.onPickerHidden()
            }
            sidePanelView?.visibility = View.GONE
            updateBlur(false)
            // Round-H trap-fix: same `?.` smart-cast gap as in updateBlur().
            // windowManager.removeViewImmediate(null) throws IllegalArgumentException;
            // the previous try/catch silently absorbed it without removing the
            // view from WM. Use takeIf + let to shadow a non-null receiver.
            rootLayout?.takeIf { it.parent != null }?.let { rl ->
                try { windowManager.removeViewImmediate(rl) } catch (e: Exception) {}
            }
            syncHandleVisibility()
            sidePanelView?.animatePickerToggle(false)
            
            return
        }

        if (!wasOpen) {
            // Safety: if panel is already marked closed but rootLayout is somehow still attached, kill it
            // Round-H trap-fix: kotlin does NOT smart-cast rootLayout through `?.`
            // — see FloatingPanelService.updateBlur for the full analysis. Use
            // takeIf + let so the call site sees a non-null receiver.
            rootLayout?.takeIf { it.parent != null }?.let { rl ->
                try { windowManager.removeView(rl) } catch (e: Exception) {}
            }
            syncHandleVisibility()
            return
        }

        if (isPickerOpen) closePicker()
        if (sidePanelView == null) {
            // Audit fix: the animated cleanup below lives inside
            // sidePanelView?.let, so when the panel view failed to init
            // (initSidePanel sets it to null) an animated close did nothing
            // at all — rootLayout stayed attached to WindowManager as an
            // empty full-screen overlay and the edge handle stayed GONE.
            // Fall back to the immediate cleanup path in that case.
            updateBlur(false)
            rootLayout?.takeIf { it.parent != null }?.let { rl ->
                try { windowManager.removeView(rl) } catch (e: Exception) {}
            }
            syncHandleVisibility()
            return
        }
        sidePanelView?.let { panel ->
            val isRight = panelPrefs.panelSide == PanelPreferences.SIDE_RIGHT
            val panelWidth = panel.width.toFloat()
            val stiffness = panelPrefs.animSpeed.toFloat()
            SpringAnimator.animateClose(panel, if (isRight) panelWidth else -panelWidth, stiffness = stiffness) {
                panel.visibility = View.GONE
                updateBlur(false)
                // Round-H trap-fix: kotlin does NOT smart-cast rootLayout through `?.`
                // — see FloatingPanelService.updateBlur for the full analysis. Use
                // takeIf + let so the call site sees a non-null receiver.
                rootLayout?.takeIf { it.parent != null }?.let { rl ->
                    try { windowManager.removeView(rl) } catch (e: Exception) {}
                }
                syncHandleVisibility()
                panel.animatePickerToggle(false)
            }
        }
    }

    private fun togglePicker(enableEditMode: Boolean = true) {
        // Audit M5: only the OPEN↔CLOSE state flip needs the 600ms debounce. Interior
        // state changes (e.g. user double-tapping EDIT while picker is OPEN to enter
        // edit mode, or DONE to leave it) must NOT be swallowed silently — that was
        // a real interaction bug. We classify the click as a "toggle" only if it would
        // actually flip the picker presence, otherwise it's an intra-state edit-mode
        // toggle and we let it through immediately.
        val wouldFlipPresence =
            !isPickerOpen ||
            !enableEditMode ||
            (pickerPanelView?.isEditMode == true)

        if (wouldFlipPresence) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastPickerToggleTime < 600) return
            lastPickerToggleTime = currentTime
        }

        if (isPickerOpen) {
            val currentModeIsEdit = pickerPanelView?.isEditMode ?: false
            if (enableEditMode && !currentModeIsEdit) {
                pickerPanelView?.setEditMode(true)
            } else {
                closePicker()
            }
        } else {
            openPicker(enableEditMode = enableEditMode)
        }
    }

    private fun openPicker(enableEditMode: Boolean = false) {
        if (isPickerOpen) return
        isPickerOpen = true
        // Capture both lists' scroll positions BEFORE forcing single-column
        // mode: changing the span count reflows rows and the old code then
        // scrolled the main list to the bottom, destroying its position.
        // The saved positions are restored in closePicker() after the
        // original column count returns. setColumns(1) now switches all
        // three lists (apps, recents, tools) together.
        sidePanelView?.saveListScrollStates()
        // Flag first so concurrent ACTION_REFRESH cannot re-expand tools to 2-col.
        sidePanelView?.animatePickerToggle(true)
        sidePanelView?.setColumns(1)
        pickerPanelView?.let { picker ->
            picker.setEditMode(enableEditMode)
            picker.resetSearch()
            picker.loadApps()
            picker.setOnClickListener { }
            val isRight = panelPrefs.panelSide == PanelPreferences.SIDE_RIGHT
            val sidePanelWidthDp = 72
            val sidePanelMarginDp = 12

            // Dynamic Height calculation for Picker Panel based on Screen Height
            val displayMetrics = resources.displayMetrics

            // Max height for picker: User preference, with a sane minimum for usability
            val maxAllowedHeightDp = Math.max(300f, panelPrefs.pickerMaxHeight.toFloat())
            val maxPickerHeightPx = (maxAllowedHeightDp * displayMetrics.density).toInt()

            val lp = android.widget.FrameLayout.LayoutParams(dpToPx(240), android.widget.FrameLayout.LayoutParams.WRAP_CONTENT)
            
            lp.gravity = if (isRight) Gravity.CENTER_VERTICAL or Gravity.END
                         else Gravity.CENTER_VERTICAL or Gravity.START
            
            // Fixed alignment calculation: Sidepanel occupies (margin + width) space
            val gapPx = ((sidePanelWidthDp + sidePanelMarginDp + panelPrefs.pickerGap) * displayMetrics.density).toInt()
            if (isRight) lp.marginEnd = gapPx else lp.marginStart = gapPx
            
            picker.layoutParams = lp
            // Force the internal RecyclerView to not exceed a certain height
            picker.setMaxRecyclerViewHeight(maxPickerHeightPx - dpToPx(80)) // Subtract header space (approx 80dp)
            
            picker.alpha = 0f
            picker.visibility = View.VISIBLE
            picker.handleKeyboard()
            picker.post {
                val pickerWidth = picker.width.toFloat()
                if (pickerWidth <= 0) return@post // Wait for layout if not ready
                val startX = if (isRight) -pickerWidth else pickerWidth
                val stiffness = panelPrefs.animSpeed.toFloat()
                val useSlide = panelPrefs.pickerAnimType == PanelPreferences.ANIM_TYPE_SLIDE
                SpringAnimator.animateOpen(picker, startX, isPicker = true, stiffness = stiffness, slide = useSlide)
            }
        }
    }

    private fun closePicker() {
        if (!isPickerOpen) return
        isPickerOpen = false
        // Must clear CUSTOM modify/edit chrome (editingCustomId + header buttons)
        // on every close path — not only ACTION_REFRESH / closePanel. Without this,
        // ADD → modify → outside-tap → re-open leaves Add/Edit GONE until tab switch.
        pickerPanelView?.onPickerHidden()
        // Defer column restore until the close animation settles. The delayed
        // block clears the picker flag then setColumns(originalCols) so apps
        // and tools stay in lockstep via currentCols.
        //
        // Round-12 audit L-Medium: same fix as triggerScreenshot() — reuse
        // the service-level `handler` rather than allocating a fresh
        // `Handler(Looper.getMainLooper())` per closePicker invocation. A
        // user rapidly tapping the chevron would otherwise leave N anonymous
        // handlers queued on the main looper until they all drained.
        handler.postDelayed({
            if (!isPickerOpen) {
                val originalCols = panelPrefs.panelColumns
                sidePanelView?.setEditButtonVisible(false)
                // Clear picker flag first, then restore columns so tools grid
                // mirrors apps via currentCols in one setColumns() pass.
                sidePanelView?.animatePickerToggle(false)
                sidePanelView?.setColumns(originalCols)
                // Restore each list to the scroll position it had before
                // the picker forced single-column mode. This must run after
                // setColumns(): the span-count change reflows rows, and a
                // restore issued before layout would be overwritten.
                // If the picker was re-opened meanwhile, the guard above
                // skips this whole block and the saved state is kept for
                // the next close.
                sidePanelView?.restoreListScrollStates()
            }
        }, PICKER_CLOSE_COLUMNS_DELAY_MS)
        pickerPanelView?.let { picker ->
            // onPickerHidden already exits edit mode; keep this for non-CUSTOM tabs.
            picker.setEditMode(false)
            picker.invalidateAppList()
            val isRight = panelPrefs.panelSide == PanelPreferences.SIDE_RIGHT
            val pickerWidth = picker.width.toFloat()
            val stiffness = panelPrefs.animSpeed.toFloat()
            val useSlide = panelPrefs.pickerAnimType == PanelPreferences.ANIM_TYPE_SLIDE
            SpringAnimator.animateClose(picker, if (isRight) pickerWidth else -pickerWidth, isPicker = true, stiffness = stiffness, slide = useSlide) {
                if (!isPickerOpen) {
                    picker.visibility = View.GONE
                }
            }
        }
    }

    private fun refreshApps(onComplete: (() -> Unit)? = null) {
        serviceScope.launch {
            val apps = if (currentFolderId != null) {
                when (currentFolderId) {
                    "smartedge.folder.tools" -> {
                        val tools = mutableListOf<AppInfo>()

                        // Tools folder always shows ALL available tools regardless
                        // of individual dashboard toggles. The dashboard toggles
                        // only control the inline tools GridLayout below the app
                        // list in the sidebar panel (applyTheme() in SidePanelView).
                        // Order matches the dashboard items settings page and the
                        // sidebar's inline tools section: power → volume → brightness
                        // → screenshot → black screen → lock screen → content picker.
                        tools.add(AppInfo("smartedge.shortcut.reboot", getString(R.string.tool_name_power), type = AppInfo.Type.SHORTCUT))
                        tools.add(AppInfo("smartedge.tool.volume_up", getString(R.string.tool_name_volume), type = AppInfo.Type.TOOL))
                        tools.add(AppInfo("smartedge.tool.brightness_up", getString(R.string.tool_name_brightness), type = AppInfo.Type.TOOL))
                        tools.add(AppInfo("smartedge.tool.screenshot", getString(R.string.tool_name_screenshot), type = AppInfo.Type.TOOL))
                        tools.add(AppInfo("smartedge.tool.blackscreen", getString(R.string.tool_name_blackscreen), type = AppInfo.Type.TOOL))
                        tools.add(AppInfo("smartedge.tool.lockscreen", getString(R.string.tool_name_lock), type = AppInfo.Type.TOOL))
                        tools.add(AppInfo("smartedge.tool.content_picker", getString(R.string.tool_name_content_picker), type = AppInfo.Type.TOOL))
                        
                        tools
                    }
                    else -> emptyList<AppInfo>()
                }
            } else {
                val baseApps = repository.getPanelApps().toMutableList()
                
        // Add "Tools" folder button at the top if enabled.
        // The master "showTools" switch gates both the inline tools section
        // AND the app-grid Tools folder so toggling the master switch off
        // hides all tool-related UI consistently.
        if (panelPrefs.showToolsPanelButton && panelPrefs.showTools) {
            val toolsBtn = AppInfo("smartedge.tool.tools", getString(R.string.tool_name_tools), type = AppInfo.Type.TOOL)
            if (baseApps.none { it.identifier == toolsBtn.identifier }) {
                baseApps.add(0, toolsBtn)
            }
        }
                
                baseApps
            }
            
            sidePanelView?.setApps(apps, onComplete)

            // "Recent" section: most frequently used apps from launch history.
            // Only at root level; folders show their own content.
            // Exclude apps already pinned in the sidebar app list (`apps`
            // above, which also includes the Tools folder button and any
            // notification-prepended apps) so the two lists never duplicate
            // each other. Exclusion happens before the recent-count limit
            // is applied, so the section still fills up with the next
            // most-used apps.
            val recentApps = if (panelPrefs.recentEnabled && currentFolderId == null) {
                try {
                    val excluded = RecentAppsManager.excludedPackagesFor(apps)
                    RecentAppsManager(this@FloatingPanelService).getTopRecentApps(excluded)
                } catch (_: Exception) {
                    emptyList()
                }
            } else {
                emptyList()
            }
            sidePanelView?.setRecentApps(recentApps, apps)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.panel_notification_channel),
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = getString(R.string.panel_notification_desc)
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): android.app.Notification {
        val stopIntent = Intent(this, FloatingPanelService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Use ToggleActivity to ensure the notification shade collapses automatically
        val openIntent = Intent(this, ToggleActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this, 1, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val openMainIntent = Intent(this, MainActivity::class.java)
        val openMainPending = PendingIntent.getActivity(
            this, 0, openMainIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.panel_running))
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(openMainPending)
            .addAction(android.R.drawable.ic_menu_view,
                getString(R.string.main_test_sidebar), openPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.stop_panel), stopPending)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()

    private var indicatorText: android.widget.TextView? = null
    private var indicatorFadeRunnable: Runnable? = null

    /**
     * Hides the status and navigation bars for an overlay window. On API 30+
     * this uses the platform WindowInsetsController directly (the androidx
     * view-based lookup and its converter are both deprecated in core 1.13,
     * and on API < 30 they only work for Activity-hosted views anyway, not
     * service overlays). Transient bars auto-hide again after a swipe, so no
     * visibility-change listener is needed. On API < 30 no
     * WindowInsetsController exists, so the legacy systemUiVisibility flags
     * are the only option there (they are the reason for the scoped
     * @Suppress below, not a shortcut).
     */
    private fun hideSystemBarsForOverlay(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view.windowInsetsController?.let { controller ->
                controller.hide(
                    android.view.WindowInsets.Type.statusBars() or
                        android.view.WindowInsets.Type.navigationBars()
                )
                controller.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            view.systemUiVisibility = (View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }
    }

    /** Restores the status and navigation bars hidden by [hideSystemBarsForOverlay]. */
    private fun showSystemBarsForOverlay(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view.windowInsetsController?.show(
                android.view.WindowInsets.Type.statusBars() or
                    android.view.WindowInsets.Type.navigationBars()
            )
        } else {
            @Suppress("DEPRECATION")
            view.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    /**
     * Change media volume by [steps] whole hardware steps (sign = direction).
     * One call is one tick of the edge-slide gesture, and one tick is exactly
     * one hardware step on every phone: the slide distance per step is
     * adapted to the phone's own media step count (see [VolumeStepMath]),
     * so a 160-step phone moves 0.625% per tick and a 15-step phone moves
     * one 6.67% step per tick. The step is applied by absolute index (see
     * [MediaVolumeApplier]) because a relative adjust moves more than one
     * index on some phones — on this OnePlus 13 one adjust call jumps ~10
     * indices (6%). No accumulation — a step either lands or it doesn't
     * (e.g. already at min/max), and there is nothing to carry over.
     */
    fun adjustVolume(steps: Int) {
        if (steps == 0) return
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        // The indicator is refreshed by the callback, not here: the shell
        // fallback (if needed) lands on a background thread, and the
        // indicator must show the volume after it lands, not before.
        MediaVolumeApplier.apply(audioManager, steps) {
            val max = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            val current = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            val percent = if (max > 0) (current * 100) / max else 0
            showIndicator(getString(R.string.indicator_volume_percent, percent))
        }
    }

    /**
     * Change screen brightness by [deltaPercent] percent points (sign =
     * direction). One percent point per call; 1% = 2.55 levels on the 0..255
     * scale, so the fractional part accumulates in this service's
     * [PanelPreferences.brightnessStepAccumulator] until it makes a whole level.
     */
    fun adjustBrightness(deltaPercent: Int) {
        if (deltaPercent == 0) return
        try {
            if (!android.provider.Settings.System.canWrite(this)) {
                // Throttle the prompt: without this, every brightness tick
                // while the permission is missing re-launches the settings
                // screen (and stacks a Toast per tick). Once per minute is
                // enough — the user already saw it.
                if (panelPrefs.shouldPromptWriteSettings()) {
                    android.widget.Toast.makeText(this, getString(R.string.toast_write_settings_required), android.widget.Toast.LENGTH_SHORT).show()
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                        data = android.net.Uri.parse("package:$packageName")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                }
                return
            }

            val cResolver = contentResolver
            // 1. Ensure manual mode to allow manual override (only writes
            // when the mode differs, so a drag doesn't permanently kill
            // the user's auto-brightness).
            ensureManualBrightnessMode(cResolver)

            // 2. Update standard int brightness (1-255: 0 is treated as
            // "auto" by some OEMs, so the clamp floor is 1, not 0).
            var brightness = android.provider.Settings.System.getInt(cResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS, 125)
            val units = panelPrefs.brightnessStepAccumulator.add(deltaPercent, 255)
            if (units != 0) {
                brightness = (brightness + units).coerceIn(1, 255)
                // Steps the clamp swallowed are simply dropped, NOT refunded:
                // the brightness is already at the 0/255 stop, so nothing is
                // owed, and refunding them makes the first tick in the
                // opposite direction re-emit the refunded steps (which the
                // clamp swallows again) instead of moving the brightness —
                // a dead zone at both ends of the range. Dropping them keeps
                // the accumulator consistent, so reversal moves immediately.
                android.provider.Settings.System.putInt(cResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS, brightness)

                // 3. Update modern float brightness for slider sync on Android 10+
                val floatVal = brightness / 255f
                try {
                    android.provider.Settings.System.putFloat(cResolver, "screen_brightness_float", floatVal)
                } catch (e: Exception) {
                    try {
                        android.provider.Settings.System.putString(cResolver, "screen_brightness_float", floatVal.toString())
                    } catch (e2: Exception) {}
                }

                // Force a notification change to refresh system UI slider
                try {
                    cResolver.notifyChange(android.provider.Settings.System.getUriFor(android.provider.Settings.System.SCREEN_BRIGHTNESS), null)
                    cResolver.notifyChange(android.provider.Settings.System.getUriFor("screen_brightness_float"), null)
                } catch (e: Exception) {}
            }

            val percent = (brightness * 100) / 255
            showIndicator(getString(R.string.indicator_brightness_percent, percent))
        } catch (e: Exception) {
            android.util.Log.e("FloatingPanelService", "Failed to adjust brightness", e)
        }
    }

    /**
     * The indicator lives in its own standalone overlay window (like
     * showConfirmOverlay), NOT in rootLayout: the panel is closed during the
     * indicator's primary use case (edge-slide volume/brightness), when
     * rootLayout is detached or null, so a rootLayout child would be
     * invisible. The view is created once and kept attached (GONE when idle)
     * so rapid ticks don't churn addView/removeView. Returns true when the
     * overlay is attached and ready to show text.
     */
    private fun ensureIndicatorOverlay(): Boolean {
        val existing = indicatorText
        if (existing != null && existing.isAttachedToWindow) return true
        if (existing != null) {
            // Detached without the field being cleared (defensive): drop it
            // and recreate, rather than writing into a detached view.
            indicatorText = null
        }
        val density = resources.displayMetrics.density
        indicatorText = android.widget.TextView(this).apply {
            setTextColor(android.graphics.Color.WHITE)
            textSize = 14f
            setPadding((16 * density).toInt(), (10 * density).toInt(), (16 * density).toInt(), (10 * density).toInt())
            gravity = android.view.Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#E6303030"))
                cornerRadius = 24f * density
            }
            elevation = 8f * density
            visibility = View.GONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
            // WindowManager.LayoutParams has no bottomMargin; use y offset
            // (negative = upward from the BOTTOM gravity anchor).
            y = -(90 * density).toInt()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        return try {
            windowManager.addView(indicatorText, params)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add indicator overlay", e)
            indicatorText = null
            false
        }
    }

    private fun showIndicator(text: String) {
        if (!ensureIndicatorOverlay()) return

        indicatorText?.text = text
        indicatorText?.visibility = View.VISIBLE
        indicatorText?.alpha = 1f
        indicatorText?.animate()?.cancel()

        indicatorFadeRunnable?.let { handler.removeCallbacks(it) }
        indicatorFadeRunnable = Runnable {
            indicatorText?.animate()
                ?.alpha(0f)
                ?.setDuration(INDICATOR_FADE_DURATION_MS)
                ?.withEndAction { indicatorText?.visibility = View.GONE }
                ?.start()
        }
        handler.postDelayed(indicatorFadeRunnable!!, INDICATOR_SHOW_DURATION_MS)
    }

    /**
     * Audit prompt — localized Snackbar surfaced when Shizuku/Root binder dies
     * mid-session. Falls back to a Toast only if no anchor View is yet attached
     * to the WindowManager (i.e. the very first probe fired before the panel was
     * ever opened).
     */
    private fun showEngineLostSnackbar() {
        val msg = getString(R.string.engine_lost_msg)
        // The anchor must be attached to a window: rootLayout is non-null
        // but detached while the panel is closed, and a Snackbar anchored
        // to a detached view is never shown.
        val anchor = rootLayout?.takeIf { it.isAttachedToWindow } ?: edgeHandleView?.takeIf { it.isAttachedToWindow }
        if (anchor != null) {
            com.google.android.material.snackbar.Snackbar.make(
                anchor,
                msg,
                com.google.android.material.snackbar.Snackbar.LENGTH_LONG
            ).show()
        } else {
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
        }
    }
}
