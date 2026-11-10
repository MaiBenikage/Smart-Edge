package com.imi.smartedge.sidebar.panel

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM unit tests for [VolumeStepMath] — the drag distance that makes one
 * gesture tick exactly one hardware media-volume step on every phone.
 *
 * The contract: the distance per step is the old 1% distance scaled by
 * 100/maxSteps, so the drag distance for the full range never changes —
 * only the quantum changes, from 1% to one hardware step.
 */
class VolumeStepMathTest {

    @Test
    fun `at 100 steps one step is exactly the old 1 percent distance`() {
        assertEquals(25f, VolumeStepMath.dpPerStep(100, 25f), 0.001f)
        assertEquals(14f, VolumeStepMath.dpPerStep(100, 14f), 0.001f)
    }

    @Test
    fun `at 160 steps one step is 0_625 percent of the range`() {
        // 25dp per 1% -> 25 * 100/160 = 15.625dp per step.
        assertEquals(15.625f, VolumeStepMath.dpPerStep(160, 25f), 0.001f)
        // 14dp per 1% -> 14 * 100/160 = 8.75dp per step.
        assertEquals(8.75f, VolumeStepMath.dpPerStep(160, 14f), 0.001f)
    }

    @Test
    fun `at 15 steps one step is 6_67 percent of the range`() {
        // 25dp per 1% -> 25 * 100/15 = 166.667dp per step.
        assertEquals(166.667f, VolumeStepMath.dpPerStep(15, 25f), 0.01f)
        // 14dp per 1% -> 14 * 100/15 = 93.333dp per step.
        assertEquals(93.333f, VolumeStepMath.dpPerStep(15, 14f), 0.01f)
    }

    @Test
    fun `full-range drag distance never changes`() {
        // dpPerStep * maxSteps == dpPerPercent * 100 for any step count.
        for (max in listOf(7, 15, 16, 25, 30, 100, 160)) {
            assertEquals(2500f, VolumeStepMath.dpPerStep(max, 25f) * max, 0.01f)
            assertEquals(1400f, VolumeStepMath.dpPerStep(max, 14f) * max, 0.01f)
        }
    }

    @Test
    fun `invalid step count falls back to the 1 percent distance`() {
        assertEquals(25f, VolumeStepMath.dpPerStep(0, 25f), 0.001f)
        assertEquals(25f, VolumeStepMath.dpPerStep(-3, 25f), 0.001f)
    }
}
