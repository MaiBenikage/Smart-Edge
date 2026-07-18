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
            },
            onToolDrag = { toolId, direction ->
                when (toolId) {
                    "smartedge.tool.volume_up" -> {
                        adjustVolumeByPercent(direction * 3)
                        showVolumeIndicator()
                    }
                    "smartedge.tool.brightness_up" -> {
                        adjustBrightnessByPercent(direction * 5)
                        showBrightnessIndicator()
                    }
                }
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

        binding.btnLockScreen.setOnClickListener {
            if (panelPrefs.hapticEnabled) {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
            }
            SpringAnimator.scalePulse(it)
            onToolClick?.invoke("smartedge.tool.lockscreen")
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
            val enabledTools: List<Boolean> = listOf(
                panelPrefs.showScreenshotTool,
                panelPrefs.showBlackScreenTool,
                panelPrefs.showPowerMenu,
                panelPrefs.showVolumeKeys,
                panelPrefs.showBrightnessKeys,
                panelPrefs.showLockScreenTool
            )
            val enabledCount = enabledTools.count { it }
            val hasStandardTools = enabledCount > 0

            // Only allocate height for tools section if something is actually
            // visible (either standard tools or SysInfo). This prevents
            // compressing the RecyclerView when the tools container is GONE
            // (e.g., all individual tools disabled but showTools master ON).
            if (hasStandardTools || showSysInfoEffective) {
                // In 2-col mode two tools share a single GridLayout row.
                // Actual cell height: 32dp button + 2dp gap + ~12dp label + 4dp margin ≈ 50dp
                val perToolRowDp = 72f

                if (hasStandardTools) {
                    // Divider: 1dp line + 8dp bottom margin = 9dp
                    nonAppHeightDp += 9f
                    val toolRows = if (currentCols == 2) {
                        (enabledCount + 1) / 2  // ceil division
                    } else {
                        enabledCount
                    }
                    nonAppHeightDp += perToolRowDp * toolRows
                }

                if (showSysInfoEffective) nonAppHeightDp += 30f
            }
        }

        // Maximum allowed height for RV to keep panel within screen (with 24dp safety margin)
        val maxAllowedRvHeightDp = (screenHeightDp - nonAppHeightDp - 24).coerceAtLeast(100f)
        val targetRvHeightDp = panelPrefs.panelMaxHeight.toFloat().coerceAtMost(maxAllowedRvHeightDp)

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
            currentCols = (if (isGameMode) 2 else panelPrefs.panelColumns).coerceIn(1, 2)
            (binding.rvPanelApps.layoutManager as? GridLayoutManager)?.spanCount = currentCols
            adapter.setColumns(currentCols)
            applyTheme()
            updateSideLayout()
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

        val showLockScreen = panelPrefs.showLockScreenTool
        binding.layoutLockScreenTools.visibility = if (showLockScreen) View.VISIBLE else View.GONE

        // Hide divider when no standard interactive tools are visible
        // (only SysInfo enabled = no divider needed)
        val hasStandardTools = showPower || showVolume || showBrightness || showScreenshot || showBlackScreen || showLockScreen
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
            // btnClose and btnBack still use ImageButton with drawable icons
            binding.btnClose.imageTintList = iconColorList
            binding.btnBack.imageTintList = iconColorList
            // Tool buttons now use Button with emoji text — set text color
            binding.btnScreenshot.setTextColor(Color.WHITE)
            binding.btnVolumeDrag.setTextColor(Color.WHITE)
            binding.btnBrightnessDrag.setTextColor(Color.WHITE)
            binding.btnReboot.setTextColor(Color.WHITE)
            binding.btnBlackScreen.setTextColor(Color.WHITE)
            binding.btnLockScreen.setTextColor(Color.WHITE)

            binding.tvRamUsage.setTextColor(Color.WHITE)
            binding.tvBatTemp.setTextColor(Color.WHITE)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                binding.panelCard.clipToOutline = true
            }
        }

        val isGameMode = false // panelPrefs.getGameApps().contains(panelPrefs.currentForegroundPackage)
        val showSysInfoEffective = panelPrefs.showSysInfo || isGameMode

        // SysInfo is now a standalone section outside the tools GridLayout,
        // so it's always centered regardless of the tools column mode.
        binding.layoutSysInfo.visibility = if (showTools && showSysInfoEffective) View.VISIBLE else View.GONE

        // Tools container only depends on standard interactive tools.
        // SysInfo is independent and doesn't affect tools grid visibility.
        binding.toolsContainer.visibility = if (showTools && hasStandardTools) View.VISIBLE else View.GONE

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
                // Note: translationY visual movement intentionally removed —
                // the button stays in place; only the drag delta is tracked
                // for tick detection.

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
                view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()

                val totalTravel = Math.abs(event.rawY - dragState[2])
                if (totalTravel < tapSlopPx) onTap()
                true
            }
            android.view.MotionEvent.ACTION_CANCEL -> {
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
     *  • [currentCols] == 2 → tools flow into a 2-column grid; the divider
     *    spans both columns. SysInfo is now a standalone section outside the
     *    GridLayout, always centered regardless of column mode.
     *
     * REVISED STRATEGY (v1.6.0):
     *   The fundamental problem with GridLayout + GONE children is that
     *   even though GONE children can be skipped in layout measurement,
     *   the auto-positioning algorithm (spec(UNDEFINED, ...)) still
     *   advances the internal cell-position counter per-child-index,
     *   not per-visible-child. In 2-column mode this causes VISIBLE
     *   tools to land at non-consecutive rows, creating empty gaps in
     *   the grid and corrupting the height calculation.
     *
     *   FIX: Instead of leaving GONE children to confuse GridLayout's
     *   auto-positioning, we now assign EXPLICIT row/col positions to
     *   every VISIBLE tool child so they pack into consecutive rows.
     *   GONE children are told to occupy row=99 (outside the visible
     *   area) with 0x0 dimensions, so they never affect the grid.
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
        binding.layoutLockScreenTools
    )

    private fun syncToolsGridColumns() {
        if (!isAttachedToWindow) return

        val container = binding.toolsContainer
        val FILL = android.widget.GridLayout.FILL

        val gridCols = if (isPickerOpenInternal) 1 else panelPrefs.panelColumns.coerceIn(1, 2)

        // CLEAR AND RE-ADD strategy: removeAllViews() + sequential addView()
        // completely eliminates GridLayout's internal child-reorder bugs.
        // Previously, iterating getChildAt(i) to reset spans triggered
        // GridLayout's internal reorder, corrupting positions before the
        // explicit row/col assignment could run.
        container.removeAllViews()
        container.columnCount = gridCols  // safe on empty container

        // Re-add divider first (row 0) if visible
        val divider = binding.toolsDivider
        if (divider.visibility == View.VISIBLE) {
            val span = if (gridCols == 2) 2 else 1
            val divLp = android.widget.GridLayout.LayoutParams(
                android.widget.GridLayout.spec(0, span, FILL, 1.0f),
                android.widget.GridLayout.spec(0, 1, FILL, 0f)
            )
            divider.layoutParams = divLp
            container.addView(divider)
        }

        // Re-add ONLY visible tools with explicit row/col positions
        val rowOffset = if (divider.visibility == View.VISIBLE) 1 else 0
        val allToolCells = getToolCellList()
        var toolIdx = 0

        for (cell in allToolCells) {
            if (cell.visibility == View.VISIBLE) {
                val row = rowOffset + if (gridCols == 2) toolIdx / 2 else toolIdx
                val col = if (gridCols == 2) toolIdx % 2 else 0
                val lp = android.widget.GridLayout.LayoutParams(
                    android.widget.GridLayout.spec(col, 1, FILL, 1.0f),
                    android.widget.GridLayout.spec(row, 1, FILL, 0f)
                )
                lp.width = 0
                lp.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                cell.layoutParams = lp
                container.addView(cell)
                toolIdx++
            }
            // GONE tools are simply not added — they take zero space.
        }

        container.requestLayout()
    }

    // refreshColumnSpanOverrides removed in v1.6.0 — its logic is now
    // inlined directly in syncToolsGridColumns() via the dividerViews
    // explicit-position block.

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
