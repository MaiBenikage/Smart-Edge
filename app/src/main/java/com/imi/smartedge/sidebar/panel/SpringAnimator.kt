package com.imi.smartedge.sidebar.panel

import android.view.Choreographer
import android.view.View
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.interpolator.view.animation.FastOutSlowInInterpolator

/**
 * Utility to create spring-physics animations for the side panel.
 *
 * Improvements:
 *  1. FastOutSlowInInterpolator on alpha fades — matches Material Design motion spec.
 *  2. Choreographer-synced start — first frame always aligns to VSYNC, eliminating the
 *     "first-frame flicker" seen on high refresh rate displays (90Hz / 120Hz).
 */
object SpringAnimator {

    private val fadeInterpolator = FastOutSlowInInterpolator()
    private const val SPRING_DAMPING = SpringForce.DAMPING_RATIO_NO_BOUNCY

    // Track active springs to cancel them if a new animation starts on the same view.
    // A view can have more than one (popup mode runs a scale-X and a scale-Y
    // spring together), so this maps a view to its list of live springs.
    private val activeSprings = java.util.concurrent.ConcurrentHashMap<View, MutableList<SpringAnimation>>()

    // The pending open Runnable per view, if animateOpen posted one that has
    // not run yet. cancelExisting removes it, so a close issued within one
    // frame of an open cannot be undone by the open's posted alpha/spring
    // starting afterwards. Weak keys so a dead view does not leak here.
    private val pendingOpenRunnables = java.util.WeakHashMap<View, Runnable>()

    private fun trackSpring(view: View, spring: SpringAnimation) {
        activeSprings.getOrPut(view) { mutableListOf() }.add(spring)
    }

    private fun untrackSpring(view: View, spring: SpringAnimation) {
        val list = activeSprings[view] ?: return
        list.remove(spring)
        if (list.isEmpty()) activeSprings.remove(view)
    }

    fun animateOpen(view: View, panelWidth: Float, stiffness: Float = 400f, isPicker: Boolean = false, slide: Boolean = true, onEnd: (() -> Unit)? = null) {
        cancelExisting(view)
        
        if (stiffness <= 0f) {
            view.translationX = 0f
            view.scaleX = 1f
            view.scaleY = 1f
            view.alpha = 1f
            onEnd?.invoke()
            return
        }
        
        if (slide) {
            val startX = if (panelWidth == 0f) (if (isPicker) -500f else 500f) else panelWidth
            view.translationX = startX
            view.scaleX = 1f
            view.scaleY = 1f
        } else {
            view.translationX = 0f
            view.scaleX = 0.85f
            view.scaleY = 0.85f
        }
        
        view.alpha = 0f
        view.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        // Scale alpha duration inversely with stiffness (Balanced at 400f -> 200ms)
        val alphaDuration = (220 * (400f / stiffness)).toLong().coerceIn(50, 600)

        // Keep the posted Runnable so cancelExisting can remove it: a
        // close issued within one frame of an open must not be undone by
        // the open's posted alpha/spring starting afterwards.
        val openRunnable = Runnable {
            pendingOpenRunnables.remove(view)
            view.animate()
                .alpha(1f)
                .setDuration(alphaDuration)
                .setInterpolator(fadeInterpolator)
                .start()

            if (slide) {
                val spring = SpringAnimation(view, DynamicAnimation.TRANSLATION_X, 0f)
                spring.spring.stiffness = stiffness
                spring.spring.dampingRatio = SPRING_DAMPING
                spring.addEndListener { _, canceled, _, _ ->
                    // A canceled animation (e.g. close interrupted by a new
                    // open) must NOT run onEnd — its teardown belongs to the
                    // old animation and would tear down the new state.
                    if (canceled) return@addEndListener
                    untrackSpring(view, spring)
                    view.setLayerType(View.LAYER_TYPE_NONE, null)
                    onEnd?.invoke()
                }
                trackSpring(view, spring)
                spring.start()
            } else {
                // Smooth scale spring for "Popup" mode. Both springs are
                // tracked, so a rapid open→close cancels them instead of
                // leaving them running against the new animation.
                val scaleXSpring = SpringAnimation(view, DynamicAnimation.SCALE_X, 1f)
                scaleXSpring.spring.stiffness = stiffness * 1.2f // Slightly snappier scale
                scaleXSpring.spring.dampingRatio = SpringForce.DAMPING_RATIO_LOW_BOUNCY
                scaleXSpring.addEndListener { _, canceled, _, _ ->
                    if (!canceled) untrackSpring(view, scaleXSpring)
                }
                val scaleYSpring = SpringAnimation(view, DynamicAnimation.SCALE_Y, 1f)
                scaleYSpring.spring.stiffness = stiffness * 1.2f
                scaleYSpring.spring.dampingRatio = SpringForce.DAMPING_RATIO_LOW_BOUNCY
                scaleYSpring.addEndListener { _, canceled, _, _ ->
                    if (canceled) return@addEndListener
                    untrackSpring(view, scaleYSpring)
                    view.setLayerType(View.LAYER_TYPE_NONE, null)
                    onEnd?.invoke()
                }
                trackSpring(view, scaleXSpring)
                trackSpring(view, scaleYSpring)
                scaleXSpring.start()
                scaleYSpring.start()
            }
        }
        pendingOpenRunnables[view] = openRunnable
        view.post(openRunnable)
    }

    fun animateClose(view: View, panelWidth: Float, stiffness: Float = 350f, isPicker: Boolean = false, slide: Boolean = true, onEnd: (() -> Unit)? = null) {
        cancelExisting(view)
        
        // The close must reverse the open. The panel close call passes the
        // same signed width as its open (right: +w, left: -w), so use it
        // as-is. The picker close call passes the NEGATED open startX
        // (right: +w, left: -w, while open starts at right: -w, left: +w),
        // so negate it back to close toward the side it opened from. The
        // old picker branch forced both signs negative, so a left-side
        // picker opened from +w and closed to -w — sliding across the
        // screen instead of back out the way it came in.
        val targetX = when {
            panelWidth != 0f -> if (isPicker) -panelWidth else panelWidth
            isPicker -> -500f
            else -> 500f
        }

        if (stiffness <= 0f) {
            // Animations disabled: jump straight to the closed state.
            // (The old code set translationX = targetX and then immediately
            // overwrote it with 0f — the first assignment was dead.)
            view.translationX = if (slide) targetX else 0f
            view.scaleX = 1f
            view.scaleY = 1f
            view.alpha = 0f
            onEnd?.invoke()
            return
        }

        view.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        // Scale alpha duration inversely with stiffness
        val alphaDuration = (180 * (350f / stiffness)).toLong().coerceIn(50, 500)

        view.animate()
            .alpha(0f)
            .setDuration(alphaDuration)
            .setInterpolator(fadeInterpolator)
            .withEndAction {
                if (!slide) {
                    view.setLayerType(View.LAYER_TYPE_NONE, null)
                    view.scaleX = 1f
                    view.scaleY = 1f
                    onEnd?.invoke()
                }
            }
            .start()

        if (slide) {
            val spring = SpringAnimation(view, DynamicAnimation.TRANSLATION_X, targetX)
            spring.spring.stiffness = stiffness
            spring.spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
            spring.addEndListener { _, canceled, _, _ ->
                // Same guard as animateOpen: a canceled close must not run
                // its teardown or it destroys a panel that was re-opened
                // mid-close (the "swipe once, panel vanishes" race).
                if (canceled) return@addEndListener
                untrackSpring(view, spring)
                view.setLayerType(View.LAYER_TYPE_NONE, null)
                view.translationX = 0f
                onEnd?.invoke()
            }
            trackSpring(view, spring)
            spring.start()
        } else {
            val scaleXSpring = SpringAnimation(view, DynamicAnimation.SCALE_X, 0.9f)
            scaleXSpring.spring.stiffness = stiffness * 1.5f
            scaleXSpring.spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
            scaleXSpring.addEndListener { _, canceled, _, _ ->
                if (!canceled) untrackSpring(view, scaleXSpring)
            }
            val scaleYSpring = SpringAnimation(view, DynamicAnimation.SCALE_Y, 0.9f)
            scaleYSpring.spring.stiffness = stiffness * 1.5f
            scaleYSpring.spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
            scaleYSpring.addEndListener { _, canceled, _, _ ->
                if (!canceled) untrackSpring(view, scaleYSpring)
            }
            trackSpring(view, scaleXSpring)
            trackSpring(view, scaleYSpring)
            scaleXSpring.start()
            scaleYSpring.start()
        }
    }

    private fun cancelExisting(view: View) {
        view.animate().cancel()
        // Drop a not-yet-run open Runnable: without this, an open followed
        // within one frame by a close would have its posted alpha/spring
        // start after the close and undo it (in popup mode the close's
        // onEnd would never fire, leaving the picker visible forever).
        pendingOpenRunnables.remove(view)?.let { view.removeCallbacks(it) }
        activeSprings.remove(view)?.forEach { it.cancel() }
    }

    /**
     * Cancel any pending open Runnable and live springs on a view, e.g.
     * when the service tears the view's window down. A running spring
     * keeps the view (and its window params) alive past detach.
     */
    fun cancel(view: View) {
        cancelExisting(view)
    }

    fun scalePulse(view: View) {
        view.animate().cancel()
        view.animate()
            .scaleX(0.9f)
            .scaleY(0.9f)
            .setDuration(60)
            .setInterpolator(fadeInterpolator)
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(SCALE_RESET_DURATION_MS)
                    .setInterpolator(fadeInterpolator)
                    .start()
            }
            .start()
    }
}
