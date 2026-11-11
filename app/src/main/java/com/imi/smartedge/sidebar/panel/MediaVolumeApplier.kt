package com.imi.smartedge.sidebar.panel

import android.media.AudioManager
import android.util.Log

/**
 * Applies a media-volume change of [steps] whole hardware steps (sign =
 * direction) by setting the absolute volume index.
 *
 * Why not the relative `adjustStreamVolume`? On some phones one adjust call
 * moves more than one index: on this OnePlus 13 a single physical volume
 * press — and a single `adjustStreamVolume(ADJUST_RAISE)` call — jumps the
 * volume by ~10 indices (6%), while `setStreamVolume` to an absolute index
 * moves exactly one index (proven by the Volume Probe's live test, which set
 * and read back exact indices). So one tick must be an absolute set, not a
 * relative adjust, or the "one tick = one step" promise is broken by the
 * phone's coarse adjust step.
 *
 * Tries, in order:
 * 1. `setStreamVolume` to the absolute target (exact, one binder call).
 *    Some OEM forks (ColorOS/OxygenOS) reject it for third-party apps
 *    despite MODIFY_AUDIO_SETTINGS — that is the "do not have
 *    setStreamVolume Permission" log — so a SecurityException falls through.
 * 2. The privileged shell absolute set
 *    (`cmd media_session volume --stream 3 --set <target>`), also exact.
 * 3. Relative `adjustStreamVolume` as a last resort (coarse on phones whose
 *    adjust step is more than one index).
 *
 * Returns true if the volume reached the target (or was already there).
 */
object MediaVolumeApplier {

    private const val TAG = "MediaVolumeApplier"

    fun apply(audioManager: AudioManager, steps: Int): Boolean {
        if (steps == 0) return true
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return false
        val before = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val target = (before + steps).coerceIn(0, max)
        if (target == before) return true

        // 1. Exact absolute set.
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            return true
        } catch (e: SecurityException) {
            // OEM ExtImpl may reject setStreamVolume for third-party apps;
            // fall through to the shell absolute set.
        } catch (e: Exception) {
            Log.w(TAG, "setStreamVolume failed", e)
        }

        // 2. Exact absolute set via the privileged shell.
        if (AutomationManager.execute("cmd media_session volume --stream 3 --set $target")) {
            return true
        }

        // 3. Last resort: relative adjust (coarse on some phones).
        val direction = if (steps > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        return try {
            repeat(Math.abs(steps)) {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "adjustStreamVolume failed", e)
            false
        }
    }
}
