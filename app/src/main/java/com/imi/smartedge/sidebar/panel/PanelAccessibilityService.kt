package com.imi.smartedge.sidebar.panel

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log

/** Snapshot of one visible control for Content Picker. */
data class ContentInfo(
    val bounds: android.graphics.Rect,
    val text: String?,
    val contentDescription: String?,
    val viewIdResourceName: String?,
    val className: String?
)

class PanelAccessibilityService : AccessibilityService() {

    private lateinit var panelPrefs: PanelPreferences
    private var lastImmersiveState = false
    private var lastPackageName: String? = null
    // Round-12 audit L-Medium: pre-allocate a single main-looper Handler
    // for the few postDelayed sites in this class. Previously each call to
    // ACTION_PREVIOUS_APP allocated a fresh `Handler(Looper.getMainLooper())`
    // (now fixed below to use this field instead). Holding the reference
    // here also lets onDestroy sweep pending runnables when the system
    // un-binds the service, preventing the Looper from holding a lambda
    // that captures `this` past the service's death.
    private val accessibilityHandler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        panelPrefs = PanelPreferences(this)
    }

    private fun checkImmersiveMode() {
        if (!panelPrefs.serviceEnabled) return
        val root = rootInActiveWindow ?: return
        
        // Strategy: Check if the main window covers the whole screen area
        // and doesn't have system bars visible. Since we can't directly check system bar visibility
        // easily from here, we look at the window bounds vs screen bounds.
        
        val displayMetrics = resources.displayMetrics
        val screenRect = android.graphics.Rect(0, 0, displayMetrics.widthPixels, displayMetrics.heightPixels)
        
        val windowRect = android.graphics.Rect()
        root.getBoundsInScreen(windowRect)
        
        // Many immersive apps (videos/games) have bounds that match screen closely.
        // We use a small tolerance (5%) to account for notches, display cutouts, 
        // or system-level padding that might report a slightly smaller root window.
        val isImmersive = windowRect.width() >= screenRect.width() * 0.95 && 
                         windowRect.height() >= screenRect.height() * 0.95
        
        if (isImmersive != lastImmersiveState) {
            lastImmersiveState = isImmersive
            val intent = Intent(this, FloatingPanelService::class.java).apply {
                action = FloatingPanelService.ACTION_UPDATE_IMMERSIVE
                putExtra("is_immersive", isImmersive)
            }
            startService(intent)
        }
    }

    companion object {
        private const val TAG = "PanelAccessibility"
        const val ACTION_TAKE_SCREENSHOT = "com.imi.smartedge.sidebar.panel.ACTION_TAKE_SCREENSHOT"
        const val ACTION_SHOW_POWER_MENU = "com.imi.smartedge.sidebar.panel.ACTION_SHOW_POWER_MENU"
        const val ACTION_TRIGGER_SHORTCUT = "com.imi.smartedge.sidebar.panel.ACTION_TRIGGER_SHORTCUT"
        const val ACTION_ONE_HANDED = "com.imi.smartedge.sidebar.panel.ACTION_ONE_HANDED"
        const val ACTION_PREVIOUS_APP = "com.imi.smartedge.sidebar.panel.ACTION_PREVIOUS_APP"
        const val ACTION_BACK = "com.imi.smartedge.sidebar.panel.ACTION_BACK"
        const val ACTION_HOME = "com.imi.smartedge.sidebar.panel.ACTION_HOME"
        const val ACTION_RECENTS = "com.imi.smartedge.sidebar.panel.ACTION_RECENTS"
        const val ACTION_NOTIFICATIONS = "com.imi.smartedge.sidebar.panel.ACTION_NOTIFICATIONS"
        const val ACTION_QUICK_SETTINGS = "com.imi.smartedge.sidebar.panel.ACTION_QUICK_SETTINGS"
        const val ACTION_LOCK_SCREEN = "com.imi.smartedge.sidebar.panel.ACTION_LOCK_SCREEN"
        const val ACTION_CONTENT_PICK_REFRESH = "com.imi.smartedge.sidebar.panel.ACTION_CONTENT_PICK_REFRESH"

        // Timing (ms)
        const val SCREENSHOT_RESTORE_NOTIFY_DELAY_MS = 300L
        const val PREV_APP_INTERVAL_OFF_MS = 150L
        const val PREV_APP_INTERVAL_REDUCED_MS = 200L
        const val PREV_APP_INTERVAL_DEFAULT_MS = 350L
        const val PREV_APP_INTERVAL_SLOW_MS = 500L

        /** Set by FloatingPanelService while Content Picker is active; invoked on the
         *  main thread each time the service collects a fresh control snapshot. */
        @Volatile
        var contentPickerCallback: ((List<ContentInfo>) -> Unit)? = null
        
        @Volatile
        var isRunning = false
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TAKE_SCREENSHOT -> {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
                }
                // Tell the overlay service it is safe to restore alphas after the
                // system has had a moment to start the capture pipeline.
                accessibilityHandler.postDelayed({
                    try {
                        val restore = Intent(this, FloatingPanelService::class.java).apply {
                            action = FloatingPanelService.ACTION_SCREENSHOT_UI_RESTORE
                        }
                        startService(restore)
                    } catch (_: Exception) {}
                }, SCREENSHOT_RESTORE_NOTIFY_DELAY_MS)
            }
            ACTION_SHOW_POWER_MENU -> {
                performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
            }
            ACTION_ONE_HANDED -> {
                // Round-12 audit L-Low: Toast.makeText is already main-thread
                // safe and runs the show on the calling thread. Wrapping the
                // show in another `Handler(Looper.getMainLooper()).post { … }`
                // only added an extra main-looper round trip; this branch
                // already runs on the main looper (AccessibilityService
                // onStartCommand dispatches on the main thread by contract).
                android.widget.Toast.makeText(this, getString(R.string.one_handed_not_supported), android.widget.Toast.LENGTH_SHORT).show()
            }
            ACTION_PREVIOUS_APP -> {
                // Double-tap KEYCODE_APP_SWITCH to switch to the previous app.
                // The interval is scaled by the device's animator_duration_scale
                // so it adapts automatically to each OEM's animation speed:
                //   scale 0   (animations off)  → 150ms
                //   scale 0.5 (reduced)         → 200ms
                //   scale 1.0 (default)          → 350ms
                //   scale >1  (slow / accessibility) → 500ms
                val animScale = try {
                    android.provider.Settings.Global.getFloat(
                        contentResolver,
                        android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                        1f
                    )
                } catch (_: Exception) { 1f }
                val intervalMs = when {
                    animScale <= 0f  -> PREV_APP_INTERVAL_OFF_MS
                    animScale < 0.8f -> PREV_APP_INTERVAL_REDUCED_MS
                    animScale < 1.5f -> PREV_APP_INTERVAL_DEFAULT_MS
                    else             -> PREV_APP_INTERVAL_SLOW_MS
                }
                performGlobalAction(GLOBAL_ACTION_RECENTS)
                accessibilityHandler.postDelayed({
                    performGlobalAction(GLOBAL_ACTION_RECENTS)
                }, intervalMs)
            }
            ACTION_BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            ACTION_HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            ACTION_RECENTS -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            ACTION_NOTIFICATIONS -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            ACTION_QUICK_SETTINGS -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            ACTION_LOCK_SCREEN -> {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                }
            }
            ACTION_TRIGGER_SHORTCUT -> {
                val shortcut = intent.getStringExtra("shortcut")
                if (shortcut == "smartedge.shortcut.one_hand") {
                    // Round-12 audit L-Low: same drop-the-redundant-Handler fix
                    // as ACTION_ONE_HANDED above. Toast from the main thread is
                    // already main-thread safe.
                    android.widget.Toast.makeText(this, getString(R.string.one_handed_not_supported), android.widget.Toast.LENGTH_SHORT).show()
                    // Attempting standard fallback if the OEM supports it via AccessibilityService
                    // true specific one-handed mode intents are heavily fragmented
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                        // In Android 12+, there's no public GLOBAL_ACTION_ONE_HANDED.
                        // We rely on standard gesture dispatch or root if really necessary.
                    }
                }
            }
            ACTION_CONTENT_PICK_REFRESH -> {
                collectControlsAndNotify()
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Recursively walk the active window's node tree and collect visible controls
     * with their screen bounds plus description/id metadata for Content Picker.
     * Skips nodes belonging to our own overlay windows so the picker never
     * highlights its own mask.
     */
    private fun collectControlsAndNotify() {
        val cb = contentPickerCallback ?: return
        val root = rootInActiveWindow ?: run { cb(emptyList()); return }
        val out = ArrayList<ContentInfo>(64)
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null) return
            try {
                val pkg = node.packageName?.toString()
                if (pkg == packageName) { /* skip our own overlay windows */ }
                else {
                    val b = android.graphics.Rect()
                    node.getBoundsInScreen(b)
                    // Skip zero-area / offscreen invisible nodes.
                    if (b.width() > 0 && b.height() > 0 &&
                        b.right > 0 && b.bottom > 0
                    ) {
                        val cd = node.contentDescription?.toString()
                        val vid = node.viewIdResourceName
                        val cls = node.className?.toString()
                        val txt = node.text?.toString()
                        // Keep nodes that are interactive OR have copyable metadata
                        // (contentDescription / viewIdResourceName). Text-only nodes
                        // without id are still useful for contentDescription-less
                        // icons, so include them too but prefer metadata-bearing ones.
                        if (node.isClickable || !cd.isNullOrBlank() || !vid.isNullOrBlank() || !txt.isNullOrBlank()) {
                            out.add(ContentInfo(b, txt, cd, vid, cls))
                        }
                    }
                }
            } catch (_: Exception) {}
            for (i in 0 until node.childCount) {
                walk(node.getChild(i))
            }
        }
        try {
            walk(root)
        } catch (_: Exception) {}
        cb(out)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString() ?: return
            if (packageName == lastPackageName) return
            lastPackageName = packageName

            // Store current foreground package for Context/Game mode
            panelPrefs.currentForegroundPackage = packageName
            
            // Get the current active keyboard package
            val defaultIme = android.provider.Settings.Secure.getString(
                contentResolver,
                android.provider.Settings.Secure.DEFAULT_INPUT_METHOD
            )
            val imePackage = defaultIme?.substringBefore("/") ?: ""
            
            val isSystemPkg = packageName == "android" || packageName == "com.android.systemui"
            
            if (packageName != "com.imi.smartedge.sidebar.panel" && packageName != imePackage && !isSystemPkg) {
                if (panelPrefs.serviceEnabled) {
                    val closeIntent = Intent(this, FloatingPanelService::class.java).apply {
                        action = FloatingPanelService.ACTION_CLOSE_PANEL
                    }
                    startService(closeIntent)

                    // Notify service to update game mode state based on new foreground package
                    val refreshIntent = Intent(this, FloatingPanelService::class.java).apply {
                        action = FloatingPanelService.ACTION_REFRESH
                    }
                    startService(refreshIntent)
                }
            }
        }
        
        // Audit U-High: only re-evaluate immersive state on full window
        // STATE changes. The previous logic also fired on every
        // TYPE_WINDOW_CONTENT_CHANGED event, which arrives frequently during
        // progress bar / animation / video playback frames — each invocation
        // makes a synchronous `rootInActiveWindow` IPC + bounds calculation on
        // the AccessibilityService main thread. Limiting to STATE_CHANGED
        // drops the per-frame cost without changing visible behavior, since
        // immersive/fullscreen state only flips at app-change boundaries in
        // practice (entering/leaving fullscreen video re-issues a STATE event).
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            checkImmersiveMode()
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        isRunning = false
        val stopIntent = Intent(this, FloatingPanelService::class.java).apply {
            action = FloatingPanelService.ACTION_STOP_RUNTIME
        }
        startService(stopIntent)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Round-12 audit L-Medium: drain the cached Handler so any pending
        // postDelayed runnables (e.g. ACTION_PREVIOUS_APP's double-RECENTS)
        // can't fire after the AccessibilityService is being torn down.
        // Without this, the Looper keeps the bound lambda (capturing `this`)
        // alive until it naturally runs, even though the service is gone.
        accessibilityHandler.removeCallbacksAndMessages(null)
    }
}
