package com.imi.smartedge.sidebar.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests for the black-screen brightness save/restore helper.
 *
 * Both [saveAndDimBrightness] and [restoreBrightness] accept lambdas for
 * read/write side effects, making them fully testable without an Android
 * Context or ContentResolver.
 */
class BlackScreenBrightnessTest {

    // ── Fixtures ──────────────────────────────────────────────────

    /** Simulates a mutable cell of system settings for a single test.
     *  Backing fields use underscore prefix to avoid Kotlin property-setter
     *  JVM signature clashes with the helper methods. */
    private class FakeBrightnessState(
        var _autoMode: Boolean = false,
        var _brightness: Int = 125,
        var _floatBrightness: Float? = null
    ) {
        val snapshot: BrightnessSnapshot get() = BrightnessSnapshot(_autoMode, _brightness, _floatBrightness)

        fun readAutoMode(): Boolean = _autoMode
        fun readBrightness(): Int = _brightness
        fun readFloatBrightness(): Float = _floatBrightness ?: (_brightness / 255f)
        fun writeAutoMode(v: Boolean) { _autoMode = v }
        fun writeBrightness(v: Int) { _brightness = v }
        fun writeFloatBrightness(v: Float) { _floatBrightness = v }
    }

    // ════════════════════════════════════════════════════════════════
    //  saveAndDimBrightness tests
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `dim from auto mode`() {
        val state = FakeBrightnessState(_autoMode = true, _brightness = 180)

        val saved = saveAndDimBrightness(
            readAutoMode = state::readAutoMode,
            readBrightness = state::readBrightness,
            setAutoMode = state::writeAutoMode,
            setBrightness = state::writeBrightness
        )

        assertTrue("auto mode was saved as true", saved.autoMode)
        assertEquals("brightness was saved", 180, saved.brightness)
        assertFalse("auto mode is now disabled", state._autoMode)
        assertEquals("brightness is now minimum", 1, state._brightness)
    }

    @Test
    fun `dim from manual mode`() {
        val state = FakeBrightnessState(_autoMode = false, _brightness = 200)

        val saved = saveAndDimBrightness(
            readAutoMode = state::readAutoMode,
            readBrightness = state::readBrightness,
            setAutoMode = state::writeAutoMode,
            setBrightness = state::writeBrightness
        )

        assertFalse("auto mode was saved as false", saved.autoMode)
        assertEquals("brightness was saved", 200, saved.brightness)
        assertFalse("auto mode stays disabled", state._autoMode)
        assertEquals("brightness is now minimum", 1, state._brightness)
    }

    @Test
    fun `dim from brightness 0`() {
        val state = FakeBrightnessState(_autoMode = false, _brightness = 0)

        val saved = saveAndDimBrightness(
            readAutoMode = state::readAutoMode,
            readBrightness = state::readBrightness,
            setAutoMode = state::writeAutoMode,
            setBrightness = state::writeBrightness
        )

        assertEquals("brightness 0 was saved", 0, saved.brightness)
        assertEquals("brightness set to minimum", 1, state._brightness)
    }

    @Test
    fun `dim from max brightness`() {
        val state = FakeBrightnessState(_autoMode = false, _brightness = 255)

        val saved = saveAndDimBrightness(
            readAutoMode = state::readAutoMode,
            readBrightness = state::readBrightness,
            setAutoMode = state::writeAutoMode,
            setBrightness = state::writeBrightness
        )

        assertEquals("max brightness saved", 255, saved.brightness)
        assertEquals("dimmed to 1", 1, state._brightness)
    }

    @Test
    fun `float brightness fallback is called correctly`() {
        val state = FakeBrightnessState(_autoMode = true, _brightness = 150)
        var floatCalled = false

        saveAndDimBrightness(
            readAutoMode = state::readAutoMode,
            readBrightness = state::readBrightness,
            setAutoMode = state::writeAutoMode,
            setBrightness = state::writeBrightness,
            setFloatBrightness = { f ->
                floatCalled = true
                state.writeFloatBrightness(f)
            }
        )

        assertTrue("float callback was invoked", floatCalled)
        assertEquals(0.0f, state._floatBrightness ?: -1f, 0.001f)
    }

    @Test
    fun `float callback is null`() {
        val state = FakeBrightnessState(_autoMode = true, _brightness = 150)

        val saved = saveAndDimBrightness(
            readAutoMode = state::readAutoMode,
            readBrightness = state::readBrightness,
            setAutoMode = state::writeAutoMode,
            setBrightness = state::writeBrightness,
            setFloatBrightness = null
        )

        assertEquals("brightness still saved", 150, saved.brightness)
        assertTrue("auto mode saved", saved.autoMode)
        assertEquals("brightness dimmed", 1, state._brightness)
    }

    // ════════════════════════════════════════════════════════════════
    //  restoreBrightness tests
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `restore from auto mode`() {
        val state = FakeBrightnessState(_autoMode = false, _brightness = 1)
        val saved = BrightnessSnapshot(autoMode = true, brightness = 180)

        restoreBrightness(
            snapshot = saved,
            setBrightness = state::writeBrightness,
            setAutoMode = state::writeAutoMode
        )

        assertEquals("brightness restored", 180, state._brightness)
        assertTrue("auto mode re-enabled", state._autoMode)
    }

    @Test
    fun `restore from manual mode`() {
        val state = FakeBrightnessState(_autoMode = true, _brightness = 1)
        val saved = BrightnessSnapshot(autoMode = false, brightness = 200)

        restoreBrightness(
            snapshot = saved,
            setBrightness = state::writeBrightness,
            setAutoMode = state::writeAutoMode
        )

        assertEquals("brightness restored", 200, state._brightness)
        assertFalse("auto mode stays off", state._autoMode)
    }

    @Test
    fun `restore clamps brightness to 1 when value is 0`() {
        val state = FakeBrightnessState(_autoMode = false, _brightness = 1)
        val saved = BrightnessSnapshot(autoMode = false, brightness = 0)

        restoreBrightness(
            snapshot = saved,
            setBrightness = state::writeBrightness,
            setAutoMode = state::writeAutoMode
        )

        assertEquals("clamped to 1", 1, state._brightness)
    }

    @Test
    fun `restore clamps negative brightness to 1`() {
        val state = FakeBrightnessState(_autoMode = false, _brightness = 1)
        val saved = BrightnessSnapshot(autoMode = false, brightness = -5)

        restoreBrightness(
            snapshot = saved,
            setBrightness = state::writeBrightness,
            setAutoMode = state::writeAutoMode
        )

        assertEquals("negative clamped to 1", 1, state._brightness)
    }

    @Test
    fun `restore accepts 255`() {
        val state = FakeBrightnessState(_autoMode = false, _brightness = 1)
        val saved = BrightnessSnapshot(autoMode = false, brightness = 255)

        restoreBrightness(
            snapshot = saved,
            setBrightness = state::writeBrightness,
            setAutoMode = state::writeAutoMode
        )

        assertEquals("255 is valid", 255, state._brightness)
    }

    // ════════════════════════════════════════════════════════════════
    //  Full round-trip tests
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `full save-then-restore round-trip restores original state exactly`() {
        val state = FakeBrightnessState(_autoMode = true, _brightness = 200)

        val saved = saveAndDimBrightness(
            readAutoMode = state::readAutoMode,
            readBrightness = state::readBrightness,
            setAutoMode = state::writeAutoMode,
            setBrightness = state::writeBrightness
        )

        assertEquals(1, state._brightness)
        assertFalse(state._autoMode)

        restoreBrightness(
            snapshot = saved,
            setBrightness = state::writeBrightness,
            setAutoMode = state::writeAutoMode
        )

        assertEquals(200, state._brightness)
        assertTrue("auto mode restored", state._autoMode)
    }

    @Test
    fun `round-trip from manual mode`() {
        val state = FakeBrightnessState(_autoMode = false, _brightness = 75)

        val saved = saveAndDimBrightness(
            readAutoMode = state::readAutoMode,
            readBrightness = state::readBrightness,
            setAutoMode = state::writeAutoMode,
            setBrightness = state::writeBrightness
        )

        assertEquals(1, state._brightness)
        assertFalse(state._autoMode)

        restoreBrightness(
            snapshot = saved,
            setBrightness = state::writeBrightness,
            setAutoMode = state::writeAutoMode
        )

        assertEquals(75, state._brightness)
        assertFalse("manual mode preserved", state._autoMode)
    }

    @Test
    fun `round-trip with float fallback`() {
        // A2: on Android 12+ the system slider follows the float, not the
        // int — so the round-trip must restore the float value too, not
        // just the int brightness and auto mode. This test used to pass
        // while the float stayed at 0.0 after restore (restoreBrightness
        // took no setFloatBrightness parameter); it now asserts the float.
        val state = FakeBrightnessState(_autoMode = true, _brightness = 150, _floatBrightness = 150 / 255f)
        var floatValue: Float? = null

        val saved = saveAndDimBrightness(
            readAutoMode = state::readAutoMode,
            readBrightness = state::readBrightness,
            setAutoMode = state::writeAutoMode,
            setBrightness = state::writeBrightness,
            setFloatBrightness = { f -> floatValue = f },
            readFloatBrightness = state::readFloatBrightness
        )

        assertEquals("float was captured in the snapshot", 150 / 255f, saved.floatBrightness ?: -1f, 0.001f)
        assertEquals("float was set to 0.0", 0.0f, floatValue ?: -1f, 0.001f)
        assertEquals("brightness dimmed", 1, state._brightness)

        restoreBrightness(
            snapshot = saved,
            setBrightness = state::writeBrightness,
            setAutoMode = state::writeAutoMode,
            setFloatBrightness = state::writeFloatBrightness
        )

        assertEquals("brightness restored", 150, state._brightness)
        assertTrue("auto mode restored", state._autoMode)
        assertEquals("float brightness restored (not left at 0.0)", 150 / 255f, state._floatBrightness ?: -1f, 0.001f)
    }

    @Test
    fun `restore without a float in the snapshot leaves the float alone`() {
        val state = FakeBrightnessState(_autoMode = true, _brightness = 1, _floatBrightness = 0.0f)
        var floatWrites = 0
        val saved = BrightnessSnapshot(autoMode = true, brightness = 180, floatBrightness = null)

        restoreBrightness(
            snapshot = saved,
            setBrightness = state::writeBrightness,
            setAutoMode = state::writeAutoMode,
            setFloatBrightness = { floatWrites++ }
        )

        assertEquals("brightness restored", 180, state._brightness)
        assertEquals("no float write when the snapshot has no float", 0, floatWrites)
        assertEquals("float untouched", 0.0f, state._floatBrightness ?: -1f, 0.001f)
    }

    // ════════════════════════════════════════════════════════════════
    //  Edge cases
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `dim already at minimum`() {
        val state = FakeBrightnessState(_autoMode = false, _brightness = 1)

        val saved = saveAndDimBrightness(
            readAutoMode = state::readAutoMode,
            readBrightness = state::readBrightness,
            setAutoMode = state::writeAutoMode,
            setBrightness = state::writeBrightness
        )

        assertEquals("saved brightness is 1", 1, saved.brightness)
        assertEquals("stays at 1", 1, state._brightness)
        assertFalse(state._autoMode)
    }

    @Test
    fun `restore with same value already set`() {
        val state = FakeBrightnessState(_autoMode = false, _brightness = 1)
        val saved = BrightnessSnapshot(autoMode = false, brightness = 1)

        restoreBrightness(
            snapshot = saved,
            setBrightness = state::writeBrightness,
            setAutoMode = state::writeAutoMode
        )

        assertEquals(1, state._brightness)
        assertFalse(state._autoMode)
    }
}
