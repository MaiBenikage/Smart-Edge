package com.imi.smartedge.sidebar.panel

import android.content.Context
import android.graphics.Color
import android.os.Build
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.util.Log
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 4-tab floating picker panel (APPS / ACTIVITIES / SHORTCUTS / URLS).
 *
 * Layout strategy:
 *   - Single RecyclerView with multiple view types (sealed class [PickerItem]).
 *   - Tree-style lazy expansion for the ACTIVITIES and SHORTCUTS tabs: only
 *     expanded packages have their children rendered.
 *   - The URLS tab uses drag-to-reorder (ItemTouchHelper, initiated from the
 *     drag handle on the left of each row) and swipe-to-delete (whole row).
 *   - Each URLS row has an in-place edit mode (single row at a time); tapping
 *     the edit button on a different row auto-saves the previous one.
 *
 * Sync with sidebar is handled through [onToggleApp] (for APPS/ACTIVITIES/SHORTCUTS)
 * and the new [onAddCustomItem] / [onUpdateCustomItem] / [onRemoveCustomItem]
 * callbacks (for URLS). [FloatingPanelService] is the wiring host.
 */
class AppPickerPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    // ====================================================================================
    //                                   Tabs
    // ====================================================================================
    enum class PickerTab { APPS, ACTIVITIES, SHORTCUTS, CUSTOM }

    // ====================================================================================
    //                                Public callbacks
    // ====================================================================================
    var onClose: (() -> Unit)? = null
    var onAppLaunched: (() -> Unit)? = null
    /** Fired whenever the picker's EDIT/DONE state changes, so the sidebar can
     *  enable/disable long-press drag-to-reorder (ItemTouchHelper) to match. */
    var onEditModeChanged: ((Boolean) -> Unit)? = null

    /** Fired when a user toggles a regular app, activity, or shortcut in edit mode. */
    var onToggleApp: ((AppInfo, Boolean) -> Unit)? = null

    /** Fired when the user adds a new custom item (URLS tab, + ADD). */
    var onAddCustomItem: ((CustomItem) -> Unit)? = null

    /** Fired when a custom item's title/content are saved. */
    var onUpdateCustomItem: ((CustomItem) -> Unit)? = null

    /** Fired when a custom item is removed (swipe or future "delete" button). */
    var onRemoveCustomItem: ((String) -> Unit)? = null

    /** Round-7 U3: fired after a drag-and-drop reorder of URLS-tab items is
     *  persisted. The hosting service should refresh the sidebar so the user
     *  sees the new positions immediately rather than after the next panel
     *  close-open cycle. */
    var onCustomItemsReordered: (() -> Unit)? = null

    // ====================================================================================
    //                                    State
    // ====================================================================================
    var isEditMode = false
        private set

    private var activeTab: PickerTab = PickerTab.APPS
    private val expandedPackages = linkedSetOf<String>()
    // Per-item editing state for custom tab
    private var editingCustomId: String? = null

    // In-progress edit cache, keyed by item id. A TextWatcher on each
    // CustomRow's etTitle/etContent writes here on every keystroke so that
    // the live custom-item edit flow can recover the user's typed text even
    // when the row has scrolled offscreen and its ViewHolder has been
    // recycled (in which case [findViewHolderForAdapterPosition] returns null).
    private val pendingCustomEdits = mutableMapOf<String, Pair<String, String>>()

    // Unified lazy data for the APPS / ACTIVITIES / SHORTCUTS tabs: one shared
    // cache (loaded in parallel on first use) instead of three separate lists,
    // so the three tabs always show consistent data with a single refresh path.
    // CUSTOM-tab items are user data from SharedPreferences and stay separate.
    private var pickerData: AppRepository.PickerData? = null
    private var customItems: MutableList<CustomItem> = mutableListOf()

    // ====================================================================================
    //                                    Views
    // ====================================================================================
    private val pickerPanelCard: View
    private val rvPickerGrid: RecyclerView
    private val etSearch: EditText
    private val btnSettings: ImageButton
    private val btnEdit: TextView          // tab-aware: "EDIT" or "DONE"
    private val btnAdd: TextView           // custom tab only: "+ ADD"
    private val tvHeader: TextView
    private val tabApps: TextView
    private val tabActivities: TextView
    private val tabShortcuts: TextView
    private val tabCustom: TextView
    private val tabViews: List<Pair<PickerTab, TextView>>

    private val adapter = PickerAdapter()
    private val notificationAdapter = NotificationStripAdapter()
    private val repository = AppRepository(context)
    private val panelPrefs = PanelPreferences(context)

    private var _scope = CoroutineScope(Dispatchers.Main + Job())
    // Audit fix: search previously rebuilt the whole flattened list on
    // every keystroke with no debounce. A 200ms debounce coalesces fast
    // typing into a single rebuild; the runnable is cancelled on detach
    // so it cannot fire after the view leaves the window.
    private val searchHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val searchDebounceRunnable = Runnable { rebuildAndSubmit() }
    private var itemTouchHelper: ItemTouchHelper? = null
    private var lastMaxPx: Int = -1
    private var notificationChangedListener: (() -> Unit)? = null
    private var accentColor: Int = Color.parseColor("#4A9EFF")
    // `lateinit` was emitting "Lateinit is unnecessary" since [gestureDetector]
    // is always assigned in [init]. Plain nullable `var` removes the warning
    // and lets ID-recycled callers use `gestureDetector?.onTouchEvent(...)`.
    private var gestureDetector: GestureDetector? = null

    // ====================================================================================
    //                                Lifecycle
    // ====================================================================================
    init {
        val view = LayoutInflater.from(context).inflate(R.layout.picker_panel_layout, this, true)
        pickerPanelCard = view.findViewById(R.id.pickerPanelCard)
        rvPickerGrid = view.findViewById(R.id.rvPickerGrid)
        etSearch = view.findViewById(R.id.etPickerSearch)
        btnSettings = view.findViewById(R.id.btnPickerClose)
        btnEdit = view.findViewById(R.id.btnPickerEdit)
        btnAdd = view.findViewById(R.id.btnPickerAdd)
        tvHeader = view.findViewById(R.id.tvPickerHeader)
        tabApps = view.findViewById(R.id.tabApps)
        tabActivities = view.findViewById(R.id.tabActivities)
        tabShortcuts = view.findViewById(R.id.tabShortcuts)
        tabCustom = view.findViewById(R.id.tabCustom)
        tabViews = listOf(
            PickerTab.APPS to tabApps,
            PickerTab.ACTIVITIES to tabActivities,
            PickerTab.SHORTCUTS to tabShortcuts,
            PickerTab.CUSTOM to tabCustom
        )

        // Tabs
        tabViews.forEach { (tab, view2) ->
            view2.setOnClickListener { switchTab(tab) }
        }

        // Force floating keyboard for overlay panels
        etSearch.privateImeOptions = "nm"
        etSearch.imeOptions = etSearch.imeOptions or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI

        // Search → rebuild flattened list, debounced 200ms so fast typing
        // does not rebuild (and re-submit to DiffUtil) on every keystroke.
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchHandler.removeCallbacks(searchDebounceRunnable)
                searchHandler.postDelayed(searchDebounceRunnable, 200L)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // One scroll listener for both jobs (was two): hide the keyboard
        // when the user starts dragging, and persist the scroll offset
        // only when scrolling settles (SCROLL_STATE_IDLE) instead of on
        // every onScrolled event — the old listener enqueued a
        // SharedPreferences apply() (a disk-write) per scroll event.
        rvPickerGrid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING && etSearch.hasFocus()) {
                    etSearch.clearFocus()
                    hideKeyboard()
                }
                if (newState == RecyclerView.SCROLL_STATE_IDLE && panelPrefs.rememberScroll) {
                    panelPrefs.lastPickerScroll = rv.computeVerticalScrollOffset()
                }
            }
        })

        // Layout manager: all four tabs share the same vertical list layout so
        // items look identical everywhere (no more 2-column grid on APPS only).
        rvPickerGrid.layoutManager = LinearLayoutManager(context)
        rvPickerGrid.setHasFixedSize(false)
        // Audit fix: recycling was fully disabled (cache size 0, pool 0),
        // so every scroll re-inflated rows. bindFull() fully re-binds every
        // holder (text, icon via Glide.clear + load, check state), so reuse
        // is safe. Modest cache only — the picker is a short-lived overlay.
        rvPickerGrid.setItemViewCacheSize(4)
        // setDrawingCacheEnabled() is no longer recommended by Android (deprecated
        // since API 28). Hardware-accelerated rendering (which is the default
        // since API 14) is the supported replacement; passing `false` here was
        // already a no-op because RecyclerView is HW-accelerated by default.
        rvPickerGrid.recycledViewPool.setMaxRecycledViews(0, 8)
        rvPickerGrid.adapter = adapter

        // Drag/swipe helper for custom items
        itemTouchHelper = ItemTouchHelper(buildCustomItemTouchCallback())
        itemTouchHelper?.attachToRecyclerView(rvPickerGrid)

        // Header row buttons
        btnSettings.setOnClickListener {
            val intent = android.content.Intent(context, SettingsMainActivity::class.java).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e("AppPickerPanelView", "Failed to open settings", e)
            }
            onAppLaunched?.invoke()
        }
        btnEdit.setOnClickListener { onTopRightButtonClick() }
        btnAdd.setOnClickListener {
            if (panelPrefs.hapticEnabled) it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            SpringAnimator.scalePulse(it)
            addNewCustomItem()
        }

        // Notification strip (unchanged from prior implementation)
        val rvNotifications = view.findViewById<RecyclerView>(R.id.rvPickerNotifications)
        rvNotifications.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        rvNotifications.adapter = notificationAdapter
        val btnToggleNotifs = view.findViewById<View>(R.id.btnToggleNotifications)
        val ivChevron = view.findViewById<ImageView>(R.id.ivNotificationsChevron)
        btnToggleNotifs.setOnClickListener {
            val isHidden = rvNotifications.visibility == View.GONE
            rvNotifications.visibility = if (isHidden) View.VISIBLE else View.GONE
            view.findViewById<View>(R.id.divNotifications).visibility = rvNotifications.visibility
            ivChevron.rotation = if (isHidden) 90f else 0f
        }

        // Outside-tap on the picker card dismisses the search keyboard
        pickerPanelCard.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN && etSearch.hasFocus()) {
                etSearch.clearFocus()
                hideKeyboard()
            }
            false
        }

        // Swipe-down to close (preserved from the original)
        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (!rvPickerGrid.canScrollVertically(-1) && vy > 800f) {
                    onClose?.invoke()
                    return true
                }
                return false
            }
        })

        applyTheme()
        updateTabUI()
        updateHeaderAndEditButton()
        rebuildAndSubmit()
        loadApps()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        gestureDetector?.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!_scope.coroutineContext[Job]!!.isActive) {
            _scope = CoroutineScope(Dispatchers.Main + Job())
        }
        notificationChangedListener = { _scope.launch { updateNotifications() } }
        NotificationTrackingService.addOnNotificationsChangedListener(notificationChangedListener!!)
        updateNotifications()
        // Re-load custom items from prefs (in case sidebar sync changed them)
        customItems = panelPrefs.getCustomItems().toMutableList()
        if (activeTab == PickerTab.CUSTOM) rebuildAndSubmit()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // Persist the final scroll offset even if the picker closes
        // mid-scroll (before the idle write fires).
        if (panelPrefs.rememberScroll) {
            panelPrefs.lastPickerScroll = rvPickerGrid.computeVerticalScrollOffset()
        }
        searchHandler.removeCallbacks(searchDebounceRunnable)
        notificationChangedListener?.let {
            NotificationTrackingService.removeOnNotificationsChangedListener(it)
        }
        notificationChangedListener = null
        _scope.coroutineContext[Job]?.cancel()
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_UP &&
            event.keyCode == android.view.KeyEvent.KEYCODE_BACK) {
            if (activeTab == PickerTab.CUSTOM && editingCustomId != null) {
                cancelCustomItemEdit()
                return true
            }
            onClose?.invoke()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // ====================================================================================
    //                                Tab switching
    // ====================================================================================
    private fun switchTab(newTab: PickerTab) {
        if (newTab == activeTab) return

        // Save any pending edit before switching (URLS only). forceDiscard=true
        // so an invalid (e.g. file://) edit is silently dropped instead of
        // blocking the tab switch — matches the spec of "tab switch cancels edit".
        // Audit U1: surface a toast on discard so the user knows their edit was
        // dropped, not silently lost.
        if (activeTab == PickerTab.CUSTOM && editingCustomId != null) {
            cancelCustomItemEdit()
        }

        activeTab = newTab

        // All tabs share the same vertical list layout (unified item style).
        rvPickerGrid.layoutManager = LinearLayoutManager(context)

        updateTabUI()
        updateHeaderAndEditButton()

        // Lazy-load the unified cache on first switch to any of the three tabs.
        when (newTab) {
            PickerTab.APPS, PickerTab.ACTIVITIES, PickerTab.SHORTCUTS ->
                if (pickerData == null) loadPickerData(forceRefresh = false)
            PickerTab.CUSTOM -> customItems = panelPrefs.getCustomItems().toMutableList()
        }

        // Reset custom editing states on tab switch
        if (newTab == PickerTab.CUSTOM) {
            editingCustomId = null
        } else {
            // Per-item edit: discard changes
            editingCustomId = null
            // Global edit mode: exit without saving sidebar changes
            // (sidebar changes only saved when Done is clicked)
            if (isEditMode) {
                isEditMode = false
                onEditModeChanged?.invoke(false)
                updateHeaderAndEditButton()
            }
        }

        rebuildAndSubmit()
    }

    private fun updateTabUI() {
        tabViews.forEach { (tab, v) ->
            val selected = tab == activeTab
            v.setTextColor(if (selected) Color.WHITE else Color.parseColor("#80FFFFFF"))
            v.background = if (selected) {
                android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = 8f * resources.displayMetrics.density
                    setColor(Color.parseColor("#1AFFFFFF"))
                }
            } else null
        }
    }

    private fun updateHeaderAndEditButton() {
        val isCustomModify = activeTab == PickerTab.CUSTOM && editingCustomId != null

        // ── Header text ──
        tvHeader.text = when (activeTab) {
            PickerTab.APPS -> if (isEditMode) context.getString(R.string.picker_manage_smart_edge) else context.getString(R.string.picker_all_apps)
            PickerTab.ACTIVITIES -> context.getString(R.string.picker_all_activities)
            PickerTab.SHORTCUTS -> context.getString(R.string.picker_all_shortcuts)
            PickerTab.CUSTOM -> context.getString(R.string.picker_custom_title)
        }

        // ── ADD button: custom tab only, hidden in modify/edit states ──
        btnAdd.visibility = if (activeTab == PickerTab.CUSTOM && !isCustomModify && !isEditMode) View.VISIBLE else View.GONE

        // ── EDIT button: hidden during modify state ──
        btnEdit.visibility = if (isCustomModify) View.GONE else View.VISIBLE
        btnEdit.text = if (isEditMode) context.getString(R.string.done).uppercase() else context.getString(R.string.picker_edit).uppercase()
        btnEdit.setTextColor(if (isEditMode) accentColor else Color.parseColor("#4A9EFF"))
    }

    private fun onTopRightButtonClick() {
        if (activeTab == PickerTab.CUSTOM) {
            setEditMode(!isEditMode)
        } else {
            setEditMode(!isEditMode)
        }
    }

    // ====================================================================================
    //                                Public API
    // ====================================================================================
    fun setEditMode(enabled: Boolean) {
        if (isEditMode == enabled) return
        isEditMode = enabled
        onEditModeChanged?.invoke(enabled)
        updateHeaderAndEditButton()
        if (activeTab == PickerTab.CUSTOM) {
            // ListAdapter items carry stale `isEditing` flags — must rebuild
            // the flattened list so CustomRow gets the updated isEditMode value.
            rebuildAndSubmit()
        } else {
            adapter.notifyItemRangeChanged(0, adapter.itemCount, "EDIT_MODE_CHANGE")
        }
    }

    /** Called by the host when the picker is hidden (outside tap / panel close).
     *  Cancels any in-flight custom-item edit and exits global edit mode so the
     *  Add/Edit buttons are not left hidden when the picker reopens on the
     *  CUSTOM tab. */
    fun onPickerHidden() {
        if (editingCustomId != null) {
            cancelCustomItemEdit()
        }
        if (isEditMode) {
            // setEditMode already refreshes header + rebuilds CUSTOM rows.
            setEditMode(false)
        } else {
            // Even when already out of global edit mode, force header chrome
            // (Add/Edit) back to the normal CUSTOM state after a fold/hide.
            updateHeaderAndEditButton()
            if (activeTab == PickerTab.CUSTOM) rebuildAndSubmit()
        }
    }

    fun setMaxRecyclerViewHeight(maxPx: Int) {
        lastMaxPx = maxPx
        updatePickerHeight()
    }

    fun resetSearch() {
        if (etSearch.text.isNotEmpty()) etSearch.setText("")
    }

    fun handleKeyboard() {
        if (!panelPrefs.autoShowKeyboard) {
            etSearch.clearFocus()
            pickerPanelCard.requestFocus()
            hideKeyboard()
        } else {
            etSearch.requestFocus()
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(etSearch, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    fun invalidateAppList() {
        pickerData = null
        repository.clearPickerCache()
    }

    fun clearIcons() {
        adapter.notifyDataSetChanged()
    }

    fun applyTheme() {
        val theme = panelPrefs.uiTheme
        val density = resources.displayMetrics.density

        val drawable = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
        }
        val themeBgColor = when (theme) {
            PanelPreferences.THEME_ORIGIN -> Color.parseColor("#1F1F1F")
            PanelPreferences.THEME_HYPEROS -> Color.parseColor("#E6252525")
            else -> try { Color.parseColor(panelPrefs.panelBackgroundColor) }
            catch (e: Exception) { Color.parseColor("#E61A1C1E") }
        }
        drawable.setColor(themeBgColor)
        val finalRadius = if (theme == PanelPreferences.THEME_HYPEROS) 16f else panelPrefs.panelCornerRadius.toFloat()
        drawable.cornerRadius = finalRadius * density
        if (theme == PanelPreferences.THEME_HYPEROS) {
            drawable.setStroke((1.5 * density).toInt(), Color.parseColor("#4DFFFFFF"))
        } else if (theme == PanelPreferences.THEME_RICH) {
            val accent = try { Color.parseColor(panelPrefs.accentColor) } catch (e: Exception) { Color.parseColor("#4A9EFF") }
            drawable.setStroke((2 * density).toInt(), accent)
        } else if (theme == PanelPreferences.THEME_REALME) {
            drawable.colors = intArrayOf(Color.parseColor("#333333"), Color.parseColor("#1A1A1A"))
            drawable.orientation = android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM
            drawable.setStroke((1 * density).toInt(), Color.parseColor("#33FFFFFF"))
        }
        pickerPanelCard.background = drawable
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) pickerPanelCard.clipToOutline = true

        accentColor = try {
            if (panelPrefs.useCustomAccent) Color.parseColor(panelPrefs.accentColor)
            else Color.parseColor("#4A9EFF")
        } catch (e: Exception) { Color.parseColor("#4A9EFF") }

        tvHeader.setTextColor(Color.WHITE)
        btnEdit.setTextColor(if (isEditMode) accentColor else Color.parseColor("#4A9EFF"))
        etSearch.setTextColor(Color.WHITE)
        etSearch.setHintTextColor(Color.parseColor("#66FFFFFF"))

        val searchBg = findViewById<View>(R.id.etPickerSearch).parent as? View
        searchBg?.let {
            val sd = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = (SEARCH_CORNER_RADIUS_DP * density)
                val baseColor = themeBgColor
                val alpha = (Color.alpha(baseColor) * 0.5f).toInt()
                val r = (Color.red(baseColor) * 0.8f).toInt()
                val g = (Color.green(baseColor) * 0.8f).toInt()
                val b = (Color.blue(baseColor) * 0.8f).toInt()
                setColor(Color.argb(alpha, r, g, b))
                setStroke((1 * density).toInt(), Color.parseColor("#1AFFFFFF"))
            }
            it.background = sd
        }

        adapter.setAccentColor(accentColor)
        notificationAdapter.setAccentColor(accentColor)
        rebuildAndSubmit()  // re-render with theme
    }

    fun getPickerCardRect(outRect: android.graphics.Rect) {
        pickerPanelCard.getGlobalVisibleRect(outRect)
    }

    fun loadApps(forceRefresh: Boolean = false) {
        // Compatibility API — refreshes the unified picker cache for the
        // active tab (APPS / ACTIVITIES / SHORTCUTS share one cache).
        if (!forceRefresh && pickerData != null && activeTab == PickerTab.APPS) {
            // Sync in-panel flags from prefs for all three lists, on
            // copies: the cached PickerData is shared with the repository,
            // so mutating its items in place would also mutate the
            // adapter's old list and DiffUtil would skip the rebind.
            val synced = pickerData?.let { syncPanelState(it) }
            if (synced != pickerData) {
                pickerData = synced
                rebuildAndSubmit()
            }
            return
        }
        when (activeTab) {
            PickerTab.APPS, PickerTab.ACTIVITIES, PickerTab.SHORTCUTS ->
                loadPickerData(forceRefresh)
            PickerTab.CUSTOM -> {
                customItems = panelPrefs.getCustomItems().toMutableList()
                rebuildAndSubmit()
                // Round-F: refresh header add/edit button visibility. openPicker()
                // calls loadApps() on re-show, but the previous hide did not reset
                // the button state — without this the Add/Edit buttons stay GONE
                // (activeTab is still CUSTOM and a stale isEditMode/editingCustomId
                // makes updateHeaderAndEditButton show them hidden) until the user
                // switches tabs and back.
                updateHeaderAndEditButton()
            }
        }
    }

    // ====================================================================================
    //                                Data loading
    // ====================================================================================
    /**
     * Return a copy of [data] with isInPanel re-synced from prefs for all
     * three lists. The repository cache is shared, so the view must not
     * mutate its items in place: the old code mutated the cached AppInfos
     * (also the adapter's old list), so DiffUtil saw no change and skipped
     * the rebind that would show the new check state — and it only synced
     * the APPS list, leaving ACTIVITIES/SHORTCUTS badges stale.
     */
    private fun syncPanelState(data: AppRepository.PickerData): AppRepository.PickerData {
        val panelIds = panelPrefs.getPanelApps().toSet()
        return data.copy(
            apps = data.apps.map { it.copy(isInPanel = panelIds.contains(it.identifier)) },
            activities = data.activities.map { group ->
                group.copy(items = group.items.map { it.copy(isInPanel = panelIds.contains(it.identifier)) })
            },
            shortcuts = data.shortcuts.map { group ->
                group.copy(items = group.items.map { it.copy(isInPanel = panelIds.contains(it.identifier)) })
            }
        )
    }

    /** Generation counter for picker loads: only the newest load may touch the header/list. */
    private var pickerLoadGeneration = 0

    /**
     * Load the unified picker cache (apps + activities + shortcuts, in
     * parallel) and rebuild the current tab. One function, one cache, one
     * refresh path for all three tabs.
     */
    private fun loadPickerData(forceRefresh: Boolean = false) {
        val generation = ++pickerLoadGeneration
        tvHeader.text = context.getString(R.string.loading_apps)
        _scope.launch {
            val data = withContext(Dispatchers.IO) { repository.getPickerData(forceRefresh) }
            // A newer load superseded this one: don't touch the header or
            // the list — its coroutine owns them now.
            if (generation != pickerLoadGeneration) return@launch
            // Set the correct tab header explicitly instead of restoring
            // the captured text: a concurrent load captured "Loading…" as
            // its original header, and restoring it left the header stuck
            // on "Loading…" after the data had loaded.
            updateHeaderAndEditButton()
            pickerData = syncPanelState(data)
            rebuildAndSubmit()
            playStaggeredEntryAnim()
        }
    }

    // ====================================================================================
    //                                Custom item operations
    // ====================================================================================

    /** Add a new blank custom item and enter edit mode for it. */
    private fun addNewCustomItem() {
        // Guard: if a previous blank draft is still open, cancel it first so we
        // never stack multiple blank rows on top of each other (each of which
        // would otherwise survive as a stale blank item).
        if (editingCustomId != null) {
            cancelCustomItemEdit()
        }
        val newItem = CustomItem(
            id = java.util.UUID.randomUUID().toString(),
            isUrl = true,
            title = "",
            content = ""
        )
        customItems.add(newItem)
        editingCustomId = newItem.id
        updateHeaderAndEditButton()
        rebuildAndSubmit {
            val pos = adapter.currentList.indexOfFirst {
                (it is PickerItem.CustomRow) && it.item.id == newItem.id
            }
            if (pos >= 0) {
                (rvPickerGrid.findViewHolderForAdapterPosition(pos)
                    as? PickerAdapter.CustomViewHolder)?.let { h ->
                    h.etTitle.requestFocus()
                    (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                        .showSoftInput(h.etTitle, InputMethodManager.SHOW_IMPLICIT)
                }
            }
        }
    }

    /** Enter edit mode on a specific custom item (Edit → Confirm flow). */
    private fun onCustomItemEditTapped(id: String) {
        // Existence check only — the item itself is read from the adapter list.
        if (customItems.none { it.id == id }) return

        if (editingCustomId == id) {
            // CONFIRM: save changes
            if (!saveCustomItemEdit(id)) {
                showInvalidCustomUriToast()
                return
            }
            editingCustomId = null
            updateHeaderAndEditButton()
            rebuildAndSubmit()
        } else {
            // CANCEL previous if any
            if (editingCustomId != null) {
                cancelCustomItemEdit()
            }
            editingCustomId = id
            // Enter editing mode on this row
            rebuildAndSubmit {
                val pos = adapter.currentList.indexOfFirst {
                    (it is PickerItem.CustomRow) && it.item.id == id
                }
                if (pos >= 0) {
                    (rvPickerGrid.findViewHolderForAdapterPosition(pos)
                        as? PickerAdapter.CustomViewHolder)?.let { h ->
                        h.etTitle.requestFocus()
                        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                            .showSoftInput(h.etTitle, InputMethodManager.SHOW_IMPLICIT)
                    }
                }
            }
        }
    }

    /** Cancel edit without saving. */
    private fun cancelCustomItemEdit() {
        val id = editingCustomId ?: return
        editingCustomId = null
        pendingCustomEdits.remove(id)
        // If this was a new blank item (not yet saved), remove it — and also
        // purge any legacy blank row that may have been persisted by older
        // builds (which called onAddCustomItem on ADD). A blank item can never
        // be valid (isValidCustom rejects it), so it must never survive cancel.
        val idx = customItems.indexOfFirst { it.id == id }
        if (idx >= 0) {
            val item = customItems[idx]
            if (item.title.isBlank() && item.content.isBlank()) {
                customItems.removeAt(idx)
                // Defensive cleanup: if a blank draft was persisted (legacy ADD
                // path called onAddCustomItem immediately), drop it from prefs +
                // sidebar too so it can't come back on the next picker open.
                if (panelPrefs.getCustomItems().any { it.id == id }) {
                    panelPrefs.removeCustomItem(id)
                    onRemoveCustomItem?.invoke(id)
                }
            }
        }
        updateHeaderAndEditButton()
        rebuildAndSubmit()
    }

    /** Delete a custom item, removing from sidebar first if pinned. */
    private fun onCustomItemDeleteTapped(id: String) {
        val idx = customItems.indexOfFirst { it.id == id }
        if (idx < 0) return
        val sidebarId = PanelPreferences.CUSTOM_ID_PREFIX + id
        if (panelPrefs.isInPanel(sidebarId)) {
            panelPrefs.removeApp(sidebarId)
        }
        customItems.removeAt(idx)
        panelPrefs.removeCustomItem(id)
        onRemoveCustomItem?.invoke(id)
        if (editingCustomId == id) editingCustomId = null
        rebuildAndSubmit()
    }

    /** Toggle sidebar membership for a custom item via the edit-mode badge. */
    private fun toggleCustomInPanel(id: String) {
        val ci = customItems.firstOrNull { it.id == id } ?: return
        val sidebarId = PanelPreferences.CUSTOM_ID_PREFIX + id
        val newState = !panelPrefs.isInPanel(sidebarId)
        // Synthesize an AppInfo exactly like AppRepository does so the shared
        // onToggleApp callback (addApp/removeApp + refresh + scroll) works for
        // custom rows without a bespoke code path.
        val app = AppInfo(
            packageName = sidebarId,
            appName = ci.title.ifBlank { context.getString(R.string.custom_untitled) },
            isInPanel = newState,
            type = AppInfo.Type.CUSTOM,
            intentUri = ci.content,
            activityName = if (ci.isUrl) "URL" else "INTENT",
            appearanceKey = panelPrefs.appearanceKey
        )
        onToggleApp?.invoke(app, newState)
        rebuildAndSubmit()
    }

    /** Save the edit on a custom item. Returns false if content is invalid. */
    private fun saveCustomItemEdit(id: String): Boolean {
        val idx = customItems.indexOfFirst { it.id == id }
        if (idx < 0) return false
        val original = customItems[idx]
        val cached = pendingCustomEdits[id]
        val holder = rvPickerGrid.findViewHolderForAdapterPosition(
            adapter.currentList.indexOfFirst {
                (it is PickerItem.CustomRow) && it.item.id == id
            }
        ) as? PickerAdapter.CustomViewHolder

        val newTitle: String = cached?.first
            ?: holder?.etTitle?.text?.toString()
            ?: original.title
        val newContent: String = cached?.second
            ?: holder?.etContent?.text?.toString()
            ?: original.content

        val trimmedContent = newContent.trim()
        val isUrl = !trimmedContent.startsWith("intent:")

        if (!isValidCustom(newTitle, trimmedContent)) {
            pendingCustomEdits.remove(id)
            return false
        }

        val updated = original.copy(title = newTitle, content = trimmedContent, isUrl = isUrl)
        customItems[idx] = updated
        val existed = panelPrefs.getCustomItems().any { it.id == id }
        if (!existed) {
            onAddCustomItem?.invoke(updated)
        } else if (original.title != newTitle || original.content != newContent || original.isUrl != isUrl) {
            onUpdateCustomItem?.invoke(updated)
        } else {
            panelPrefs.updateCustomItem(updated)
        }
        pendingCustomEdits.remove(id)
        return true
    }


    private fun showInvalidCustomUriToast() {
        android.widget.Toast.makeText(
            context,
            context.getString(R.string.custom_uri_validation_msg),
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    private fun toggleHeaderExpansion(packageName: String) {
        if (expandedPackages.contains(packageName)) {
            expandedPackages.remove(packageName)
        } else {
            expandedPackages.add(packageName)
        }
        rebuildAndSubmit()
    }

    // ====================================================================================
    //                                Search filter
    // ====================================================================================
    private fun currentQuery(): String = etSearch.text.toString().trim()

    private fun matchesQuery(text: String, q: String): Boolean =
        text.contains(q, ignoreCase = true)

    // ====================================================================================
    //                                Flattening
    // ====================================================================================
    private fun buildFlattenedList(): List<PickerItem> {
        val q = currentQuery()
        val result = mutableListOf<PickerItem>()
        when (activeTab) {
            PickerTab.APPS -> {
                val apps = pickerData?.apps.orEmpty()
                if (apps.isEmpty()) {
                    result.add(PickerItem.EmptyState(context.getString(R.string.picker_no_apps_found)))
                } else {
                    val filtered = if (q.isEmpty()) apps else apps.filter { matchesQuery(it.appName, q) }
                    if (filtered.isEmpty()) {
                        result.add(PickerItem.EmptyState(context.getString(R.string.picker_no_matches_for, q)))
                    } else {
                        filtered.forEach { result.add(PickerItem.AppRow(it)) }
                    }
                }
            }
            PickerTab.ACTIVITIES -> {
                val activitiesByPackage = pickerData?.activities.orEmpty()
                if (activitiesByPackage.isEmpty()) {
                    result.add(PickerItem.EmptyState(context.getString(R.string.picker_no_activities_available)))
                } else {
                    var anyMatches = false
                    activitiesByPackage.forEach { group ->
                        val matching = if (q.isEmpty()) group.items else group.items.filter { matchesQuery(it.appName, q) }
                        val expanded = expandedPackages.contains(group.packageName) || (!q.isEmpty() && matching.isNotEmpty())
                        if (q.isEmpty() || matching.isNotEmpty() || expandedPackages.contains(group.packageName)) {
                            anyMatches = true
                            // The header count is the number of children
                            // actually shown (matching), not the group's
                            // total: searching for one activity in an app
                            // with 20 used to read "20" above a single row.
                            // group.appLabel comes from the repository (no
                            // main-thread PackageManager call here).
                            result.add(PickerItem.TreeHeader(group.packageName, group.appLabel, matching.size, expanded))
                            if (expanded) {
                                matching.forEach { result.add(PickerItem.TreeChild(it)) }
                            }
                        }
                    }
                    if (!anyMatches) result.add(PickerItem.EmptyState(context.getString(R.string.picker_no_activities_match, q)))
                }
            }
            PickerTab.SHORTCUTS -> {
                val shortcutsByPackage = pickerData?.shortcuts.orEmpty()
                if (shortcutsByPackage.isEmpty()) {
                    val msg = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) {
                        context.getString(R.string.picker_requires_android_71)
                    } else {
                        context.getString(R.string.picker_no_static_shortcuts_found)
                    }
                    result.add(PickerItem.EmptyState(msg))
                } else {
                    var anyMatches = false
                    shortcutsByPackage.forEach { group ->
                        val matching = if (q.isEmpty()) group.items else group.items.filter { matchesQuery(it.appName, q) }
                        val expanded = expandedPackages.contains(group.packageName) || (!q.isEmpty() && matching.isNotEmpty())
                        if (q.isEmpty() || matching.isNotEmpty() || expandedPackages.contains(group.packageName)) {
                            anyMatches = true
                            result.add(PickerItem.TreeHeader(group.packageName, group.appLabel, matching.size, expanded))
                            if (expanded) {
                                matching.forEach { result.add(PickerItem.TreeChild(it)) }
                            }
                        }
                    }
                    if (!anyMatches) result.add(PickerItem.EmptyState(context.getString(R.string.picker_no_shortcuts_match, q)))
                }
            }
            PickerTab.CUSTOM -> {
                val filtered = if (q.isEmpty()) customItems else customItems.filter {
                    matchesQuery(it.title, q) || matchesQuery(it.content, q)
                }
                val sidebarIds = panelPrefs.getPanelApps().toSet()
                filtered.forEach { ci ->
                    result.add(PickerItem.CustomRow(
                        item = ci,
                        isEditing = isEditMode,
                        isItemEditing = editingCustomId == ci.id,
                        isInSidebar = sidebarIds.contains(PanelPreferences.CUSTOM_ID_PREFIX + ci.id)
                    ))
                }
                if (filtered.isEmpty()) {
                    result.add(PickerItem.EmptyState(context.getString(R.string.picker_tap_add_custom_first)))
                }
            }
        }
        return result
    }

    private fun rebuildAndSubmit(onCommit: (() -> Unit)? = null) {
        val items = buildFlattenedList()
        // Round-13 audit M4 — focus-on-new-row race fix. ListAdapter.submitList runs
        // its DiffUtil computation on the supplied executor (the synchronous variant
        // here dispatches on a background thread by default) and only THEN dispatches
        // the commit callback. Acquirers that want to grab a ViewHolder by position
        // (e.g. addNewCustomItem scrolling to & focusing the new row) used to call
        // `rvPickerGrid.post { findViewHolderForAdapterPosition(...) }` BEFORE the
        // commit ran, which always returned null → silent keyboard-failure. Routing
        // the focus logic through an onCommit hook guarantees it executes AFTER
        // submitList has finished applying + laying out.
        adapter.submitList(items) {
            updatePickerHeight()
            // Force full rebind of EVERY custom row (first + last included).
            // Payload-only / last-only rebinds left edge rows with GONE buttons
            // or a disabled drag handle after recycle from modify/edit states.
            if (activeTab == PickerTab.CUSTOM && adapter.itemCount > 0) {
                adapter.notifyItemRangeChanged(0, adapter.itemCount)
            }
            onCommit?.invoke()
        }
    }

    private fun updatePickerHeight() {
        if (lastMaxPx == -1) return
        val lp = rvPickerGrid.layoutParams
        val itemsCount = adapter.itemCount
        if (itemsCount == 0) {
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            rvPickerGrid.layoutParams = lp
            return
        }
        val density = resources.displayMetrics.density

        // Unified: every tab is a single-column list with ~64dp rows
        // (app card / header / custom row all share the same row height).
        val itemHeightDp = 64
        val itemHeightPx = (itemHeightDp * density).toInt()
        val rows = itemsCount
        val estimated = rows * itemHeightPx

        if (estimated < lastMaxPx) {
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
        } else {
            lp.height = lastMaxPx
        }
        rvPickerGrid.layoutParams = lp
    }

    private fun playStaggeredEntryAnim() {
        rvPickerGrid.post {
            val lm = rvPickerGrid.layoutManager ?: return@post
            val n = lm.childCount
            if (n == 0) return@post
            for (i in 0 until n) {
                val v = lm.getChildAt(i) ?: continue
                v.alpha = 0f
                v.translationY = 50f
                v.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(EDIT_MODE_ANIM_DURATION_MS)
                    .setStartDelay(i * EDIT_MODE_ITEM_STAG_DELAY_MS)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
            }
        }
    }

    // ====================================================================================
    //                                Notifications (preserved)
    // ====================================================================================
    private fun updateNotifications() {
        if (!panelPrefs.showNotificationApps) {
            findViewById<View>(R.id.layoutPickerNotifications).visibility = View.GONE
            return
        }
        val pkgs = NotificationTrackingService.getActiveNotificationPackages()
        if (pkgs.isEmpty()) {
            findViewById<View>(R.id.layoutPickerNotifications).visibility = View.GONE
        } else {
            val panelApps = panelPrefs.getPanelApps().toSet()
            val filteredPkgs = pkgs.filter { !panelApps.contains(it) }
            val appInfos = filteredPkgs.mapNotNull { pkg ->
                try {
                    val pm = context.packageManager
                    val ai = pm.getApplicationInfo(pkg, 0)
                    AppInfo(pkg, ai.loadLabel(pm).toString(), isInPanel = false, type = AppInfo.Type.APP)
                } catch (e: Exception) { null }
            }
            if (appInfos.isEmpty()) {
                findViewById<View>(R.id.layoutPickerNotifications).visibility = View.GONE
            } else {
                findViewById<View>(R.id.layoutPickerNotifications).visibility = View.VISIBLE
                notificationAdapter.submitList(appInfos)
            }
        }
    }

    // ====================================================================================
    //                                Helpers
    // ====================================================================================
    private fun hideKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(etSearch.windowToken, 0)
    }

    // ====================================================================================
    //                                ItemTouchHelper (custom items only)
    // ====================================================================================
    private fun buildCustomItemTouchCallback(): ItemTouchHelper.Callback {
        return object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0  // no swipe-to-delete
        ) {
            override fun isLongPressDragEnabled(): Boolean = false  // drag from handle only

            override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int {
                if (activeTab != PickerTab.CUSTOM) return 0
                if (editingCustomId != null || isEditMode) return 0  // block all gestures while in any edit state
                if (vh !is PickerAdapter.CustomViewHolder) return 0
                if (currentQuery().isNotEmpty()) return 0
                return super.getMovementFlags(rv, vh)
            }

            override fun onMove(
                rv: RecyclerView,
                from: RecyclerView.ViewHolder,
                to: RecyclerView.ViewHolder
            ): Boolean {
                if (from !is PickerAdapter.CustomViewHolder) return false
                if (to !is PickerAdapter.CustomViewHolder) return false
                val fromPos = from.bindingAdapterPosition
                val toPos = to.bindingAdapterPosition
                if (fromPos == RecyclerView.NO_POSITION || toPos == RecyclerView.NO_POSITION) return false
                // Audit v1.5.3: retrieve the actual item from the ADAPTER's
                // currentList (which may be filtered) and find its real index in
                // the customItems backing list. Using filtered positions to index
                // into the unfiltered customItems would cause data corruption
                // when a search query is active.
                val adapterList = adapter.currentList
                val fromItem = (adapterList.getOrNull(fromPos) as? PickerItem.CustomRow)?.item ?: return false
                val toItem = (adapterList.getOrNull(toPos) as? PickerItem.CustomRow)?.item ?: return false
                val realFrom = customItems.indexOfFirst { it.id == fromItem.id }
                val realTo = customItems.indexOfFirst { it.id == toItem.id }
                if (realFrom < 0 || realTo < 0) return false
                val item = customItems.removeAt(realFrom)
                customItems.add(realTo, item)
                adapter.notifyItemMoved(fromPos, toPos)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                // No-op: swipe-to-delete is disabled (movement flags = 0).
                // Abstract method must be implemented to satisfy SimpleCallback.
            }

            override fun clearView(rv: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(rv, viewHolder)
                // Persist the new order after a drag
                panelPrefs.reorderCustomItems(customItems)
                // Audit L3 — mirror the new custom-list order into the sidebar
                // (KEY_PANEL_APPS) so the user sees the same arrangement in both
                // surfaces, not in only one of them.
                panelPrefs.resyncPanelAppsOrderFromCustomItems(customItems)
                // Round-7 U3: notify the host so the sidebar reflects the
                // reorder immediately. Without this hook the persisted
                // KEY_PANEL_APPS is updated but SidePanelView's adapter
                // still sees the old order until a refresh cycle runs.
                onCustomItemsReordered?.invoke()
            }
        }
    }

    // ====================================================================================
    //                                PickerItem sealed class
    // ====================================================================================
    sealed class PickerItem {
        abstract val stableId: String

        data class AppRow(val app: AppInfo) : PickerItem() {
            override val stableId = "row_${app.identifier}"
        }

        data class TreeHeader(
            val packageName: String,
            val appName: String,
            val childCount: Int,
            val isExpanded: Boolean
        ) : PickerItem() {
            override val stableId = "hdr_$packageName"
        }

        data class TreeChild(val app: AppInfo) : PickerItem() {
            override val stableId = "row_${app.identifier}"
        }

        data class CustomRow(
            val item: CustomItem,
            val isEditing: Boolean = false,     // global edit mode (show sidebar badge)
            val isItemEditing: Boolean = false,  // this specific item is being edited
            val isInSidebar: Boolean = false     // badge: is item in sidebar?
        ) : PickerItem() {
            override val stableId = "cust_${item.id}"
        }

        data class EmptyState(val message: String) : PickerItem() {
            override val stableId = "empty_$message"
        }
    }

    private object PickerDiff : DiffUtil.ItemCallback<PickerItem>() {
        override fun areItemsTheSame(o: PickerItem, n: PickerItem): Boolean = o.stableId == n.stableId
        override fun areContentsTheSame(o: PickerItem, n: PickerItem): Boolean = o == n
    }

    // ====================================================================================
    //                                PickerAdapter
    // ====================================================================================
    inner class PickerAdapter : ListAdapter<PickerItem, RecyclerView.ViewHolder>(PickerDiff) {

        private var accent: Int = Color.parseColor("#4A9EFF")
        private var accentCsl: android.content.res.ColorStateList =
            android.content.res.ColorStateList.valueOf(accent)

        fun setAccentColor(color: Int) {
            accent = color
            accentCsl = android.content.res.ColorStateList.valueOf(color)
        }

        override fun getItemViewType(position: Int): Int = when (getItem(position)) {
            is PickerItem.AppRow, is PickerItem.TreeChild -> VT_APP
            is PickerItem.TreeHeader -> VT_HEADER
            is PickerItem.CustomRow -> VT_CUSTOM
            is PickerItem.EmptyState -> VT_EMPTY
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                // VT_APP: one unified horizontal card for every tab (no more
                // rich-vs-modern split) so all four tabs look identical.
                VT_APP -> AppViewHolder(inflater.inflate(R.layout.item_picker_app_rich, parent, false))
                VT_HEADER -> HeaderViewHolder(inflater.inflate(R.layout.item_picker_app_header, parent, false))
                VT_CUSTOM -> CustomViewHolder(inflater.inflate(R.layout.item_picker_custom_url, parent, false))
                VT_EMPTY -> EmptyViewHolder(inflater.inflate(R.layout.item_picker_empty_state, parent, false))
                else -> error("Unknown viewType $viewType")
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            bindFull(holder, position)
        }

        // Always full-bind custom rows even when DiffUtil/payloads fire — partial
        // payload binds previously left first/last rows with GONE buttons / dead drag.
        override fun onBindViewHolder(
            holder: RecyclerView.ViewHolder,
            position: Int,
            payloads: MutableList<Any>
        ) {
            bindFull(holder, position)
        }

        private fun bindFull(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = getItem(position)) {
                is PickerItem.AppRow -> bindApp(holder as AppViewHolder, item.app, isChild = false)
                is PickerItem.TreeChild -> bindApp(holder as AppViewHolder, item.app, isChild = true)
                is PickerItem.TreeHeader -> bindHeader(holder as HeaderViewHolder, item)
                is PickerItem.CustomRow -> bindCustom(holder as CustomViewHolder, item)
                is PickerItem.EmptyState -> bindEmpty(holder as EmptyViewHolder, item)
            }
        }

        // --- APP / CHILD ---
        // Unified item metrics: one icon size / text size for every tab
        // (single source of truth — the layout XML is no longer overridden
        // with conflicting per-theme values).
        private fun bindApp(holder: AppViewHolder, app: AppInfo, isChild: Boolean) {
            val scale = panelPrefs.scaleFactor
            val iconPx = (context.dpToPx(PICKER_ICON_DP) * scale).toInt()

            holder.ivIcon.layoutParams.let { lp ->
                lp.width = iconPx
                lp.height = iconPx
                holder.ivIcon.layoutParams = lp
            }
            holder.tvName.textSize = PICKER_NAME_SP * scale
            holder.tvPackage?.textSize = PICKER_PKG_SP * scale
            holder.tvName.text = app.appName
            holder.tvPackage?.text = app.packageName

            // Indent child rows to show tree structure
            holder.itemView.setPadding(
                if (isChild) (CHILD_INDENT_DP * resources.displayMetrics.density).toInt() else 0,
                holder.itemView.paddingTop,
                holder.itemView.paddingRight,
                holder.itemView.paddingBottom
            )

            val textColor = Color.WHITE
            val subTextColor = Color.parseColor("#B3FFFFFF")
            holder.tvName.setTextColor(textColor)
            holder.tvPackage?.setTextColor(subTextColor)

            // Glide.with(applicationContext) — picker views live inside a Service
            // WindowManager overlay, so view.context is the Service. Pin Glide's
            // lifecycle to the Application to avoid any chance of the view
            // holding a reference past its native destruction.
            Glide.with(holder.itemView.context.applicationContext).clear(holder.ivIcon)
            Glide.with(holder.itemView.context.applicationContext)
                .load(AppIconRequest(app.packageName, panelPrefs.appearanceKey))
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .placeholder(android.R.drawable.sym_def_app_icon)
                .error(android.R.drawable.sym_def_app_icon)
                .override((120 * scale).toInt(), (120 * scale).toInt())
                .into(holder.ivIcon)
            IconShapeHelper.applyShape(holder.ivIcon, panelPrefs.iconShape)

            // Check icon: shown in edit mode (only for the 3 non-custom tabs)
            if (isEditMode) {
                holder.ivCheck.visibility = View.VISIBLE
                val inPanel = panelPrefs.isInPanel(app.identifier)
                if (holder.ivCheck is ImageView) {
                    holder.ivCheck.imageTintList = if (inPanel) accentCsl
                    else android.content.res.ColorStateList.valueOf(Color.parseColor("#B3FFFFFF"))
                }
                holder.ivCheck.rotation = if (inPanel) 45f else 0f
            } else {
                holder.ivCheck.visibility = View.GONE
            }

            holder.itemView.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                if (isEditMode) {
                    if (panelPrefs.hapticEnabled) it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    toggleInPanel(getItem(pos) as PickerItem, pos)
                } else {
                    if (panelPrefs.hapticEnabled) it.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    launchApp(app)
                }
            }
        }

        private fun toggleInPanel(item: PickerItem, position: Int) {
            val app = when (item) {
                is PickerItem.AppRow -> item.app
                is PickerItem.TreeChild -> item.app
                else -> return
            }
            val newState = !panelPrefs.isInPanel(app.identifier)
            app.isInPanel = newState
            onToggleApp?.invoke(app, newState)
            notifyItemChanged(position, "TOGGLE")
        }

        private fun launchCustomItemContent(ci: CustomItem) {
            // Reuse launchApp() with a synthetic CUSTOM AppInfo so URL/intent
            // handling + the safety gate stay consistent with sidebar launches.
            val app = AppInfo(
                packageName = PanelPreferences.CUSTOM_ID_PREFIX + ci.id,
                appName = ci.title.ifBlank { "Untitled" },
                isInPanel = true,
                type = AppInfo.Type.CUSTOM,
                intentUri = ci.content,
                appearanceKey = panelPrefs.appearanceKey
            )
            launchApp(app)
        }

        private fun launchApp(app: AppInfo) {
            val pos = adapter.currentList.indexOfFirst {
                (it is PickerItem.AppRow && it.app.identifier == app.identifier) ||
                (it is PickerItem.TreeChild && it.app.identifier == app.identifier)
            }
            rvPickerGrid.findViewHolderForAdapterPosition(pos)?.itemView?.let { SpringAnimator.scalePulse(it) }

            // Safety gate for hand-authored content only: any parseable intent:
            // is accepted (cross-app components & selectors are allowed by user
            // policy); only unparseable URIs are refused. ACTIVITY / SHORTCUT rows
            // have no gate at all since they come from the exported enumeration.
            if (app.type == AppInfo.Type.CUSTOM && !context.isSafeIntentUri(app.intentUri)) {
                showLaunchBlockedUI(app)
                return
            }

            val intent: android.content.Intent? = when {
                app.type == AppInfo.Type.CUSTOM -> {
                    try {
                        if (app.intentUri.orEmpty().startsWith("intent:")) {
                            android.content.Intent.parseUri(app.intentUri, android.content.Intent.URI_INTENT_SCHEME)
                                .apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
                        } else {
                            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(app.intentUri)).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
                        }
                    } catch (e: Exception) { null }
                }
                app.intentUri != null -> try {
                    android.content.Intent.parseUri(app.intentUri, android.content.Intent.URI_INTENT_SCHEME)
                } catch (e: Exception) {
                    context.packageManager.getLaunchIntentForPackage(app.packageName)
                }
                else -> context.packageManager.getLaunchIntentForPackage(app.packageName)
            }
            if (intent != null) {
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(intent)
                } catch (e: Exception) {
                    Log.e("AppPickerPanelView", "Failed to launch ${app.identifier}", e)
                }
            }
            onAppLaunched?.invoke()
        }

        // --- HEADER ---
        private fun bindHeader(holder: HeaderViewHolder, item: PickerItem.TreeHeader) {
            holder.tvName.text = item.appName
            holder.tvCount.text = item.childCount.toString()
            holder.ivChevron.rotation = if (item.isExpanded) 90f else 0f
            Glide.with(holder.itemView.context.applicationContext).clear(holder.ivIcon)
            Glide.with(holder.itemView.context.applicationContext)
                .load(AppIconRequest(item.packageName, panelPrefs.appearanceKey))
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .placeholder(android.R.drawable.sym_def_app_icon)
                .error(android.R.drawable.sym_def_app_icon)
                .override(96, 96)
                .into(holder.ivIcon)
            holder.itemView.setOnClickListener { toggleHeaderExpansion(item.packageName) }
        }

        // --- CUSTOM ---
        private fun bindCustom(holder: CustomViewHolder, item: PickerItem.CustomRow) {
            val ci = item.item
            val itemId = ci.id
            val inGlobalEdit = item.isEditing          // Edit state
            val isThisItemEditing = item.isItemEditing // Modify state

            // Horizontal scroll for long title/content (layout sets
            // scrollHorizontally + scrollbars="horizontal"; TextView needs a
            // movement method to actually show/scroll the horizontal bar).
            holder.tvTitle.movementMethod = android.text.method.ScrollingMovementMethod()
            holder.tvContent.movementMethod = android.text.method.ScrollingMovementMethod()

            // NOTE: do NOT call holder.setIsRecyclable(false) here. Marking the
            // holder non-recyclable while it is being removed (cancel / delete /
            // switchTab) makes RecyclerView's ItemAnimator leave a ghost view in
            // the layout, which visually overlaps the hint text and stacks up
            // with subsequently added rows. Keep every holder recyclable and rely
            // on pendingCustomEdits (restored below) to survive recycling.

            // ══════════ MODIFY STATE: only the edited item is interactive ══════════
            if (isThisItemEditing) {
                holder.readMode.visibility = View.GONE
                holder.editMode.visibility = View.VISIBLE

                // TextWatchers
                val titleWatcher = object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        if (holder.isBinding) return
                        pendingCustomEdits[itemId] = (holder.etTitle.text?.toString().orEmpty()) to (holder.etContent.text?.toString().orEmpty())
                    }
                }
                val contentWatcher = object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        if (holder.isBinding) return
                        pendingCustomEdits[itemId] = (holder.etTitle.text?.toString().orEmpty()) to (holder.etContent.text?.toString().orEmpty())
                    }
                }
                (holder.etTitle.getTag(R.id.etCustomTitle) as? TextWatcher)?.let { holder.etTitle.removeTextChangedListener(it) }
                (holder.etContent.getTag(R.id.etCustomContent) as? TextWatcher)?.let { holder.etContent.removeTextChangedListener(it) }
                holder.etTitle.addTextChangedListener(titleWatcher)
                holder.etTitle.setTag(R.id.etCustomTitle, titleWatcher)
                holder.etContent.addTextChangedListener(contentWatcher)
                holder.etContent.setTag(R.id.etCustomContent, contentWatcher)

                holder.isBinding = true
                // Restore from the pending draft (survives holder recycling), then
                // fall back to the persisted item values.
                val draft = pendingCustomEdits[itemId]
                val restoreTitle = draft?.first ?: ci.title
                val restoreContent = draft?.second ?: ci.content
                if (holder.etTitle.text.toString() != restoreTitle) holder.etTitle.setText(restoreTitle)
                if (holder.etContent.text.toString() != restoreContent) holder.etContent.setText(restoreContent)
                holder.isBinding = false
                holder.etContent.hint = context.getString(R.string.custom_uri_hint)

                // Buttons: Confirm + Cancel
                holder.btnEdit.visibility = View.VISIBLE
                holder.btnDelete.visibility = View.VISIBLE
                holder.btnEdit.setImageResource(R.drawable.ic_check)
                holder.btnEdit.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#4A9EFF"))
                holder.btnEdit.contentDescription = context.getString(R.string.cd_confirm)
                holder.btnDelete.setImageResource(R.drawable.ic_close)
                holder.btnDelete.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FF6B6B"))
                holder.btnDelete.contentDescription = context.getString(R.string.cd_cancel)

                holder.btnEdit.setOnClickListener {
                    if (panelPrefs.hapticEnabled) it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    SpringAnimator.scalePulse(it)
                    onCustomItemEditTapped(itemId)
                }
                holder.btnDelete.setOnClickListener {
                    if (panelPrefs.hapticEnabled) it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    SpringAnimator.scalePulse(it)
                    cancelCustomItemEdit()
                }

                // Drag + badge disabled
                holder.dragHandle.alpha = 0.3f
                holder.dragHandle.setOnTouchListener(null)
                holder.badge.visibility = View.GONE
                holder.badge.setOnClickListener(null)
                // Modify state: body tap must NOT open the URL/intent.
                holder.body.setOnClickListener(null)
                holder.body.isClickable = false
                holder.body.isEnabled = false

            // ══════════ MODIFY-READONLY: other rows while one item is edited ══════════
            } else if (!inGlobalEdit && editingCustomId != null) {
                holder.readMode.visibility = View.VISIBLE
                holder.editMode.visibility = View.GONE
                // Detach any TextWatchers left over from a previous Modify bind so
                // a recycled holder can't leak edits into pendingCustomEdits while
                // it is being re-used for a read-only row.
                (holder.etTitle.getTag(R.id.etCustomTitle) as? TextWatcher)?.let { holder.etTitle.removeTextChangedListener(it) }
                (holder.etContent.getTag(R.id.etCustomContent) as? TextWatcher)?.let { holder.etContent.removeTextChangedListener(it) }
                holder.etTitle.setTag(R.id.etCustomTitle, null)
                holder.etContent.setTag(R.id.etCustomContent, null)
                holder.tvTitle.text = ci.title.ifBlank { context.getString(R.string.custom_untitled) }
                holder.tvContent.text = ci.content.ifBlank { context.getString(R.string.custom_no_url_intent) }

                // Keep button column space with INVISIBLE (not GONE) so row height
                // matches NORMAL and the list does not jump when entering modify.
                holder.btnEdit.visibility = View.INVISIBLE
                holder.btnDelete.visibility = View.INVISIBLE
                holder.btnEdit.setOnClickListener(null)
                holder.btnDelete.setOnClickListener(null)
                holder.badge.visibility = View.GONE
                holder.badge.setOnClickListener(null)
                holder.body.setOnClickListener(null)
                holder.body.isClickable = false
                holder.body.isEnabled = false
                holder.dragHandle.alpha = 0.3f
                holder.dragHandle.setOnTouchListener(null)

            // ══════════ EDIT STATE: badge selection mode ══════════
            } else if (inGlobalEdit) {
                holder.readMode.visibility = View.VISIBLE
                holder.editMode.visibility = View.GONE
                // Detach any TextWatchers left over from a previous Modify bind so
                // a recycled holder can't leak edits into pendingCustomEdits while
                // it is being re-used for a read-only row.
                (holder.etTitle.getTag(R.id.etCustomTitle) as? TextWatcher)?.let { holder.etTitle.removeTextChangedListener(it) }
                (holder.etContent.getTag(R.id.etCustomContent) as? TextWatcher)?.let { holder.etContent.removeTextChangedListener(it) }
                holder.etTitle.setTag(R.id.etCustomTitle, null)
                holder.etContent.setTag(R.id.etCustomContent, null)
                holder.tvTitle.text = ci.title.ifBlank { context.getString(R.string.custom_untitled) }
                holder.tvContent.text = ci.content.ifBlank { context.getString(R.string.custom_no_url_intent) }

                // Badge: enabled and visible — tap toggles sidebar membership.
                // badge is declared as ImageView, so no type check is needed.
                holder.badge.visibility = View.VISIBLE
                holder.badge.imageTintList = if (item.isInSidebar) accentCsl
                    else android.content.res.ColorStateList.valueOf(Color.parseColor("#B3FFFFFF"))
                holder.badge.rotation = if (item.isInSidebar) 45f else 0f
                holder.badge.setOnClickListener {
                    if (panelPrefs.hapticEnabled) it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    SpringAnimator.scalePulse(it)
                    toggleCustomInPanel(itemId)
                }

                // INVISIBLE (not GONE): preserve right-column width/height so
                // global EDIT does not shrink row spacing and jump the list.
                holder.btnEdit.visibility = View.INVISIBLE
                holder.btnDelete.visibility = View.INVISIBLE
                holder.btnEdit.setOnClickListener(null)
                holder.btnDelete.setOnClickListener(null)
                holder.body.setOnClickListener(null)
                holder.body.isClickable = false
                holder.body.isEnabled = false
                holder.dragHandle.alpha = 0.3f
                holder.dragHandle.setOnTouchListener(null)

            // ══════════ NORMAL STATE ══════════
            } else {
                holder.readMode.visibility = View.VISIBLE
                holder.editMode.visibility = View.GONE
                // Same defensive watcher detach as EDIT STATE (see above).
                (holder.etTitle.getTag(R.id.etCustomTitle) as? TextWatcher)?.let { holder.etTitle.removeTextChangedListener(it) }
                (holder.etContent.getTag(R.id.etCustomContent) as? TextWatcher)?.let { holder.etContent.removeTextChangedListener(it) }
                holder.etTitle.setTag(R.id.etCustomTitle, null)
                holder.etContent.setTag(R.id.etCustomContent, null)
                holder.tvTitle.text = ci.title.ifBlank { context.getString(R.string.custom_untitled) }
                holder.tvContent.text = ci.content.ifBlank { context.getString(R.string.custom_no_url_intent) }

                // Badge: enabled but hidden (for drag reorder, not shown visually)
                holder.badge.visibility = View.GONE
                holder.badge.setOnClickListener(null)

                // Buttons: Edit + Delete — always re-show after any prior GONE/INVISIBLE.
                holder.btnEdit.visibility = View.VISIBLE
                holder.btnDelete.visibility = View.VISIBLE
                holder.btnEdit.isEnabled = true
                holder.btnDelete.isEnabled = true
                holder.btnEdit.isClickable = true
                holder.btnDelete.isClickable = true
                holder.btnEdit.setImageResource(R.drawable.ic_edit)
                holder.btnEdit.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#1AFFFFFF"))
                holder.btnEdit.contentDescription = context.getString(R.string.cd_edit)
                // Trash icon for normal-state delete
                holder.btnDelete.setImageResource(R.drawable.ic_delete)
                holder.btnDelete.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#1AFFFFFF"))
                holder.btnDelete.contentDescription = context.getString(R.string.cd_delete)

                holder.btnEdit.setOnClickListener {
                    if (panelPrefs.hapticEnabled) it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    SpringAnimator.scalePulse(it)
                    onCustomItemEditTapped(itemId)
                }
                holder.btnDelete.setOnClickListener {
                    if (panelPrefs.hapticEnabled) it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    SpringAnimator.scalePulse(it)
                    onCustomItemDeleteTapped(itemId)
                }

                // Drag enabled — re-arm every bind so recycled first/last rows
                // never keep a null listener from modify/edit states.
                holder.dragHandle.alpha = 1.0f
                holder.dragHandle.isEnabled = true
                holder.dragHandle.isClickable = true
                holder.dragHandle.setOnTouchListener { v, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            // Prevent the parent RecyclerView / sidebar scroll from
                            // stealing the gesture once a drag is armed. Without
                            // disallowIntercept the vertical scroll can cancel the
                            // ItemTouchHelper drag, making edge rows undraggable.
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                            // Use adapterPosition so a recycled holder still starts
                            // drag for the currently bound row, not a stale one.
                            val pos = holder.bindingAdapterPosition
                            if (pos != RecyclerView.NO_POSITION) {
                                itemTouchHelper?.startDrag(holder)
                            }
                            true
                        }
                        MotionEvent.ACTION_MOVE -> true
                        MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> {
                            v.parent?.requestDisallowInterceptTouchEvent(false)
                            false
                        }
                        else -> false
                    }
                }

                // Body (title + content area) opens the custom URL/intent in NORMAL
                // state only. Modify / edit states disable it above.
                holder.body.isEnabled = true
                holder.body.isClickable = true
                holder.body.setOnClickListener {
                    if (panelPrefs.hapticEnabled) it.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    SpringAnimator.scalePulse(holder.body)
                    launchCustomItemContent(ci)
                }
            }
        }

        // --- EMPTY ---
        private fun bindEmpty(holder: EmptyViewHolder, item: PickerItem.EmptyState) {
            holder.tvMessage.text = item.message
        }

        // --- ViewHolders ---
        inner class AppViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val ivIcon: ImageView = view.findViewById(R.id.ivPickerAppIcon)
            val tvName: TextView = view.findViewById(R.id.tvPickerAppName)
            val ivCheck: View = view.findViewById(R.id.ivPickerCheck)
            val vHighlight: View = view.findViewById(R.id.vPickerBgHighlight)
            val tvPackage: TextView? = view.findViewById(R.id.tvPickerPackageName)
        }

        inner class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val ivIcon: ImageView = view.findViewById(R.id.ivHeaderIcon)
            val tvName: TextView = view.findViewById(R.id.tvHeaderName)
            val tvCount: TextView = view.findViewById(R.id.tvHeaderCount)
            val ivChevron: ImageView = view.findViewById(R.id.ivHeaderChevron)
        }

        inner class CustomViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            var isBinding: Boolean = true
            val dragHandle: ImageView = view.findViewById(R.id.ivCustomDragHandle)
            val body: View = view.findViewById(R.id.customBody)
            val readMode: View = view.findViewById(R.id.customReadMode)
            val editMode: View = view.findViewById(R.id.customEditMode)
            val tvTitle: TextView = view.findViewById(R.id.tvCustomTitle)
            val tvContent: TextView = view.findViewById(R.id.tvCustomContent)
            val etTitle: EditText = view.findViewById(R.id.etCustomTitle)
            val etContent: EditText = view.findViewById(R.id.etCustomContent)
            val btnEdit: ImageButton = view.findViewById(R.id.btnCustomEdit)
            val btnDelete: ImageButton = view.findViewById(R.id.btnCustomDelete)
            val badge: ImageView = view.findViewById(R.id.customBadge)
        }

        inner class EmptyViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvMessage: TextView = view.findViewById(R.id.tvEmptyStateMessage)
        }
    }

    private companion object {
        const val VT_APP = 0
        const val VT_HEADER = 1
        const val VT_CUSTOM = 2
        const val VT_EMPTY = 3
        // Length caps + scheme allowlist for custom items now live in
        // CustomItemValidation (pure, unit-tested); see isValidCustom below.

        // UI constants (dp)
        const val SEARCH_CORNER_RADIUS_DP = 22
        const val ICON_SIZE_RICH_DP = 48
        const val ICON_SIZE_NORMAL_DP = 44
        const val CHILD_INDENT_DP = 16
        const val BASE_TEXT_SIZE_RICH = 15f
        const val BASE_TEXT_SIZE_NORMAL = 14f
        const val BASE_PKG_TEXT_SIZE_RICH = 13f
        const val BASE_PKG_TEXT_SIZE_NORMAL = 12f
        // Unified picker item metrics — single source of truth used by
        // bindApp() for every tab (matches item_picker_app_rich.xml):
        // 32dp icon inside the 40dp icon circle, 13sp name, 10sp package.
        const val PICKER_ICON_DP = 32
        const val PICKER_NAME_SP = 13f
        const val PICKER_PKG_SP = 10f

        // Animation timing (ms)
        const val EDIT_MODE_ANIM_DURATION_MS = 400L
        const val EDIT_MODE_ITEM_STAG_DELAY_MS = 30L
    }

    /**
     * Scheme + length validation for a custom URL/intent the user is editing.
     * Delegates to [CustomItemValidation] (pure, unit-tested) so the view
     * and the test exercise the same code.
     */
    internal fun isValidCustom(title: String, content: String): Boolean =
        CustomItemValidation.isValidCustom(title, content)

    /**
     * Audit S2 — expose the picker commit / discard decision so the hosting
     * service can drain pending edits before tearing the picker down.
     *
     * Cancels the in-progress custom-item edit (dropping the draft and
     * removing any unsaved blank item). We keep the signature `internal` so
     * the intent stays contained: this is the *only* legitimate cross-class
     * surface for "I'm about to close the picker".
     */
    internal fun commitPendingEdits() {
        // Custom tab per-item edit (Modify state): cancel discards the draft and
        // removes any unsaved blank item so a stale blank row can't survive
        // picker close. The caller (closePicker) is closing the UI so in-edit
        // feedback would not be visible anyway.
        if (editingCustomId != null) cancelCustomItemEdit()
    }

    /**
     * Audit U6 — when an unsafe custom URL is caught, instead of just toasting
     * and returning, auto-switch the user into edit mode on the offending row so
     * they can fix the string in place. Falls back to plain toast if the offending
     * app doesn't correspond to a custom row in our list.
     */
    private fun showLaunchBlockedUI(app: AppInfo) {
        android.widget.Toast.makeText(
            context,
            context.getString(R.string.custom_uri_returned_to_edit),
            android.widget.Toast.LENGTH_SHORT
        ).show()
        val customPrefix = PanelPreferences.CUSTOM_ID_PREFIX
        val customItemId = if (app.identifier.startsWith(customPrefix)) {
            app.identifier.removePrefix(customPrefix)
        } else null
        if (customItemId != null) {
            // Ensure the row is visible: switch to the URLS tab if we weren't on it.
            if (activeTab != PickerTab.CUSTOM) switchTab(PickerTab.CUSTOM)
            onCustomItemEditTapped(customItemId)
        }
    }

    // ====================================================================================
    //                                Notification strip adapter (preserved, simplified)
    // ====================================================================================
    inner class NotificationStripAdapter : ListAdapter<AppInfo, NotificationStripAdapter.VH>(object : DiffUtil.ItemCallback<AppInfo>() {
        override fun areItemsTheSame(o: AppInfo, n: AppInfo) = o.identifier == n.identifier
        override fun areContentsTheSame(o: AppInfo, n: AppInfo) = o.appName == n.appName
    }) {
        private var accent: Int = Color.parseColor("#4A9EFF")
        fun setAccentColor(c: Int) { accent = c }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_picker_notification, parent, false)
            return VH(v)
        }
        override fun onBindViewHolder(h: VH, position: Int) {
            val app = getItem(position)
            val scale = panelPrefs.scaleFactor
            val iconSize = (ICON_SIZE_NORMAL_DP * scale).toInt()
            h.ivIcon.layoutParams.let { lp ->
                lp.width = iconSize; lp.height = iconSize
                h.ivIcon.layoutParams = lp
            }
            h.tvName.textSize = 10f * scale
            h.tvName.text = app.appName
            Glide.with(h.itemView.context.applicationContext).clear(h.ivIcon)
            Glide.with(h.itemView.context.applicationContext)
                .load(AppIconRequest(app.packageName, panelPrefs.appearanceKey))
                .placeholder(android.R.drawable.sym_def_app_icon)
                .into(h.ivIcon)
            IconShapeHelper.applyShape(h.ivIcon, panelPrefs.iconShape)
            h.itemView.setOnClickListener { launchAppViaNotif(app) }
        }
        private fun launchAppViaNotif(app: AppInfo) {
            val intent = context.packageManager.getLaunchIntentForPackage(app.packageName)
            if (intent != null) {
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                try { context.startActivity(intent) } catch (e: Exception) { Log.e("AppPickerPanelView", "launchAppViaNotif", e) }
            }
            onAppLaunched?.invoke()
        }
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val ivIcon: ImageView = v.findViewById(R.id.ivPickerAppIcon)
            val tvName: TextView = v.findViewById(R.id.tvPickerAppName)
            val ivCheck: View = v.findViewById(R.id.ivPickerCheck)
        }
    }
}
