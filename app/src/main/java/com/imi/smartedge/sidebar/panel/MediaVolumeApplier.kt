package com.imi.smartedge.sidebar.panel

import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Applies a media-volume change of [steps] whole hardware steps (sign =
 * direction) by setting the absolute volume index.
 *
 * Why not the relative `adjustStreamVolume`? On some phones one adjust call
 * moves more than one index: on the OnePlus 13 a single
 * `adjustStreamVolume(ADJUST_RAISE)` jumps ~10 indices (6%), while
 * `setStreamVolume` to an absolute index moves exactly one. So one tick must
 * be an absolute set, or the phone's coarse adjust step breaks
 * "one tick = one step".
 *
 * Paths, in order:
 * 1. `setStreamVolume` to the absolute target on the calling (main) thread,
 *    verified by read-back. ColorOS/OxygenOS reject it for third-party apps
 *    (SecurityException or a silent no-op), which falls through. Skipped
 *    while shell batches are still landing, so the two threads never
 *    read-modify-write the volume at the same time.
 * 2. Privileged shell absolute set (`cmd media_session volume --set`) on one
 *    background thread (a shell call can block for seconds; one thread keeps
 *    the sets ordered). Ticks are coalesced into [pendingShellSteps] and one
 *    drain lands them in batches of at most
 *    [SlideStepMath.MAX_SHELL_BATCH_STEPS], each re-derived from the live
 *    volume so queued ticks compose.
 * 3. Relative `adjustStreamVolume`, ONLY when no privileged shell exists at
 *    all. It is never used as a fallback for a shell set that reported
 *    failure: such a set (10 s timeout, killed process, non-zero exit) may
 *    still have landed, and a coarse adjust on top of it moved the volume a
 *    whole coarse step past the target, which was the intermittent "volume
 *    jumps ~10%" bug. A shell batch that truly failed is dropped; the
 *    indicator then shows the real volume.
 *
 * [onApplied] refreshes the indicator. On the synchronous path it fires once,
 * inline. On the shell path only the newest callback is kept (every caller's
 * callback just re-reads the live volume), and it fires on the main thread
 * after every batch and once more when the queue drains, so the indicator
 * follows the volume as it moves and ends on its final value.
 *
 * Must be called on the main thread. Returns true only when the volume reached
 * the target synchronously (or was already there).
 */
object MediaVolumeApplier {

    private const val TAG = "MediaVolumeApplier"
    private const val STREAM = AudioManager.STREAM_MUSIC

    private val shellExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "MediaVolumeApplier-shell").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    private val pendingShellSteps = AtomicInteger(0)
    private val shellTaskActive = AtomicBoolean(false)

    /** Newest shell-path callback. Main thread only. Dropped when the queue goes idle. */
    private var shellCallback: ((Boolean) -> Unit)? = null

    fun apply(
        audioManager: AudioManager,
        steps: Int,
        onApplied: ((applied: Boolean) -> Unit)? = null
    ): Boolean {
        if (steps == 0) {
            onApplied?.invoke(true)
            return true
        }
        val max = audioManager.getStreamMaxVolume(STREAM)
        if (max <= 0) {
            onApplied?.invoke(false)
            return false
        }
        val before = audioManager.getStreamVolume(STREAM)
        val target = MediaVolumeMath.target(before, steps, max)
        if (target == before && !shellTaskActive.get()) {
            onApplied?.invoke(true)
            return true
        }

        // 1. Exact absolute set, verified by read-back.
        if (!shellTaskActive.get()) {
            try {
                audioManager.setStreamVolume(STREAM, target, 0)
                if (audioManager.getStreamVolume(STREAM) == target) {
                    onApplied?.invoke(true)
                    return true
                }
            } catch (e: SecurityException) {
                // OEM rejects setStreamVolume for third-party apps.
            } catch (e: Exception) {
                Log.w(TAG, "setStreamVolume failed", e)
            }
        }

        // 2./3. Queue for the background drain.
        if (onApplied != null) shellCallback = onApplied
        pendingShellSteps.addAndGet(steps)
        if (shellTaskActive.compareAndSet(false, true)) {
            shellExecutor.execute { drainShellSteps(audioManager, max) }
        }
        return false
    }

    /**
     * Lands the queued steps in bounded batches until the queue is empty.
     * The active flag is cleared only when a pass finds nothing pending, with
     * a re-check so a tick queued between the take and the clear is never
     * stranded.
     */
    private fun drainShellSteps(audioManager: AudioManager, max: Int) {
        while (true) {
            val pending = pendingShellSteps.getAndSet(0)
            if (pending == 0) {
                shellTaskActive.set(false)
                if (pendingShellSteps.get() != 0 && shellTaskActive.compareAndSet(false, true)) continue
                mainHandler.post {
                    // Final refresh, then drop the callback if still idle.
                    // Runs on the main thread, where apply() runs, so a tick
                    // queued since keeps its callback; if a later drain
                    // already finished, this refresh shows its final volume.
                    val cb = shellCallback
                    if (!shellTaskActive.get()) shellCallback = null
                    cb?.invoke(true)
                }
                return
            }
            val batch = SlideStepMath.shellBatchTake(pending)
            if (batch != pending) pendingShellSteps.addAndGet(pending - batch)
            val applied = applyBatch(audioManager, batch, max)
            mainHandler.post { shellCallback?.invoke(applied) }
        }
    }

    /** One batch: shell absolute set, or the relative adjust when no shell exists. */
    private fun applyBatch(audioManager: AudioManager, steps: Int, max: Int): Boolean = try {
        val live = audioManager.getStreamVolume(STREAM)
        val target = MediaVolumeMath.target(live, steps, max)
        when {
            target == live -> true
            // The adjust decision is a pure function (see
            // MediaVolumeMath.mayUseAdjust): adjust only when no shell
            // engine exists at all. hasShellEngine() never probes — it is
            // a cheap cached read — so evaluating it even when the shell
            // set succeeded costs nothing.
            MediaVolumeMath.mayUseAdjust(
                AutomationManager.execute("cmd media_session volume --stream 3 --set $target"),
                AutomationManager.hasShellEngine()
            ) -> applyByAdjust(audioManager, steps, target)
            // Never stack an adjust on a shell set (see class KDoc, path 3).
            else -> audioManager.getStreamVolume(STREAM) == target
        }
    } catch (e: Exception) {
        Log.w(TAG, "volume batch failed", e)
        false
    }

    /**
     * Last-resort relative adjust (no privileged shell). Reads back after every
     * call and stops as soon as the target is reached or crossed (one adjust can
     * move several indices on phones with a coarse adjust step), or when an
     * adjust makes no progress. The termination rule lives in [MediaVolumeMath].
     */
    private fun applyByAdjust(audioManager: AudioManager, steps: Int, target: Int): Boolean {
        val direction = if (steps > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        var current = audioManager.getStreamVolume(STREAM)
        repeat(Math.abs(steps)) {
            if (MediaVolumeMath.hasReached(current, target, steps)) return true
            val beforeAdjust = current
            audioManager.adjustStreamVolume(STREAM, direction, 0)
            current = audioManager.getStreamVolume(STREAM)
            if (!MediaVolumeMath.shouldAdjustAgain(current, beforeAdjust, target, steps)) {
                return MediaVolumeMath.hasReached(current, target, steps)
            }
        }
        return MediaVolumeMath.hasReached(current, target, steps)
    }
}
