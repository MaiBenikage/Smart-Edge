package com.imi.smartedge.sidebar.panel

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.Gravity

class EdgeHandleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var onTrigger: (() -> Unit)? = null
    var onAdjustBrightness: ((delta: Int) -> Unit)? = null
    var onAdjustVolume: ((steps: Int) -> Unit)? = null
    var onSideChanged: ((newSide: String) -> Unit)? = null
    
    var isRightSide: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                updatePill()
            }
        }
    var showPill: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                updatePill()
            }
        }
    var isGameActive: Boolean = false

    private val panelPrefs = PanelPreferences(context)
    private val handler = Handler(Looper.getMainLooper())

    private var startX = 0f
    private var startY = 0f
    private var lastSlideY = 0f
    private var accumulatedDy = 0f
    private var isSlidingSeek = false
    private var isSlidingVolume = false
    private var isTopHalf = false
    private var hasPassedThreshold = false
    private var isTriggered = false

    private var isTempHighAlpha = false
    private var lastPillState: String? = null

    // Desired window touchability when layoutParams does not exist yet
    // (creation time, before the service passes params to addView).
    // setWindowTouchable() records it here, and onAttachedToWindow()
    // applies it once the params exist. Without this, a handle created
    // hidden stayed touchable: the flag-set no-op'd on null params and
    // the lastPillState dedupe defeated the init block's retry.
    private var pendingWindowTouchable: Boolean? = null

    // Audit fix: the IME-insets listener created a fresh ValueAnimator on
    // every insets change without cancelling the previous one, so two
    // animators could fight over params.y. One shared animator, cancelled
    // before each new run, is the only one that may drive the handle.
    private var imeAnimator: android.animation.ValueAnimator? = null

    private val density = resources.displayMetrics.density
    private val triggerThreshold = 16 * density

    // ── Drag-to-reposition state ──────────────────────────────────────────────
    private var isDragMode = false
    private var dragStartRawY = 0f
    private var dragStartWindowY = 0f    // WindowManager params.y at drag start
    private var lastMoveRawY = 0f
    private var lastMoveRawX = 0f

    /** Long-press runnable: performs action */
    private val longPressRunnable = Runnable {
        // A long-press is confirmed as non-tap: discard any pending tap
        // from an earlier tap, or the tap action would fire alongside
        // the long-press action.
        handler.removeCallbacks(tapRunnable)
        tapCount = 0
        if (panelPrefs.longPressAction != PanelPreferences.ACTION_NONE) {
            performAction(panelPrefs.longPressAction, PanelPreferences.KEY_LONG_PRESS_ACTION)
            isTriggered = true
            // If we didn't enter drag mode, vibrate and reset scale
            if (!isDragMode) {
                vibrateHaptic(40)
                animate().scaleX(1f).scaleY(1f).setDuration(SCALE_RESET_DURATION_MS).start()
            }
        }
    }

    private val holdRunnable = Runnable {
        // A hold is confirmed as non-tap: discard any pending tap.
        handler.removeCallbacks(tapRunnable)
        tapCount = 0
        if (!hasPassedThreshold) return@Runnable
        isTriggered = true
        vibrateHaptic()
        onTrigger?.invoke()
        if (showPill) {
            animate().scaleX(1f).scaleY(1f).setDuration(SCALE_RESET_DURATION_MS).start()
        }
    }

    // ── Tap Detection ─────────────────────────────────────────────────────────
    private var tapCount = 0
    private val tapTimeoutMs = ViewConfiguration.getDoubleTapTimeout().toLong()
    private val tapRunnable = Runnable {
        val currentCount = tapCount
        tapCount = 0
        when (currentCount) {
            1 -> performAction(panelPrefs.tapAction, PanelPreferences.KEY_TAP_ACTION)
            2 -> {
                // No double-tap action set: fall back to the single-tap
                // action instead of doing nothing.
                val action = panelPrefs.doubleTapAction
                if (action != PanelPreferences.ACTION_NONE) performAction(action, PanelPreferences.KEY_DOUBLE_TAP_ACTION)
                else performAction(panelPrefs.tapAction, PanelPreferences.KEY_TAP_ACTION)
            }
            else -> if (currentCount >= 3) {
                val action = panelPrefs.tripleTapAction
                if (action != PanelPreferences.ACTION_NONE) {
                    performAction(action, PanelPreferences.KEY_TRIPLE_TAP_ACTION)
                } else {
                    val doubleAction = panelPrefs.doubleTapAction
                    if (doubleAction != PanelPreferences.ACTION_NONE) performAction(doubleAction, PanelPreferences.KEY_DOUBLE_TAP_ACTION)
                    else performAction(panelPrefs.tapAction, PanelPreferences.KEY_TAP_ACTION)
                }
            }
        }
    }

    private val resetAlphaRunnable = Runnable {
        isTempHighAlpha = false
        alpha = panelPrefs.panelOpacity / 100f
        updatePill()
    }

    fun showTemporarily() {
        isTempHighAlpha = true
        alpha = 1.0f
        handler.removeCallbacks(resetAlphaRunnable)
        handler.postDelayed(resetAlphaRunnable, TEMP_ALPHA_RESET_DELAY_MS)
    }

    private fun performAction(actionId: Int, slotKey: String) {
        ActionDispatcher.performAction(
            context = context,
            actionId = actionId,
            panelPrefs = panelPrefs,
            onTriggerPanel = { triggerPanel() },
            onDragHandle = { enterDragMode() },
            slotKey = slotKey
        )
    }

    private fun enterDragMode() {
        isDragMode = true
        // Cancel any in-flight IME-avoidance animation: the insets listener
        // returns early in drag mode, so without this a pre-drag animation
        // would keep fighting the drag.
        imeAnimator?.cancel()
        vibrateHaptic(40)

        val params = layoutParams as? WindowManager.LayoutParams
        if (params != null) {
            dragStartWindowY = params.y.toFloat()
            dragStartRawY = lastMoveRawY
        }

        // Grow the pill slightly to signal drag mode
        animate().scaleX(1.2f).scaleY(1.2f).setDuration(SCALE_RESET_DURATION_MS).start()
    }

    private fun triggerPanel() {
        vibrateHaptic()
        onTrigger?.invoke()
    }

    private fun handleTap() {
        // If no double/triple-tap actions are set, there is nothing to
        // disambiguate: fire the single-tap action at once instead of
        // waiting out the double-tap timeout on every tap (Notch logic).
        if (panelPrefs.doubleTapAction == PanelPreferences.ACTION_NONE &&
            panelPrefs.tripleTapAction == PanelPreferences.ACTION_NONE) {
            performAction(panelPrefs.tapAction, PanelPreferences.KEY_TAP_ACTION)
            tapCount = 0
            return
        }
        tapCount++
        handler.removeCallbacks(tapRunnable)

        // If user reached triple tap, trigger immediately if configured
        if (tapCount >= 3) {
            if (panelPrefs.tripleTapAction != PanelPreferences.ACTION_NONE) {
                performAction(panelPrefs.tripleTapAction, PanelPreferences.KEY_TRIPLE_TAP_ACTION)
                tapCount = 0
                return
            }
        }
        
        handler.postDelayed(tapRunnable, tapTimeoutMs)
    }

    init {
        // Same LTR pin as SidePanelView: the handle swipe direction and
        // the pill corner radii use absolute left/right, so the overlay
        // root must not mirror under an RTL locale.
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        setLayerType(LAYER_TYPE_HARDWARE, null)
        setWillNotDraw(false)
        post { 
            updatePill()
            setupImeListener()
        }
    }

    private fun setupImeListener() {
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            if (isDragMode) return@setOnApplyWindowInsetsListener insets
            
            val imeVisible = insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())
            val imeHeight = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom
            
            val params = layoutParams as? WindowManager.LayoutParams
            if (params != null) {
                val screenHeight = resources.displayMetrics.heightPixels
                if (imeVisible && imeHeight > 0) {
                    val keyboardTop = screenHeight - imeHeight
                    val h = height
                    val handleCenterAbsY = (screenHeight / 2) + params.y
                    val handleBottomAbsY = handleCenterAbsY + (h / 2)

                    if (handleBottomAbsY > keyboardTop) {
                        val overlap = handleBottomAbsY - keyboardTop
                        val margin = (32 * density).toInt()
                        // Clamp like the restore branch below: without this,
                        // a keyboard covering most of the screen pushes the
                        // handle past -maxOffset and off the top of the screen.
                        val safeMargin = (10 * density).toInt()
                        val maxOffset = maxHandleOffsetPx(screenHeight, h, safeMargin)
                        val newY = (params.y - overlap - margin).coerceIn(-maxOffset, maxOffset)

                        imeAnimator?.cancel()
                        imeAnimator = android.animation.ValueAnimator.ofInt(params.y, newY.toInt()).apply {
                            duration = 200
                            addUpdateListener { animation ->
                                params.y = animation.animatedValue as Int
                                updateLayoutSafely(params)
                            }
                            start()
                        }
                    }
                } else {
                    val h = if (showPill) (panelPrefs.handleHeight * density).toInt()
                            else (panelPrefs.handleHeight * 1.5f * density).toInt()
                    val safeMargin = (10 * density).toInt()
                    val maxOffset = maxHandleOffsetPx(screenHeight, h, safeMargin)
                    val targetY = (panelPrefs.handleVerticalOffset * density).toInt().coerceIn(-maxOffset, maxOffset)
                    
                    if (params.y != targetY) {
                        imeAnimator?.cancel()
                        imeAnimator = android.animation.ValueAnimator.ofInt(params.y, targetY).apply {
                            duration = 200
                            addUpdateListener { animation ->
                                params.y = animation.animatedValue as Int
                                updateLayoutSafely(params)
                            }
                            start()
                        }
                    }
                }
            }
            insets
        }
    }

    private fun updateLayoutSafely(params: WindowManager.LayoutParams) {
        if (isAttachedToWindow) {
            try {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                wm.updateViewLayout(this, params)
            } catch (e: Exception) {}
        }
    }

    fun updateState(isRight: Boolean, isPill: Boolean, opacity: Int) {
        isRightSide = isRight
        showPill = isPill
        if (!isTempHighAlpha) {
            alpha = opacity / 100f
        }
        updatePill()
    }

    fun updatePill() {
        val currentPkg = panelPrefs.currentForegroundPackage
        val hidePillInCurrentApp = panelPrefs.autoHideInFullscreen && panelPrefs.isWhitelistedFromAutoHide(currentPkg)
        
        // Build a unique key for the current visual state to prevent redundant updates
        val stateKey = "${isRightSide}_${showPill}_${hidePillInCurrentApp}_${panelPrefs.pillColor}_${panelPrefs.handleWidth}_${panelPrefs.pillWidth}_${panelPrefs.panelOpacity}"
        if (stateKey == lastPillState) return
        // Do NOT commit lastPillState yet: the window-touchable flag is
        // part of this state, and at creation time layoutParams does not
        // exist yet, so setWindowTouchable() defers it (see below).
        // Committing first would make the init block's posted
        // updatePill() retry early-return on the unchanged key and leave
        // the flag unapplied — a hidden handle staying touchable.
        // The key is committed only once the flag has landed.
        val flagApplied: Boolean
        if (showPill && !hidePillInCurrentApp) {
            val cornerRadius = 12 * density
            val shape = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                if (isRightSide) {
                    cornerRadii = floatArrayOf(cornerRadius, cornerRadius, 0f, 0f, 0f, 0f, cornerRadius, cornerRadius)
                } else {
                    cornerRadii = floatArrayOf(0f, 0f, cornerRadius, cornerRadius, cornerRadius, cornerRadius, 0f, 0f)
                }
                
                try {
                    val color = Color.parseColor(panelPrefs.pillColor)
                    setColor(color)
                } catch (e: Exception) {
                    setColor(Color.WHITE)
                }
                // No stroke: the pill is a single unified color with no outline ring.
            }

            val triggerWidthDp = panelPrefs.handleWidth
            val pillWidthDp = panelPrefs.pillWidth
            val insetDp = (triggerWidthDp - pillWidthDp).coerceAtLeast(0)
            val insetPx = (insetDp * density).toInt()

            val newInset = if (isRightSide) {
                android.graphics.drawable.InsetDrawable(shape, insetPx, 0, 0, 0)
            } else {
                android.graphics.drawable.InsetDrawable(shape, 0, 0, insetPx, 0)
            }
            background = newInset
            
            if (!isTempHighAlpha) {
                alpha = panelPrefs.panelOpacity / 100f
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                post { systemGestureExclusionRects = listOf(Rect(0, 0, width, height)) }
            }
            // Re-enable touch if the pill was hidden (and untouchable) in
            // the previous app.
            flagApplied = setWindowTouchable(true)
        } else {
            background = null
            alpha = 0f
            // Hidden in this app: the pill must not block anything. Clear
            // the gesture exclusion rects (they would swallow the system
            // Back gesture on this strip) and make the window untouchable
            // so touches reach the app underneath instead of dying on an
            // invisible overlay.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                systemGestureExclusionRects = emptyList()
            }
            flagApplied = setWindowTouchable(false)
        }
        // Commit the state key only once the whole side effect — including
        // the window-touchable flag — has landed. If the flag was deferred
        // (creation time, no layoutParams yet), the next updatePill() call
        // re-runs the branch and lands it (the init block's posted retry,
        // or onAttachedToWindow() below).
        if (flagApplied) lastPillState = stateKey
        invalidate()
    }

    /**
     * Toggle FLAG_NOT_TOUCHABLE on this view's window params. The params
     * object is mutated first so the flag sticks even if the view is not
     * attached yet; [updateLayoutSafely] applies it live when attached.
     *
     * Returns true when the flag was applied to live params, false when
     * it was deferred: at creation time layoutParams is still null (the
     * service creates the params and passes them to addView), so the
     * desired state is recorded in [pendingWindowTouchable] and applied
     * in [onAttachedToWindow] once the params exist.
     */
    private fun setWindowTouchable(touchable: Boolean): Boolean {
        val params = layoutParams as? WindowManager.LayoutParams
        if (params == null) {
            pendingWindowTouchable = touchable
            return false
        }
        pendingWindowTouchable = null
        val newFlags = if (touchable) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        if (newFlags != params.flags) {
            params.flags = newFlags
            updateLayoutSafely(params)
        }
        return true
    }

    private var downTime = 0L

    // Per-gesture snapshot of the prefs / device values the MOVE path
    // needs, read once on ACTION_DOWN. Re-reading them on every MOVE
    // re-splits and decodes the whole fullscreen-hide-list string
    // (isWhitelistedFromAutoHide) and re-issues a binder call
    // (getStreamMaxVolume via mediaMaxSteps) per event. A gesture lasts
    // well under a second; these values cannot meaningfully change
    // mid-gesture.
    private var gestureHidePill = false
    private var gestureGesturesEnabled = false
    private var gestureSlideVolume = false
    private var gestureSlideBrightness = false
    private var gesturePixelsPerVolumeUnit = 1f
    private var gesturePixelsPerBrightnessUnit = 1f
    private var gestureSwipeThreshold = 0f
    private var gestureHoldTime = HOLD_DURATION_MS

    // Long-press delay, doubled in games when deliberate gestures are on
    // (same rule as NotchHandleView). The tap check in onTouchEvent uses
    // the same value so the tap/long-press boundary stays consistent when
    // the delay doubles.
    private fun longPressTimeoutMs(): Long {
        val base = ViewConfiguration.getLongPressTimeout().toLong()
        val deliberate = isGameActive && panelPrefs.deliberateGestureInGames
        return if (deliberate) base * 2 else base
    }

    private fun snapshotGesturePrefs() {
        gestureHidePill = panelPrefs.autoHideInFullscreen &&
            panelPrefs.isWhitelistedFromAutoHide(panelPrefs.currentForegroundPackage)
        gestureGesturesEnabled = panelPrefs.gesturesEnabled
        gestureSlideVolume = panelPrefs.slideVolumeEnabled
        gestureSlideBrightness = panelPrefs.slideBrightnessEnabled
        val slideMultiplier = 100f / panelPrefs.slideSensitivity.coerceIn(1, 200)
        // One unit = one hardware step for volume, 1 percent point for
        // brightness. Volume steps match the phone's own media step count
        // (see VolumeStepMath): the drag distance per step adapts so one
        // unit is always exactly one hardware step, while brightness still
        // accumulates its fractional part in FloatingPanelService.
        gesturePixelsPerVolumeUnit =
            (VolumeStepMath.dpPerStep(mediaMaxSteps(), 25f) * density * slideMultiplier).coerceAtLeast(1f)
        gesturePixelsPerBrightnessUnit = (3f * density * slideMultiplier).coerceAtLeast(1f)
        // Sensitivity scaling: 100% = base (16dp), 200% = 8dp, 50% = 32dp.
        val swipeSensitivity = panelPrefs.swipeSensitivity.coerceIn(10, 300)
        val scaledThreshold = triggerThreshold * (100f / swipeSensitivity)
        val deliberate = isGameActive && panelPrefs.deliberateGestureInGames
        gestureSwipeThreshold = if (deliberate) scaledThreshold * 2.5f else scaledThreshold
        gestureHoldTime = if (deliberate) HOLD_DURATION_MS * 2 else HOLD_DURATION_MS
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (onTrigger == null) return false

        if (event.action == MotionEvent.ACTION_DOWN) {
            snapshotGesturePrefs()
        }
        if (gestureHidePill && !isDragMode) return false

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.rawX
                startY = event.rawY
                lastMoveRawX = event.rawX
                lastMoveRawY = event.rawY
                lastSlideY = event.rawY
                accumulatedDy = 0f
                isSlidingSeek = false
                isSlidingVolume = false
                isTopHalf = event.y < height / 2
                dragStartRawY = event.rawY
                downTime = event.eventTime
                hasPassedThreshold = false
                isTriggered = false
                isDragMode = false

                // A tap from an earlier gesture may still be pending
                // (tapRunnable waits out the double-tap timeout). Cancel
                // the pending fire so it cannot go off during this
                // gesture; tapCount is kept so a second tap still counts
                // toward a double-tap, and a confirmed non-tap gesture
                // resets it below.
                if (tapCount > 0) handler.removeCallbacks(tapRunnable)

                // Schedule long-press → perform action
                handler.postDelayed(longPressRunnable, longPressTimeoutMs())

                if (showPill && gestureGesturesEnabled) {
                    animate().scaleX(0.85f).scaleY(0.95f).setDuration(SCALE_PRESS_SMALL_DURATION_MS).start()
                }

                // Record current window Y for drag baseline (fallback)
                val params = layoutParams as? WindowManager.LayoutParams
                dragStartWindowY = params?.y?.toFloat() ?: 0f

                return true
            }

            MotionEvent.ACTION_MOVE -> {
                lastMoveRawX = event.rawX
                lastMoveRawY = event.rawY
                val totalDy = event.rawY - dragStartRawY
                val currentY = event.rawY
                val dySinceLast = currentY - lastSlideY
                lastSlideY = currentY

                // ── Drag-reposition mode ──────────────────────────────────────
                if (isDragMode) {
                    val params = layoutParams as? WindowManager.LayoutParams
                    if (params != null) {
                        val screenH = resources.displayMetrics.heightPixels
                        val safeMargin = (10 * density).toInt()
                        
                        // Relative movement: NewPos = StartPos + (CurrentFinger - StartFinger)
                        val dy = event.rawY - dragStartRawY
                        val maxOffset = maxHandleOffsetPx(screenH, height, safeMargin)
                        
                        val newY = (dragStartWindowY + dy).toInt()
                            .coerceIn(-maxOffset, maxOffset)
                        
                        if (params.y != newY) {
                            params.y = newY
                            updateLayoutSafely(params)
                        }
                    }

                    // Flip side based on absolute screen position
                    val screenW = resources.displayMetrics.widthPixels
                    val leftThreshold = screenW * 0.35f
                    val rightThreshold = screenW * 0.65f
                    
                    // The 35%/65% thresholds are the hysteresis: after a
                    // flip the opposite threshold applies, so the side
                    // cannot immediately flip back.
                    if (isRightSide && event.rawX < leftThreshold) {
                        flipSide(PanelPreferences.SIDE_LEFT)
                    } else if (!isRightSide && event.rawX > rightThreshold) {
                        flipSide(PanelPreferences.SIDE_RIGHT)
                    }
                    return true
                }

                // ── Slide Seek Gesture (Volume/Brightness) ────────────────────
                val slideEnabled = gestureSlideVolume || gestureSlideBrightness
                if (slideEnabled && !hasPassedThreshold && !isTriggered) {
                    val absDx = Math.abs(event.rawX - startX)
                    val absDyFromStart = Math.abs(currentY - startY)

                    if (!isSlidingSeek && absDyFromStart > triggerThreshold / 2 && absDyFromStart > absDx * 2) {
                        // Volume/Brightness takes priority OVER the open-panel gesture
                        val volumeOn = gestureSlideVolume
                        val brightnessOn = gestureSlideBrightness
                        
                        isSlidingVolume = when {
                            volumeOn && brightnessOn -> isTopHalf 
                            volumeOn -> true
                            else -> false
                        }
                        
                        isSlidingSeek = true
                        accumulatedDy = 0f 
                        handler.removeCallbacks(longPressRunnable)
                        handler.removeCallbacks(holdRunnable)
                        // A slide is confirmed as non-tap: discard any
                        // pending tap from an earlier tap.
                        handler.removeCallbacks(tapRunnable)
                        tapCount = 0
                        vibrateHaptic(10)
                    }

                    if (isSlidingSeek) {
                        // Audit M-D: a direction flip discards the stale
                        // backlog (see SlideStepMath.accumulateDelta), so
                        // a flick one way cannot keep emitting steps in
                        // the old direction while the finger moves back.
                        accumulatedDy = SlideStepMath.accumulateDelta(accumulatedDy, dySinceLast)
                        // Distances per unit come from the ACTION_DOWN
                        // snapshot (see snapshotGesturePrefs), not from
                        // fresh pref reads / binder calls per MOVE.
                        // Pacing (see SlideStepMath): at most MAX_STEPS_PER_MOVE
                        // per event, so a fast flick or a coalesced MOVE cannot
                        // dump a whole slide's worth of steps in one frame. The
                        // remainder stays in accumulatedDy and drains on later
                        // MOVEs and at gesture end (see ACTION_UP/CANCEL).
                        if (isSlidingVolume) {
                            val pixelsPerUnit = gesturePixelsPerVolumeUnit
                            val steps = SlideStepMath.stepsToEmit(accumulatedDy, pixelsPerUnit)
                            if (steps != 0) {
                                onAdjustVolume?.invoke(-steps)
                                accumulatedDy -= steps * pixelsPerUnit
                            }
                        } else {
                            val pixelsPerUnit = gesturePixelsPerBrightnessUnit
                            val steps = SlideStepMath.stepsToEmit(accumulatedDy, pixelsPerUnit)
                            if (steps != 0) {
                                onAdjustBrightness?.invoke(-steps)
                                accumulatedDy -= steps * pixelsPerUnit
                            }
                        }
                        return true
                    }
                }

                // ── Normal panel-open gesture ─────────────────────────────────
                if (!gestureGesturesEnabled || isTriggered) return true
                val dx = if (isRightSide) (startX - event.rawX) else (event.rawX - startX)

                val effectiveThreshold = gestureSwipeThreshold

                // Tolerance for horizontal movement before cancelling long-press
                // We double it to allow for more natural finger roll/wobble when trying to move handle
                if (dx > triggerThreshold * 2.5f) {
                    handler.removeCallbacks(longPressRunnable)
                }
                
                // Tolerance for vertical movement before cancelling long-press
                // Very generous vertical tolerance (4x) so users can start moving without cancellation
                if (!hasPassedThreshold && Math.abs(totalDy) > triggerThreshold * 4f && Math.abs(totalDy) > Math.abs(event.rawX - startX) * 2f) {
                    handler.removeCallbacks(longPressRunnable)
                }

                if (!hasPassedThreshold && dx > effectiveThreshold) {
                    hasPassedThreshold = true
                    handler.removeCallbacks(longPressRunnable)
                    // A swipe is confirmed as non-tap: discard any
                    // pending tap from an earlier tap.
                    handler.removeCallbacks(tapRunnable)
                    tapCount = 0

                    // Hold time comes from the ACTION_DOWN snapshot (games
                    // double it when deliberate gestures are on).
                    handler.postDelayed(holdRunnable, gestureHoldTime)
                    if (showPill) {
                        animate().scaleX(0.7f).scaleY(0.9f).setDuration(gestureHoldTime).start()
                    }
                }

                if (hasPassedThreshold && dx < 4 * density) {
                    hasPassedThreshold = false
                    handler.removeCallbacks(holdRunnable)
                    if (showPill) {
                        animate().scaleX(0.85f).scaleY(0.95f).setDuration(80).start()
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(holdRunnable)
                handler.removeCallbacks(longPressRunnable)

                if (isDragMode) {
                    saveFinalPosition()
                    isDragMode = false
                    animate().scaleX(1f).scaleY(1f).setDuration(SCALE_RESET_DURATION_MS).start()
                    return true
                }

                if (showPill && !isTriggered && gestureGesturesEnabled) {
                    animate().scaleX(1f).scaleY(1f).setDuration(SCALE_RESET_DURATION_MS).start()
                }

                if (hasPassedThreshold && !isTriggered) {
                    triggerPanel()
                    isTriggered = true
                }

                // Audit M-C (regression from a361e4f): a tap from an
                // earlier gesture may still be pending (tapCount > 0,
                // tapRunnable cancelled in ACTION_DOWN). If this touch
                // does not count as a tap, the first tap must still fire
                // — re-arm it below, the same re-post the ACTION_CANCEL
                // path does. A wiggly second tap used to lift without
                // passing a slide/swipe threshold, count nothing and
                // re-post nothing, so tapCount stayed 1 with no runnable
                // pending and the next tap — even minutes later — ran
                // the double-tap action.
                var countedAsTap = false
                if (!hasPassedThreshold && !isTriggered && !isSlidingSeek && event.action == MotionEvent.ACTION_UP) {
                    val duration = event.eventTime - downTime
                    if (duration < longPressTimeoutMs()) {
                        handleTap()
                        countedAsTap = true
                    }
                }

                if (event.action == MotionEvent.ACTION_UP && !countedAsTap && tapCount > 0) {
                    // This touch was not a tap (held past the long-press
                    // timeout, or the long-press was cancelled by
                    // movement without a slide/swipe confirming a
                    // non-tap): no double-tap can follow it, so re-arm
                    // the pending tap instead of leaving tapCount stale.
                    // tapRunnable is not pending here — it was cancelled
                    // in ACTION_DOWN and only handleTap() re-posts it.
                    handler.postDelayed(tapRunnable, tapTimeoutMs)
                }

                if (event.action == MotionEvent.ACTION_CANCEL && tapCount > 0) {
                    // The second touch was cancelled (system interruption):
                    // no double-tap can follow, so re-arm the pending tap
                    // instead of leaving tapCount stale (the next tap would
                    // wrongly count as a double-tap). tapRunnable was
                    // cancelled in ACTION_DOWN and is not pending here.
                    handler.postDelayed(tapRunnable, tapTimeoutMs)
                }

                // Lift counts as one last MOVE: capped like the others, and
                // any larger backlog is dropped (see SlideStepMath).
                if (isSlidingSeek) {
                    val pixelsPerUnit = if (isSlidingVolume) gesturePixelsPerVolumeUnit else gesturePixelsPerBrightnessUnit
                    val (left, remainder) = SlideStepMath.liftEmit(accumulatedDy, pixelsPerUnit)
                    if (left != 0) {
                        if (isSlidingVolume) onAdjustVolume?.invoke(-left) else onAdjustBrightness?.invoke(-left)
                    }
                    accumulatedDy = remainder
                }

                isSlidingSeek = false
                isSlidingVolume = false
                hasPassedThreshold = false
                return true
            }
        }
        return true
    }

    private fun flipSide(newSide: String) {
        if ((newSide == PanelPreferences.SIDE_RIGHT) == isRightSide) return

        vibrateHaptic(30)
        isRightSide = newSide == PanelPreferences.SIDE_RIGHT

        val params = layoutParams as? WindowManager.LayoutParams ?: return
        params.gravity = if (isRightSide) Gravity.END or Gravity.CENTER_VERTICAL
                        else Gravity.START or Gravity.CENTER_VERTICAL

        updateLayoutSafely(params)
        
        // If we are dragging, re-snapshot coordinates because the gravity change
        // might have shifted the relative center of the Y axis on some devices.
        if (isDragMode) {
            dragStartWindowY = params.y.toFloat()
            dragStartRawY = lastMoveRawY
        }

        updatePill()
    }

    private fun saveFinalPosition() {
        val params = layoutParams as? WindowManager.LayoutParams ?: return
        val offsetDp = (params.y / density).toInt()
        panelPrefs.handleVerticalOffset = offsetDp

        val newSide = if (isRightSide) PanelPreferences.SIDE_RIGHT else PanelPreferences.SIDE_LEFT
        if (panelPrefs.panelSide != newSide) {
            panelPrefs.panelSide = newSide
            onSideChanged?.invoke(newSide)
        }
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

    /** Live media-volume step count for this phone (e.g. 160, 15, 7). */
    private fun mediaMaxSteps(): Int {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        return audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Only claim the gesture exclusion while the pill is actually
            // visible (same condition as updatePill()'s visible branch):
            // updatePill() clears the rects when hidden, and an
            // unconditional re-add here would swallow the system Back
            // gesture on the invisible strip until the next updatePill().
            val currentPkg = panelPrefs.currentForegroundPackage
            val hidePillInCurrentApp = panelPrefs.autoHideInFullscreen &&
                panelPrefs.isWhitelistedFromAutoHide(currentPkg)
            systemGestureExclusionRects = if (showPill && !hidePillInCurrentApp) {
                listOf(Rect(0, 0, width, height))
            } else {
                emptyList()
            }
        }
    }

    /**
     * Round-12 audit L-Medium: EdgeHandleView is a WindowManager overlay view
     * that can be detached (service stop, panel close mid-gesture, screen
     * rotation, panel re-added at a new position). The 4 Handler-owned
     * runnables (longPress, hold, tap, resetAlpha) are scheduled in
     * onTouchEvent but only some are explicitly removed in ACTION_UP/CANCEL.
     * If the view is detached mid-touch, the Looper keeps the still-bound
     * lambdas (capturing `this`) alive past the view's natural death, so
     * a delayed fire can still call `onTrigger`/`vibrateHaptic`/`updatePill`
     * on a dead view. Cancel everything here.
     */
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacksAndMessages(null)
        // Audit M-C: a detach mid-touch removes the pending tapRunnable
        // above, so reset tapCount too — otherwise a stale 1 survives
        // the detach and the next tap after re-attach counts as a
        // double-tap.
        tapCount = 0
        // A running IME-avoidance animator keeps the view and its params
        // alive and keeps calling updateLayoutSafely after detach.
        imeAnimator?.cancel()
        imeAnimator = null
    }

    /**
     * Apply a touchability change deferred at creation time (see
     * [setWindowTouchable]): the params exist now, so a handle created
     * hidden is made untouchable here even if updatePill() ran before
     * the params were created. Idempotent — if the flag already landed
     * via the updatePill() retry, this is a no-op.
     */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        pendingWindowTouchable?.let { setWindowTouchable(it) }
    }

    fun updateFromPrefs() {
        // Use the existing panelPrefs field, not a second PanelPreferences:
        // its properties are live SharedPreferences reads, so it already
        // sees fresh values, and a second instance invites the two to drift.
        isRightSide = panelPrefs.panelSide == PanelPreferences.SIDE_RIGHT
        showPill = panelPrefs.showPill
        
        if (!isTempHighAlpha) {
            alpha = panelPrefs.panelOpacity / 100f
        }

        val params = layoutParams as? WindowManager.LayoutParams
        if (params != null) {
            val screenH = resources.displayMetrics.heightPixels
            val safeMargin = (10 * density).toInt()

            val h = cappedHandleHeightPx(
                if (showPill) (panelPrefs.handleHeight * density).toInt()
                else (panelPrefs.handleHeight * 1.5f * density).toInt(),
                screenH,
                safeMargin
            )

            val maxOffset = maxHandleOffsetPx(screenH, h, safeMargin)
            val requestedOffset = (panelPrefs.handleVerticalOffset * density).toInt()

            params.y = requestedOffset.coerceIn(-maxOffset, maxOffset)
            params.width = (panelPrefs.handleWidth * density).toInt()
            params.height = h

            updateLayoutSafely(params)
        }
        updatePill()
    }

    private companion object {
        const val HOLD_DURATION_MS = 250L
        const val TEMP_ALPHA_RESET_DELAY_MS = 3000L
        const val SCALE_PRESS_SMALL_DURATION_MS = 100L
    }
}
