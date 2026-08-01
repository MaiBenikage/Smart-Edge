package com.imi.smartedge.sidebar.panel

/**
 * Pure snapshot of the screen brightness state so it can be saved before
 * the black-screen overlay activates and restored on dismiss.
 *
 * No Android dependencies — this is a plain-data class that works on
 * any JVM (unit tests included).
 */
data class BrightnessSnapshot(
    /** true = auto-brightness was on, false = manual mode was on. */
    val autoMode: Boolean,
    /** The SCREEN_BRIGHTNESS int value [0..255] before activation. */
    val brightness: Int
)

/**
 * Save the current brightness state and immediately clamp to minimum
 * brightness + manual (non-auto) mode.
 *
 * This is a **pure** helper: all side-effects (reading/writing system
 * settings) are injected via lambdas, so it can be unit-tested without
 * an Android Context or ContentResolver.
 *
 * @param readAutoMode   returns `true` if auto-brightness is currently enabled.
 * @param readBrightness returns the current brightness int [0..255].
 * @param setAutoMode    writes `true` (auto) or `false` (manual).
 * @param setBrightness  writes the brightness int [0..255].
 * @param setFloatBrightness  optional API 31+ float brightness write.
 * @return a [BrightnessSnapshot] that can be passed to [restoreBrightness].
 */
fun saveAndDimBrightness(
    readAutoMode: () -> Boolean,
    readBrightness: () -> Int,
    setAutoMode: (Boolean) -> Unit,
    setBrightness: (Int) -> Unit,
    setFloatBrightness: ((Float) -> Unit)? = null
): BrightnessSnapshot {
    val saved = BrightnessSnapshot(
        autoMode = readAutoMode(),
        brightness = readBrightness()
    )
    // Disable auto mode
    setAutoMode(false)
    // Minimum brightness (1 not 0 — some OEMs treat 0 as "auto")
    setBrightness(1)
    // API 31+ float fallback
    setFloatBrightness?.invoke(0.0f)
    return saved
}

/**
 * Restore the brightness state captured by [saveAndDimBrightness].
 *
 * @param snapshot    the previously saved state.
 * @param setBrightness  writes the brightness int [0..255].
 * @param setAutoMode    writes `true` (auto) or `false` (manual).
 */
fun restoreBrightness(
    snapshot: BrightnessSnapshot,
    setBrightness: (Int) -> Unit,
    setAutoMode: (Boolean) -> Unit
) {
    // Clamp to valid range
    setBrightness(snapshot.brightness.coerceIn(1, 255))
    setAutoMode(snapshot.autoMode)
}
