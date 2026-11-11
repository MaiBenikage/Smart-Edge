package com.imi.smartedge.sidebar.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests for [PercentStepAccumulator] — the 1% step logic for
 * brightness, shared by the edge-slide path (FloatingPanelService) and the
 * tools long-press-drag path (SidePanelView).
 *
 * The contract: each call adds `deltaPercent` percent points and returns
 * the whole hardware steps/levels that should now be applied, carrying the
 * sub-step remainder forward. A brightness level is 100/255 ≈ 0.39%, so one
 * +1% tick makes 2–3 levels. (The volume-scale cases below still exercise
 * the generic math at a 15-step scale, even though volume itself now applies
 * whole hardware steps directly — see VolumeStepMath.)
 */
class PercentStepAccumulatorTest {

    // ─── Volume: 15 hardware steps ─────────────────────────────────────

    @Test
    fun `zero delta applies nothing`() {
        val acc = PercentStepAccumulator()
        assertEquals(0, acc.add(0, 15))
        assertEquals(0.0, acc.pendingPercent(), 0.001)
    }

    @Test
    fun `seven plus-one-percent ticks make one volume step on a 15-step device`() {
        val acc = PercentStepAccumulator()
        repeat(6) { assertEquals(0, acc.add(1, 15)) }
        assertEquals(1, acc.add(1, 15))
        // 7% applied as one 6.67% step; 0.33% stays pending.
        assertEquals(0.333, acc.pendingPercent(), 0.01)
    }

    @Test
    fun `fourteen plus-one-percent ticks make two volume steps`() {
        val acc = PercentStepAccumulator()
        var steps = 0
        repeat(14) { steps += acc.add(1, 15) }
        assertEquals(2, steps)
        assertEquals(0.667, acc.pendingPercent(), 0.01)
    }

    @Test
    fun `full 100 percent makes exactly 15 volume steps`() {
        val acc = PercentStepAccumulator()
        assertEquals(15, acc.add(100, 15))
        assertEquals(0.0, acc.pendingPercent(), 0.01)
    }

    @Test
    fun `large delta applies many steps at once and keeps the remainder`() {
        val acc = PercentStepAccumulator()
        assertEquals(7, acc.add(50, 15))
        assertEquals(3.333, acc.pendingPercent(), 0.01)
    }

    @Test
    fun `negative ticks accumulate symmetrically`() {
        val acc = PercentStepAccumulator()
        repeat(6) { assertEquals(0, acc.add(-1, 15)) }
        assertEquals(-1, acc.add(-1, 15))
        assertEquals(-0.333, acc.pendingPercent(), 0.01)
    }

    @Test
    fun `reversing direction cancels the pending remainder`() {
        val acc = PercentStepAccumulator()
        assertEquals(0, acc.add(5, 15))
        assertEquals(0, acc.add(-3, 15))
        assertEquals(2.0, acc.pendingPercent(), 0.001)
        // +5% more: 7% total, so one step, 0.33% pending.
        assertEquals(1, acc.add(5, 15))
        assertEquals(0.333, acc.pendingPercent(), 0.01)
    }

    // ─── Brightness: 255 levels ────────────────────────────────────────

    @Test
    fun `one percent of brightness is two to three levels`() {
        val acc = PercentStepAccumulator()
        // 1% = 2.55 levels: the first ticks apply 2, 3, 2, 3 levels.
        assertEquals(2, acc.add(1, 255))
        assertEquals(3, acc.add(1, 255))
        assertEquals(2, acc.add(1, 255))
        assertEquals(3, acc.add(1, 255))
    }

    @Test
    fun `full 100 percent of brightness makes exactly 255 levels`() {
        val acc = PercentStepAccumulator()
        var levels = 0
        repeat(100) { levels += acc.add(1, 255) }
        assertEquals(255, levels)
        assertEquals(0.0, acc.pendingPercent(), 0.01)
    }

    @Test
    fun `negative brightness ticks accumulate symmetrically`() {
        val acc = PercentStepAccumulator()
        assertEquals(-2, acc.add(-1, 255))
        assertEquals(-3, acc.add(-1, 255))
    }

    // ─── Scale edge cases ──────────────────────────────────────────────

    @Test
    fun `one-percent steps are exact when the scale is 100`() {
        val acc = PercentStepAccumulator()
        assertEquals(1, acc.add(1, 100))
        assertEquals(37, acc.add(37, 100))
        assertEquals(0.0, acc.pendingPercent(), 0.001)
    }

    @Test
    fun `single-step scale needs the full 100 percent for one step`() {
        val acc = PercentStepAccumulator()
        assertEquals(0, acc.add(1, 1))
        assertEquals(0, acc.add(98, 1))
        assertEquals(1, acc.add(1, 1))
        assertEquals(0.0, acc.pendingPercent(), 0.01)
    }

    @Test
    fun `invalid scale applies nothing and keeps the remainder`() {
        val acc = PercentStepAccumulator()
        assertEquals(0, acc.add(5, 0))
        assertEquals(0, acc.add(5, -3))
        assertEquals(0.0, acc.pendingPercent(), 0.001)
        // The rejected percent is dropped, not banked: a later valid call
        // starts from zero.
        assertEquals(0, acc.add(1, 15))
    }

    // ─── Refund: steps that never landed ───────────────────────────────

    @Test
    fun `refund returns unapplied steps to the pending remainder`() {
        val acc = PercentStepAccumulator()
        repeat(7) { acc.add(1, 15) } // 1 step applied, 0.33% pending
        assertEquals(0.333, acc.pendingPercent(), 0.01)
        acc.refund(1, 15) // that step never landed
        assertEquals(7.0, acc.pendingPercent(), 0.01)
        // The refunded percent is spendable again.
        assertEquals(1, acc.add(1, 15))
    }

    @Test
    fun `refund of negative steps restores a negative remainder`() {
        val acc = PercentStepAccumulator()
        repeat(7) { acc.add(-1, 15) } // -1 step applied, -0.33% pending
        acc.refund(-1, 15)
        assertEquals(-7.0, acc.pendingPercent(), 0.01)
        assertEquals(-1, acc.add(-1, 15))
    }

    @Test
    fun `refund of zero or with invalid scale is a no-op`() {
        val acc = PercentStepAccumulator()
        acc.add(5, 15)
        acc.refund(0, 15)
        acc.refund(1, 0)
        assertEquals(5.0, acc.pendingPercent(), 0.001)
    }

    @Test
    fun `remainder stays within one step while adjustments keep landing`() {
        val acc = PercentStepAccumulator()
        val oneStep = 100.0 / 15.0
        repeat(100) {
            acc.add(1, 15)
            assertTrue(kotlin.math.abs(acc.pendingPercent()) < oneStep + 0.001)
        }
    }

    @Test
    fun `scale change does not emit a spurious jump`() {
        // A10: the total percent is scale-independent, but the steps
        // already handed out are in the old scale's units. Without the
        // rebase, 50% at scale 15 (7 steps) then +1% at scale 255
        // returned 123 levels in one call. With the rebase the second
        // call returns just the new percent's worth (3 levels).
        val acc = PercentStepAccumulator()
        assertEquals(7, acc.add(50, 15))
        assertEquals(3, acc.add(1, 255))
        // Reaching 100% at the new scale emits the remaining levels:
        // 100% of 255 is 255 levels, 130 already accounted for, so 125.
        assertEquals(125, acc.add(49, 255))
    }

    @Test
    fun `scale change keeps the remainder valid`() {
        // 50% at scale 15, then the scale changes to 255: the pending
        // remainder (0.5% of a step) carries over instead of being lost
        // or double-counted.
        val acc = PercentStepAccumulator()
        acc.add(50, 15) // 7 steps, 0.5% of a 15-step device pending (50% = 7.5 steps)
        // One more percent at the new scale: 51% of 255 = 130 levels total.
        val steps = acc.add(1, 255)
        assertTrue("second call returns a small step count, got $steps", steps in 0..5)
    }
}
