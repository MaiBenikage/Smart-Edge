package com.imi.smartedge.sidebar.panel

/**
 * Pure decision math for [MediaVolumeApplier]'s volume fallback chain.
 *
 * Extracted so the target computation and the coarse-adjust termination
 * rule are unit-testable on the JVM (see MediaVolumeMathTest) without an
 * AudioManager. No Android dependency (the direction is a plain sign).
 *
 * The applier tries, in order: an exact absolute `setStreamVolume`, a
 * privileged shell absolute set, and finally a relative
 * `adjustStreamVolume` loop. The loop is the only part with subtle
 * termination logic: one adjust can move several indices on phones
 * whose adjust step is coarse, so it must stop as soon as the target
 * is reached *or crossed*, and it must stop when an adjust makes no
 * progress (boundary / no-op adjust) instead of spinning.
 */
object MediaVolumeMath {

    /** Clamped absolute target index for a change of [steps] from [current] on a stream with [max] steps. */
    fun target(current: Int, steps: Int, max: Int): Int =
        (current + steps).coerceIn(0, max)

    /** True when [current] has reached [target], or crossed it in the requested direction (a coarse adjust step can overshoot). */
    fun hasReached(current: Int, target: Int, steps: Int): Boolean =
        if (steps > 0) current >= target else current <= target

    /**
     * Whether the coarse-adjust fallback should apply another adjust,
     * given the volume before ([beforeAdjust]) and after ([current])
     * the last adjust, the [target], and the requested [steps].
     *
     * Stops when the target is reached/crossed, or when the last adjust
     * made no progress (`current == beforeAdjust`). Never applies an
     * adjust when the volume is already at (or past) the target.
     */
    fun shouldAdjustAgain(current: Int, beforeAdjust: Int, target: Int, steps: Int): Boolean {
        if (hasReached(current, target, steps)) return false
        if (current == beforeAdjust) return false // no progress; stop
        return true
    }
}
