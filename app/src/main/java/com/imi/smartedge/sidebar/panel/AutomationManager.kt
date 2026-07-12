package com.imi.smartedge.sidebar.panel

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import rikka.shizuku.Shizuku
import java.io.DataOutputStream

/**
 * Aud L5/U11/L7 — engine evaluation is now decoupled from synchronous reads.
 *
 *   - Heavy probes (Shizuku.pingBinder, checkSelfPermission, Runtime.exec("su -c id"))
 *     run on a process-lifetime [scope] coroutine once per [refresh] call.
 *   - Cheap callers ([isShizukuAvailable], [isRootAvailable], [isAutomationPossible])
 *     read the in-memory [_engineState] / [isAutomationCached] — so a sync call inside
 *     FloatingPanelService.addEdgeHandle() no longer forks a `Runtime.exec`.
 *   - [engineState] is the public [StateFlow] for any reactive UI (MainActivity banner,
 *     etc.) — fold-friendly, lifecycle-safe.
 *   - Shizuku's binder-died at runtime is observed via a process-scoped
 *     [Shizuku.OnBinderDeadListener]. On death we drop the cache to NONE under
 *     the same Mutex as [refresh] so a mid-flight probe cannot overwrite the reset
 *     with a stale state.
 *   - [onEngineLost] is a fire-and-forget main-thread callback cleared on death so
 *     FloatingPanelService can hide its handle and surface a banner without holding
 *     a reference to any UI.
 *
 * Each existing public signature is preserved byte-for-byte so external callers
 * (FloatingPanelService, SetupActivity, AccessibilityGuideDialog) need only add a
 * single `AutomationManager.refresh()` call in their onResume.
 */
object AutomationManager {
    private const val TAG = "AutomationManager"

    enum class EngineState { UNKNOWN, NONE, SHIZUKU_READY, ROOT_READY, SHIZUKU_AND_ROOT }

    private val _engineState = MutableStateFlow(EngineState.UNKNOWN)

    /** Public reactive view of engine health. Collect inside repeatOnLifecycle(STARTED) for UI. */
    val engineState: StateFlow<EngineState> = _engineState.asStateFlow()

    /** Cheap synchronous read; populated by [refresh] and invalidated on binder death. */
    @Volatile
    private var isAutomationCached: Boolean = false

    /**
     * Optional UI hook fired on the main thread when the binder dies or root
     * probe fails during a [refresh] and the cached state was previously true.
     * Reset to null automatically on each binder-death so the host can re-register.
     */
    @Volatile
    var onEngineLost: (() -> Unit)? = null

    // Process-lifetime scope justifies itself because Shizuku's binder is a
    // system-level context that outlives any individual Activity. Registering the
    // listeners in MainActivity.onCreate / onDestroy forces fine-grained balancing
    // and leaks the Listener if the Activity is destroyed mid-flight.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Serializes [_engineState]/[isAutomationCached] writes so the binder-death
    // path cannot lose a race to a mid-flight [refresh] and overwrite NONE with
    // a stale SHIZUKU_READY.
    private val refreshMutex = Mutex()
    private var isListenerRegistered: Boolean = false

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        scope.launch {
            refreshMutex.withLock {
                Log.w(TAG, "Shizuku binder died — invalidating engine cache")
                isAutomationCached = false
                _engineState.value = EngineState.NONE
            }
            Handler(Looper.getMainLooper()).post { onEngineLost?.invoke() }
        }
    }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            refresh()
        }
    }

    /**
     * Fire-and-forget engine probe. Safe to call from onResume (idempotent, cheap
     * no-op if cached == recenlty probed). Registers the Shizuku listeners on
     * the first invocation only; subsequent calls just re-probe.
     */
    fun refresh() {
        scope.launch {
            if (!isListenerRegistered) {
                try {
                    Shizuku.addBinderDeadListener(binderDeadListener)
                    Shizuku.addRequestPermissionResultListener(permissionListener)
                    isListenerRegistered = true
                } catch (e: Exception) {
                    // Shizuku not installed — leave listeners unregistered forever;
                    // refresh() will keep probing on its own.
                }
            }

            val shizukuProbe = isShizukuAvailableInternal()
            val rootProbe = isRootAvailableInternal()

            // Re-ping inside the lock to guarantee we don't overwrite a NONE
            // (set by a binder-death that fired mid-probe) with a stale READY.
            refreshMutex.withLock {
                val actuallyShizuku = shizukuProbe && try {
                    Shizuku.pingBinder()
                } catch (e: Exception) {
                    false
                }
                val newState = when {
                    actuallyShizuku && rootProbe -> EngineState.SHIZUKU_AND_ROOT
                    actuallyShizuku -> EngineState.SHIZUKU_READY
                    rootProbe -> EngineState.ROOT_READY
                    else -> EngineState.NONE
                }
                val wasReady = isAutomationCached
                isAutomationCached = actuallyShizuku || rootProbe
                _engineState.value = newState
                // If we transitioned READY → NONE mid-session, fire the UI hook.
                if (wasReady && !isAutomationCached) {
                    Handler(Looper.getMainLooper()).post { onEngineLost?.invoke() }
                }
            }
        }
    }

    // --- CHEAP SYNCHRONOUS PUBLIC APIs (cache reads; do not fork) ---

    fun isAutomationPossible(): Boolean = isAutomationCached

    fun isShizukuAvailable(): Boolean {
        val s = _engineState.value
        return s == EngineState.SHIZUKU_READY || s == EngineState.SHIZUKU_AND_ROOT
    }

    fun isRootAvailable(): Boolean {
        val s = _engineState.value
        return s == EngineState.ROOT_READY || s == EngineState.SHIZUKU_AND_ROOT
    }

    // --- EXPENSIVE PROBES (background-only by contract) ---

    private fun isShizukuAvailableInternal(): Boolean {
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    private fun isRootAvailableInternal(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su -c id")
            val reader = process.inputStream.bufferedReader()
            val output = reader.readLine()
            process.waitFor() == 0 && output != null && output.contains("uid=0")
        } catch (e: Exception) {
            false
        }
    }

    // --- UNCHANGED UTILITY APIs (signatures preserved) ---

    fun requestRootPermission(onResult: (Boolean) -> Unit) {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val thread = Thread {
                try {
                    val exitCode = process.waitFor()
                    onResult(exitCode == 0)
                } catch (e: Exception) {
                    onResult(false)
                }
            }
            thread.start()
        } catch (e: Exception) {
            onResult(false)
        }
    }

    fun checkRootAndRequestPermission(context: Context, onResult: (Boolean) -> Unit) {
        if (isShizukuAvailableInternal()) {
            onResult(true)
            return
        }

        val suExists = try {
            Runtime.getRuntime().exec("which su").waitFor() == 0
        } catch (e: Exception) {
            false
        }

        if (suExists) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
                .setTitle("Root Detected")
                .setIcon(android.R.drawable.ic_dialog_info)
                .setMessage("Smart Edge has detected root access. Using root for gestures is more efficient and saves battery compared to the Accessibility Service.\n\nWould you like to grant root permission now?")
                .setPositiveButton("Grant Permission") { _, _ -> requestRootPermission(onResult) }
                .setNegativeButton("Not Now") { _, _ -> onResult(false) }
                .show()
        } else if (isShizukuAvailableInternal()) {
            // The user has Shizuku installed but hasn't granted us access yet —
            // surface the ADB / dialog route so they can grant via Shizuku app.
            com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
                .setTitle("Shizuku Detected")
                .setIcon(android.R.drawable.ic_dialog_info)
                .setMessage("Smart Edge detected Shizuku. Grant Smart Edge permission in the Shizuku app to use Shizuku for gestures (recommended over Root).")
                .setPositiveButton("OK") { _, _ ->
                    try {
                        Shizuku.requestPermission(0)
                    } catch (e: Exception) {
                        onResult(false)
                    }
                }
                .setNegativeButton("Not Now") { _, _ -> onResult(false) }
                .show()
        } else {
            onResult(false)
        }
    }

    // Shizuku 11/12 exposes this (Array<String>, Array<String>?, String?) overload;
    // Shizuku 13 deprecates it in favor of an (Array<String>, Array<String>?, String?)
    // overload with refined Kotlin nullability on the String! platform types. The
    // migration is gated on a major Shizuku version bump; for now suppress the
    // single-method deprecation warning at the call site.
    @Suppress("DEPRECATION")
    fun execute(command: String): Boolean {
        if (isShizukuAvailable()) {
            return try {
                val process = Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
                process.waitFor() == 0
            } catch (e: Exception) {
                Log.e(TAG, "Shizuku execution failed: $command", e)
                false
            }
        } else if (isRootAvailable()) {
            return try {
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
                process.waitFor() == 0
            } catch (e: Exception) {
                Log.e(TAG, "Root execution failed: $command", e)
                false
            }
        }
        return false
    }

    fun performBack() = execute("input keyevent 4")
    fun performHome() = execute("input keyevent 3")
    fun performRecents() = execute("input keyevent 187")
    fun performNotifications() = execute("cmd statusbar expand-notifications")
    fun performQuickSettings() = execute("cmd statusbar expand-settings")
    fun performSplitScreen() = execute("cmd statusbar toggle-split-screen")

    fun performPowerMenu() = execute("input keyevent 26 --longpress") // Long press power for menu

    fun performLockScreen() {
        // Locking is tricky via shell. input keyevent 26 (power) is the best fallback
        execute("input keyevent 26")
    }

    fun performPreviousApp() {
        // Previous app is usually two recents taps
        performRecents()
        Thread.sleep(200)
        performRecents()
    }

    fun takeScreenshot() {
        // Shell screenshot command varies, but 'screencap' is common.
        // However, triggering the system screenshot UI is better.
        execute("input keyevent 120") // KEYCODE_SYSRQ/Screenshot
    }
}
