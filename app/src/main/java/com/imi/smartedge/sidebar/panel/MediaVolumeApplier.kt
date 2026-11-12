package com.imi.smartedge.sidebar.panel

import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors

/**
 * Applies a media-volume change of [steps] whole hardware steps (sign =
 * direction) by setting the absolute volume index.
 *
 * Why not the relative `adjustStreamVolume`? On some phones one adjust call
 * moves more than one index: on this OnePlus 13 a single physical volume
 * press — and a single `adjustStreamVolume(ADJUST_RAISE)` call — jumps the
 * volume by ~10 indices (6%), while `setStreamVolume` to an absolute index
 * moves exactly one index (proven by the Volume Probe's live test, which set
 * and read back exact indices). So one tick must be an absolute set, not a
 * relative adjust, or the "one tick = one step" promise is broken by the
 * phone's coarse adjust step.
 *
 * Tries, in order:
 * 1. `setStreamVolume` to the absolute target (exact, one binder call),
 *    verified by reading the volume back. Some OEM forks (ColorOS/OxygenOS)
 *    reject it for third-party apps despite MODIFY_AUDIO_SETTINGS — that is
 *    the "do not have setStreamVolume Permission" log — so a
 *    SecurityException (or a read-back that shows no change) falls through.
 * 2. The privileged shell absolute set
 *    (`cmd media_session volume --stream 3 --set <target>`), also exact and
 *    also verified by read-back. This runs on a single background thread,
 *    never on the calling (often main/UI) thread: a shell call can block
 *    for seconds on a cold engine probe, and a volume tick must never freeze
 *    the touch dispatcher. A single thread also keeps shell sets ordered —
 *    each sets an absolute target, so out-of-order completion would move
 *    the volume backwards. The target is re-derived from the live volume
 *    when the task runs, so queued ticks compose instead of overwriting
 *    each other. Ticks are coalesced: a fast drag accumulates its steps
 *    and one task drains them, instead of one shell call per tick (which
 *    kept the volume changing for seconds after the finger lifted).
 * 3. Relative `adjustStreamVolume` as a last resort (coarse on phones whose
 *    adjust step is more than one index). It reads back after every call
 *    and stops as soon as the target is reached or crossed, or when an
 *    adjust makes no progress — so it can never overshoot by more than one
 *    coarse step, and it applies zero adjusts when the volume is already
 *    there (which also covers a stale read-back from steps 1/2 that
 *    actually landed).
 *
 * [onApplied] is invoked exactly once, on the main thread, with whether the
 * volume reached (or, for the coarse adjust fallback, crossed) the target.
 * It fires inline for the synchronous paths and when the background shell
 * work completes for the shell path. The return value is true only when the
 * volume reached the target synchronously (or was already there); false
 * means it did not land synchronously or is still pending on the shell.
 */
object MediaVolumeApplier {

    private const val TAG = "MediaVolumeApplier"

    private val shellExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "MediaVolumeApplier-shell").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    // Coalescing state for the shell path: a fast drag queues many ticks,
    // each of which used to run its own shell call (hundreds of ms each),
    // so the volume kept changing for seconds after the finger lifted.
    // Steps now accumulate in pendingShellSteps and a single task drains
    // them; callbacks accumulate alongside and all fire with the drain's
    // result (callers only refresh the indicator from the live volume).
    private val pendingShellSteps = java.util.concurrent.atomic.AtomicInteger(0)
    private val shellTaskActive = java.util.concurrent.atomic.AtomicBoolean(false)
    private val pendingShellCallbacks =
        java.util.Collections.synchronizedList(mutableListOf<(Boolean) -> Unit>())

    fun apply(
        audioManager: AudioManager,
        steps: Int,
        onApplied: ((applied: Boolean) -> Unit)? = null
    ): Boolean {
        if (steps == 0) {
            onApplied?.invoke(true)
            return true
        }
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) {
            onApplied?.invoke(false)
            return false
        }
        val before = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val target = MediaVolumeMath.target(before, steps, max)
        if (target == before) {
            onApplied?.invoke(true)
            return true
        }

        // 1. Exact absolute set, verified by read-back. The landing is
        // logged (see below): a silent fallthrough to a weaker path must
        // be visible in logcat, or the next on-device jump cannot be
        // diagnosed.
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == target) {
                Log.d(TAG, "setStreamVolume landed $steps step(s): $before -> $target")
                onApplied?.invoke(true)
                return true
            }
            // Silent no-op (no throw, no change): fall through to the shell.
            // A stale read-back is harmless — the shell sets the same
            // absolute target, and the adjust fallback re-reads before acting.
            Log.w(TAG, "setStreamVolume no-op: requested $target, still $before; falling through to shell")
        } catch (e: SecurityException) {
            // OEM ExtImpl may reject setStreamVolume for third-party apps;
            // fall through to the shell absolute set.
            Log.w(TAG, "setStreamVolume rejected (SecurityException); falling through to shell", e)
        } catch (e: Exception) {
            Log.w(TAG, "setStreamVolume failed", e)
        }

        // 2. Exact absolute set via the privileged shell, off the main thread.
        // Coalesced: the steps accumulate and a single task drains them (see
        // drainShellSteps), instead of one shell call per tick.
        onApplied?.let { pendingShellCallbacks.add(it) }
        pendingShellSteps.addAndGet(steps)
        if (shellTaskActive.compareAndSet(false, true)) {
            shellExecutor.execute { drainShellSteps(audioManager, max) }
        }
        // Pending: the callback fires when the background work completes.
        return false
    }

    /**
     * Drains the accumulated shell steps in batches. Each batch re-derives
     * its target from the live volume and applies the accumulated steps as
     * one absolute set (shell, then the relative-adjust fallback), then
     * fires the callbacks that arrived with those steps. Loops while new
     * ticks arrive mid-drain; the active flag is only cleared when a drain
     * finds nothing pending, with a re-check so a tick that lands between
     * the last take and the clear is never stranded.
     */
    private fun drainShellSteps(audioManager: AudioManager, max: Int) {
        while (true) {
            val steps = pendingShellSteps.getAndSet(0)
            if (steps == 0) {
                shellTaskActive.set(false)
                if (pendingShellSteps.get() != 0 && shellTaskActive.compareAndSet(false, true)) {
                    continue
                }
                invokePendingCallbacks(true)
                return
            }
            var applied = false
            var liveTarget = -1
            try {
                // Re-derive the target from the live volume: queued ticks
                // must compose (each moves `steps` from wherever the volume
                // is when this task runs), not overwrite each other.
                val live = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                liveTarget = MediaVolumeMath.target(live, steps, max)
                if (liveTarget != live &&
                    AutomationManager.execute("cmd media_session volume --stream 3 --set $liveTarget")
                ) {
                    applied = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == liveTarget
                } else if (liveTarget == live) {
                    applied = true
                }
            } catch (e: Exception) {
                Log.w(TAG, "shell volume set failed", e)
            }
            Log.d(TAG, "shell volume set: steps=$steps target=$liveTarget applied=$applied")
            if (!applied) {
                // 3. Last resort: relative adjust (coarse on some phones).
                val live = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                val liveTarget = MediaVolumeMath.target(live, steps, max)
                applied = applyByAdjust(audioManager, steps, liveTarget)
            }
            invokePendingCallbacks(applied)
        }
    }

    private fun invokePendingCallbacks(applied: Boolean) {
        val callbacks: List<(Boolean) -> Unit> = synchronized(pendingShellCallbacks) {
            val copy = pendingShellCallbacks.toList()
            pendingShellCallbacks.clear()
            copy
        }
        if (callbacks.isEmpty()) return
        mainHandler.post { callbacks.forEach { it(applied) } }
    }

    /**
     * Last-resort relative adjust. Reads back after every call and stops as
     * soon as the target is reached or crossed (one adjust can move several
     * indices on phones with a coarse adjust step), or when an adjust makes
     * no progress (boundary / no-op adjust). Never applies more adjusts than
     * requested, and applies zero adjusts when the volume is already there.
     * The termination rule lives in [MediaVolumeMath] (pure, unit-tested).
     */
    private fun applyByAdjust(audioManager: AudioManager, steps: Int, target: Int): Boolean {
        val direction = if (steps > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        return try {
            var current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            var ok = false
            for (i in 0 until Math.abs(steps)) {
                if (MediaVolumeMath.hasReached(current, target, steps)) {
                    ok = true
                    break
                }
                val beforeAdjust = current
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
                current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                if (!MediaVolumeMath.shouldAdjustAgain(current, beforeAdjust, target, steps)) {
                    ok = MediaVolumeMath.hasReached(current, target, steps)
                    break
                }
            }
            if (!ok) ok = MediaVolumeMath.hasReached(current, target, steps)
            // The coarse adjust can only land on a multiple of the phone's
            // adjust step, so it overshoots a fine target by up to one
            // coarse step (~6% here). Log the landing so an on-device jump
            // can be told apart from a burst of legitimate steps.
            Log.w(TAG, "adjust fallback: steps=$steps target=$target landed=$current")
            ok
        } catch (e: Exception) {
            Log.w(TAG, "adjustStreamVolume failed", e)
            false
        }
    }
}
