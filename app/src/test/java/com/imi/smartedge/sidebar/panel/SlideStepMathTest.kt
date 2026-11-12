package com.imi.smartedge.sidebar.panel

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM unit tests for [SlideStepMath] — the pacing that stops a fast
 * flick (or a coalesced MOVE event) from dumping a whole slide's worth of
 * volume steps in a single frame.
 *
 * The contract: at most [SlideStepMath.MAX_STEPS_PER_MOVE] steps are
 * emitted per MOVE event (gesture end counts as one more MOVE), and the
 * remainder is kept (in pixels) and drains on later MOVE events. A backlog
 * still left at lift beyond the cap is dropped, never landed as a burst.
 */
class SlideStepMathTest {

    @Test
    fun `below one unit emits nothing`() {
        assertEquals(0, SlideStepMath.stepsToEmit(20f, 41f))
        assertEquals(0, SlideStepMath.stepsToEmit(-20f, 41f))
        assertEquals(0, SlideStepMath.stepsToEmit(0f, 41f))
    }

    @Test
    fun `exactly one unit emits one step, sign preserved`() {
        assertEquals(1, SlideStepMath.stepsToEmit(41f, 41f))
        assertEquals(-1, SlideStepMath.stepsToEmit(-41f, 41f))
        // 1.9 units truncates toward zero, like the old call sites.
        assertEquals(1, SlideStepMath.stepsToEmit(78f, 41f))
        assertEquals(-1, SlideStepMath.stepsToEmit(-78f, 41f))
    }

    @Test
    fun `a 16-unit flick is capped at MAX_STEPS_PER_MOVE per call`() {
        // 16 units of travel in one MOVE event (the ~10% jump on a
        // 160-step phone) emits at most the cap, never the whole burst.
        val ppu = 41f
        assertEquals(2, SlideStepMath.stepsToEmit(16 * ppu, ppu))
        assertEquals(-2, SlideStepMath.stepsToEmit(-16 * ppu, ppu))
        assertEquals(2, SlideStepMath.MAX_STEPS_PER_MOVE)
    }

    @Test
    fun `capped remainder drains over later calls and lands the exact total`() {
        // Caller pattern: emit, subtract steps * pixelsPerUnit, repeat.
        // A 16-unit flick lands exactly 16 steps in total, just spread
        // over several MOVE events instead of one frame.
        val ppu = 41f
        var accumulated = 16 * ppu
        var total = 0
        var calls = 0
        while (true) {
            val steps = SlideStepMath.stepsToEmit(accumulated, ppu)
            if (steps == 0) break
            total += steps
            accumulated -= steps * ppu
            calls++
            // Guard against an infinite loop if the cap ever stops draining.
            if (calls > 100) throw AssertionError("remainder did not drain")
        }
        assertEquals(16, total)
        assertEquals(8, calls)
        // The leftover is below one unit, so nothing more is owed.
        assertEquals(0, SlideStepMath.stepsToEmit(accumulated, ppu))
    }

    @Test
    fun `gesture end is capped like a MOVE, so a flick backlog never lands as a burst`() {
        // A flick then an immediate lift: 16 units are still held back.
        // The lift emits at most the cap; the uncapped drain used to land
        // all 16 as one absolute set (the ~10% jump at lift).
        val ppu = 41f
        assertEquals(2, SlideStepMath.stepsToEmit(16 * ppu + 10f, ppu))
        assertEquals(-2, SlideStepMath.stepsToEmit(-16 * ppu - 10f, ppu))
        assertEquals(1, SlideStepMath.stepsToEmit(ppu + 10f, ppu))
    }

    @Test
    fun `sub-unit remainder is kept, not lost`() {
        // 0.9 units emits nothing; another 0.2 units then makes a step.
        val ppu = 41f
        var accumulated = 0.9f * ppu
        assertEquals(0, SlideStepMath.stepsToEmit(accumulated, ppu))
        accumulated += 0.2f * ppu
        assertEquals(1, SlideStepMath.stepsToEmit(accumulated, ppu))
    }

    @Test
    fun `unusable unit size emits nothing`() {
        assertEquals(0, SlideStepMath.stepsToEmit(100f, 0f))
        assertEquals(0, SlideStepMath.stepsToEmit(100f, -5f))
    }

    @Test
    fun `shell batch take is bounded to MAX_SHELL_BATCH_STEPS, sign preserved`() {
        assertEquals(4, SlideStepMath.shellBatchTake(16))
        assertEquals(-4, SlideStepMath.shellBatchTake(-16))
        assertEquals(4, SlideStepMath.MAX_SHELL_BATCH_STEPS)
        assertEquals(2, SlideStepMath.shellBatchTake(2))
        assertEquals(1, SlideStepMath.shellBatchTake(1))
        assertEquals(-1, SlideStepMath.shellBatchTake(-1))
        assertEquals(0, SlideStepMath.shellBatchTake(0))
    }

    @Test
    fun `a 16-step shell accumulation drains in bounded batches and lands the exact total`() {
        // Simulates drainShellSteps: take a bounded batch, keep the
        // leftover queued, loop. A whole slide's worth of ticks queued
        // while a shell call was in flight used to land as one absolute
        // set (~10% in one frame); now it lands as 4-step batches.
        var pending = 16
        var total = 0
        var batches = 0
        while (pending != 0) {
            val take = SlideStepMath.shellBatchTake(pending)
            assertEquals(4, take)
            pending -= take
            total += take
            batches++
            if (batches > 100) throw AssertionError("shell queue did not drain")
        }
        assertEquals(16, total)
        assertEquals(4, batches)
        assertEquals(0, pending)
    }

    @Test
    fun `a negative shell accumulation drains in bounded batches too`() {
        var pending = -5
        var total = 0
        var batches = 0
        while (pending != 0) {
            val take = SlideStepMath.shellBatchTake(pending)
            pending -= take
            total += take
            batches++
            if (batches > 100) throw AssertionError("shell queue did not drain")
        }
        assertEquals(-5, total)
        assertEquals(2, batches)
    }
}
