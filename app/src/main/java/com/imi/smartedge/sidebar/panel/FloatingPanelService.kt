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

    private lateinit var windowManager: WindowManager
    private var edgeHandleView: EdgeHandleView? = null
    private var notchHandleView: NotchHandleView? = null
    private var sidePanelView: SidePanelView? = null
    private var pickerPanelView: AppPickerPanelView? = null
    
    private var rootLayout: android.widget.FrameLayout? = null
    private var rootParams: WindowManager.LayoutParams? = null

    private val activeTorches = mutableSetOf<String>()
    private var isFlashlightOn = false
    private var lastManualToggleTime = 0L
    private var cameraManager: android.hardware.camera2.CameraManager? = null
    private val torchCallback = object : android.hardware.camera2.CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            super.onTorchModeChanged(cameraId, enabled)
            // Ignore system callbacks for a short period after a manual toggle
            if (System.currentTimeMillis() - lastManualToggleTime > 500) {
                if (enabled) activeTorches.add(cameraId) else activeTorches.remove(cameraId)
                isFlashlightOn = activeTorches.isNotEmpty()
                Log.d(TAG, "External torch change: $enabled for camera $cameraId. Master state: $isFlashlightOn")
            }
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
    private var savedBrightnessMode: Int = android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
    /** Original screen-off timeout (ms) saved before true screen-off, restored on exit. */
    private var savedScreenOffTimeout: Int = -1
    /** True when true screen-off (via device admin) is active rather than the dim overlay. */
    private var trueScreenOffActive: Boolean = false

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

    private var isPanelOpen = false
    private var isPickerOpen = false
    private var isImmersiveMode = false
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

            // If it was removed, purge all references to this package.
            if (action == Intent.ACTION_PACKAGE_REMOVED && !intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                panelPrefs.removeApp(packageName)
                // Drop intent-URI pins that target the uninstalled package (parseUri, not substring).
                val remaining = panelPrefs.getPanelApps().filterNot { id ->
                    id.startsWith("intent:") && repository.intentUriTargetsPackage(id, packageName)
                }
                if (remaining.size != panelPrefs.getPanelApps().size) {
                    panelPrefs.setPanelApps(remaining)
                }
                panelPrefs.setGameApps(panelPrefs.getGameApps().filterNot { it == packageName })
                panelPrefs.setFullscreenWhitelist(
                    panelPrefs.getFullscreenWhitelist().filterNot { it == packageName }
                )
                if (panelPrefs.favoriteAppPackage == packageName) {
                    panelPrefs.favoriteAppPackage = ""
                }
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
        const val ACTION_UPDATE_IMMERSIVE = "com.imi.smartedge.sidebar.panel.UPDATE_IMMERSIVE"
        const val ACTION_TOGGLE_FLASHLIGHT = "com.imi.smartedge.sidebar.panel.TOGGLE_FLASHLIGHT"
        const val ACTION_LAUNCH_CAMERA = "com.imi.smartedge.sidebar.panel.LAUNCH_CAMERA"
        const val ACTION_TOGGLE_ROTATION = "com.imi.smartedge.sidebar.panel.TOGGLE_ROTATION"
        const val ACTION_OPEN_FAV_APP = "com.imi.smartedge.sidebar.panel.OPEN_FAV_APP"

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
        AutomationManager.onEngineLost = {
            if (panelPrefs.useAutomationForGestures) {
                showEngineLostSnackbar()
                addEdgeHandle()
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            TileService.requestListeningState(this, android.content.ComponentName(this, PanelTileService::class.java))
        }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        panelPrefs = PanelPreferences(this)
        // Audit L1: provision the shared repository BEFORE initSidePanel() calls
        // refreshApps() — otherwise the launch would NPE on repository.getPanelApps().
        repository = AppRepository(this)

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
            // // addNotchHandle() // Commented out per user request
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
            if (panelPrefs.getPanelApps().isEmpty()) {
                val topApps = repository.getTop5Apps()
                panelPrefs.setPanelApps(topApps)
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
                // Non-null intent guaranteed by the prior `if (intent == null || action == null)` guard;
                // Kotlin smart-casts the receiver.
                val newState = intent.getBooleanExtra("target_state", !panelPrefs.serviceEnabled)
                panelPrefs.setServiceEnabled(newState, commit = true)
                
                // Request Tile Update explicitly
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    TileService.requestListeningState(this, android.content.ComponentName(this, PanelTileService::class.java))
                }
                
                if (newState) {
                    addEdgeHandle()
                    // addNotchHandle()
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
            PanelAccessibilityService.ACTION_TAKE_SCREENSHOT -> {
                handler.postDelayed({ triggerScreenshot() }, SCREENSHOT_TRIGGER_DELAY_MS)
            }
            ACTION_REFRESH -> {
                serviceScope.launch {
                    if (panelPrefs.getPanelApps().isEmpty()) {
                        val topApps = repository.getTop5Apps()
                        panelPrefs.setPanelApps(topApps)
                    }
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
                        // addNotchHandle()
                        edgeHandleView?.visibility = if (isPanelOpen) View.GONE else View.VISIBLE
                        notchHandleView?.visibility = if (isPanelOpen) View.GONE else View.VISIBLE
                    }

                    // Update game mode state: user-curated list OR dynamic landscape+fullscreen+non-video.
                    val currentPkg = panelPrefs.currentForegroundPackage
                    val isInGameList = panelPrefs.getGameApps().contains(currentPkg)
                    val isDynamicGame = if (isInGameList) true else {
                        val isLandscape = resources.configuration.orientation ==
                                android.content.res.Configuration.ORIENTATION_LANDSCAPE
                        isLandscape && currentPkg.isNotBlank() &&
                                !isCurrentPackageLauncher() &&
                                !isVideoPlayerPackage(currentPkg)
                    }
                    edgeHandleView?.isGameActive = isDynamicGame
                    
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
                        sidePanelView?.animatePickerToggle(false)
                    } else if (isPickerOpen) {
                        pickerPanelView?.loadApps() 
                    }
                    refreshApps()
                }
            }
            ACTION_CLOSE_PANEL -> closePanel(immediate = false)
            ACTION_SCREENSHOT -> {
                handler.postDelayed({ triggerScreenshot() }, SCREENSHOT_TRIGGER_DELAY_MS)
            }
            ACTION_SCREENSHOT_UI_RESTORE -> {
                restoreScreenshotUi()
            }
            ACTION_UPDATE_IMMERSIVE -> {
                // Non-null intent guaranteed by the prior `if (intent == null || action == null)` guard;
                // Kotlin smart-casts the receiver.
                isImmersiveMode = intent.getBooleanExtra("is_immersive", false)
                edgeHandleView?.isImmersiveMode = isImmersiveMode
            }
            ACTION_SHOW_TEMP -> {
                addEdgeHandle(forceRecreate = false)
                // addNotchHandle()
                edgeHandleView?.showTemporarily()
            }
            ACTION_TOGGLE_FLASHLIGHT -> toggleFlashlight()
            ACTION_LAUNCH_CAMERA -> launchCamera()
            ACTION_TOGGLE_ROTATION -> toggleAutoRotation()
            ACTION_OPEN_FAV_APP -> openFavoriteApp()
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
            try {
                val intent = Intent(this, PanelAccessibilityService::class.java).apply {
                    action = PanelAccessibilityService.ACTION_TAKE_SCREENSHOT
                }
                startService(intent)
            } catch (e: SecurityException) {
                Log.e(TAG, "startService(PanelAccessibilityService) denied; falling back to AutomationManager.takeScreenshot()", e)
                try {
                    AutomationManager.takeScreenshot()
                } catch (e2: Exception) {
                    Log.e(TAG, "Automation fallback also failed", e2)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to dispatch screenshot intent", e)
            }
            // Safety net if accessibility never acks restore (OEM quirks).
            handler.postDelayed(screenshotRestoreRunnable, SCREENSHOT_RESTORE_DELAY_MS)
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
            
            lastManualToggleTime = System.currentTimeMillis()
            manager.setTorchMode(cameraId, newState)
            // Update state AFTER the hardware call succeeds to prevent
            // desync if setTorchMode throws CameraAccessException.
            isFlashlightOn = newState
            if (newState) activeTorches.add(cameraId) else activeTorches.clear()
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
            try {
                val intent = Intent(this, PanelAccessibilityService::class.java).apply {
                    action = PanelAccessibilityService.ACTION_LOCK_SCREEN
                }
                startService(intent)
            } catch (e2: SecurityException) {
                Log.e(TAG, "startService denied for lock; trying AutomationManager", e2)
                try {
                    AutomationManager.performLockScreen()
                } catch (e3: Exception) {
                    Log.e(TAG, "Automation lock fallback failed", e3)
                    showIndicator(getString(R.string.toast_lock_screen_failed))
                }
            } catch (e2: Exception) {
                Log.e(TAG, "Accessibility lock fallback failed", e2)
                showIndicator(getString(R.string.toast_lock_screen_failed))
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
        }

        contentPickerOverlay = overlay
        contentPickerOverlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_FULLSCREEN or
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
        showIndicator(getString(R.string.content_picker_hint))
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
        // Only give per-tap feedback via a Toast (resource string); the "Copied to
        // clipboard" confirmation is delivered when the picker flushes on dismiss.
        try {
            android.widget.Toast.makeText(
                this,
                getString(R.string.content_picker_concatenated),
                android.widget.Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {}
    }

    /** Join every accumulated snippet with newlines and write them to the
     *  clipboard once when the picker is dismissed via two-finger gesture. */
    private fun flushContentPickerToClipboard() {
        if (contentPickerAccumulated.isEmpty()) return
        val joined = contentPickerAccumulated.joinToString("\n")
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Content Picker", joined))
            // Confirm the flush (actual clipboard write) once, right before the
            // picker overlay is removed on dismissal.
            try {
                android.widget.Toast.makeText(
                    this,
                    getString(R.string.content_picker_copied),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {}
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

    private fun openFavoriteApp() {
        val pkg = panelPrefs.favoriteAppPackage
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
                }} else null
            )
            savedBrightnessMode = if (snapshot.autoMode)
                android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
            else
                android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            savedBrightness = snapshot.brightness

            // 4. Wake lock strategy — two modes:
            //    - True screen-off (device admin active + pref enabled):
            //      PARTIAL_WAKE_LOCK keeps the CPU running so the foreground
            //      app stays alive, while the screen itself is turned off via
            //      a minimal SCREEN_OFF_TIMEOUT and keyguard is suppressed.
            //    - Legacy dim overlay: SCREEN_BRIGHT_WAKE_LOCK guarantees the
            //      overlay window's FLAG_KEEP_SCREEN_ON is honored on OEM forks.
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
                    try {
                        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
                        val cn = android.content.ComponentName(this, SmartEdgeDeviceAdminReceiver::class.java)
                        if (dpm.isAdminActive(cn)) {
                            dpm.setKeyguardDisabled(cn, true)
                        }
                    } catch (e: Exception) {}
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
                } else {
                    blackScreenWakeLock = powerManager.newWakeLock(
                        android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                        android.os.PowerManager.ON_AFTER_RELEASE,
                        "SmartEdge:BlackScreen"
                    )
                    blackScreenWakeLock?.acquire() // held until Black Screen is dismissed (no fixed timeout so long-running background tasks survive past 10 min)
                }
            } catch (_: Exception) {}

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
                // Hide status & navigation bars (immersive mode).
                @Suppress("DEPRECATION")
                systemUiVisibility = (View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
                setOnSystemUiVisibilityChangeListener { vis ->
                    if (vis and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION == 0) {
                        @Suppress("DEPRECATION")
                        systemUiVisibility = (View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
                    }
                }
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
            // FLAG_FULLSCREEN + FLAG_LAYOUT_NO_LIMITS extends the overlay into
            // the system-bar regions, and systemUiVisibility on the view below
            // hides the bars entirely.
            blackScreenOverlayParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
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
                        windowManager.addView(overlay, params)
                        // Ensure the overlay owns focus so Back is delivered here.
                        overlay.isFocusableInTouchMode = true
                        overlay.requestFocus()
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
                @Suppress("DEPRECATION")
                overlay.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            }

            // 2. Remove the black overlay safely
            blackScreenOverlay?.let { overlay ->
                try {
                    if (overlay.isAttachedToWindow) {
                        windowManager.removeView(overlay)
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
                try {
                    val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
                    val cn = android.content.ComponentName(this, SmartEdgeDeviceAdminReceiver::class.java)
                    if (dpm.isAdminActive(cn)) {
                        dpm.setKeyguardDisabled(cn, false)
                    }
                } catch (e: Exception) {}
            }

            // 4. Restore brightness + auto-mode via the pure helper.
            //    The BrightnessSnapshot was constructed by activateBlackScreen
            //    and stored in savedBrightness / savedBrightnessMode fields.
            val resolver = contentResolver
            val canWrite = android.provider.Settings.System.canWrite(this)
            restoreBrightness(
                snapshot = BrightnessSnapshot(
                    autoMode = savedBrightnessMode == android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC,
                    brightness = savedBrightness
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
                }
            )

            showIndicator(getString(R.string.indicator_black_screen_off))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deactivate black screen", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
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
        // Audit L6: cancel every pending message on the foreground Handler.
        // `handler.removeCallbacksAndMessages(null)` clears the indicator-fade
        // runnable as well as anything scheduled via postDelayed above.
        handler.removeCallbacksAndMessages(null)
        // Drop screenshot-restore state so it doesn't pin Views across restarts.
        screenshotHideViews = null
        screenshotPrevAlphas = null
        // Round-13 audit M2: also nil out the indicator TextView reference.
        // Even though `removeView(rootLayout)` removes the FrameLayout and its
        // children from WindowManager, the Kotlin property here still holds a
        // hard reference to the TextView object, which transitively anchors
        // the View internals (mContext, mAttachInfo, mListenerInfo). Across
        // service restarts that pile up. The fade-runnable cancellation above
        // removes the timing path; this drops the memory anchor.
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
        blackScreenWakeLock?.let { if (it.isHeld) it.release() }
        blackScreenWakeLock = null
        removeView(blackScreenOverlay)
        // Drop any active control picker overlay + callback.
        stopContentPicker()
        // Lifecycle guard: if the service dies while a true screen-off session is
        // active, restore the timeout and keyguard so the device doesn't stay in
        // an unlocked, ultra-short-timeout state permanently.
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
            try {
                val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
                val cn = android.content.ComponentName(this, SmartEdgeDeviceAdminReceiver::class.java)
                if (dpm.isAdminActive(cn)) {
                    dpm.setKeyguardDisabled(cn, false)
                }
            } catch (e: Exception) {}
        }
    }

    // Audit U-Med: this service is the live overlay panel; it MUST outlive
    // any short-lived ConfigurationActivity the user swipes from Recents,
    // because that's exactly how the Android swipe-from-recents UX is
    // expected to behave for foreground services. Calling stopSelf() here
    // terminated the entire sidebar the moment the user cleaned up the
    // launcher task — the most unexpected failure mode we have in the wild.
    // The service still terminates voluntarily through Notification-stop /
    // quick-tile / explicit user action or through the system's own
    // low-memory killer. We do NOT call stopSelf().
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
    }

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

    private fun addEdgeHandle(forceRecreate: Boolean = false) {
        val anyTriggerEnabled = panelPrefs.gesturesEnabled || 
                                panelPrefs.tapToOpen || 
                                panelPrefs.doubleTapToOpen || 
                                panelPrefs.tripleTapToOpen

        // --- MANDATORY ENGINE CHECK ---
        // If neither Accessibility is ON nor Native Automation is POSSIBLE, we must NOT show the handle.
        val hasActiveEngine = isAccessibilityServiceEnabled() || (panelPrefs.useAutomationForGestures && AutomationManager.isAutomationPossible())

        if (!anyTriggerEnabled || (panelPrefs.onlyOnHome && !isCurrentPackageLauncher()) || !hasActiveEngine) {
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
                val h = if (isPillVisible) (panelPrefs.handleHeight * density).toInt()
                        else (screenH * 0.60f).toInt()
                val maxOffset = (screenH / 2) - (h / 2) - safeMargin
                val requestedOffset = (panelPrefs.handleVerticalOffset * density).toInt()

                params.width = (panelPrefs.handleWidth * density).toInt()
                params.height = h
                params.y = requestedOffset.coerceIn(-maxOffset, maxOffset)
                
                try {
                    edgeHandleView?.let { windowManager.updateViewLayout(it, params) }
                } catch (e: Exception) {}
            }
            
            edgeHandleView?.updateState(
                isRight, 
                isPillVisible, 
                this.isImmersiveMode, 
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
            onAdjustBrightness = { delta ->
                adjustBrightness(delta)
            }
            onAdjustVolume = { delta ->
                adjustVolume(delta)
            }
            onSideChanged = { _ ->
                // Pill was dragged to the opposite edge — sync the whole service UI
                sidePanelView?.updateSideLayout()
            }
            isRightSide = isRight
            showPill = isPillVisible
            isImmersiveMode = this@FloatingPanelService.isImmersiveMode
            alpha = panelPrefs.panelOpacity / 100f
        }

        val handleWidth = panelPrefs.handleWidth // Use user-defined width
        val handleHeight = if (isPillVisible) dpToPx(panelPrefs.handleHeight) 
                           else dpToPx((panelPrefs.handleHeight * 1.5f).toInt())

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
            val maxOffset = (screenH / 2) - (handleHeight / 2) - safeMargin
            
            val requestedOffset = dpToPx(panelPrefs.handleVerticalOffset)
            y = requestedOffset.coerceIn(-maxOffset, maxOffset)
            
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
                onAppsChanged = { refreshApps() }
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
            // picker with the EditText rows intact but `editingItemId` still
            // populated. The next session would then log an indexing -1 error
            // when the ghost item was looked up. Saving on close also catches
            // the case where the user filled a valid title/URL but didn't tap
            // DONE — without the explicit save the row was silently lost.
            onClose = { pickerPanelView?.commitPendingEdits(); closePicker() }
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
            edgeHandleView?.visibility = View.VISIBLE
            sidePanelView?.animatePickerToggle(false)
            
            if (!panelPrefs.serviceEnabled) {
                stopSelf()
            }
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
            edgeHandleView?.visibility = View.VISIBLE
            return
        }

        if (isPickerOpen) closePicker()
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
                edgeHandleView?.visibility = View.VISIBLE
                panel.animatePickerToggle(false) 
                
                // If service is NOT enabled in prefs, stop it now (Test mode over)
                if (!panelPrefs.serviceEnabled) {
                    stopSelf()
                }
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
        // Flag first so concurrent ACTION_REFRESH cannot re-expand tools to 2-col.
        sidePanelView?.animatePickerToggle(true)
        sidePanelView?.setColumns(1)
        sidePanelView?.scrollToBottom()
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
            }
        }, PICKER_CLOSE_COLUMNS_DELAY_MS)
        pickerPanelView?.let { picker ->
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
                "Open Sidebar", openPending)
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

    fun adjustVolume(delta: Int) {
        if (delta == 0) return
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val direction = if (delta > 0) android.media.AudioManager.ADJUST_RAISE else android.media.AudioManager.ADJUST_LOWER

        try {
            // Repeat the adjustment for the magnitude of delta to maintain speed
            repeat(Math.abs(delta)) {
                audioManager.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, direction, 0)
            }
        } catch (e: SecurityException) {
            // OEM ExtImpl (ColorOS/OxygenOS) may reject adjustStreamVolume for
            // third-party apps despite MODIFY_AUDIO_SETTINGS. Fall back to the
            // privileged shell path when available.
            val current = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            val max = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            if (max > 0) {
                val target = (current + delta).coerceIn(0, max)
                if (!AutomationManager.execute("cmd media_session volume --stream 3 --set $target")) {
                    Log.w(TAG, "Privileged volume fallback failed", e)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "adjustStreamVolume failed", e)
        }

        val current = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        val max = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
        val percent = if (max > 0) (current * 100) / max else 0
        showIndicator(getString(R.string.indicator_volume_percent, percent))
        // System toast with the same info as the volume tool, so the result is
        // visible even when the overlay indicator is not (e.g. edge-slide).
        try {
            android.widget.Toast.makeText(
                this,
                getString(R.string.indicator_volume_percent, percent),
                android.widget.Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {}
    }

    fun adjustBrightness(delta: Int) {
        if (delta == 0) return
        try {
            if (!android.provider.Settings.System.canWrite(this)) {
                android.widget.Toast.makeText(this, getString(R.string.toast_write_settings_required), android.widget.Toast.LENGTH_SHORT).show()
                val intent = Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = android.net.Uri.parse("package:$packageName")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                return
            }

            val cResolver = contentResolver
            // 1. Ensure manual mode to allow manual override
            android.provider.Settings.System.putInt(cResolver, 
                android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE, 
                android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)

            // 2. Update standard int brightness (0-255)
            var brightness = android.provider.Settings.System.getInt(cResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS, 125)
            brightness = (brightness + delta).coerceIn(0, 255)
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

            val percent = (brightness * 100) / 255
            showIndicator(getString(R.string.indicator_brightness_percent, percent))
            // System toast with the same info as the brightness tool, so the
            // result is visible even when the overlay indicator is not.
            try {
                android.widget.Toast.makeText(
                    this,
                    getString(R.string.indicator_brightness_percent, percent),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {}
        } catch (e: Exception) {
            android.util.Log.e("FloatingPanelService", "Failed to adjust brightness", e)
        }
    }

    private fun showIndicator(text: String) {
        val root = rootLayout
        if (root != null) {
            if (indicatorText == null) {
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

                    layoutParams = android.widget.FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
                        bottomMargin = (90 * density).toInt()
                    }
                    elevation = 8f * density
                }
                root.addView(indicatorText)
            }

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
    }

    /**
     * Audit prompt — localized Snackbar surfaced when Shizuku/Root binder dies
     * mid-session. Falls back to a Toast only if no anchor View is yet attached
     * to the WindowManager (i.e. the very first probe fired before the panel was
     * ever opened).
     */
    private fun showEngineLostSnackbar() {
        val msg = getString(R.string.engine_lost_msg)
        val anchor = rootLayout ?: edgeHandleView
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
