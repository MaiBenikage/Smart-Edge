package com.imi.smartedge.sidebar.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests for [MediaVolumeMath], the decision math behind
 * `MediaVolumeApplier`'s volume fallback chain (exact absolute set →
 * shell absolute set → coarse relative adjust).
 *
 * The applier itself needs an AudioManager, so it is only verifiable
 * on-device — but the target computation and the adjust-loop
 * termination rule are pure, and this suite pins them down.
 */
class MediaVolumeMathTest {

    // ─── target() ────────────────────────────────────────────────

    @Test fun `target moves one step up`() {
        assertEquals(11, MediaVolumeMath.target(10, 1, 160))
    }

    @Test fun `target moves one step down`() {
        assertEquals(9, MediaVolumeMath.target(10, -1, 160))
    }

    @Test fun `target clamps at the top`() {
        assertEquals(160, MediaVolumeMath.target(159, 5, 160))
        assertEquals(160, MediaVolumeMath.target(160, 1, 160))
    }

    @Test fun `target clamps at zero`() {
        assertEquals(0, MediaVolumeMath.target(1, -5, 160))
        assertEquals(0, MediaVolumeMath.target(0, -1, 160))
    }

    @Test fun `target of zero steps is the current volume`() {
        assertEquals(42, MediaVolumeMath.target(42, 0, 160))
    }

    @Test fun `target on a 15-step device`() {
        assertEquals(8, MediaVolumeMath.target(7, 1, 15))
        assertEquals(15, MediaVolumeMath.target(14, 3, 15))
    }

    // ─── hasReached() ────────────────────────────────────────────

    @Test fun `hasReached at the target`() {
        assertTrue(MediaVolumeMath.hasReached(10, 10, 1))
        assertTrue(MediaVolumeMath.hasReached(10, 10, -1))
    }

    @Test fun `hasReached past the target in the requested direction (coarse overshoot)`() {
        // One adjust moved 10 indices (the OnePlus coarse step): up past
        // the target counts as reached, not as an overshoot to correct.
        assertTrue(MediaVolumeMath.hasReached(20, 11, 1))
        assertTrue(MediaVolumeMath.hasReached(0, 9, -1))
    }

    @Test fun `hasReached does not count the opposite direction`() {
        assertFalse(MediaVolumeMath.hasReached(9, 10, 1))
        assertFalse(MediaVolumeMath.hasReached(11, 10, -1))
    }

    // ─── shouldAdjustAgain() ─────────────────────────────────────

    @Test fun `shouldAdjustAgain continues while progress is made and target not reached`() {
        // Up: 5 -> 6, target 10: keep going.
        assertTrue(MediaVolumeMath.shouldAdjustAgain(6, 5, 10, 1))
        // Down: 5 -> 4, target 0: keep going.
        assertTrue(MediaVolumeMath.shouldAdjustAgain(4, 5, 0, -1))
    }

    @Test fun `shouldAdjustAgain stops when the target is reached`() {
        assertFalse(MediaVolumeMath.shouldAdjustAgain(10, 9, 10, 1))
        assertFalse(MediaVolumeMath.shouldAdjustAgain(0, 1, 0, -1))
    }

    @Test fun `shouldAdjustAgain stops when the target is crossed (coarse overshoot)`() {
        assertFalse(MediaVolumeMath.shouldAdjustAgain(20, 10, 11, 1))
        assertFalse(MediaVolumeMath.shouldAdjustAgain(0, 10, 9, -1))
    }

    @Test fun `shouldAdjustAgain stops when an adjust makes no progress`() {
        // At the top boundary: the adjust was a no-op.
        assertFalse(MediaVolumeMath.shouldAdjustAgain(160, 160, 160, 1))
        // Stuck mid-range (adjust silently rejected): same value twice.
        assertFalse(MediaVolumeMath.shouldAdjustAgain(50, 50, 60, 1))
        assertFalse(MediaVolumeMath.shouldAdjustAgain(50, 50, 40, -1))
    }

    @Test fun `shouldAdjustAgain does not adjust when already at the target`() {
        assertFalse(MediaVolumeMath.shouldAdjustAgain(10, 10, 10, 1))
        assertFalse(MediaVolumeMath.shouldAdjustAgain(10, 10, 10, -1))
    }
}
