package com.imi.smartedge.sidebar.panel

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import rikka.shizuku.Shizuku
import java.io.DataOutputStream
import java.util.concurrent.atomic.AtomicBoolean

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

    /** Maximum time to wait for a spawned `su`/Shizuku process before killing it. */
    private const val EXEC_TIMEOUT_SECONDS = 10L

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

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * The onResult waiting for a Shizuku permission grant/deny, set when
     * checkRootAndRequestPermission asks Shizuku for permission and
     * delivered by [permissionListener]. Without this, the grant result
     * went nowhere and the settings switch that triggered the flow stayed
     * checked while the pref was never saved.
     */
    @Volatile
    private var pendingShizukuResult: ((Boolean) -> Unit)? = null

    // Serializes [_engineState]/[isAutomationCached] writes so the binder-death
    // path cannot lose a race to a mid-flight [refresh] and overwrite NONE with
    // a stale SHIZUKU_READY.
    private val refreshMutex = Mutex()
    // Audit L3: AtomicBoolean guards Shizuku listener registration. Two concurrent
    // refresh() calls could both pass `if (!isListenerRegistered)` and register
    // the same listeners twice — Shizuku.addBinderDeadListener on a duplicate
    // throws IllegalArgumentException which was silently swallowed. compareAndSet
    // guarantees a single registration across all coroutine invocations.
    private val listenerRegistered = AtomicBoolean(false)

    // Audit fix — refresh() throttle + single-flight. The old refresh()
    // forked a `su` probe on every onResume with no timestamp, in-flight
    // flag, or cache-age check, so concurrent calls probed in parallel
    // and rapid activity resumes re-probed constantly. Skip a probe
    // started within REFRESH_THROTTLE_MS of the last one (unless forced,
    // e.g. after a Shizuku permission grant), and never run two probes
    // at once.
    private const val REFRESH_THROTTLE_MS = 5_000L
    @Volatile
    private var lastRefreshAtMs: Long = 0L
    private val refreshInFlight = AtomicBoolean(false)

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        scope.launch {
            refreshMutex.withLock {
                Log.w(TAG, "Shizuku binder died — invalidating engine cache")
                // Downgrade, don't drop: if the previous state was
                // SHIZUKU_AND_ROOT, root is still available — only the
                // Shizuku half is gone. Dropping to NONE would make root
                // actions fall back to a per-call sync re-probe (two su
                // forks per action) and fire a false "engine lost" banner.
                val was = _engineState.value
                if (was == EngineState.SHIZUKU_AND_ROOT) {
                    _engineState.value = EngineState.ROOT_READY
                    // Cache stays true: root is still usable.
                } else {
                    isAutomationCached = false
                    _engineState.value = EngineState.NONE
                    Handler(Looper.getMainLooper()).post { onEngineLost?.invoke() }
                }
            }
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        // Shizuku started after the app: re-probe now instead of waiting
        // for some activity's onResume (the service path never re-probes).
        refresh(force = true)
    }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        val granted = grantResult == PackageManager.PERMISSION_GRANTED
        // A permission grant is a real state change — bypass the throttle.
        val refreshJob = if (granted) refresh(force = true) else null
        // Deliver the pending checkRootAndRequestPermission callback (if
        // any) on the main thread, after the refresh has completed, so
        // the caller's UI reads the fresh engine state.
        pendingShizukuResult?.let { pending ->
            pendingShizukuResult = null
            scope.launch {
                refreshJob?.join()
                mainHandler.post { pending(granted) }
            }
        }
    }

    /**
     * Fire-and-forget engine probe. Safe to call from onResume (idempotent, cheap
     * no-op if cached == recenlty probed). Registers the Shizuku listeners on
     * the first invocation only; subsequent calls just re-probe.
     *
     * Throttled to at most one probe per [REFRESH_THROTTLE_MS] unless
     * [force] is true, and single-flight: a probe already running makes
     * this call a no-op instead of forking a parallel `su` probe.
     */
    fun refresh(force: Boolean = false): Job {
        return scope.launch {
            if (!force) {
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastRefreshAtMs < REFRESH_THROTTLE_MS &&
                    _engineState.value != EngineState.UNKNOWN
                ) return@launch
            }
            if (!refreshInFlight.compareAndSet(false, true)) return@launch
            try {
                lastRefreshAtMs = android.os.SystemClock.uptimeMillis()
            // Audit L3: compareAndSet returns true exactly once across all racing
            // refresh() calls. Losers no-op (they don't need to register; the
            // winning thread already did).
            if (listenerRegistered.compareAndSet(false, true)) {
                try {
                    Shizuku.addBinderDeadListener(binderDeadListener)
                    Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
                    Shizuku.addRequestPermissionResultListener(permissionListener)
                } catch (e: Exception) {
                    // Shizuku not installed — roll back so a later refresh() can
                    // retry once Shizuku actually starts.
                    listenerRegistered.set(false)
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
            } finally {
                refreshInFlight.set(false)
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

    /**
     * Shizuku is installed and its binder is alive, but we have not been
     * granted the Shizuku permission yet. Distinct from
     * [isShizukuAvailableInternal] (which also requires the grant): the
     * root-permission flow uses this to offer the Shizuku grant dialog.
     */
    private fun isShizukuBinderAlive(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            false
        }
    }

    private fun isRootAvailableInternal(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su -c id")
            try {
                // Wait with the timeout FIRST: a hung `su` prompt writes
                // nothing, so reading the stream before the wait would block
                // forever and defeat the bound — and wedge refreshInFlight,
                // leaving every later refresh() a no-op (see refresh()).
                // `su -c id` prints one short line, so there is no
                // pipe-buffer deadlock risk in waiting before draining.
                val finished = process.waitFor(EXEC_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    return false
                }
                // The process has exited, so the stream is now safe to drain.
                val output = process.inputStream.bufferedReader().use { it.readLine() }
                process.exitValue() == 0 && output != null && output.contains("uid=0")
            } finally {
                closeProcessStreams(process)
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Wait for a spawned process with a timeout, destroying it if it hangs,
     * and always close its streams. The old runShizuku/runRoot used an
     * unbounded `waitFor()` and never closed/drained the streams, so a hung
     * `su`/Shizuku process could block the calling (sometimes UI) thread
     * indefinitely and leak file descriptors.
     */
    private fun waitForProcess(process: Process): Boolean {
        return try {
            val finished = process.waitFor(EXEC_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                false
            } else {
                process.exitValue() == 0
            }
        } catch (e: Exception) {
            Log.w(TAG, "waitForProcess failed", e)
            false
        } finally {
            closeProcessStreams(process)
        }
    }

    private fun closeProcessStreams(process: Process) {
        try { process.inputStream.close() } catch (_: Exception) {}
        try { process.errorStream.close() } catch (_: Exception) {}
        try { process.outputStream.close() } catch (_: Exception) {}
    }

    // --- UNCHANGED UTILITY APIs (signatures preserved) ---

    fun requestRootPermission(onResult: (Boolean) -> Unit) {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val thread = Thread {
                try {
                    // Bounded wait (was an unbounded waitFor() on a raw thread).
                    val granted = waitForProcess(process)
                    if (granted) {
                        // A grant is a real state change: refresh the engine
                        // cache now, so isAutomationPossible() is true when
                        // the caller's callback runs.
                        refresh(force = true)
                    }
                    // The callback is documented as running on the main
                    // thread (SecureSettingsDialog touches UI in it): post
                    // it there instead of invoking it on this raw thread.
                    mainHandler.post { onResult(granted) }
                } catch (e: Exception) {
                    mainHandler.post { onResult(false) }
                }
            }
            thread.start()
        } catch (e: Exception) {
            onResult(false)
        }
    }

    fun checkRootAndRequestPermission(context: Context, onResult: (Boolean) -> Unit) {
        // Probes fork processes and do binder IPC: never on the calling
        // (UI) thread — the old code ran `which su` with a 10 s waitFor()
        // inline from click listeners, freezing the UI for up to 10 s.
        scope.launch {
            if (isShizukuAvailableInternal()) {
                mainHandler.post { onResult(true) }
                return@launch
            }
            val suExists = suBinaryExists() || try {
                val process = Runtime.getRuntime().exec("which su")
                waitForProcess(process)
            } catch (e: Exception) {
                false
            }
            val shizukuAlive = isShizukuBinderAlive()
            mainHandler.post {
                showPermissionDialog(context, suExists, shizukuAlive, onResult)
            }
        }
    }

    /**
     * su detection without forking: `which` is absent on some ROMs (false
     * "no root"). Check the usual install paths first, including Magisk's.
     */
    private fun suBinaryExists(): Boolean =
        java.io.File("/system/bin/su").exists() ||
            java.io.File("/system/xbin/su").exists() ||
            java.io.File("/sbin/su").exists()

    /**
     * Show the root / Shizuku grant dialog. Runs on the main thread (called
     * after the background probes finish). The [onResult] callback is
     * delivered on the main thread in every path — including when the
     * dialog is dismissed without a button press, which previously never
     * called back at all.
     */
    private fun showPermissionDialog(context: Context, suExists: Boolean, shizukuAlive: Boolean, onResult: (Boolean) -> Unit) {
        // The activity may have gone away while the probes ran.
        val activity = context as? android.app.Activity
        if (activity != null && (activity.isFinishing || activity.isDestroyed)) {
            onResult(false)
            return
        }
        var buttonPressed = false
        if (suExists) {
            val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
                .setTitle(R.string.dialog_root_detected_title)
                .setIcon(android.R.drawable.ic_dialog_info)
                .setMessage(R.string.dialog_root_detected_msg)
                .setPositiveButton(R.string.dialog_grant_permission) { _, _ ->
                    buttonPressed = true
                    requestRootPermission(onResult)
                }
                .setNegativeButton(R.string.dialog_not_now) { _, _ ->
                    buttonPressed = true
                    onResult(false)
                }
                .show()
            dialog.setOnDismissListener {
                if (!buttonPressed) onResult(false)
            }
        } else if (shizukuAlive) {
            // The user has Shizuku installed but hasn't granted us access yet —
            // surface the ADB / dialog route so they can grant via Shizuku app.
            // (Was `isShizukuAvailableInternal()` here, which also requires
            // the grant — but the top of this function already returned when
            // that was true, so the branch could never be reached.)
            val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
                .setTitle(R.string.dialog_shizuku_detected_title)
                .setIcon(android.R.drawable.ic_dialog_info)
                .setMessage(R.string.dialog_shizuku_detected_msg)
                .setPositiveButton(R.string.dialog_ok) { _, _ ->
                    buttonPressed = true
                    try {
                        // The grant/deny result arrives via permissionListener,
                        // which delivers this callback on the main thread.
                        pendingShizukuResult = onResult
                        Shizuku.requestPermission(0)
                    } catch (e: Exception) {
                        pendingShizukuResult = null
                        onResult(false)
                    }
                }
                .setNegativeButton(R.string.dialog_not_now) { _, _ ->
                    buttonPressed = true
                    onResult(false)
                }
                .show()
            dialog.setOnDismissListener {
                if (!buttonPressed) {
                    pendingShizukuResult = null
                    onResult(false)
                }
            }
        } else {
            onResult(false)
        }
    }

    // Audit L4: when the user just granted Shizuku permission but [refresh] is
    // still in-flight, the cache may not yet reflect READY — and execute() would
    // silently drop the call. The synchronous-fallback re-probes via the internal
    // Sync-fallback throttle (see execute()): while the cache is cold,
    // every execute() call would otherwise re-probe `su -c id` — on a
    // device where Magisk denies us, each probe forks su (deny toast) and
    // can stall up to 10 s. At most one sync probe per throttle window;
    // inside the window the last outcome is reused, including negatives.
    @Volatile
    private var lastSyncProbeAtMs: Long = 0L
    @Volatile
    private var lastSyncProbeState: EngineState = EngineState.UNKNOWN

    // helpers on the calling thread (these are confined ping/exec calls, not
    // coroutines) so a single input event still gets through to the engine.
    fun execute(command: String): Boolean {
        if (isShizukuAvailable()) {
            return runShizuku(command)
        } else if (isRootAvailable()) {
            return runRoot(command)
        }
        // Audit L4 fallback: cache cold, re-probe synchronously before giving up.
        // Rate-limited to one sync probe per refresh-throttle window: the
        // fallback path runs per volume tick (MediaVolumeApplier), so an
        // unthrottled probe forks `su -c id` on every tick.
        val now = android.os.SystemClock.uptimeMillis()
        val cached = lastSyncProbeState
        val engine = if (cached != EngineState.UNKNOWN && now - lastSyncProbeAtMs < REFRESH_THROTTLE_MS) {
            cached
        } else {
            val probed = when {
                isShizukuAvailableInternal() -> EngineState.SHIZUKU_READY
                isRootAvailableInternal() -> EngineState.ROOT_READY
                else -> EngineState.NONE
            }
            lastSyncProbeState = probed
            lastSyncProbeAtMs = now
            probed
        }
        return try {
            when (engine) {
                EngineState.SHIZUKU_READY -> runShizuku(command)
                EngineState.ROOT_READY -> runRoot(command)
                else -> false
            }
        } catch (e: Exception) {
            Log.w(TAG, "execute() sync-fallback failed: $command", e)
            false
        }
    }

    // Audit L3 (Shizuku 13): upstream `api:13.1.0` marked this overload
    // `@Deprecated` in Java itself, so the Kotlin warning persists regardless of
    // platform-type nullability. The replacement overload is unavailable in
    // 13.1.0, so pin the call with a narrow `@Suppress` and revisit when
    // Shizuku ships a non-deprecated API.
    // Signature: newProcess(@NonNull String[] command, @Nullable String[] envp, @Nullable String dir).
    @Suppress("DEPRECATION")
    private fun runShizuku(command: String): Boolean = try {
        val process = Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
        waitForProcess(process)
    } catch (e: Exception) {
        Log.e(TAG, "Shizuku execution failed: $command", e)
        false
    }

    private fun runRoot(command: String): Boolean = try {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
        waitForProcess(process)
    } catch (e: Exception) {
        Log.e(TAG, "Root execution failed: $command", e)
        false
    }

    fun performBack() = execute("input keyevent 4")
    fun performHome() = execute("input keyevent 3")
    fun performRecents() = execute("input keyevent 187")
    fun performNotifications() = execute("cmd statusbar expand-notifications")
    fun performQuickSettings() = execute("cmd statusbar expand-settings")

    // NOTE: --longpress must come BEFORE the keycode: AOSP's
    // InputShellCommand only reads it as the first arg; after the keycode
    // it is parsed as a second keycode (KEYCODE_UNKNOWN) and the press is
    // a normal short POWER press (screen off) instead of the power menu.
    fun performPowerMenu() = execute("input keyevent --longpress 26") // Long press power for menu

    // KEYCODE_SLEEP (223) turns the screen off without toggling: keyevent
    // 26 (POWER) would wake the screen if it is already off.
    fun performLockScreen(): Boolean = execute("input keyevent 223")

    fun performPreviousApp(): Boolean {
        performRecents()
        try { Thread.sleep(200) } catch (_: InterruptedException) {}
        return performRecents()
    }

    fun takeScreenshot(): Boolean = execute("input keyevent 120") // KEYCODE_SYSRQ/Screenshot
}
