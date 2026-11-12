package com.imi.smartedge.sidebar.panel

/**
 * Pacing for the height-delta (vertical drag) → steps conversion shared by
 * the three volume/brightness drag paths: the edge slide (EdgeHandleView),
 * the dashboard long-press drag (SidePanelView.handleDragTouch) and the
 * Tools-folder long-press drag (PanelAppsAdapter).
 *
 * The problem it fixes: all three paths used to convert the whole
 * accumulated travel into steps in a single MOVE event, with no upper
 * bound. Android coalesces MOVE events, so a fast flick — or a MOVE
 * delivered after the UI thread was briefly busy — can carry several
 * hundred pixels of travel in one event. At ~41px per volume step on a
 * 160-step phone that is 10+ steps in one frame, and the applier lands
 * them as a single absolute set, so the volume (and the toast) jumps
 * ~10% in one frame instead of tracking the finger. The total was
 * correct — proportional to the finger's travel — but the landing was a
 * single burst, which reads as a sudden jump.
 *
 * The fix: at most [MAX_STEPS_PER_MOVE] steps are emitted per MOVE event.
 * The remainder stays in the caller's accumulator (in pixels, so it is
 * valid at any pixels-per-unit — a sensitivity change mid-gesture needs
 * no rebasing) and drains on later MOVE events and at gesture end
 * ([drainSteps]). At 60Hz MOVE events the cap still allows ~75%/s,
 * faster than a deliberate swipe, so it only binds on coalesced/batched
 * MOVE deltas — normal slides are unchanged, and the final value is
 * still exactly the finger's total travel.
 *
 * Pure JVM logic with no Android dependency, so it is unit-tested
 * directly (see SlideStepMathTest). The truncation matches the call
 * sites' existing behaviour: Float.toInt() truncates toward zero, so a
 * -1.9-step remainder emits -1 step and keeps the -0.9, and the sign of
 * the emitted steps always matches the sign of the travel.
 */
object SlideStepMath {

    /** Max whole steps emitted for one MOVE event (per direction). */
    const val MAX_STEPS_PER_MOVE = 2

    /**
     * Whole steps to emit now for [accumulatedPx] of travel at
     * [pixelsPerUnit], capped at [MAX_STEPS_PER_MOVE] per call (sign
     * preserved). The caller keeps the remainder by subtracting
     * `steps * pixelsPerUnit` from the accumulated travel. Returns 0
     * when the travel is below one unit, or when [pixelsPerUnit] is not
     * a usable unit size.
     */
    fun stepsToEmit(accumulatedPx: Float, pixelsPerUnit: Float): Int {
        if (pixelsPerUnit <= 0f) return 0
        val units = (accumulatedPx / pixelsPerUnit).toInt()
        return units.coerceIn(-MAX_STEPS_PER_MOVE, MAX_STEPS_PER_MOVE)
    }

    /**
     * Whole steps still owed for [accumulatedPx] at [pixelsPerUnit],
     * uncapped: call at gesture end (UP/CANCEL) so the value lands
     * exactly where the finger stopped. Normally 0 — the per-MOVE cap
     * keeps the remainder small and later MOVE events drain it; a larger
     * remainder only survives a final coalesced MOVE delta, and landing
     * it at lift is the correct final value.
     */
    fun drainSteps(accumulatedPx: Float, pixelsPerUnit: Float): Int {
        if (pixelsPerUnit <= 0f) return 0
        return (accumulatedPx / pixelsPerUnit).toInt()
    }

    /**
     * Steps to apply now from a queue of [pending] steps, for the shell
     * drain (MediaVolumeApplier.drainShellSteps). The drain used to take
     * the whole accumulation and land it as one absolute set: a fast
     * slide queues many ticks while one shell call is in flight (or
     * while the shell engine is cold), so a single batch could be a
     * whole slide's worth of steps (~10% in one frame) even though the
     * gesture emits at most [MAX_STEPS_PER_MOVE] per MOVE event. That
     * is the boundary the gesture-side pacing alone cannot fix — the
     * coalescing re-batches the paced ticks. Bounding the batch to the
     * same cap keeps the landing paced too; the caller keeps the
     * leftover (`pending - take`) queued and the drain loops until it
     * is empty, so the total still lands exactly. Sign is preserved,
     * so a direction reversal nets against the queue instead of
     * overshooting.
     */
    fun shellBatchTake(pending: Int): Int =
        pending.coerceIn(-MAX_STEPS_PER_MOVE, MAX_STEPS_PER_MOVE)
}
