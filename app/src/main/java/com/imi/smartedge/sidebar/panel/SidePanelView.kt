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
    var onAppsChanged: (() -> Unit)? = null
    var onAddClick: ((Boolean) -> Unit)? = null
    var onScreenshot: (() -> Unit)? = null
    var onFolderOpen: ((String) -> Unit)? = null
    var onBackNavigation: (() -> Unit)? = null
    var onToolClick: ((String) -> Unit)? = null
    var onBlackScreen: (() -> Unit)? = null

    private val binding: SidePanelLayoutBinding = SidePanelLayoutBinding.inflate(LayoutInflater.from(context), this, true)
    private val adapter: PanelAppsAdapter
    private val panelPrefs = PanelPreferences(context)
    // Default to 1 column. Every write-path (setColumns, updateStyles) coerces via
    // coerceIn(1, 2) before this field sees a new value, so an out-of-range read
    // (e.g. a corrupt prefs value hand-edited through ADB) can never propagate into
    // GridLayoutManager.spanCount / toolsContainer.columnCount and crash the process.
    private var currentCols = 1
    private var isPickerOpenInternal = false

    // Track folder navigation
    private val navigationStack = java.util.ArrayDeque<String>()

    private val springRotation: SpringAnimation = SpringAnimation(binding.btnClose, SpringAnimation.ROTATION)

    private fun getFinalScaleFactor(): Float {
        return context.getAutoScalingFactor() * panelPrefs.scaleFactor
    }

    private val updateHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() {
            updateSystemInfo()
            updateHandler.postDelayed(this, 3000)
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
    private val volumeDragState = FloatArray(3)
    private val brightnessDragState = FloatArray(3)

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
            binding.tvRamUsage.text = "RAM: ${usedMegs}MB"

            val intent = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            val temp = intent?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
            binding.tvBatTemp.text = "BAT: ${temp / 10}°C"
        } catch (e: Exception) {}
    }

    private val gestureDetector = android.view.GestureDetector(context, object : android.view.GestureDetector.SimpleOnGestureListener() {
        override fun onFling(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            val isRight = panelPrefs.panelSide == PanelPreferences.SIDE_RIGHT
            if (Math.abs(velocityY) > Math.abs(velocityX)) return false
            if (isRight && velocityX > 1200f) {
                onClose?.invoke()
                return true
            } else if (!isRight && velocityX < -1200f) {
                onClose?.invoke()
                return true
            }
            return false
        }
    })

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        gestureDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    init {
        springRotation.spring = SpringForce().apply {
            dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
            stiffness = SpringForce.STIFFNESS_MEDIUM
        }

        binding.panelCard.setOnClickListener { }

        adapter = PanelAppsAdapter(
            context,
            onRemove = { removedApp ->
                panelPrefs.removeApp(removedApp.identifier)
                onAppsChanged?.invoke()
            },
            onAddClick = { isEdit -> onAddClick?.invoke(isEdit) },
            onAppLaunched = { onClose?.invoke() },
            onFolderClick = { folderId ->
                navigationStack.push(folderId)
                updateNavigationUI()
                onFolderOpen?.invoke(folderId)
            },
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

        binding.rvPanelApps.setHasFixedSize(false)
        binding.rvPanelApps.isNestedScrollingEnabled = false
        binding.rvPanelApps.setItemViewCacheSize(0)
        (binding.rvPanelApps.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = true
        binding.rvPanelApps.recycledViewPool.setMaxRecycledViews(0, 0)

        binding.rvPanelApps.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                if (panelPrefs.rememberScroll) {
                    panelPrefs.lastSidebarScroll = recyclerView.computeVerticalScrollOffset()
                }
            }
        })

        val itemTouchHelper = androidx.recyclerview.widget.ItemTouchHelper(object : androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(
            androidx.recyclerview.widget.ItemTouchHelper.UP or androidx.recyclerview.widget.ItemTouchHelper.DOWN or
            androidx.recyclerview.widget.ItemTouchHelper.LEFT or androidx.recyclerview.widget.ItemTouchHelper.RIGHT,
            0
        ) {
            override fun isLongPressDragEnabled(): Boolean {
                return adapter.isEditMode
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
                val apps = adapter.getApps()
                val identifiers = apps.map { it.identifier }

                panelPrefs.setPanelApps(identifiers)
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
        btnVolumeDragTouchListener = View.OnTouchListener { v, event ->
            handleDragTouch(
                view = v,
                event = event,
                dragState = volumeDragState,
                tickDistanceDp = 14f,
                onTap = {
                    // Tap → small +3% bump. Single-shot IPC.
                    adjustVolumeByPercent(3)
                    showVolumeIndicator()
                },
                onStep = { direction ->
                    // Each drag tick asks for ±3% relative to current — one IPC per
                    // coalesced tick (the previous code called adjustStreamVolume in a
                    // repeat-abs-loop, spamming N binder calls per frame).
                    adjustVolumeByPercent(direction * 3)
                    showVolumeIndicator()
                }
            )
        }
        btnBrightnessDragTouchListener = View.OnTouchListener { v, event ->
            handleDragTouch(
                view = v,
                event = event,
                dragState = brightnessDragState,
                tickDistanceDp = 6f,
                onTap = { adjustBrightnessByPercent(6); showBrightnessIndicator() },
                onStep = { direction ->
                    // 5% per drag tick so a full screen-height of finger travel covers
                    // the [0..255] brightness range. The tap path bumps +6%.
                    adjustBrightnessByPercent(direction * 5)
                    showBrightnessIndicator()
                }
            )
        }
        binding.btnVolumeDrag.setOnTouchListener(btnVolumeDragTouchListener)
        binding.btnBrightnessDrag.setOnTouchListener(btnBrightnessDragTouchListener)

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
        syncToolsGridColumns()

        applyTheme()
    }

    fun updateSideLayout() {
        val isRight = panelPrefs.panelSide == PanelPreferences.SIDE_RIGHT
        binding.btnClose.rotation = if (isRight) 180f else 0f

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
        // Top Padding (12) + Bottom Padding (4) + Tools Margin (4) + Close Btn (48) = 68dp
        var nonAppHeightDp = 68f

        val isGameMode = false // panelPrefs.getGameApps().contains(panelPrefs.currentForegroundPackage)
        val showSysInfoEffective = panelPrefs.showSysInfo || isGameMode

        if (panelPrefs.showTools && navigationStack.isEmpty()) {
            // In 2-column mode tools share a row, so per-tool allocation is roughly
            // halved vertically. Keep the 1-col layout generous (84dp) for the
            // original icon + label stack.
            val perToolRowDp = if (currentCols == 2) 60f else 84f
            nonAppHeightDp += 50f // Divider cell
            if (panelPrefs.showScreenshotTool) nonAppHeightDp += perToolRowDp
            if (panelPrefs.showBlackScreenTool) nonAppHeightDp += perToolRowDp
            if (panelPrefs.showPowerMenu) nonAppHeightDp += perToolRowDp
            if (showSysInfoEffective) nonAppHeightDp += 30f
            if (panelPrefs.showVolumeKeys) nonAppHeightDp += perToolRowDp
            if (panelPrefs.showBrightnessKeys) nonAppHeightDp += perToolRowDp
        }

        // Maximum allowed height for RV to keep panel within screen (with 24dp safety margin)
        val maxAllowedRvHeightDp = (screenHeightDp - nonAppHeightDp - 24).coerceAtLeast(100f)
        val targetRvHeightDp = panelPrefs.panelMaxHeight.toFloat().coerceAtMost(maxAllowedRvHeightDp)

        // Apply dynamic height using ConstraintLayout's max-height constraint instead of brittle manual item height guessing
        val rvLp = binding.rvPanelApps.layoutParams
        val maxRvHeightPx = context.dpToPx((targetRvHeightDp * scale).toInt())

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

        // Refresh tools-grid column mirror so the tools layout tracks the app column count.
        syncToolsGridColumns()
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

    fun setColumns(cols: Int) {
        // Round-13 audit M1: AOSP GridLayoutManager(0) throws IllegalArgumentException
        // ("Span count must be at least 1") and a (columnCount > 0) check on the tools
        // GridLayout would crash the same way via setColumnCount. coerceIn(1, 2)
        // guarantees neither receives a degenerate value, regardless of what
        // FloatingPanelService / SavedInstanceState data pass in.
        val safe = cols.coerceIn(1, 2)
        currentCols = safe
        adapter.setColumns(safe)
        (binding.rvPanelApps.layoutManager as? GridLayoutManager)?.spanCount = safe
        syncToolsGridColumns()
        updateSideLayout()
    }

    fun setEditButtonVisible(visible: Boolean) {
        adapter.setShowAddButton(visible)
        updateSideLayout()
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

            // Restore scroll position if enabled (only for root level)
            if (panelPrefs.rememberScroll && navigationStack.isEmpty()) {
                binding.rvPanelApps.post {
                    binding.rvPanelApps.scrollBy(0, panelPrefs.lastSidebarScroll)
                }
            } else {
                binding.rvPanelApps.post {
                    binding.rvPanelApps.scrollToPosition(0)
                }
            }

            onComplete?.invoke()
        }
    }

    fun updateStyles() {
        if (!isPickerOpenInternal) {
            val isGameMode = false // panelPrefs.getGameApps().contains(panelPrefs.currentForegroundPackage)
            // Round-13 audit M1: also coerce here so a stale or hand-edited
            // panelColumns preference cannot drive a 0/3 syncToolsGridColumns call.
            currentCols = (if (isGameMode) 2 else panelPrefs.panelColumns).coerceIn(1, 2)
            (binding.rvPanelApps.layoutManager as? GridLayoutManager)?.spanCount = currentCols
            adapter.setColumns(currentCols)
            syncToolsGridColumns()
        }
        applyTheme()
        updateSideLayout()
    }

    fun refreshIcons() {
        adapter.refreshIcons()
    }

    fun applyTheme() {
        val inFolder = navigationStack.isNotEmpty()
        val showTools = panelPrefs.showTools && !inFolder
        binding.toolsContainer.visibility = if (showTools) View.VISIBLE else View.GONE

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
                    val accent = try { Color.parseColor(panelPrefs.accentColor) } catch (e: Exception) { Color.parseColor("#4A9EFF") }
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

            // Force white/light icons and text for dark floating panel
            val iconColorList = ColorStateList.valueOf(Color.WHITE)
            binding.btnClose.imageTintList = iconColorList
            binding.btnScreenshot.imageTintList = iconColorList
            binding.btnVolumeDrag.imageTintList = iconColorList
            binding.btnBrightnessDrag.imageTintList = iconColorList
            binding.btnReboot.imageTintList = iconColorList
            binding.btnBlackScreen.imageTintList = iconColorList
            binding.btnBack.imageTintList = iconColorList

            binding.tvRamUsage.setTextColor(Color.WHITE)
            binding.tvBatTemp.setTextColor(Color.WHITE)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                binding.panelCard.clipToOutline = true
            }
        }

        val isGameMode = false // panelPrefs.getGameApps().contains(panelPrefs.currentForegroundPackage)
        val showSysInfoEffective = panelPrefs.showSysInfo || isGameMode

        binding.layoutSysInfo.visibility = if (showSysInfoEffective) View.VISIBLE else View.GONE

        // Final visibility check for tools container: hide if all sub-elements are gone
        val hasAnyVisibleTool = showPower || showVolume || showBrightness || showScreenshot || showBlackScreen || showSysInfoEffective
        binding.toolsContainer.visibility = if (showTools && hasAnyVisibleTool) View.VISIBLE else View.GONE

        // Sync tools grid columns after theme-related visibility changes since they
        // can affect the visible cell count.
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
        val root = parent as? android.widget.FrameLayout
        if (root != null) {
            if (panelIndicatorText == null) {
                val density = resources.displayMetrics.density
                panelIndicatorText = android.widget.TextView(context).apply {
                    setTextColor(android.graphics.Color.WHITE)
                    textSize = 14f
                    setPadding((16 * density).toInt(), (10 * density).toInt(), (16 * density).toInt(), (10 * density).toInt())
                    gravity = android.view.Gravity.CENTER
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(android.graphics.Color.parseColor("#E6303030"))
                        cornerRadius = 24f * density
                    }
                    layoutParams = android.widget.FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
                        bottomMargin = (90 * density).toInt()
                    }
                    elevation = 8f * density
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
                    ?.setDuration(300)
                    ?.withEndAction { panelIndicatorText?.visibility = View.GONE }
                    ?.start()
            }
            panelHandler.postDelayed(panelIndicatorFadeRunnable!!, 1500)
        }
    }

    private fun showVolumeIndicator() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val current = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        val max = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
        val percent = if (max > 0) (current * 100) / max else 0
        showToolIndicator("Volume: $percent%")
    }

    private fun showBrightnessIndicator() {
        try {
            val cResolver = context.contentResolver
            val bri = android.provider.Settings.System.getInt(
                cResolver,
                android.provider.Settings.System.SCREEN_BRIGHTNESS,
                125
            )
            showToolIndicator("Brightness: ${(bri * 100) / 255}%")
        } catch (e: Exception) {}
    }

    /**
     * Set media volume to an absolute percentage in [0..100]. Single-shot IPC.
     * Replaces the legacy `adjustStreamVolume`-in-a-loop pattern that produced N
     * synchronous binder calls per drag frame.
     */
    private fun setVolumePercent(percent: Int) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val max = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
        if (max <= 0) return
        val target = ((percent.coerceIn(0, 100) / 100f) * max).toInt()
        audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, target, 0)
    }

    /**
     * Adjust media volume by `delta` percent (sign indicates direction). One IPC.
     */
    private fun adjustVolumeByPercent(delta: Int) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val max = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
        if (max <= 0) return
        val current = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        // Audit L1 — the previous code translated percent → index via `(percent*max)/100`
        // and read back the new index every tick. For standard 15-step streams, a
        // 3% delta round-trips to indices 7 → 7.35 → 7, so the volume visibly froze.
        // Compute the index step directly and enforce at least ±1 step per gesture.
        val step = if (delta > 0) Math.max(1, (max * delta) / 100)
                   else Math.min(-1, (max * delta) / 100)
        audioManager.setStreamVolume(
            android.media.AudioManager.STREAM_MUSIC,
            (current + step).coerceIn(0, max),
            0
        )
    }

    /**
     * Adjust screen brightness by `deltaPercent` percent (sign indicates direction).
     * One Settings.System.putInt call.
     */
    private fun adjustBrightnessByPercent(deltaPercent: Int) {
        try {
            if (!android.provider.Settings.System.canWrite(context)) {
                android.widget.Toast.makeText(
                    context,
                    "Requires 'Write System Settings' permission",
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
            android.provider.Settings.System.putInt(
                cResolver,
                android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,
                android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            val current = android.provider.Settings.System.getInt(
                cResolver,
                android.provider.Settings.System.SCREEN_BRIGHTNESS,
                125
            )
            val deltaUnits = (deltaPercent * 255) / 100
            val brightness = (current + deltaUnits).coerceIn(0, 255)
            android.provider.Settings.System.putInt(
                cResolver,
                android.provider.Settings.System.SCREEN_BRIGHTNESS,
                brightness
            )
            try {
                android.provider.Settings.System.putFloat(
                    cResolver,
                    "screen_brightness_float",
                    brightness / 255f
                )
            } catch (e: Exception) {}
        } catch (e: Exception) {}
    }

    /**
     * Shared touch handler for the two drag-to-adjust buttons (Volume, Brightness).
     *
     * Behavior:
     *  • ACTION_DOWN  — record anchor; play scale pulse + brief haptic.
     *  • ACTION_MOVE  — translate the button's view up/down by ∆Y (capped at ±60dp visually).
     *                   For every `tickDistanceDp * density` of vertical motion since the
     *                   last tick, fire [onStep] with direction = +1 if user dragged UP,
     *                   -1 if DOWN. Multiple steps are coalesced so a fast flick still
     *                   feels responsive.
     *  • ACTION_UP    — spring the button back to translationY = 0. If total travel is
     *                   less than tapSlop (8dp), fire [onTap] once so the button still
     *                   works for users who don't discover the drag gesture.
     *  • ACTION_CANCEL— snap back without firing anything (covers window-manager
     *                   panics / panel-close mid-touch).
     */
    private fun handleDragTouch(
        view: View,
        event: android.view.MotionEvent,
        dragState: FloatArray,
        tickDistanceDp: Float,
        onTap: () -> Unit,
        onStep: (direction: Int) -> Unit
    ): Boolean {
        val density = resources.displayMetrics.density
        val tickPx = tickDistanceDp * density
        val maxVisualTravelPx = 60f * density
        val tapSlopPx = 8f * density

        return when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                dragState[0] = event.rawY
                dragState[1] = event.rawY
                dragState[2] = event.rawY
                if (panelPrefs.hapticEnabled) {
                    view.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                }
                SpringAnimator.scalePulse(view)
                true
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                // Positive dy = finger went UP (rawY decreased) = increase.
                val dy = dragState[0] - event.rawY
                view.translationY = dy.coerceIn(-maxVisualTravelPx, maxVisualTravelPx)

                val sinceLastTick = dragState[1] - event.rawY
                if (Math.abs(sinceLastTick) >= tickPx) {
                    val direction = if (sinceLastTick > 0f) +1 else -1
                    // Coalesce multiple ticks per move event so a fast flick still
                    // reaches its full delta (instead of getting clamped to ±1 step).
                    val ticks = (Math.abs(sinceLastTick) / tickPx).toInt().coerceAtLeast(1)
                    repeat(ticks) { onStep(direction) }
                    dragState[1] = event.rawY
                }
                true
            }
            android.view.MotionEvent.ACTION_UP -> {
                SpringAnimation(
                    view,
                    androidx.dynamicanimation.animation.DynamicAnimation.TRANSLATION_Y
                ).animateToFinalPosition(0f)
                view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()

                val totalTravel = Math.abs(event.rawY - dragState[2])
                if (totalTravel < tapSlopPx) onTap()
                true
            }
            android.view.MotionEvent.ACTION_CANCEL -> {
                view.translationY = 0f
                view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                true
            }
            else -> false
        }
    }

    /**
     * Mirror the app-list column count across to the tools grid below the list.
     *
     * Behavior:
     *  • [currentCols] == 1 → tools stack vertically (1 cell per row).
     *  • [currentCols] == 2 → tools flow into a 2-column grid; the divider and
     *    the System Info cell each span both columns so their full-width visual
     *    treatment isn't stranded on one side.
     *
     * ROUND-16 (FINAL) — pre-emptive span downgrade strategy:
     *
     * ROOT CAUSE (confirmed across OEM GridLayout forks):
     *   AOSP [GridLayout.Axis.setCount] validates the new columnCount against
     *   every child's resolved start index + span via [getMaxIndex()]. Once a
     *   layout pass resolves children's [UNDEFINED] start values to concrete
     *   positions, [setColumnCount] throws [IllegalArgumentException] if those
     *   resolved indices don't fit. Additionally, many OEM GridLayout
     *   implementations cache [mMaxIndex] and fail to invalidate it on
     *   [removeAllViews()], so even a detach→setCount→reattach cycle crashes.
     *
     * FIX (single deterministic pass):
     *   1. If columnCount already matches target → no-op (avoid redundant work).
     *   2. Downgrade ALL children's [columnSpec] to span=1 BEFORE calling
     *      [setColumnCount]. Since every child now claims span=1 with
     *      UNDEFINED start, the maximum possible index is always ≤ childCount,
     *      and any target ≥ 1 satisfies the validation.
     *   3. Set [columnCount] — guaranteed safe because no child claims span > 1.
     *   4. If target == 2, re-apply span=2 overrides for divider+SysInfo.
     *
     * No try-catch, no detach-reattach. One linear, crash-proof sequence.
     */
    private fun syncToolsGridColumns() {
        val target = currentCols.coerceIn(1, 2)
        val container = binding.toolsContainer

        val FILL = android.widget.GridLayout.FILL

        val needsColumnCountChange = container.columnCount != target

        // Step 1: Downgrade ALL children to span=1 FIRST.
        // This guarantees that after setColumnCount, no child claims a span
        // greater than 1, so GridLayout's getMaxIndex() validation always
        // passes regardless of OEM cache bugs or resolved start positions.
        //
        // VISIBLE children → FILL + weight=1 (normal grid cell).
        // GONE children    → 0-weight, 0×0 size so they collapse and don't
        //                    reserve grid cells (fixes the "empty cell gap"
        //                    when a tool is disabled in settings).
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i) ?: continue
            val lp = child.layoutParams as? android.widget.GridLayout.LayoutParams
                ?: android.widget.GridLayout.LayoutParams().apply {
                    width = 0; height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                }
            if (child.visibility == View.GONE) {
                lp.columnSpec = android.widget.GridLayout.spec(
                    android.widget.GridLayout.UNDEFINED,
                    1,
                    0f
                )
                lp.width = 0
                lp.height = 0
            } else {
                lp.columnSpec = android.widget.GridLayout.spec(
                    android.widget.GridLayout.UNDEFINED,
                    1,
                    FILL,
                    1.0f
                )
                // Detect stale 0×0 dimensions left over from a previous GONE
                // state (syncToolsGridColumns zaps GONE children to 0×0).
                // Restore them to XML-equivalent defaults (0dp width + weight
                // = fill column; WRAP_CONTENT height) so the tool becomes
                // visible again after the user toggles it back on. We MUST NOT
                // unconditionally overwrite width/height on every VISIBLE child
                // because that breaks the GridLayout's internal measurement
                // cache and collapses cell allocations.
                if (lp.width == 0 && lp.height == 0) {
                    lp.width = 0
                    lp.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                }
            }
            child.layoutParams = lp
        }

        // Step 2: Only change column count if actually different (avoids
        // unnecessary re-layout), but ALWAYS proceed to step 3 so that
        // visibility changes from applyTheme() update columnSpec.
        if (needsColumnCountChange) {
            container.columnCount = target
        }

        // Step 3: Apply span=2 overrides only when expanding to 2 columns.
        refreshColumnSpanOverrides(target)
    }

    /**
     * Apply span=2 overrides for the full-width cells
     * (divider and System Info) in 2-column mode.
     */
    private fun refreshColumnSpanOverrides(target: Int) {
        if (target != 2) return
        val FILL = android.widget.GridLayout.FILL
        binding.toolsDivider?.let { divider ->
            val lp = divider.layoutParams as? android.widget.GridLayout.LayoutParams ?: return@let
            lp.columnSpec = android.widget.GridLayout.spec(
                android.widget.GridLayout.UNDEFINED,
                2,
                FILL,
                1.0f
            )
            divider.layoutParams = lp
        }
        val siLp = binding.layoutSysInfo.layoutParams as? android.widget.GridLayout.LayoutParams
        if (siLp != null) {
            siLp.columnSpec = android.widget.GridLayout.spec(
                android.widget.GridLayout.UNDEFINED,
                2,
                FILL,
                1.0f
            )
            binding.layoutSysInfo.layoutParams = siLp
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        updateHandler.removeCallbacks(updateRunnable)
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
}
