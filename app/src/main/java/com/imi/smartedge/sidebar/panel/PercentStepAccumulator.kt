package com.imi.smartedge.sidebar.panel

/**
 * Accumulates percent-point adjustments and emits whole hardware steps.
 *
 * Brightness changes in 1% steps, but 1% of brightness is 2.55 of 255
 * levels, so sub-level percentages have to accumulate until they make a
 * whole level — otherwise a slow 1%-per-tick drag would round to zero every
 * time and nothing would ever change. Volume used to share this class, but
 * one gesture tick is now exactly one hardware media step on every phone,
 * so volume applies whole steps directly with no accumulation (see
 * VolumeStepMath).
 *
 * Pure JVM logic with no Android dependency, so it is unit-tested directly
 * (see PercentStepAccumulatorTest). Both the edge-slide path
 * (FloatingPanelService) and the tools long-press-drag path (SidePanelView)
 * share this class for brightness, so the 1% behaviour is identical in both.
 *
 * The bookkeeping is exact integer arithmetic, deliberately NOT floating
 * point: accumulating 1% a hundred times in Float or Double lands a
 * fraction short of a whole-step boundary (100% of brightness came out as
 * 254 of 255 levels), and truncation then drops a whole step. Here the
 * total percent and the steps already handed out are both integers, and
 * the whole-step count is `(totalPercent * fullScaleSteps) / 100` with
 * truncating integer division — so 100 × 1% is exactly 255 levels and
 * exactly 15 steps on a 15-step device.
 *
 * The remainder is kept in percent points, so it stays valid even if the
 * full-scale step count changes between calls (e.g. the volume max changes
 * when audio is routed to a Bluetooth device).
 */
class PercentStepAccumulator {

    /** Total percent points ever added (can be negative). */
    private var totalPercent = 0

    /** Whole steps already handed out for [totalPercent] (can be negative). */
    private var stepsApplied = 0

    /** Scale of the most recent call, used by [pendingPercent]. */
    private var lastFullScaleSteps = 0

    /**
     * Adds [deltaPercent] percent points (sign = direction) and returns the
     * whole steps/levels that should now be applied, where
     * [fullScaleSteps] is how many whole steps span 0..100% (the volume
     * max, or 255 for brightness).
     *
     * Returns 0 when the accumulated change has not reached one step yet.
     */
    fun add(deltaPercent: Int, fullScaleSteps: Int): Int {
        if (deltaPercent == 0 || fullScaleSteps <= 0) return 0
        lastFullScaleSteps = fullScaleSteps
        totalPercent += deltaPercent
        // Whole steps for the accumulated percent. Int division truncates
        // toward zero, so -1.05 steps becomes -1, matching the call's sign.
        // Long multiplication: a long drag session could otherwise overflow
        // totalPercent * fullScaleSteps in Int.
        val stepsTotal = ((totalPercent.toLong() * fullScaleSteps) / 100).toInt()
        val steps = stepsTotal - stepsApplied
        stepsApplied = stepsTotal
        return steps
    }

    /**
     * Returns [steps] whole steps to the pending pool — for steps that were
     * requested by [add] but never actually landed (e.g. the AudioManager
     * call failed part-way). The caller passes the signed shortfall:
     * `steps - appliedSigned`.
     */
    fun refund(steps: Int, fullScaleSteps: Int) {
        if (steps == 0 || fullScaleSteps <= 0) return
        lastFullScaleSteps = fullScaleSteps
        stepsApplied -= steps
    }

    /** Pending fractional percent (in the most recent scale), for tests. */
    fun pendingPercent(): Double {
        if (lastFullScaleSteps <= 0) return 0.0
        return totalPercent - stepsApplied * (100.0 / lastFullScaleSteps)
    }
}
