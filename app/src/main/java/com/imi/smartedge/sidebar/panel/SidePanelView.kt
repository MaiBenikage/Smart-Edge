package com.imi.smartedge.sidebar.panel

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.AttributeSet
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.recyclerview.widget.GridLayoutManager
import com.imi.smartedge.sidebar.panel.databinding.SidePanelLayoutBinding

/**
 * High-performance Side Panel using RecyclerView.
 */
class SidePanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    var onClose: (() -> Unit)? = null
    var onAddClick: ((Boolean) -> Unit)? = null
    var onScreenshot: (() -> Unit)? = null
    var onFolderOpen: ((String) -> Unit)? = null
    var onBackNavigation: (() -> Unit)? = null
    var onToolClick: ((String) -> Unit)? = null
    var onBlackScreen: (() -> Unit)? = null
    var onLockScreen: (() -> Unit)? = null
    /** When set, forwards indicator text to the host service's unified
     *  showIndicator; otherwise showToolIndicator renders its own overlay. */
    var onShowIndicator: ((String) -> Unit)? = null

    private val binding: SidePanelLayoutBinding = SidePanelLayoutBinding.inflate(LayoutInflater.from(context), this, true)
    private val adapter: PanelAppsAdapter
    /** Adapter for the "Recent" section — same item style as the main app list. */
    private val recentAdapter: PanelAppsAdapter
    private val panelPrefs = PanelPreferences(context)
    // Default to 1 column. Every write-path (setColumns, updateStyles) coerces via
    // coerceIn(1, 2) before this field sees a new value, so an out-of-range read
    // (e.g. a corrupt prefs value hand-edited through ADB) can never propagate into
    // GridLayoutManager.spanCount / toolsContainer.columnCount and crash the process.
    private var currentCols = 1
    private var isPickerOpenInternal = false
    /**
     * True while a programmatic scroll restore is in flight. The scroll
     * listener skips saving during this window so the restore can't
     * overwrite (and corrupt) the stored position.
     */
    private var isRestoringScroll = false

    // ── Apps / Recent split (draggable separator) ──────────────────────────
    // Transient split percent used while the user is dragging the separator.
    // Non-null only between ACTION_DOWN and ACTION_UP/CANCEL; on release it
    // is persisted to PanelPreferences.appsRecentSplitPercent and cleared.
    // updateSideLayout() reads currentSplitPercent(), so a drag re-layouts
    // live without a SharedPreferences write on every MOVE event.
    private var dragSplitPercent: Int? = null
    private var separatorDragActive = false
    private var separatorDragStartY = 0f
    private var separatorDragStartSplit = PanelPreferences.DEFAULT_APPS_RECENT_SPLIT_PERCENT
    private var separatorDragStartBudgetPx = 0

    // ── Picker expand/collapse scroll preservation ─────────────────────────
    // In-memory first-visible position + top offset for each scrollable list,
    // captured before the picker forces single-column mode and restored when
    // it closes. In-memory (not prefs) on purpose: this is a transient
    // expand/collapse round-trip, not the opt-in remember-scroll feature,
    // and it must work even when rememberScroll is off.
    private data class ListScrollState(val position: Int, val top: Int)
    private var savedAppsScroll: ListScrollState? = null
    private var savedRecentScroll: ListScrollState? = null

    // Latest main-list scroll position, held in memory during a scroll and
    // written to prefs only when scrolling settles (IDLE) or the view
    // detaches — not on every onScrolled event (a disk write per event).
    private var pendingSidebarScroll: Pair<Int, Int>? = null

    private fun flushSidebarScroll() {
        val pending = pendingSidebarScroll ?: return
        pendingSidebarScroll = null
        panelPrefs.lastSidebarScrollPos = pending.first
        panelPrefs.lastSidebarScrollTop = pending.second
    }

    // Cached set of home/launcher packages — resolved once on first use in isGameMode().
    private var homePackages: Set<String>? = null

    // Track folder navigation
    private val navigationStack = java.util.ArrayDeque<String>()

    private val springRotation: SpringAnimation = SpringAnimation(binding.btnClose, SpringAnimation.ROTATION)

    private fun getFinalScaleFactor(): Float {
        return panelPrefs.scaleFactor
    }

    private val updateHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() {
            updateSystemInfo()
            updateHandler.postDelayed(this, MEMORY_UPDATE_INTERVAL_MS)
        }
    }

    // ── Drag-to-adjust buttons (Volume + Brightness) ──
    //
    // STATE MUST be declared BEFORE [init] so Kotlin's class-property init order
    // sees them initialized before [init] captures them via the lambda bodies
    // of the two touch listeners below. The pure helper functions
    // (handleDragTouch, performVolumeTick, showVolumeIndicator, syncToolsGridColumns)
    // are intentionally left AFTER [init] - function references resolve at
    // compile time and don't impose an init-order constraint.
    //
    // Per-button scratchpad reused across touches. Indices:
    //   [0] = anchored rawY on ACTION_DOWN
    //   [1] = last rawY at which onStep fired (delta is computed relative to this)
    //   [2] = start rawY for tap-vs-drag classification on ACTION_UP
    //   [3] = accumulated vertical travel (px) between emitted units.
    //         Per-button on purpose: the volume and brightness buttons must
    //         not share one accumulator, or a half-unit left over on one
    //         button would leak into the other button's next drag.
    private val volumeDragState = FloatArray(4)
    private val brightnessDragState = FloatArray(4)

    // Dedicated handler + transient indicator used by the drag buttons. Distinct
    // from [updateHandler] / [updateRunnable] (which drive the periodic system-info
    // refresh).
    private val panelHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var panelIndicatorText: android.widget.TextView? = null
    private var panelIndicatorFadeRunnable: Runnable? = null

    // `lateinit` was generating "Lateinit is unnecessary" warnings because the
    // listeners are always assigned in the [init] block below. Convert to plain
    // `val` properties — the init block initializes them before first use.
    @SuppressLint("ClickableViewAccessibility")
    private val btnVolumeDragTouchListener: View.OnTouchListener

    @SuppressLint("ClickableViewAccessibility")
    private val btnBrightnessDragTouchListener: View.OnTouchListener

    private fun updateSystemInfo() {
        if (!panelPrefs.showSysInfo) return

        try {
            val mi = android.app.ActivityManager.MemoryInfo()
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            activityManager.getMemoryInfo(mi)
            val availableMegs = mi.availMem / 1048576L
            val totalMegs = mi.totalMem / 1048576L
            val usedMegs = totalMegs - availableMegs
            binding.tvRamUsage.text = context.getString(R.string.fmt_ram_mb, usedMegs)

            val intent = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            val temp = intent?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
            binding.tvBatTemp.text = context.getString(R.string.fmt_bat_temp, temp / 10)
        } catch (e: Exception) {}
    }

    // Fling-to-close guards: panelOpenedAtMs marks when the panel became
    // visible (flings inside the quiet period are ignored); gestureDownX
    // records the current gesture's start X so onFling can require a real
    // outward displacement instead of just a velocity spike.
    // itemTouchDragActive tracks an in-progress ItemTouchHelper reorder:
    // a fast horizontal reorder must not close the panel mid-drag.
    private var panelOpenedAtMs = 0L
    private var gestureDownX = 0f
    private var itemTouchDragActive = false

    private val gestureDetector = android.view.GestureDetector(context, object : android.view.GestureDetector.SimpleOnGestureListener() {
        override fun onFling(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            // The tail of the swipe that opened the panel often carries an
            // outward velocity spike (finger lift / overshoot correction).
            // Ignore flings right after opening, and require a real outward
            // displacement — not just a velocity spike — before closing.
            if (android.os.SystemClock.uptimeMillis() - panelOpenedAtMs < FLING_QUIET_PERIOD_MS) return false
            // Never close mid-drag: a separator drag or an edit-mode
            // reorder can easily exceed the fling velocity.
            if (separatorDragActive || itemTouchDragActive) return false
            val isRight = panelPrefs.panelSide == PanelPreferences.SIDE_RIGHT
            if (Math.abs(velocityY) > Math.abs(velocityX)) return false
            val dx = e2.x - gestureDownX
            val density = context.resources.displayMetrics.density
            val minDxPx = FLING_MIN_DISPLACEMENT_DP * density
            // Velocity threshold in dp/s, scaled by density: a fixed px/s
            // value is far too easy to hit on high-density screens.
            val minVelocityPx = FLING_MIN_VELOCITY_DP * density
            if (isRight && velocityX > minVelocityPx && dx > minDxPx) {
                onClose?.invoke()
                return true
            } else if (!isRight && velocityX < -minVelocityPx && dx < -minDxPx) {
                onClose?.invoke()
                return true
            }
            return false
        }
    })

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
            gestureDownX = ev.x
        }
        // Don't feed the fling detector during an internal drag (separator
        // or edit-mode reorder): requestDisallowInterceptTouchEvent can't
        // stop this detector — it is fed from dispatchTouchEvent — so the
        // guard has to live here (and in onFling itself).
        if (!separatorDragActive && !itemTouchDragActive) {
            gestureDetector.onTouchEvent(ev)
        }
        return super.dispatchTouchEvent(ev)
    }

    /**
     * Call when the panel becomes visible. Starts the quiet period during
     * which fling-to-close is ignored, so the opening swipe's tail can't
     * immediately close the just-opened panel.
     */
    fun notifyPanelOpened() {
        panelOpenedAtMs = android.os.SystemClock.uptimeMillis()
    }

    init {
        // The panel's geometry is absolute, not locale-mirrored: the side
        // is a user setting (panelSide), and the fling direction, the
        // handle swipe and the pill corner radii all use absolute
        // left/right. Pin the overlay root to LTR so the relative
        // START/END card positioning cannot mirror under an RTL locale
        // and disagree with them. (Text inside still renders per locale.)
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        springRotation.spring = SpringForce().apply {
            dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
            stiffness = SpringForce.STIFFNESS_MEDIUM
        }

        binding.panelCard.setOnClickListener { }

        adapter = PanelAppsAdapter(
            context,
            // The Add button only shows in edit mode, so it always opens
            // the picker in edit mode (true).
            onAddClick = { onAddClick?.invoke(true) },
            onAppLaunched = { onClose?.invoke() },
            // The body lives in onFolderOpened(): this lambda is created
            // while `adapter` is being assigned, so it cannot read
            // `adapter` itself ("Variable 'adapter' must be initialized").
            onFolderClick = { folderId -> onFolderOpened(folderId) },
            onToolClick = { toolId ->
                onToolClick?.invoke(toolId)
            },
            onToolDrag = { toolId, direction ->
                when (toolId) {
                    "smartedge.tool.volume_up" -> {
                        adjustVolumeByStep(direction)
                    }
                    "smartedge.tool.brightness_up" -> {
                        adjustBrightnessByPercent(direction)
                        showBrightnessIndicator()
                    }
                }
            }
        )

        // Recent section: same item style as the main list, but read-only
        // (no add button, no edit/drag, no folders).
        recentAdapter = PanelAppsAdapter(
            context,
            onAddClick = { },
            onAppLaunched = { onClose?.invoke() },
            onFolderClick = { },
            onToolClick = { toolId ->
                onToolClick?.invoke(toolId)
            }
        )

        binding.btnBack.setOnClickListener {
            if (panelPrefs.hapticEnabled) {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
            }
            SpringAnimator.scalePulse(it)
            navigateBack()
        }

        currentCols = panelPrefs.panelColumns
        adapter.setColumns(currentCols)
        binding.rvPanelApps.layoutManager = GridLayoutManager(context, currentCols)
        binding.rvPanelApps.adapter = adapter

        // Audit fix: recycling was fully disabled (cache size 0, pool 0),
        // so every scroll/invalidation re-inflated and re-bound every row
        // and re-loaded every icon. Re-enable a modest cache: onBindViewHolder
        // already fully resets recycled holders (clears the touch listener,
        // icon background/tint/padding, and Glide target), so reuse cannot
        // carry stale state. setHasFixedSize stays false — the panel height
        // is wrap-content up to a max, so its size does change with content.
        binding.rvPanelApps.setHasFixedSize(false)
        binding.rvPanelApps.isNestedScrollingEnabled = false
        binding.rvPanelApps.setItemViewCacheSize(4)
        (binding.rvPanelApps.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = true
        binding.rvPanelApps.recycledViewPool.setMaxRecycledViews(0, 8) // APP
        binding.rvPanelApps.recycledViewPool.setMaxRecycledViews(1, 2) // ADD
        binding.rvPanelApps.recycledViewPool.setMaxRecycledViews(2, 4) // FOLDER
        binding.rvPanelApps.recycledViewPool.setMaxRecycledViews(3, 4) // TOOL

        // Recent section list: mirrors the main list's column count and item style.
        recentAdapter.setColumns(currentCols)
        binding.rvRecentApps.layoutManager = GridLayoutManager(context, currentCols)
        binding.rvRecentApps.adapter = recentAdapter
        binding.rvRecentApps.setHasFixedSize(false)
        binding.rvRecentApps.isNestedScrollingEnabled = true
        binding.rvRecentApps.setItemViewCacheSize(4)
        (binding.rvRecentApps.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = true
        binding.rvRecentApps.recycledViewPool.setMaxRecycledViews(0, 8)
        binding.rvRecentApps.recycledViewPool.setMaxRecycledViews(1, 2)
        binding.rvRecentApps.recycledViewPool.setMaxRecycledViews(2, 4)
        binding.rvRecentApps.recycledViewPool.setMaxRecycledViews(3, 4)
        applyRecentMaxHeight()

        // Draggable separator between the app list and the Recent section.
        // The touch listener handles the whole gesture itself (see
        // handleSeparatorDrag); it is not a click target, so no performClick.
        @SuppressLint("ClickableViewAccessibility")
        val separatorTouchListener = android.view.View.OnTouchListener { _, event ->
            handleSeparatorDrag(event)
        }
        binding.separatorAppsRecent.setOnTouchListener(separatorTouchListener)

        binding.rvPanelApps.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                // Never record the programmatic restore below — it would
                // overwrite the saved position with the restored one and,
                // combined with repeated setApps() calls, ratchet the list
                // down to the bottom ("remember scroll jumps to bottom").
                // Save position + top offset (not raw pixels): robust when
                // the list content changes between save and restore, and
                // RecyclerView.scrollTo() bypasses the LayoutManager anyway.
                // Keep it in memory only: the prefs write happens when
                // scrolling settles (onScrollStateChanged IDLE) and in
                // onDetachedFromWindow, so a fling doesn't enqueue a
                // SharedPreferences apply() per scroll event.
                if (panelPrefs.rememberScroll && !isRestoringScroll) {
                    val lm = recyclerView.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager
                    val pos = lm?.findFirstVisibleItemPosition()
                        ?: androidx.recyclerview.widget.RecyclerView.NO_POSITION
                    if (lm != null && pos != androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
                        pendingSidebarScroll = pos to (lm.findViewByPosition(pos)?.top ?: 0)
                    }
                }
            }

            override fun onScrollStateChanged(recyclerView: androidx.recyclerview.widget.RecyclerView, newState: Int) {
                if (newState == androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE) {
                    flushSidebarScroll()
                }
            }
        })

        val itemTouchHelper = androidx.recyclerview.widget.ItemTouchHelper(object : androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(
            androidx.recyclerview.widget.ItemTouchHelper.UP or androidx.recyclerview.widget.ItemTouchHelper.DOWN or
            androidx.recyclerview.widget.ItemTouchHelper.LEFT or androidx.recyclerview.widget.ItemTouchHelper.RIGHT,
            0
        ) {
            override fun isLongPressDragEnabled(): Boolean {
                // No dragging inside a folder: a drag there would run
                // clearView on the folder's 7 tool ids and overwrite the
                // whole pinned list with them.
                return adapter.isEditMode && navigationStack.isEmpty()
            }

            override fun onSelectedChanged(viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                // Track an in-progress reorder so fling-to-close (fed from
                // dispatchTouchEvent) can't fire mid-drag.
                itemTouchDragActive = actionState == androidx.recyclerview.widget.ItemTouchHelper.ACTION_STATE_DRAG
            }

            override fun onMove(
                recyclerView: androidx.recyclerview.widget.RecyclerView,
                viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                target: androidx.recyclerview.widget.RecyclerView.ViewHolder
            ): Boolean {
                if (viewHolder is PanelAppsAdapter.AddViewHolder) return false
                val from = viewHolder.bindingAdapterPosition
                var to = target.bindingAdapterPosition

                if (target is PanelAppsAdapter.AddViewHolder) {
                    // Snap to the last available app position
                    to = adapter.itemCount - 2
                }

                if (from == androidx.recyclerview.widget.RecyclerView.NO_POSITION || to == androidx.recyclerview.widget.RecyclerView.NO_POSITION) return false
                if (from == to) return false

                adapter.moveItem(from, to)
                return true
            }

            override fun onSwiped(viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder, direction: Int) {}

            override fun clearView(recyclerView: androidx.recyclerview.widget.RecyclerView, viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                // Never save from inside a folder: the folder shows the 7
                // tool ids, not the pinned apps.
                if (navigationStack.isNotEmpty()) return
                // Reorder only what is shown, preserve the rest. The
                // displayed list omits pinned items that can't currently
                // be resolved (an uninstalled app, a deleted custom
                // item), and saving the displayed list alone would delete
                // those pins permanently on every reorder. So the saved
                // order is (displayed ∩ pinned, in displayed order) +
                // (previously persisted pins not on screen, in stored
                // order). Injected entries (Tools folder, notification-
                // prepended apps) are in neither part and still never
                // become permanent.
                val stored = panelPrefs.getPanelApps()
                val pinned = stored.toSet()
                val shown = adapter.getApps()
                    .map { it.identifier }
                    .filter { it in pinned }
                val shownSet = shown.toSet()
                val hidden = stored.filter { it !in shownSet }
                panelPrefs.setPanelApps(shown + hidden)
                updateSideLayout()
            }
        })
        itemTouchHelper.attachToRecyclerView(binding.rvPanelApps)

        updateSideLayout()

        binding.btnClose.setOnClickListener {
            if (panelPrefs.hapticEnabled) {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
            }
            SpringAnimator.scalePulse(it)
            onAddClick?.invoke(false)
        }

        binding.btnScreenshot.setOnClickListener {
            if (panelPrefs.hapticEnabled) {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
            }
            SpringAnimator.scalePulse(it)
            onScreenshot?.invoke()
        }

        binding.btnBlackScreen.setOnClickListener {
            if (panelPrefs.hapticEnabled) {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
            }
            SpringAnimator.scalePulse(it)
            onBlackScreen?.invoke()
        }

        binding.btnLockScreen.setOnClickListener {
            if (panelPrefs.hapticEnabled) {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
            }
            SpringAnimator.scalePulse(it)
            onLockScreen?.invoke()
        }

        binding.btnContentPicker.setOnClickListener {
            if (panelPrefs.hapticEnabled) {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
            }
            SpringAnimator.scalePulse(it)
            onToolClick?.invoke("smartedge.tool.content_picker")
        }

        // ── Drag-to-adjust buttons (Volume + Brightness) ──
        // Replaces the 4 old ±buttons. Each drag button:
        //   • tap (no drag)          → single forward bump
        //   • drag UP  / finger ↑    → increase  (system audio / screen brightness)
        //   • drag DOWN/ finger ↓    → decrease
        //   • the button visually tracks the finger up to ±60dp, then springs back.
        // The touch listeners live above [init] as [lateinit var]s and are assigned
        // here just before wiring. Their lambda bodies reference [volumeDragState] /
        // [brightnessDragState] / [handleDragTouch]; those capture-by-name references
        // are resolved lazily at touch-event time, by which point instance construction
        // (including all property initializers below) is complete.
        // Tap and step actions, shared by the touch listener (physical
        // tap/drag) and the click listener + accessibility actions below
        // (TalkBack double-tap and Increase/Decrease).
        val volumeTap = {
            // Tap → show the system volume panel (no value change required).
            showSystemVolumeBar()
        }
        val volumeStep: (Int) -> Unit = { direction ->
            // Each unit = one hardware volume step (same semantics as
            // edge-slide); the drag distance per unit adapts to the
            // phone's media step count.
            adjustVolumeByStep(direction)
        }
        val brightnessTap = { toggleAutoBrightness() }
        val brightnessStep: (Int) -> Unit = { direction ->
            // Each unit = one brightness level (same semantics as edge-slide).
            adjustBrightnessByPercent(direction)
            showBrightnessIndicator()
        }
        btnVolumeDragTouchListener = View.OnTouchListener { v, event ->
            handleDragTouch(
                view = v,
                event = event,
                dragState = volumeDragState,
                // 25dp was the 1% distance; it now scales to one hardware
                // media step on this phone (see VolumeStepMath), so the
                // full-range drag distance stays the same.
                distancePerUnitDp = { VolumeStepMath.dpPerStep(mediaMaxSteps(), 25f) },
                // Enabled only after a 600ms long-press (LONG_PRESS_DRAG_MS, see handleDragTouch).
                onStep = volumeStep
            )
        }
        btnBrightnessDragTouchListener = View.OnTouchListener { v, event ->
            handleDragTouch(
                view = v,
                event = event,
                dragState = brightnessDragState,
                distancePerUnitDp = { 3f },    // mirrors EdgeHandleView brightness: 3dp/unit
                // Enabled only after a 600ms long-press (LONG_PRESS_DRAG_MS, see handleDragTouch).
                onStep = brightnessStep
            )
        }
        binding.btnVolumeDrag.setOnTouchListener(btnVolumeDragTouchListener)
        binding.btnBrightnessDrag.setOnTouchListener(btnBrightnessDragTouchListener)
        // TalkBack: a double-tap sends performClick(), which the touch-only
        // buttons ignored. Route it to the same tap action as a physical
        // tap, and expose Increase/Decrease actions so the value can be
        // stepped without the drag gesture.
        binding.btnVolumeDrag.setOnClickListener { volumeTap() }
        binding.btnBrightnessDrag.setOnClickListener { brightnessTap() }
        androidx.core.view.ViewCompat.addAccessibilityAction(
            binding.btnVolumeDrag,
            context.getString(R.string.accessibility_action_increase)
        ) { _, _ -> volumeStep(+1); true }
        androidx.core.view.ViewCompat.addAccessibilityAction(
            binding.btnVolumeDrag,
            context.getString(R.string.accessibility_action_decrease)
        ) { _, _ -> volumeStep(-1); true }
        androidx.core.view.ViewCompat.addAccessibilityAction(
            binding.btnBrightnessDrag,
            context.getString(R.string.accessibility_action_increase)
        ) { _, _ -> brightnessStep(+1); true }
        androidx.core.view.ViewCompat.addAccessibilityAction(
            binding.btnBrightnessDrag,
            context.getString(R.string.accessibility_action_decrease)
        ) { _, _ -> brightnessStep(-1); true }

        binding.btnReboot.setOnClickListener {
            if (panelPrefs.hapticEnabled) {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
            }
            SpringAnimator.scalePulse(it)

            if (panelPrefs.useAutomationForGestures && AutomationManager.isAutomationPossible()) {
                AutomationManager.performPowerMenu()
            } else {
                // Trigger System Power Menu via Accessibility Service
                val intent = Intent(context, PanelAccessibilityService::class.java).apply {
                    action = PanelAccessibilityService.ACTION_SHOW_POWER_MENU
                }
                context.startService(intent)
            }
            onClose?.invoke()
        }

        // Mirror the app-list column count across to the tools grid below the list,
        // since the toolsContainer is a GridLayout whose columnCount isn't driven by
        // ConstraintLayout attributes.
        applyTheme()
        // Sync tools grid columns AFTER applyTheme() sets actual tool visibility.
        // During init the XML default is all GONE; syncing before applyTheme()
        // assigns row=99 to all tools, then applyTheme() makes some VISIBLE
        // but the stale row=99 params leave them invisible.
        syncToolsGridColumns()
    }

    fun updateSideLayout() {
        val isRight = panelPrefs.panelSide == PanelPreferences.SIDE_RIGHT
        // Honor the picker-open state: resetting to the closed left/right arrow
        // here while the picker is open would flip the chevron back to pointing
        // sideways the moment any other path calls updateSideLayout() (e.g.
        // clicking EDIT in the picker → setEditButtonVisible → updateSideLayout).
        binding.btnClose.rotation = when {
            isPickerOpenInternal -> 90f
            isRight -> 180f
            else -> 0f
        }

        val scale = getFinalScaleFactor()
        val lp = binding.panelCard.layoutParams

        // Scale only the icon area, keeping the padding/chrome fixed
        val newWidthDp = if (currentCols == 2) {
            52f + (88f * scale)
        } else {
            32f + (40f * scale)
        }

        lp.width = context.dpToPx(newWidthDp.toInt())
        binding.panelCard.layoutParams = lp

        // Calculate maximum allowed height for the RecyclerView to ensure the panel fits on screen
        val displayMetrics = context.resources.displayMetrics
        val screenHeightPx = displayMetrics.heightPixels
        val screenHeightDp = screenHeightPx / displayMetrics.density

        // Subtract estimated height of other UI elements (paddings, tools, close button)
        val nonAppHeightDp = computeNonAppHeightDp()

        // Apps / Recent split: when the Recent section is visible the two
        // lists share one height budget — the height they would have
        // occupied together under the old budgeting (main list up to
        // panelMaxHeight + recent content height, capped so the whole panel
        // still fits on screen with a 24dp safety margin). The budget is
        // divided by the persisted (or currently-dragged) split percent,
        // default 50/50, so dragging the separator redistributes height
        // between the lists without changing the panel's total height.
        // The 28dp recent divider+header and the 16dp separator are chrome
        // on top of the two lists' heights.
        val recentVisible = binding.layoutRecentSection.visibility == View.VISIBLE
        binding.separatorAppsRecent.visibility =
            if (recentVisible) View.VISIBLE else View.GONE
        val recentChromeDp = if (recentVisible) 28f + SEPARATOR_HEIGHT_DP else 0f
        val splitHeights = splitHeightsDp(
            screenHeightDp = screenHeightDp,
            nonAppHeightDp = nonAppHeightDp,
            recentChromeDp = recentChromeDp
        )
        // Maximum allowed height for RV to keep panel within screen (with 24dp safety margin).
        // The lower clamp mirrors the split path's 56dp floor (one partial
        // row): without it a 0/negative panelMaxHeight collapses the list
        // to 0dp, while the split path never goes below 56dp.
        val targetRvHeightDp = (splitHeights?.first
            ?: panelPrefs.panelMaxHeight.toFloat().coerceAtMost(
                (screenHeightDp - nonAppHeightDp - 24).coerceAtLeast(100f)
            )).coerceAtLeast(AppsRecentSplit.MIN_LIST_HEIGHT_DP)
        // Apply the recent list's share of the budget here as well, so the
        // two heights can never fight: updateSideLayout() is the single
        // source of truth for both heights, and applyRecentMaxHeight()
        // deliberately does nothing while the section is visible.
        if (splitHeights != null) {
            val recentPx = (splitHeights.second * displayMetrics.density).toInt()
            val recentLp = binding.rvRecentApps.layoutParams
            if (recentLp.height != recentPx) {
                recentLp.height = recentPx
                binding.rvRecentApps.layoutParams = recentLp
            }
        }

        // Apply dynamic height using ConstraintLayout's max-height constraint instead of brittle manual item height guessing
        val rvLp = binding.rvPanelApps.layoutParams
        val maxRvHeightPx = context.dpToPx(targetRvHeightDp.toInt())

        val constraintSet = androidx.constraintlayout.widget.ConstraintSet()
        constraintSet.clone(binding.panelCard)
        constraintSet.constrainMaxHeight(binding.rvPanelApps.id, maxRvHeightPx)
        // Ensure height works correctly with constraints
        rvLp.height = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.WRAP_CONTENT
        (rvLp as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams)?.matchConstraintMaxHeight = maxRvHeightPx
        (rvLp as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams)?.constrainedHeight = true

        constraintSet.applyTo(binding.panelCard)
        binding.rvPanelApps.layoutParams = rvLp

        val containerLp = binding.panelContainer.layoutParams as? android.widget.RelativeLayout.LayoutParams
        if (containerLp != null) {
            if (isRight) {
                containerLp.addRule(android.widget.RelativeLayout.ALIGN_PARENT_END)
                containerLp.removeRule(android.widget.RelativeLayout.ALIGN_PARENT_START)
                containerLp.marginEnd = context.dpToPx(12)
                containerLp.marginStart = 0
            } else {
                containerLp.addRule(android.widget.RelativeLayout.ALIGN_PARENT_START)
                containerLp.removeRule(android.widget.RelativeLayout.ALIGN_PARENT_END)
                containerLp.marginStart = context.dpToPx(12)
                containerLp.marginEnd = 0
            }
            binding.panelContainer.layoutParams = containerLp
        }

        // Tools-grid sync is handled by applyTheme() which runs after
        // updateSideLayout() in updateStyles(). The column-count mirroring
        // here was the THIRD call in a single updateStyles() → applyTheme()
        // → updateSideLayout() chain, causing GridLayout measurement thrash
        // on OEM forks. Removing this eliminates the triple-call pattern.
    }

    fun scrollToTop() {
        binding.rvPanelApps.scrollToPosition(0)
    }

    fun scrollToBottom() {
        val count = adapter.itemCount
        if (count > 0) binding.rvPanelApps.scrollToPosition(count - 1)
    }

    fun scrollToApp(identifier: String) {
        val apps = adapter.currentList
        val index = apps.indexOfFirst { it.identifier == identifier }
        if (index != -1) {
            binding.rvPanelApps.smoothScrollToPosition(index)
            adapter.highlightItem(identifier)
        }
    }

    fun animatePickerToggle(isOpen: Boolean) {
        isPickerOpenInternal = isOpen
        val targetRotation = if (isOpen) 90f else (if (panelPrefs.panelSide == PanelPreferences.SIDE_RIGHT) 180f else 0f)
        springRotation.animateToFinalPosition(targetRotation)
    }

    /**
     * Returns true when the foreground app is in landscape fullscreen and is NOT
     * a video player. Used to auto-switch to 2-column layout and show SysInfo.
     */
    private fun isGameMode(): Boolean {
        val pkg = panelPrefs.currentForegroundPackage
        if (pkg.isBlank()) return false
        // Must be landscape orientation.
        val isLandscape = context.resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE
        if (!isLandscape) return false
        // Exclude the home screen / launcher (cached after first resolution).
        val pkgs = homePackages ?: run {
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_HOME)
            val resolved = context.packageManager.queryIntentActivities(intent,
                android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                .mapTo(mutableSetOf()) { it.activityInfo.packageName }
            homePackages = resolved
            resolved
        }
        if (pkgs.contains(pkg) || pkg == context.packageName) return false
        // Exclude video players / streaming apps.
        if (context.isVideoPlayerPackage(pkg)) return false
        // At this point: landscape + non-home + non-video → treat as game/fullscreen.
        return true
    }

    fun setColumns(cols: Int) {
        // Round-13 audit M1: AOSP GridLayoutManager(0) throws IllegalArgumentException
        // ("Span count must be at least 1") and a (columnCount > 0) check on the tools
        // GridLayout would crash the same way via setColumnCount. coerceIn(1, 2)
        // guarantees neither receives a degenerate value, regardless of what
        // FloatingPanelService / SavedInstanceState data pass in.
        val safe = cols.coerceIn(1, 2)
        // Preserve scroll across the span-count reflow — but only when no
        // round-trip save is already pending. openPicker saves first and
        // closePicker restores; a save here would overwrite its snapshot
        // with the picker's own scrolled position and the close restore
        // would then return to the picker position, not the pre-picker one.
        val ownSave = savedAppsScroll == null && savedRecentScroll == null
        if (ownSave) saveListScrollStates()
        currentCols = safe
        adapter.setColumns(safe)
        (binding.rvPanelApps.layoutManager as? GridLayoutManager)?.spanCount = safe
        // All three lists switch together: the Recent section previously
        // kept its old span count here (it was only updated in
        // updateStyles(), which is skipped while the picker is open), so
        // opening the picker left recents in 2-column mode while apps and
        // tools went single-column.
        recentAdapter.setColumns(safe)
        (binding.rvRecentApps.layoutManager as? GridLayoutManager)?.spanCount = safe
        syncToolsGridColumns()
        updateSideLayout()
        if (ownSave) restoreListScrollStates()
    }

    /**
     * Capture the current scroll position of both scrollable lists, so
     * [restoreListScrollStates] can put each list back exactly where it
     * was after a column change. Called by the host service before it
     * forces single-column mode for the picker.
     */
    fun saveListScrollStates() {
        savedAppsScroll = scrollStateOf(binding.rvPanelApps)
        savedRecentScroll = scrollStateOf(binding.rvRecentApps)
    }

    /**
     * Restore the scroll positions captured by [saveListScrollStates].
     * Must be called *after* the column count has been restored, and posts
     * the restore so it runs after the new layout pass — restoring before
     * layout would be overwritten by the span-count reflow. The
     * [isRestoringScroll] guard stops the main list's scroll listener from
     * overwriting the saved (remember-scroll) position with the restored one.
     * Saved state is cleared after use so a later, unrelated column change
     * cannot resurrect a stale position.
     */
    fun restoreListScrollStates() {
        val apps = savedAppsScroll
        val recent = savedRecentScroll
        savedAppsScroll = null
        savedRecentScroll = null
        if (apps == null && recent == null) return
        binding.rvPanelApps.post {
            isRestoringScroll = true
            try {
                if (apps != null) {
                    val lm = binding.rvPanelApps.layoutManager
                        as? androidx.recyclerview.widget.LinearLayoutManager
                    val count = adapter.itemCount
                    if (lm != null && count > 0) {
                        lm.scrollToPositionWithOffset(
                            apps.position.coerceIn(0, count - 1),
                            clampScrollTop(apps.top)
                        )
                    }
                }
                if (recent != null) {
                    val lm = binding.rvRecentApps.layoutManager
                        as? androidx.recyclerview.widget.LinearLayoutManager
                    val count = recentAdapter.itemCount
                    if (lm != null && count > 0) {
                        lm.scrollToPositionWithOffset(
                            recent.position.coerceIn(0, count - 1),
                            clampScrollTop(recent.top)
                        )
                    }
                }
            } finally {
                binding.rvPanelApps.post { isRestoringScroll = false }
            }
        }
    }

    private fun scrollStateOf(
        recyclerView: androidx.recyclerview.widget.RecyclerView
    ): ListScrollState? {
        val lm = recyclerView.layoutManager
            as? androidx.recyclerview.widget.LinearLayoutManager ?: return null
        val pos = lm.findFirstVisibleItemPosition()
        if (pos == androidx.recyclerview.widget.RecyclerView.NO_POSITION) return null
        return ListScrollState(pos, lm.findViewByPosition(pos)?.top ?: 0)
    }

    /**
     * Clamp a restored scroll top offset to [-itemHeight, 0]. The top of
     * the first visible item is 0 when it is fully visible and negative
     * while it is partially scrolled off the top; anything outside that
     * range (a stale persisted pref, or a snapshot taken under a different
     * column count) would jump the list to a wrong position.
     * Item height here is the grid cell height (40dp icon + ~8sp label +
     * vertical padding ≈ 68dp) shared by both lists.
     */
    private fun clampScrollTop(top: Int): Int {
        val itemHeightPx = (68 * resources.displayMetrics.density).toInt()
        return top.coerceIn(-itemHeightPx, 0)
    }

    fun setEditButtonVisible(visible: Boolean) {
        adapter.setShowAddButton(visible)
        updateSideLayout()
    }

    /**
     * Handles a folder row tap from the main list. Leaves edit mode when a
     * folder opens: dragging inside a folder would save the folder's contents
     * as the pinned list, and the Add button must not show inside a folder.
     */
    private fun onFolderOpened(folderId: String) {
        adapter.setShowAddButton(false)
        navigationStack.push(folderId)
        updateNavigationUI()
        onFolderOpen?.invoke(folderId)
    }

    fun navigateBack() {
        if (navigationStack.isNotEmpty()) {
            navigationStack.pop()
            updateNavigationUI()
            onBackNavigation?.invoke()
        }
    }

    private fun updateNavigationUI() {
        val inFolder = navigationStack.isNotEmpty()
        binding.btnBack.visibility = if (inFolder) View.VISIBLE else View.GONE
        binding.btnClose.visibility = if (inFolder) View.GONE else View.VISIBLE
        applyTheme()
        updateSideLayout()
    }

    fun resetNavigation() {
        navigationStack.clear()
        updateNavigationUI()
    }

    fun setApps(apps: List<AppInfo>, onComplete: (() -> Unit)? = null) {
        adapter.submitList(apps) {
            updateSideLayout()

            // Restore scroll position if enabled (only for root level).
            // Use scrollToPositionWithOffset, not scrollTo/scrollBy: the raw
            // View scroll APIs bypass RecyclerView's LayoutManager (visually
            // a no-op or, when stacked across repeated setApps() calls, a
            // ratchet to the bottom). Position + top offset survives content
            // changes between save and restore.
            if (panelPrefs.rememberScroll && navigationStack.isEmpty()) {
                binding.rvPanelApps.post {
                    isRestoringScroll = true
                    val lm = binding.rvPanelApps.layoutManager
                        as? androidx.recyclerview.widget.LinearLayoutManager
                    val count = adapter.itemCount
                    val pos = panelPrefs.lastSidebarScrollPos.coerceIn(0, (count - 1).coerceAtLeast(0))
                    if (lm != null && count > 0) {
                        lm.scrollToPositionWithOffset(pos, clampScrollTop(panelPrefs.lastSidebarScrollTop))
                    }
                    binding.rvPanelApps.post { isRestoringScroll = false }
                }
            } else {
                binding.rvPanelApps.post {
                    binding.rvPanelApps.scrollToPosition(0)
                }
            }

            onComplete?.invoke()
        }
    }

    /**
     * Update the "Recent" section. Called by the host service alongside
     * [setApps]; hidden when the feature is off, when inside a folder, or
     * when there is no recorded history yet.
     *
     * [sidebarApps] is the list just handed to [setApps] (the service's
     * own copy — including the Tools folder button and any
     * notification-prepended apps). It is passed in explicitly instead of
     * reading `adapter.currentList`, so the exclusion uses exactly the
     * list that was just set, without depending on adapter state or
     * commit timing.
     */
    fun setRecentApps(apps: List<AppInfo>, sidebarApps: List<AppInfo>) {
        // Defence in depth for the no-duplication rule: the service already
        // excludes sidebar apps before the recent-count limit, but any
        // future caller of setRecentApps gets the same guarantee here.
        // Build the exclusion set once: it walks all sidebar apps and
        // parses intent URIs, so rebuilding it per recent item is O(n*m).
        val excludedPackages = RecentAppsManager.excludedPackagesFor(sidebarApps)
        val filteredApps = apps.filter {
            it.packageName !in excludedPackages
        }
        val visible = panelPrefs.recentEnabled &&
            navigationStack.isEmpty() &&
            filteredApps.isNotEmpty()
        val list = if (visible) filteredApps else emptyList()
        binding.layoutRecentSection.visibility = if (visible) View.VISIBLE else View.GONE
        // Preserve the recent list's scroll position across the refresh:
        // submitList() resets it to the top otherwise. Kept in a local
        // snapshot (not the shared save/restore fields) so it can't
        // clobber a picker round-trip save in progress.
        val recentScroll = scrollStateOf(binding.rvRecentApps)
        // Size the list AND re-budget the main list height in the commit
        // callback so recentListHeightDp() reads the new item count, not
        // the stale one.
        recentAdapter.submitList(list) {
            applyRecentMaxHeight()
            updateSideLayout()
            if (recentScroll != null) {
                binding.rvRecentApps.post {
                    val lm = binding.rvRecentApps.layoutManager
                        as? androidx.recyclerview.widget.LinearLayoutManager
                    val count = recentAdapter.itemCount
                    if (lm != null && count > 0) {
                        lm.scrollToPositionWithOffset(
                            recentScroll.position.coerceIn(0, count - 1),
                            clampScrollTop(recentScroll.top)
                        )
                    }
                }
            }
        }
    }

    /**
     * Recent list height in dp, mirroring [applyRecentMaxHeight]'s math so
     * [updateSideLayout] budgets exactly what the section will occupy.
     */
    private fun recentListHeightDp(): Int {
        val count = recentAdapter.itemCount.coerceAtLeast(1)
        val cols = currentCols.coerceAtLeast(1)
        val rows = kotlin.math.ceil(count / cols.toDouble()).toInt().coerceAtLeast(1)
        return (rows * 68).coerceAtMost(RECENT_MAX_HEIGHT_DP)
    }

    /**
     * Size the recent list. (rvRecentApps is a direct child of a
     * LinearLayout, so ConstraintLayout max-height params don't apply — we
     * set an explicit pixel height instead.)
     *
     * When the Recent section is visible, [updateSideLayout] owns the
     * height: the list gets its share of the shared apps/recent budget
     * (see [splitHeightsDp]), which may be taller than its content (empty
     * space below the last row) or shorter (it then scrolls internally —
     * it is the nested-scrolling list). This function therefore does
     * nothing in that case; setRecentApps() calls updateSideLayout()
     * immediately after this. Only when the section is hidden does the
     * old content-height cap apply.
     */
    private fun applyRecentMaxHeight() {
        if (binding.layoutRecentSection.visibility == View.VISIBLE) return
        val count = recentAdapter.itemCount
        if (count == 0) return
        val density = resources.displayMetrics.density
        // Approx sidebar grid cell: 40dp icon + ~8sp label + vertical padding.
        val rowHeightPx = (68 * density).toInt()
        val cols = currentCols.coerceAtLeast(1)
        val rows = kotlin.math.ceil(count / cols.toDouble()).toInt().coerceAtLeast(1)
        val maxPx = context.dpToPx(RECENT_MAX_HEIGHT_DP).toInt()
        val targetPx = (rows * rowHeightPx).coerceAtMost(maxPx)
        val lp = binding.rvRecentApps.layoutParams
        if (lp.height != targetPx) {
            lp.height = targetPx
            binding.rvRecentApps.layoutParams = lp
        }
    }

    /** Split percent currently in effect: the live drag value, if any, else the persisted pref. */
    private fun currentSplitPercent(): Int =
        dragSplitPercent ?: panelPrefs.appsRecentSplitPercent

    /**
     * Height (dp) of everything in the panel except the two lists — chrome
     * that updateSideLayout() subtracts before splitting the remaining
     * budget between the app list and the recent list. Shared by
     * updateSideLayout() and the separator drag so both use the same
     * budget: the drag must map 100% of its travel to the split budget,
     * not to the lists' measured heights (which shrink to content when a
     * list is short, making the same drag move the percent too far).
     */
    private fun computeNonAppHeightDp(): Float {
        // The height math lives in ToolsLayout (pure, unit-tested) so the
        // test suite calls the real implementation instead of a mirror.
        val isGameMode = isGameMode()
        val showSysInfoEffective = panelPrefs.showSysInfo || isGameMode
        val tools = ToolsLayout.ToolsVisibility(
            // Order MUST match getToolCellList()
            showPower = panelPrefs.showPowerMenu,
            showVolume = panelPrefs.showVolumeKeys,
            showBrightness = panelPrefs.showBrightnessKeys,
            showScreenshot = panelPrefs.showScreenshotTool,
            showBlackScreen = panelPrefs.showBlackScreenTool,
            showLockScreen = panelPrefs.showLockScreenTool,
            showContentPicker = panelPrefs.showContentPickerTool
        )
        return ToolsLayout.nonAppHeightDp(
            tools = tools,
            showTools = panelPrefs.showTools,
            inFolder = navigationStack.isNotEmpty(),
            gridCols = currentCols,
            showSysInfo = showSysInfoEffective
        )
    }

    /**
     * Height budget for the two lists, in dp, as (appsHeight, recentHeight),
     * or null when the Recent section is hidden (no split to apply).
     *
     * @param screenHeightDp screen height in dp.
     * @param nonAppHeightDp chrome height in dp *excluding* the recent
     * section (60dp base + tools + SysInfo, as computed in
     * [updateSideLayout]; +16dp in a folder, where btnBack replaces
     * btnClose).
     * @param recentChromeDp recent divider+header (28dp) + separator height,
     * or 0 when the section is hidden.
     */
    private fun splitHeightsDp(
        screenHeightDp: Float,
        nonAppHeightDp: Float,
        recentChromeDp: Float
    ): Pair<Float, Float>? {
        if (binding.layoutRecentSection.visibility != View.VISIBLE) return null
        val recentContentDp = recentListHeightDp().toFloat()
        // Whole panel must fit on screen with a 24dp safety margin.
        val maxCombinedDp = (screenHeightDp - nonAppHeightDp - recentChromeDp - 24f)
            .coerceAtLeast(100f)
        // Historical combined budget: main list up to panelMaxHeight plus
        // the recent content height. Capping by maxCombinedDp preserves
        // the panel's total height exactly; the split only redistributes it.
        val combinedDp = (panelPrefs.panelMaxHeight.toFloat() + recentContentDp)
            .coerceAtMost(maxCombinedDp)
            .coerceAtLeast(56f)
        val split = currentSplitPercent()
        return AppsRecentSplit.splitHeights(combinedDp, split)
    }

    /**
     * Drag handler for the apps/recent separator.
     *
     * • ACTION_DOWN — anchor on the current split and the shared budget in
     *   px (from [splitHeightsDp], so the percent maps to the same budget
     *   updateSideLayout() uses). Disallow parent interception so the
     *   RecyclerView doesn't steal the drag; fling-to-close is guarded
     *   separately by the separatorDragActive flag in dispatchTouchEvent
     *   and onFling (requestDisallowInterceptTouchEvent can't stop the
     *   GestureDetector, which is fed from dispatchTouchEvent).
     * • ACTION_MOVE — dragging down grows the app list and shrinks recents
     *   (and vice versa). Re-layouts live via the transient
     *   [dragSplitPercent]; nothing is written to prefs per move.
     * • ACTION_UP — persist the final split once, show the new percent,
     *   and re-layout from the persisted value.
     * • ACTION_CANCEL — discard the drag and restore the persisted split.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun handleSeparatorDrag(event: android.view.MotionEvent): Boolean {
        return when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                if (binding.layoutRecentSection.visibility != View.VISIBLE) return false
                val displayMetrics = resources.displayMetrics
                val screenHeightDp = displayMetrics.heightPixels / displayMetrics.density
                // The drag budget is the split budget updateSideLayout()
                // uses (same inputs: computeNonAppHeightDp +
                // recentChromeDp), NOT the lists' measured heights: a short
                // list shrinks to its content, so the measured sum is
                // smaller than the budget and the same drag would move the
                // percent too far. This also works before first layout.
                val split = splitHeightsDp(
                    screenHeightDp = screenHeightDp,
                    nonAppHeightDp = computeNonAppHeightDp(),
                    recentChromeDp = 28f + SEPARATOR_HEIGHT_DP
                ) ?: return false
                separatorDragStartBudgetPx =
                    ((split.first + split.second) * displayMetrics.density).toInt()
                if (separatorDragStartBudgetPx <= 0) return false
                separatorDragStartY = event.rawY
                separatorDragStartSplit = currentSplitPercent()
                dragSplitPercent = separatorDragStartSplit
                separatorDragActive = true
                parent?.requestDisallowInterceptTouchEvent(true)
                true
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                if (!separatorDragActive || separatorDragStartBudgetPx <= 0) return false
                val deltaPercent =
                    ((event.rawY - separatorDragStartY) / separatorDragStartBudgetPx * 100f).toInt()
                val newSplit = (separatorDragStartSplit + deltaPercent).coerceIn(
                    PanelPreferences.MIN_APPS_RECENT_SPLIT_PERCENT,
                    PanelPreferences.MAX_APPS_RECENT_SPLIT_PERCENT
                )
                if (newSplit != dragSplitPercent) {
                    dragSplitPercent = newSplit
                    updateSideLayout()
                }
                true
            }
            android.view.MotionEvent.ACTION_UP -> {
                if (!separatorDragActive) return false
                separatorDragActive = false
                parent?.requestDisallowInterceptTouchEvent(false)
                val finalSplit = dragSplitPercent ?: currentSplitPercent()
                dragSplitPercent = null
                panelPrefs.appsRecentSplitPercent = finalSplit
                if (panelPrefs.hapticEnabled) {
                    binding.separatorAppsRecent.performHapticFeedback(
                        android.view.HapticFeedbackConstants.CONTEXT_CLICK
                    )
                }
                showToolIndicator(
                    context.getString(
                        R.string.indicator_apps_recent_split,
                        finalSplit, 100 - finalSplit
                    )
                )
                updateSideLayout()
                true
            }
            android.view.MotionEvent.ACTION_CANCEL -> {
                separatorDragActive = false
                dragSplitPercent = null
                parent?.requestDisallowInterceptTouchEvent(false)
                updateSideLayout()
                true
            }
            else -> false
        }
    }

    fun updateStyles() {
        if (!isPickerOpenInternal) {
            val isGameMode = isGameMode()
            // Preserve scroll across the span-count reflow (same own-save
            // guard as setColumns: never clobber a pending round-trip save).
            val ownSave = savedAppsScroll == null && savedRecentScroll == null
            if (ownSave) saveListScrollStates()
            currentCols = (if (isGameMode) 2 else panelPrefs.panelColumns).coerceIn(1, 2)
            (binding.rvPanelApps.layoutManager as? GridLayoutManager)?.spanCount = currentCols
            adapter.setColumns(currentCols)
            // Keep the Recent section in the same column mode.
            (binding.rvRecentApps.layoutManager as? GridLayoutManager)?.spanCount = currentCols
            recentAdapter.setColumns(currentCols)
            applyTheme()
            // Sync tools grid AFTER applyTheme() sets visibility, so
            // GONE/VISIBLE children get correct row/col specs for the
            // current column mode. Without this, changing tool visibility
            // in settings leaves stale GridLayout params and tools go
            // missing in 2-column mode.
            syncToolsGridColumns()
            updateSideLayout()
            if (ownSave) restoreListScrollStates()
            // Belt-and-suspenders: post a second pass after the current frame
            // layout pass settles, so OEM GridLayout forks get refreshed specs.
            // Removed post{} second-pass: the clear-and-re-add strategy in
            // syncToolsGridColumns() produces a correct layout on the first pass.
        } else {
            // Picker is open (forced to 1-column via setColumns(1) in openPicker).
            // DO NOT run applyTheme() or syncToolsGridColumns() here — they would
            // overwrite the picker's 1-column grid with stale currentCols, causing:
            //   1. Tools rendered in single column (grid stuck at columnCount=1)
            //   2. Inflated tools-section height (64dp/row instead of54dp/row)
            //   3. Enabled tools pushed off-screen
            // The correct column count is restored when the picker closes via
            // setColumns(originalCols) in closePicker().
            updateSideLayout()
        }
    }

    fun refreshIcons() {
        adapter.refreshIcons()
        recentAdapter.refreshIcons()
    }

    /** Bind dashboard tool ImageButtons to vector drawables via ToolIconHelper. */
    private fun applyToolButtonIcons(tint: ColorStateList = ColorStateList.valueOf(Color.WHITE)) {
        val pairs = listOf(
            binding.btnScreenshot to "screenshot",
            binding.btnVolumeDrag to "volume",
            binding.btnBrightnessDrag to "brightness",
            binding.btnReboot to "power",
            binding.btnBlackScreen to "black_screen",
            binding.btnLockScreen to "lock_screen",
            binding.btnContentPicker to "content_picker"
        )
        for ((btn, key) in pairs) {
            val icon = ToolIconHelper.forDashboard(context, key)
            if (icon != null) {
                btn.setImageDrawable(icon)
            }
            btn.imageTintList = tint
            btn.scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
        }
    }

    fun applyTheme() {
        val inFolder = navigationStack.isNotEmpty()
        val showTools = panelPrefs.showTools && !inFolder

        val showPower = panelPrefs.showPowerMenu
        binding.layoutPowerTools.visibility = if (showPower) View.VISIBLE else View.GONE

        val showVolume = panelPrefs.showVolumeKeys
        binding.layoutVolumeTools.visibility = if (showVolume) View.VISIBLE else View.GONE

        val showBrightness = panelPrefs.showBrightnessKeys
        binding.layoutBrightnessTools.visibility = if (showBrightness) View.VISIBLE else View.GONE

        val showScreenshot = panelPrefs.showScreenshotTool
        val scVisibility = if (showScreenshot) View.VISIBLE else View.GONE
        binding.layoutScreenshotTools.visibility = scVisibility

        val showBlackScreen = panelPrefs.showBlackScreenTool
        val blkVisibility = if (showBlackScreen) View.VISIBLE else View.GONE
        binding.layoutBlackScreenTools.visibility = blkVisibility

        val showLockScreen = panelPrefs.showLockScreenTool
        binding.layoutLockScreenTools.visibility = if (showLockScreen) View.VISIBLE else View.GONE

        val showContentPicker = panelPrefs.showContentPickerTool
        binding.layoutContentPickerTools.visibility = if (showContentPicker) View.VISIBLE else View.GONE

        // Hide divider when no standard interactive tools are visible
        // (only SysInfo enabled = no divider needed)
        val hasStandardTools = showPower || showVolume || showBrightness || showScreenshot || showBlackScreen || showLockScreen || showContentPicker
        binding.toolsDivider.visibility = if (hasStandardTools) View.VISIBLE else View.GONE

        if (panelPrefs.hideBackground) {
            binding.panelCard.background = null
        } else {
            val theme = panelPrefs.uiTheme

            // Revert to original dark-centric colors for floating panel
            val bgColor = when (theme) {
                PanelPreferences.THEME_ORIGIN -> Color.parseColor("#1F1F1F")
                PanelPreferences.THEME_HYPEROS -> Color.parseColor("#E6252525")
                else -> try { Color.parseColor(panelPrefs.panelBackgroundColor) } catch (e: Exception) { Color.parseColor("#E61A1C1E") }
            }

            val radius = context.dpToPx(if (theme == PanelPreferences.THEME_HYPEROS) 16 else panelPrefs.panelCornerRadius).toFloat()

            val shape = GradientDrawable().apply {
                setColor(bgColor)
                cornerRadius = radius

                if (theme == PanelPreferences.THEME_HYPEROS) {
                    setStroke(context.dpToPx(1), Color.parseColor("#4DFFFFFF"))
                } else if (theme == PanelPreferences.THEME_RICH) {
                    // The Rich stroke uses the custom accent only when the
                    // switch is on; otherwise the default blue applies.
                    val accent = if (panelPrefs.useCustomAccent) {
                        try { Color.parseColor(panelPrefs.accentColor) } catch (e: Exception) { Color.parseColor("#4A9EFF") }
                    } else {
                        Color.parseColor("#4A9EFF")
                    }
                    setStroke(context.dpToPx(2), accent)
                } else if (theme == PanelPreferences.THEME_REALME) {
                    val color1 = Color.parseColor("#333333")
                    val color2 = Color.parseColor("#1A1A1A")
                    colors = intArrayOf(color1, color2)
                    orientation = GradientDrawable.Orientation.TOP_BOTTOM
                    setStroke(context.dpToPx(1), Color.parseColor("#33FFFFFF"))
                }
            }
            binding.panelCard.background = shape

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                binding.panelCard.clipToOutline = true
            }
        }

        // Force white/light icons and text for the dark floating panel.
        // This is independent of the card background: with hideBackground
        // on, the buttons previously kept their XML defaults (#D9FFFFFF
        // tint, XML src) and later theme changes never reached them.
        val iconColorList = ColorStateList.valueOf(Color.WHITE)
        // btnClose and btnBack still use ImageButton with drawable icons
        binding.btnClose.imageTintList = iconColorList
        binding.btnBack.imageTintList = iconColorList
        // Tool buttons are ImageButtons — tint + vector drawables (ToolIconHelper).
        applyToolButtonIcons(iconColorList)

        binding.tvRamUsage.setTextColor(Color.WHITE)
        binding.tvBatTemp.setTextColor(Color.WHITE)

        val isGameMode = isGameMode()
        val showSysInfoEffective = panelPrefs.showSysInfo || isGameMode

        // SysInfo is now a standalone section outside the tools GridLayout,
        // so it's always centered regardless of the tools column mode.
        binding.layoutSysInfo.visibility = if (showTools && showSysInfoEffective) View.VISIBLE else View.GONE

        // Tools container only depends on standard interactive tools.
        // SysInfo is independent and doesn't affect tools grid visibility.
        // Visibility comes from ToolsLayout.toolsContainerVisible (pure,
        // unit-tested) so the tested logic is the production logic.
        val toolsVisibility = ToolsLayout.ToolsVisibility(
            showPower = showPower,
            showVolume = showVolume,
            showBrightness = showBrightness,
            showScreenshot = showScreenshot,
            showBlackScreen = showBlackScreen,
            showLockScreen = showLockScreen,
            showContentPicker = showContentPicker
        )
        binding.toolsContainer.visibility = if (ToolsLayout.toolsContainerVisible(
            showTools = panelPrefs.showTools,
            inFolder = inFolder,
            tools = toolsVisibility
        )) View.VISIBLE else View.GONE

        // Sync tools grid columns synchronously IMMEDIATELY after all
        // visibility changes — the GridLayout must have correct specs
        // before its first measurement pass, not after a post() delay.
        // updateStyles() also schedules a post() second-pass at the end
        // as belt-and-suspenders for OEM GridLayout forks.
        syncToolsGridColumns()

        if (showSysInfoEffective) {
            updateSystemInfo()
            updateHandler.removeCallbacks(updateRunnable)
            updateHandler.post(updateRunnable)
        } else {
            updateHandler.removeCallbacks(updateRunnable)
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Drag-to-adjust helpers (PURE FUNCTIONS, no init-order constraints)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Toast-like transient indicator anchored to the panel's parent FrameLayout.
     * Same visual style as the historical "Volume: 50%" / "Brightness: 25%" chips.
     */
    private fun showToolIndicator(text: String) {
        val host = onShowIndicator
        if (host != null) {
            // Host service owns the unified indicator (FloatingPanelService
            // showIndicator). Delegate for identical behavior; do not also render
            // a local copy.
            host(text)
            return
        }
        val root = parent as? android.widget.FrameLayout
        if (root != null) {
            if (panelIndicatorText == null) {
                val density = resources.displayMetrics.density
                panelIndicatorText = android.widget.TextView(context).apply {
                    setTextColor(android.graphics.Color.WHITE)
                    textSize = 14f
                    setPadding((INDICATOR_PADDING_H_DP * density).toInt(), (INDICATOR_PADDING_V_DP * density).toInt(), (INDICATOR_PADDING_H_DP * density).toInt(), (INDICATOR_PADDING_V_DP * density).toInt())
                    gravity = android.view.Gravity.CENTER
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(android.graphics.Color.parseColor("#E6303030"))
                        cornerRadius = (INDICATOR_CORNER_RADIUS_DP * density)
                    }
                    layoutParams = android.widget.FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
                        bottomMargin = (INDICATOR_BOTTOM_MARGIN_DP * density).toInt()
                    }
                    elevation = (INDICATOR_ELEVATION_DP * density)
                }
                root.addView(panelIndicatorText)
            }
            panelIndicatorText?.text = text
            panelIndicatorText?.visibility = View.VISIBLE
            panelIndicatorText?.alpha = 1f
            panelIndicatorText?.animate()?.cancel()
            panelIndicatorFadeRunnable?.let { panelHandler.removeCallbacks(it) }
            panelIndicatorFadeRunnable = Runnable {
                panelIndicatorText?.animate()
                    ?.alpha(0f)
                    ?.setDuration(INDICATOR_FADE_DURATION_MS)
                    ?.withEndAction { panelIndicatorText?.visibility = View.GONE }
                    ?.start()
            }
            panelHandler.postDelayed(panelIndicatorFadeRunnable!!, INDICATOR_SHOW_DURATION_MS)
        }
    }

    private fun showVolumeIndicator() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val current = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        val max = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
        val percent = if (max > 0) (current * 100) / max else 0
        val msg = context.getString(R.string.indicator_volume_percent, percent)
        // Single overlay indicator only (no per-tick Toast — that produced a
        // repeated popup on every drag step).
        showToolIndicator(msg)
    }

    private fun showBrightnessIndicator() {
        try {
            val cResolver = context.contentResolver
            val bri = android.provider.Settings.System.getInt(
                cResolver,
                android.provider.Settings.System.SCREEN_BRIGHTNESS,
                125
            )
            val msg = context.getString(R.string.indicator_brightness_percent, (bri * 100) / MAX_BRIGHTNESS)
            showToolIndicator(msg)
        } catch (e: Exception) {}
    }

    // Volume no longer accumulates — one drag tick is exactly one hardware
    // media step (see adjustVolumeByStep and VolumeStepMath). Brightness
    // still accumulates 1% steps, in this view's own accumulator (see
    // PanelPreferences.brightnessStepAccumulator).

    /** Live media-volume step count for this phone (e.g. 160, 15, 7). */
    private fun mediaMaxSteps(): Int {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        return audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
    }

    /**
     * Adjust media volume by `steps` whole hardware steps (sign indicates
     * direction). One drag tick is exactly one hardware step on every phone:
     * the drag distance per tick is adapted to the phone's own media step
     * count (see [VolumeStepMath]), so a 160-step phone moves 0.625% per
     * tick and a 15-step phone moves one 6.67% step per tick. The step is
     * applied by absolute index (see [MediaVolumeApplier]) because a
     * relative adjust moves more than one index on some phones — on this
     * OnePlus 13 one adjust call jumps ~10 indices (6%). No accumulation
     * — a step either lands or it doesn't (e.g. already at min/max).
     */
    private fun adjustVolumeByStep(steps: Int) {
        if (steps == 0) return
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        // The indicator is refreshed by the callback, not by the callers:
        // the shell fallback (if needed) lands on a background thread, and
        // the indicator must show the volume after it lands, not before.
        MediaVolumeApplier.apply(audioManager, steps) { showVolumeIndicator() }
    }

    /**
     * Adjust screen brightness by `deltaPercent` percent points (sign
     * indicates direction). 1% = 2.55 levels on the 0..255 scale; the
     * fractional part accumulates until it makes a whole level.
     * One Settings.System.putInt call.
     */
    private fun adjustBrightnessByPercent(deltaPercent: Int) {
        try {
            if (!android.provider.Settings.System.canWrite(context)) {
                // Throttle the prompt (see FloatingPanelService.adjustBrightness):
                // every brightness tick while the permission is missing would
                // otherwise re-launch the settings screen.
                if (panelPrefs.shouldPromptWriteSettings()) {
                    android.widget.Toast.makeText(
                        context,
                        context.getString(R.string.toast_write_settings_required),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                        data = android.net.Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
                return
            }
            val cResolver = context.contentResolver
            // Only writes when the mode differs, so a drag doesn't
            // permanently kill the user's auto-brightness.
            ensureManualBrightnessMode(cResolver)
            val current = android.provider.Settings.System.getInt(
                cResolver,
                android.provider.Settings.System.SCREEN_BRIGHTNESS,
                125
            )
            // 1% = 2.55 levels on the 0..255 scale; the fractional part
            // accumulates in this view's brightnessStepAccumulator (see
            // PanelPreferences) until it makes a whole level, so the applied
            // change averages exactly deltaPercent percent points.
            val units = panelPrefs.brightnessStepAccumulator.add(deltaPercent, MAX_BRIGHTNESS)
            if (units == 0) return
            // Clamp floor is 1, not 0: some OEMs treat 0 as "auto".
            val brightness = (current + units).coerceIn(1, MAX_BRIGHTNESS)
            // Steps the clamp swallowed are dropped, NOT refunded (see
            // FloatingPanelService.adjustBrightness): the brightness is
            // already at the stop, so nothing is owed, and refunding them
            // dead-zones the first reversal tick.
            android.provider.Settings.System.putInt(
                cResolver,
                android.provider.Settings.System.SCREEN_BRIGHTNESS,
                brightness
            )
            val floatVal = brightness / 255f
            try {
                android.provider.Settings.System.putFloat(
                    cResolver,
                    "screen_brightness_float",
                    floatVal
                )
            } catch (e: Exception) {
                try {
                    android.provider.Settings.System.putString(
                        cResolver,
                        "screen_brightness_float",
                        floatVal.toString()
                    )
                } catch (e2: Exception) {}
            }
            // Force a notification change so the system UI slider stays in sync
            // (mirrors FloatingPanelService.adjustBrightness).
            try {
                cResolver.notifyChange(android.provider.Settings.System.getUriFor(android.provider.Settings.System.SCREEN_BRIGHTNESS), null)
                cResolver.notifyChange(android.provider.Settings.System.getUriFor("screen_brightness_float"), null)
            } catch (e: Exception) {}
        } catch (e: Exception) {}
    }

    /**
     * Shared touch handler for the two drag-to-adjust buttons (Volume, Brightness).
     *
     * Behavior:
     *  • ACTION_DOWN  — record anchor; play scale pulse + brief haptic.
     *  • ACTION_MOVE  — translate the button's view up/down by ∆Y (capped at ±60dp visually).
     *                   For every `pixelsPerUnit` of vertical motion since the last
     *                   emission, fire [onStep] with direction = +1 if user dragged UP,
     *                   -1 if DOWN. Multiple units are coalesced so a fast flick still
     *                   feels responsive (mirrors the edge-slide accumulation).
     *  • ACTION_UP    — spring the button back to translationY = 0. If total travel is
     *                   less than tapSlop (8dp), call performClick() so the button's
     *                   OnClickListener runs the tap action — the same path a
     *                   TalkBack double-tap takes — and the button still works
     *                   for users who don't discover the drag gesture.
     *  • ACTION_CANCEL— snap back without firing anything (covers window-manager
     *                   panics / panel-close mid-touch).
     */
    // dragState layout:
    //   [0] = last rawY used for long-press cancel tracking
    //   [1] = last rawY that produced a step tick
    //   [2] = down rawY
    //   We also stash long-press armed state on the view tag to avoid expanding arrays.

    private fun handleDragTouch(
        view: View,
        event: android.view.MotionEvent,
        dragState: FloatArray,
        distancePerUnitDp: () -> Float,
        onStep: (direction: Int) -> Unit
    ): Boolean {
        val density = resources.displayMetrics.density
        // Sensitivity multiplier mirrors the edge-slide gesture: pixelsPerUnit =
        // distancePerUnitDp() * density * (100 / sensitivity). Higher sensitivity
        // (lower slideSensitivity value) → fewer pixels per unit → more steps.
        // The distance is a lambda so the volume spacing can track the phone's
        // live media step count (see VolumeStepMath); brightness is a constant.
        val multiplier = 100f / panelPrefs.slideSensitivity.coerceIn(1, 200)
        val pixelsPerUnit = (distancePerUnitDp() * density * multiplier).coerceAtLeast(1f)
        val tapSlopPx = 8f * density

        return when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                dragState[0] = event.rawY
                dragState[1] = event.rawY
                dragState[2] = event.rawY
                dragState[3] = 0f
                view.setTag(R.id.tag_drag_armed, false)
                // Cancel any previous arm callback.
                (view.getTag(R.id.tag_drag_arm_runnable) as? Runnable)?.let { view.removeCallbacks(it) }
                val arm = Runnable {
                    view.setTag(R.id.tag_drag_armed, true)
                    // Reset tick baseline at arm time so the first drag after hold is smooth.
                    dragState[1] = dragState[0]
                    if (panelPrefs.hapticEnabled) {
                        view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    }
                }
                view.setTag(R.id.tag_drag_arm_runnable, arm)
                view.postDelayed(arm, LONG_PRESS_DRAG_MS)
                if (panelPrefs.hapticEnabled) {
                    view.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                }
                SpringAnimator.scalePulse(view)
                true
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                dragState[0] = event.rawY
                val armed = view.getTag(R.id.tag_drag_armed) as? Boolean ?: false
                if (!armed) {
                    // Still waiting for the 600ms hold — cancel arm if finger wandered too far.
                    val travel = Math.abs(event.rawY - dragState[2])
                    if (travel > tapSlopPx * 2f) {
                        (view.getTag(R.id.tag_drag_arm_runnable) as? Runnable)?.let { armCb ->
                            view.removeCallbacks(armCb)
                            view.setTag(R.id.tag_drag_arm_runnable, null)
                            // One-shot reject feedback so the hold does not feel "dead".
                            if (panelPrefs.hapticEnabled) {
                                view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                            }
                        }
                    }
                    return true
                }
                // Armed: accumulate vertical travel and emit whole units, exactly
                // like the edge-slide gesture (EdgeHandleView). The accumulator
                // lives in this button's own dragState[3], so the two buttons
                // can't leak leftover travel into each other.
                // Pacing (see SlideStepMath): at most MAX_STEPS_PER_MOVE per
                // event — a fast flick or a coalesced MOVE used to dump a
                // whole slide's worth of steps in one frame, and the applier
                // landed them as one absolute set (~10% in one jump). The
                // remainder stays in dragState[3] and drains on later MOVEs
                // (capped at lift too, see ACTION_UP/CANCEL below).
                // The button also visually tracks the finger (±60dp) so the
                // drag feels connected; it springs back on UP/CANCEL.
                view.translationY = (event.rawY - dragState[2]).coerceIn(-60f * density, 60f * density)
                val dy = dragState[1] - event.rawY
                dragState[1] = event.rawY
                dragState[3] += dy
                val steps = SlideStepMath.stepsToEmit(dragState[3], pixelsPerUnit)
                if (steps != 0) {
                    val direction = if (steps > 0) +1 else -1
                    repeat(Math.abs(steps)) { onStep(direction) }
                    dragState[3] -= steps * pixelsPerUnit
                }
                true
            }
            android.view.MotionEvent.ACTION_UP -> {
                view.animate().translationY(0f).scaleX(1f).scaleY(1f).setDuration(SCALE_RESET_DURATION_MS).start()
                (view.getTag(R.id.tag_drag_arm_runnable) as? Runnable)?.let { view.removeCallbacks(it) }
                val armed = view.getTag(R.id.tag_drag_armed) as? Boolean ?: false
                val totalTravel = Math.abs(event.rawY - dragState[2])
                if (!armed && totalTravel < tapSlopPx) {
                    // Route the tap through performClick() so the
                    // OnClickListener (and TalkBack's double-tap, which
                    // calls performClick()) runs the same tap action.
                    view.performClick()
                }
                // Lift counts as one last MOVE: capped, and any larger
                // backlog is dropped (see SlideStepMath). Only when armed.
                if (armed) {
                    val left = SlideStepMath.stepsToEmit(dragState[3], pixelsPerUnit)
                    if (left != 0) {
                        val direction = if (left > 0) +1 else -1
                        repeat(Math.abs(left)) { onStep(direction) }
                    }
                }
                view.setTag(R.id.tag_drag_armed, false)
                dragState[3] = 0f
                true
            }
            android.view.MotionEvent.ACTION_CANCEL -> {
                view.animate().translationY(0f).scaleX(1f).scaleY(1f).setDuration(SCALE_RESET_DURATION_MS).start()
                (view.getTag(R.id.tag_drag_arm_runnable) as? Runnable)?.let { view.removeCallbacks(it) }
                val armed = view.getTag(R.id.tag_drag_armed) as? Boolean ?: false
                if (armed) {
                    val left = SlideStepMath.stepsToEmit(dragState[3], pixelsPerUnit)
                    if (left != 0) {
                        val direction = if (left > 0) +1 else -1
                        repeat(Math.abs(left)) { onStep(direction) }
                    }
                }
                view.setTag(R.id.tag_drag_armed, false)
                dragState[3] = 0f
                true
            }
            else -> false
        }
    }

    /** Show the system volume bar without changing the current level. */
    private fun showSystemVolumeBar() {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            audioManager.adjustStreamVolume(
                android.media.AudioManager.STREAM_MUSIC,
                android.media.AudioManager.ADJUST_SAME,
                android.media.AudioManager.FLAG_SHOW_UI
            )
        } catch (_: Exception) {}
    }

    /** Toggle automatic brightness mode. Requires WRITE_SETTINGS. */
    private fun toggleAutoBrightness() {
        try {
            if (!android.provider.Settings.System.canWrite(context)) {
                android.widget.Toast.makeText(
                    context,
                    context.getString(R.string.toast_write_settings_required),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                val intent = Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = android.net.Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return
            }
            val cResolver = context.contentResolver
            val current = android.provider.Settings.System.getInt(
                cResolver,
                android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,
                android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
            )
            val next = if (current == android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
                android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            else
                android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
            android.provider.Settings.System.putInt(
                cResolver,
                android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,
                next
            )
            val msg = if (next == android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
                context.getString(R.string.indicator_auto_brightness_on)
            else
                context.getString(R.string.indicator_auto_brightness_off)
            // Show the auto-brightness state INSIDE the overlay (same mechanism as
            // the dashboard brightness tool). A system Toast is a separate window
            // that renders BELOW the overlay, so it was effectively invisible to
            // the user when tapped from the sidebar button.
            showToolIndicator(msg)
        } catch (_: Exception) {}
    }

    /**
     * Mirror the app-list column count across to the tools grid below the list.
     *
     * Behavior:
     *  • [currentCols] == 1 → tools stack vertically (1 cell per row).
     *  • [currentCols] == 2 → tools flow into a 2-column grid; the divider
     *    spans both columns. SysInfo is now a standalone section outside the
     *    GridLayout, always centered regardless of column mode.
     *
     * REVISED STRATEGY (v2.0.0):
     *   GONE tool cells are removed from the GridLayout container entirely.
     *   Only VISIBLE tools plus the divider (if visible) participate in the
     *   grid, so GridLayout's measurement operates on exactly the children
     *   that contribute to layout. No magic row offsets needed.
     */
    /**
     * ALL KNOWN TOOL CELLS in a fixed, canonical order.
     * Using binding references directly (instead of iterating
     * container.getChildAt(i)) is immune to GridLayout's internal
     * child reordering that can happen when setLayoutParams is called.
     */
    private fun getToolCellList(): List<View> = listOf(
        binding.layoutPowerTools,
        binding.layoutVolumeTools,
        binding.layoutBrightnessTools,
        binding.layoutScreenshotTools,
        binding.layoutBlackScreenTools,
        binding.layoutLockScreenTools,
        binding.layoutContentPickerTools
    )

    /**
     * Rebuild the tools GridLayout to contain only VISIBLE tool cells plus the
     * divider (if visible). GONE cells are removed from the container entirely
     * and re-added on the next sync when toggled on. This avoids any magic
     * row/col offset (previously row=99) and lets GridLayout's measurement
     * operate on only the children that actually contribute to layout.
     */
    private fun syncToolsGridColumns() {
        try {
            syncToolsGridColumnsInner()
        } catch (e: Exception) {
            Log.w("SidePanelView", "syncToolsGridColumns failed", e)
        }
    }

    private fun syncToolsGridColumnsInner() {
        val container = binding.toolsContainer
        val FILL = android.widget.GridLayout.FILL
        val gridCols = currentCols.coerceIn(1, 2)

        // Do NOT set container.columnCount — it's only a default-placement hint
        // and does NOT constrain column widths. GridLayout calculates column widths
        // from children's actual columnSpec indices, not columnCount.

        // ── Divider: ensure it's in the container with correct span ──
        val divider = binding.toolsDivider
        if (divider.parent != container) {
            container.addView(divider, 0)
        }
        val divSpan = if (gridCols == 2) 2 else 1
        val divLp = divider.layoutParams as? android.widget.GridLayout.LayoutParams
            ?: android.widget.GridLayout.LayoutParams()
        divLp.rowSpec = android.widget.GridLayout.spec(0, 1, FILL, 0f)
        divLp.columnSpec = android.widget.GridLayout.spec(0, divSpan, FILL, 1.0f)
        divider.layoutParams = divLp

        // ── Tool cells: only VISIBLE ones stay in the container ──
        // Positions come from ToolsLayout.gridPositions (pure, unit-tested)
        // so the tested math is the production math, not a mirror of it.
        val allToolCells = getToolCellList()
        val rowOffset = if (divider.visibility == View.VISIBLE) 1 else 0
        val (positions, _) = ToolsLayout.gridPositions(
            visibility = allToolCells.map { it.visibility == View.VISIBLE },
            gridCols = gridCols,
            dividerVisible = divider.visibility == View.VISIBLE
        )
        var toolIdx = 0
        var posIdx = 0

        for (cell in allToolCells) {
            if (cell.visibility == View.VISIBLE) {
                // Ensure cell is in the container (in correct order)
                if (cell.parent != container) {
                    // Insert after divider (index = rowOffset)
                    container.addView(cell, rowOffset + toolIdx)
                } else {
                    // Already present — may need to be moved to correct position
                    val currentIndex = container.indexOfChild(cell)
                    val targetIndex = rowOffset + toolIdx
                    if (currentIndex != targetIndex) {
                        container.removeView(cell)
                        container.addView(cell, targetIndex)
                    }
                }

                val pos = positions[posIdx++]
                val lp = cell.layoutParams as? android.widget.GridLayout.LayoutParams
                    ?: android.widget.GridLayout.LayoutParams()
                lp.rowSpec = android.widget.GridLayout.spec(pos.row, 1, FILL, 0f)
                lp.columnSpec = android.widget.GridLayout.spec(pos.col, 1, FILL, 1.0f)
                lp.width = 0
                lp.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                cell.layoutParams = lp
                toolIdx++
            } else {
                // GONE: remove from container if present
                if (cell.parent == container) {
                    container.removeView(cell)
                }
            }
        }

        container.requestLayout()
    }


    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Sync tools grid columns now that the view is attached.
        // During init, syncToolsGridColumns() returns early because
        // isAttachedToWindow is false. This override catches that case.
        syncToolsGridColumns()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // Persist the final scroll position even if the panel closes
        // mid-scroll (before the idle write fires).
        flushSidebarScroll()
        updateHandler.removeCallbacks(updateRunnable)
        // The close button's rotation spring is never cancelled elsewhere:
        // stop it here so it cannot keep the detached view alive.
        springRotation.cancel()
        // Same for the panel's own open/close springs and any pending
        // posted open Runnable.
        SpringAnimator.cancel(this)
        // Round-12 audit L-Low: panelHandler drives showToolIndicator's
        // fade-out runnable. If the user drags the volume / brightness
        // button right before the service tears the panel down, the
        // pending 1.5s fade runnable keeps the indicator TextView alive
        // for that window. Sweeping callbacks here releases the indicator
        // reference immediately when the view leaves the window.
        panelHandler.removeCallbacksAndMessages(null)
        panelIndicatorFadeRunnable = null
        // Round-13 audit M2: the indicator TextView was added to `parent`
        // (the FrameLayout owned by FloatingPanelService) but never
        // explicitly removed. Holding the Kotlin reference keeps the View
        // pinned in memory after the panel is detached, and worse, on the
        // next attach a NEW TextView would be created while the old one
        // still floats around rootLayout accumulating memory for every
        // volume/brightness drag. Drop both.
        panelIndicatorText?.let { ind ->
            (parent as? android.widget.FrameLayout)?.removeView(ind)
        }
        panelIndicatorText = null
    }

    private companion object {
        const val TAG = "SidePanelView"
        // Indicator layout constants (dp)
        const val INDICATOR_PADDING_H_DP = 16
        const val INDICATOR_PADDING_V_DP = 10
        const val INDICATOR_BOTTOM_MARGIN_DP = 90
        const val INDICATOR_CORNER_RADIUS_DP = 24
        const val INDICATOR_ELEVATION_DP = 8

        // Brightness
        const val MAX_BRIGHTNESS = 255
        const val DEFAULT_BRIGHTNESS = 125

        // Memory monitoring
        const val MEMORY_UPDATE_INTERVAL_MS = 3000L

        // Recent section: cap the recent list to ~3 rows so it scrolls
        // internally instead of squeezing the main list / tools off-screen.
        const val RECENT_MAX_HEIGHT_DP = 220

        // Draggable separator between the app list and the Recent section
        // (side_panel_layout.xml): touch height, matching the XML.
        const val SEPARATOR_HEIGHT_DP = 16f

        // Fling-to-close guards: quiet period after opening (ms) and minimum
        // outward displacement (dp) required before a fling closes the panel.
        // Minimum outward velocity (dp/s), scaled by density at the call site.
        const val FLING_QUIET_PERIOD_MS = 500L
        const val FLING_MIN_DISPLACEMENT_DP = 48f
        const val FLING_MIN_VELOCITY_DP = 1200f
    }
}
