package com.imi.smartedge.sidebar.panel

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.content.Intent

class NotchHandleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        private const val TAG = "NotchHandleView"
    }

    private val panelPrefs = PanelPreferences(context)
    private val handler = Handler(Looper.getMainLooper())

    /**
     * Set by FloatingPanelService.refreshForegroundState(), mirroring
     * EdgeHandleView.isGameActive. In games, gestures must be
     * deliberate (same rule as the edge handle): the long-press delay
     * doubles (see [longPressTimeoutMs]) so an accidental touch in a
     * game is less likely to fire an action.
     */
    var isGameActive: Boolean = false
    @Volatile private var tapCount = 0
    private val tapTimeoutMs = ViewConfiguration.getDoubleTapTimeout().toLong()

    private val tapRunnable = Runnable {
        val currentCount = tapCount
        tapCount = 0
        when (currentCount) {
            1 -> performAction(panelPrefs.notchTapAction, PanelPreferences.KEY_NOTCH_TAP_ACTION)
            2 -> {
                val action = panelPrefs.notchDoubleTapAction
                if (action != PanelPreferences.ACTION_NONE) {
                    performAction(action, PanelPreferences.KEY_NOTCH_DOUBLE_TAP_ACTION)
                } else {
                    performAction(panelPrefs.notchTapAction, PanelPreferences.KEY_NOTCH_TAP_ACTION)
                }
            }
            3 -> {
                val action = panelPrefs.notchTripleTapAction
                if (action != PanelPreferences.ACTION_NONE) {
                    performAction(action, PanelPreferences.KEY_NOTCH_TRIPLE_TAP_ACTION)
                } else {
                    val doubleAction = panelPrefs.notchDoubleTapAction
                    if (doubleAction != PanelPreferences.ACTION_NONE) {
                        performAction(doubleAction, PanelPreferences.KEY_NOTCH_DOUBLE_TAP_ACTION)
                    } else {
                        performAction(panelPrefs.notchTapAction, PanelPreferences.KEY_NOTCH_TAP_ACTION)
                    }
                }
            }
            // 4+ taps: same fallback chain as 3 taps. The old code fired
            // the triple action unconditionally here, so with no triple
            // action set a 4th tap silently did nothing.
            else -> {
                val action = panelPrefs.notchTripleTapAction
                if (action != PanelPreferences.ACTION_NONE) {
                    performAction(action, PanelPreferences.KEY_NOTCH_TRIPLE_TAP_ACTION)
                } else {
                    val doubleAction = panelPrefs.notchDoubleTapAction
                    if (doubleAction != PanelPreferences.ACTION_NONE) {
                        performAction(doubleAction, PanelPreferences.KEY_NOTCH_DOUBLE_TAP_ACTION)
                    } else {
                        performAction(panelPrefs.notchTapAction, PanelPreferences.KEY_NOTCH_TAP_ACTION)
                    }
                }
            }
        }
    }

    private val longPressRunnable = Runnable {
        // A long-press is confirmed as non-tap: discard any pending tap
        // from an earlier tap, or the tap action would fire alongside
        // the long-press action.
        handler.removeCallbacks(tapRunnable)
        tapCount = 0
        if (panelPrefs.notchLongPressAction != PanelPreferences.ACTION_NONE) {
            performAction(panelPrefs.notchLongPressAction, PanelPreferences.KEY_NOTCH_LONG_PRESS_ACTION)
            vibrateHaptic(40)
        }
    }

    private fun performAction(actionId: Int, slotKey: String) {
        ActionDispatcher.performAction(
            context = context,
            actionId = actionId,
            panelPrefs = panelPrefs,
            slotKey = slotKey,
            onTriggerPanel = {
                val intent = Intent(context, FloatingPanelService::class.java).apply {
                    action = FloatingPanelService.ACTION_OPEN
                }
                context.startService(intent)
            }
        )
    }

    private fun vibrateHaptic(durationMs: Long = 25) {
        if (!panelPrefs.hapticEnabled) return
        // Strongly-typed getSystemService(Class) avoids the deprecated
        // Context.VIBRATOR_SERVICE String key. minSdk = 26 covers the API surface.
        val v = context.getSystemService(Vibrator::class.java) ?: return
        // minSdk = 26, so VibrationEffect.createOneShot(...) is always available —
        // the pre-O Vibrator.vibrate(long) overload (deprecated in API 26) is
        // unreachable under our minSdk.
        v.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    // Long-press delay, doubled in games when deliberate gestures are
    // on (same rule as EdgeHandleView's long-press timeout). The tap check in
    // onTouchEvent uses the same value so the tap/long-press boundary
    // stays consistent when the delay doubles.
    private fun longPressTimeoutMs(): Long {
        val base = ViewConfiguration.getLongPressTimeout().toLong()
        val deliberate = isGameActive && panelPrefs.deliberateGestureInGames
        return if (deliberate) base * 2 else base
    }

    private var downTime = 0L
    private var downX = 0f
    private var downY = 0f
    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downTime = event.eventTime
                downX = event.x
                downY = event.y
                // A tap from an earlier gesture may still be pending
                // (tapRunnable waits out the double-tap timeout). Cancel
                // the pending fire so it cannot go off during this
                // gesture; tapCount is kept so a second tap still counts
                // toward a double-tap, and a confirmed non-tap gesture
                // resets it below.
                if (tapCount > 0) handler.removeCallbacks(tapRunnable)
                handler.postDelayed(longPressRunnable, longPressTimeoutMs())
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                if (event.action == MotionEvent.ACTION_CANCEL && tapCount > 0) {
                    // The second touch was cancelled (system interruption):
                    // no double-tap can follow, so re-arm the pending tap
                    // instead of leaving tapCount stale (the next tap would
                    // wrongly count as a double-tap). tapRunnable was
                    // cancelled in ACTION_DOWN and is not pending here.
                    handler.postDelayed(tapRunnable, tapTimeoutMs)
                }
                if (event.action == MotionEvent.ACTION_UP) {
                    val duration = event.eventTime - downTime
                    // A tap counts only if the finger stayed inside the view
                    // and barely moved: a slide off the notch (or a drag
                    // ending here) must not fire the tap action.
                    val moved = Math.hypot((event.x - downX).toDouble(), (event.y - downY).toDouble())
                    val outOfBounds = event.x < 0 || event.x > width || event.y < 0 || event.y > height
                    if (duration < longPressTimeoutMs() && !outOfBounds && moved <= touchSlop) {
                        // If no double/triple tap actions are set, execute single tap immediately
                        if (panelPrefs.notchDoubleTapAction == PanelPreferences.ACTION_NONE &&
                            panelPrefs.notchTripleTapAction == PanelPreferences.ACTION_NONE) {
                            performAction(panelPrefs.notchTapAction, PanelPreferences.KEY_NOTCH_TAP_ACTION)
                        } else {
                            tapCount++
                            handler.removeCallbacks(tapRunnable)
                            handler.postDelayed(tapRunnable, tapTimeoutMs)
                        }
                        performClick()
                    }
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                // Cancel the long-press if the finger leaves the view's
                // bounds. (The old check compared the distance from centre
                // against the full width/height, which can never be true for
                // local coordinates — a finger could slide arbitrarily far
                // off the notch and the long-press still fired.)
                if (event.x < 0 || event.x > width || event.y < 0 || event.y > height) {
                    handler.removeCallbacks(longPressRunnable)
                    // Sliding off is confirmed as non-tap: discard any
                    // pending tap from an earlier tap.
                    handler.removeCallbacks(tapRunnable)
                    tapCount = 0
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // Mirror EdgeHandleView: a detach mid-touch (service stop, rotation,
        // panel re-add) must not let a pending tap/long-press fire on a dead
        // view. The runnables are only removed in ACTION_UP/CANCEL otherwise.
        handler.removeCallbacksAndMessages(null)
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        // Transparent, clickable hit area — nothing to render.
    }

    init {
        // Transparent but clickable
        setBackgroundColor(Color.TRANSPARENT)
    }
}
