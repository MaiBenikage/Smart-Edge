package com.imi.smartedge.sidebar.panel

/**
 * Pure math for matching a volume drag gesture to the device's own media
 * volume steps.
 *
 * One gesture tick is exactly one hardware media-volume step on every phone,
 * so the drag distance that produces a tick has to adapt to the phone's step
 * count: a hardware step is 100/maxSteps percent of the range. Given the
 * distance that used to produce a 1% change ([dpPerPercent]), the distance
 * for one step is `dpPerPercent * 100 / maxSteps` — at 100 steps that is
 * exactly the old 1% distance, on a 160-step phone it is 15.625dp (for a
 * 25dp 1% distance), and on a 15-step phone it is 166.667dp. The drag
 * distance for the full range therefore never changes; only the quantum
 * changes, from 1% to one hardware step.
 *
 * Pure JVM logic with no Android dependency, so it is unit-tested directly
 * (see VolumeStepMathTest). Callers read the live step count from
 * `AudioManager.getStreamMaxVolume(STREAM_MUSIC)` and pass it in.
 */
object VolumeStepMath {

    /**
     * Drag distance in dp that produces exactly one hardware media-volume
     * step, for a gesture whose 1% distance was [dpPerPercent]. Falls back
     * to [dpPerPercent] when [maxSteps] is not a usable step count.
     *
     * The result is floored at 1dp: for an absurd [maxSteps] the raw value
     * approaches 0, and a sub-pixel tick distance would make one pixel of
     * movement fire hundreds of ticks (each a binder call) in the adapter,
     * which doesn't clamp like the other callers.
     */
    fun dpPerStep(maxSteps: Int, dpPerPercent: Float): Float {
        if (maxSteps <= 0 || dpPerPercent <= 0f) return dpPerPercent
        return (dpPerPercent * 100f / maxSteps).coerceAtLeast(1f)
    }
}
