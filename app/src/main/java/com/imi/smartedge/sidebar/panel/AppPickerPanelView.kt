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
import androidx.recyclerview.widget.GridLayoutManager
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

    /** Fired when a user toggles a regular app, activity, or shortcut in edit mode. */
    var onToggleApp: ((AppInfo, Boolean) -> Unit)? = null

    /** Fired when the user adds a new custom item (URLS tab, + ADD). */
    var onAddCustomItem: ((CustomItem) -> Unit)? = null

    /** Fired when a custom item's title/content are saved. */
    var onUpdateCustomItem: ((CustomItem) -> Unit)? = null

    /** Fired when a custom item is removed (swipe or future "delete" button). */
    var onRemoveCustomItem: ((String) -> Unit)? = null

    // ====================================================================================
    //                                    State
    // ====================================================================================
    var isEditMode = false
        private set

    private var activeTab: PickerTab = PickerTab.APPS
    private val expandedPackages = linkedSetOf<String>()
    private var editingItemId: String? = null

    // In-progress edit cache, keyed by item id. A TextWatcher on each
    // CustomRow's etTitle/etContent writes here on every keystroke so that
    // [saveEditingItem] can recover the user's typed text even when the row
    // has scrolled offscreen and its ViewHolder has been recycled (in which
    // case [findViewHolderForAdapterPosition] returns null).
    private val pendingCustomEdits = mutableMapOf<String, Pair<String, String>>()

    // Lazy data for each tab. Populated on first switch.
    private var allApps: List<AppInfo> = emptyList()
    private var activitiesByPackage: List<Pair<String, List<AppInfo>>> = emptyList()
    private var shortcutsByPackage: List<Pair<String, List<AppInfo>>> = emptyList()
    private var customItems: MutableList<CustomItem> = mutableListOf()

    // ====================================================================================
    //                                    Views
    // ====================================================================================
    private val pickerPanelCard: View
    private val rvPickerGrid: RecyclerView
    private val etSearch: EditText
    private val btnSettings: ImageButton
    private val btnEdit: TextView          // tab-aware: "EDIT" or "+ ADD"
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
    private var itemTouchHelper: ItemTouchHelper? = null
    private var lastMaxPx: Int = -1
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

        // Search → rebuild flattened list
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                rebuildAndSubmit()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Hide keyboard on scroll
        rvPickerGrid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING && etSearch.hasFocus()) {
                    etSearch.clearFocus()
                    hideKeyboard()
                }
            }
        })
        rvPickerGrid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (panelPrefs.rememberScroll) {
                    panelPrefs.lastPickerScroll = rv.computeVerticalScrollOffset()
                }
            }
        })

        // Layout manager based on theme + active tab
        // Activities/Shortcuts/Custom → vertical list. Apps → grid (2 cols) or list (rich).
        rvPickerGrid.layoutManager = when {
            activeTab == PickerTab.APPS && panelPrefs.uiTheme != PanelPreferences.THEME_RICH ->
                GridLayoutManager(context, 2)
            else -> LinearLayoutManager(context)
        }
        rvPickerGrid.setHasFixedSize(false)
        rvPickerGrid.setItemViewCacheSize(0)
        // setDrawingCacheEnabled() is no longer recommended by Android (deprecated
        // since API 28). Hardware-accelerated rendering (which is the default
        // since API 14) is the supported replacement; passing `false` here was
        // already a no-op because RecyclerView is HW-accelerated by default.
        rvPickerGrid.recycledViewPool.setMaxRecycledViews(0, 0)
        rvPickerGrid.adapter = adapter

        // Drag/swipe helper for custom items
        itemTouchHelper = ItemTouchHelper(buildCustomItemTouchCallback())
        itemTouchHelper?.attachToRecyclerView(rvPickerGrid)

        // Header row buttons
        btnSettings.setOnClickListener {
            val intent = android.content.Intent(context, SettingsMainActivity::class.java).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            onAppLaunched?.invoke()
        }
        btnEdit.setOnClickListener { onTopRightButtonClick() }

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
        NotificationTrackingService.onNotificationsChanged = { _scope.launch { updateNotifications() } }
        updateNotifications()
        // Re-load custom items from prefs (in case sidebar sync changed them)
        customItems = panelPrefs.getCustomItems().toMutableList()
        if (activeTab == PickerTab.CUSTOM) rebuildAndSubmit()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        _scope.coroutineContext[Job]?.cancel()
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_UP &&
            event.keyCode == android.view.KeyEvent.KEYCODE_BACK) {
            if (editingItemId != null) {
                val saved = saveEditingItem()
                if (!saved) showInvalidCustomUriToast()
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
        if (activeTab == PickerTab.CUSTOM && editingItemId != null) {
            val id = editingItemId
            if (id != null) {
                val cached = pendingCustomEdits[id]
                val item = customItems.firstOrNull { it.id == id }
                val candidateContent = cached?.second ?: item?.content.orEmpty()
                if (candidateContent.isNotBlank() && !isValidCustom(cached?.first ?: item?.title.orEmpty(), candidateContent)) {
                    showInvalidCustomUriToast()
                }
            }
            saveEditingItem(forceDiscard = true)
        }

        activeTab = newTab

        // Toggle layout manager: apps grid vs everything else list
        rvPickerGrid.layoutManager = when {
            newTab == PickerTab.APPS && panelPrefs.uiTheme != PanelPreferences.THEME_RICH ->
                GridLayoutManager(context, 2)
            else -> LinearLayoutManager(context)
        }

        updateTabUI()
        updateHeaderAndEditButton()

        // Lazy-load data if needed
        when (newTab) {
            PickerTab.APPS -> if (allApps.isEmpty()) loadApps(forceRefresh = false)
            PickerTab.ACTIVITIES -> if (activitiesByPackage.isEmpty()) loadActivities()
            PickerTab.SHORTCUTS -> if (shortcutsByPackage.isEmpty()) loadShortcuts()
            PickerTab.CUSTOM -> customItems = panelPrefs.getCustomItems().toMutableList()
        }

        // Edit mode only makes sense in non-custom tabs. If we were in edit mode
        // and now switched to URLS, exit edit mode visually.
        if (newTab == PickerTab.CUSTOM && isEditMode) {
            isEditMode = false
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
        when (activeTab) {
            PickerTab.APPS -> {
                tvHeader.text = if (isEditMode) "Manage Smart Edge" else "All Apps"
                btnEdit.text = if (isEditMode) "DONE" else "EDIT"
                btnEdit.setTextColor(if (isEditMode) accentColor else Color.parseColor("#4A9EFF"))
            }
            PickerTab.ACTIVITIES -> {
                tvHeader.text = "All Activities"
                btnEdit.text = if (isEditMode) "DONE" else "EDIT"
                btnEdit.setTextColor(if (isEditMode) accentColor else Color.parseColor("#4A9EFF"))
            }
            PickerTab.SHORTCUTS -> {
                tvHeader.text = "All Shortcuts"
                btnEdit.text = if (isEditMode) "DONE" else "EDIT"
                btnEdit.setTextColor(if (isEditMode) accentColor else Color.parseColor("#4A9EFF"))
            }
            PickerTab.CUSTOM -> {
                tvHeader.text = "Custom Intents & URLs"
                btnEdit.text = "+ ADD"
                btnEdit.setTextColor(Color.parseColor("#4A9EFF"))
            }
        }
    }

    private fun onTopRightButtonClick() {
        if (activeTab == PickerTab.CUSTOM) {
            addNewCustomItem()
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
        updateHeaderAndEditButton()
        // Force-rebind the visible items so the check icon visibility updates
        adapter.notifyItemRangeChanged(0, adapter.itemCount, "EDIT_MODE_CHANGE")
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
        allApps = emptyList()
        activitiesByPackage = emptyList()
        shortcutsByPackage = emptyList()
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
                cornerRadius = 22 * density
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
        // Compatibility API — for the active tab, refresh its data.
        if (!forceRefresh && allApps.isNotEmpty() && activeTab == PickerTab.APPS) {
            // Sync in-panel flags from prefs
            val panelIds = panelPrefs.getPanelApps().toSet()
            var changed = false
            allApps.forEach {
                val inPanel = panelIds.contains(it.identifier)
                if (it.isInPanel != inPanel) {
                    it.isInPanel = inPanel
                    changed = true
                }
            }
            if (changed) rebuildAndSubmit()
            return
        }
        when (activeTab) {
            PickerTab.APPS -> loadAppsInternal(forceRefresh)
            PickerTab.ACTIVITIES -> loadActivities()
            PickerTab.SHORTCUTS -> loadShortcuts()
            PickerTab.CUSTOM -> {
                customItems = panelPrefs.getCustomItems().toMutableList()
                rebuildAndSubmit()
            }
        }
    }

    // ====================================================================================
    //                                Data loading
    // ====================================================================================
    private fun loadAppsInternal(@Suppress("UNUSED_PARAMETER") forceRefresh: Boolean) {
        // `forceRefresh` is honored upstream by [loadApps] (which short-circuits
        // the cached allApps). The internal method always re-fetches via
        // repository.getAllApps() so the param is informational here only.
        val originalHeader = tvHeader.text
        tvHeader.text = "Loading Apps..."
        _scope.launch {
            val apps = withContext(Dispatchers.IO) { repository.getAllApps() }
            allApps = apps
            tvHeader.text = originalHeader
            // Sync panel state and rebuild
            val panelIds = panelPrefs.getPanelApps().toSet()
            apps.forEach { it.isInPanel = panelIds.contains(it.identifier) }
            rebuildAndSubmit()
            playStaggeredEntryAnim()
        }
    }

    private fun loadActivities() {
        tvHeader.text = "Scanning Activities..."
        _scope.launch {
            val data = withContext(Dispatchers.IO) { repository.getActivitiesByPackage() }
            activitiesByPackage = data
            tvHeader.text = "All Activities"
            rebuildAndSubmit()
        }
    }

    private fun loadShortcuts() {
        tvHeader.text = "Scanning Shortcuts..."
        _scope.launch {
            val data = withContext(Dispatchers.IO) { repository.getShortcutsByPackage() }
            shortcutsByPackage = data
            tvHeader.text = "All Shortcuts"
            rebuildAndSubmit()
        }
    }

    // ====================================================================================
    //                                Custom item operations
    // ====================================================================================
    private fun addNewCustomItem() {
        // Save any in-progress edit on the previous row before opening a fresh row.
        // Without this, typing content into row A then clicking "+ ADD" silently
        // discards row A's edits because we'd switch editingItemId without commit.
        if (editingItemId != null) saveEditingItem(forceDiscard = true)
        val newItem = CustomItem(
            id = java.util.UUID.randomUUID().toString(),
            isUrl = true,
            title = "",
            content = ""
        )
        customItems.add(newItem)
        editingItemId = newItem.id
        onAddCustomItem?.invoke(newItem)
        rebuildAndSubmit()
        rvPickerGrid.post {
            rvPickerGrid.scrollToPosition(adapter.itemCount - 1)
            // Focus + show keyboard
            val lastHolder = rvPickerGrid.findViewHolderForAdapterPosition(adapter.itemCount - 1)
                as? PickerAdapter.CustomViewHolder
            lastHolder?.let { h ->
                h.etTitle.requestFocus()
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(h.etTitle, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    /**
     * Persist the current edit-mode row. Returns true if the edit cleared
     * (saved, dropped-as-empty, or [forceDiscard] honored), false if rejected
     * for invalid content (user stays in edit mode to fix it).
     *
     * The latest typed text is read from [pendingCustomEdits] first (populated
     * by TextWatchers on every keystroke), then falls back to the live EditTexts
     * on the visible ViewHolder, then to the original stored values. This is
     * what makes the save work for rows that have been scrolled offscreen and
     * recycled out of the ViewHolder pool.
     */
    private fun saveEditingItem(forceDiscard: Boolean = false): Boolean {
        val id = editingItemId ?: return false

        val cached = pendingCustomEdits[id]
        val holder = rvPickerGrid.findViewHolderForAdapterPosition(adapter.currentList.indexOfFirst {
            (it is PickerItem.CustomRow) && it.item.id == id
        }) as? PickerAdapter.CustomViewHolder
        val stored = customItems.firstOrNull { it.id == id }
        val newTitle: String = cached?.first
            ?: holder?.etTitle?.text?.toString()
            ?: stored?.title
            ?: ""
        val newContent: String = cached?.second
            ?: holder?.etContent?.text?.toString()
            ?: stored?.content
            ?: ""

        val idx = customItems.indexOfFirst { it.id == id }
        if (idx < 0) {
            editingItemId = null
            pendingCustomEdits.remove(id)
            return false
        }
        val original = customItems[idx]

        // Always drop brand-new empty items (the user pressed + ADD then never
        // typed anything or canceled by switching away).
        val isEmptyNew = original.title.isBlank() && original.content.isBlank() &&
            newTitle.isBlank() && newContent.isBlank()
        if (isEmptyNew) {
            customItems.removeAt(idx)
            editingItemId = null
            pendingCustomEdits.remove(id)
            rebuildAndSubmit()
            return true
        }

        // Reject invalid content unless the caller explicitly asked to discard.
        if (!isValidCustom(newTitle, newContent)) {
            // Audit L-High: when caller passed forceDiscard=true (typical from
            // switchTab() / addNewCustomItem()), an invalid URI/text MUST be
            // dropped — NOT silently saved. The previous logic skipped the
            // `return false` branch on forceDiscard and fell through to
            // write the invalid string to SharedPreferences anyway, which
            // defeated the point of the validation gate.
            if (forceDiscard) {
                Log.w(
                    "AppPickerPanelView",
                    "saveEditingItem force-discarded invalid custom uri (id=$id, content='${newContent.take(64)}')"
                )
                editingItemId = null
                pendingCustomEdits.remove(id)
                rebuildAndSubmit()
                return true
            }
            Log.w(
                "AppPickerPanelView",
                "saveEditingItem rejected: invalid custom uri (id=$id, content='${newContent.take(64)}')"
            )
            return false
        }

        // Detect URL vs intent from current content (best effort)
        val isUrl = !newContent.trim().startsWith("intent:")
        val updated = original.copy(title = newTitle, content = newContent, isUrl = isUrl)
        customItems[idx] = updated
        if (original.title != newTitle || original.content != newContent || original.isUrl != isUrl) {
            onUpdateCustomItem?.invoke(updated)
        }
        panelPrefs.updateCustomItem(updated)
        editingItemId = null
        pendingCustomEdits.remove(id)
        rebuildAndSubmit()
        return true
    }

    private fun showInvalidCustomUriToast() {
        android.widget.Toast.makeText(
            context,
            "Must start with intent:, http://, or https://  (max 64 / 2048 chars)",
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    private fun removeCustomItem(id: String) {
        val idx = customItems.indexOfFirst { it.id == id }
        if (idx < 0) return
        customItems.removeAt(idx)
        panelPrefs.removeCustomItem(id)
        pendingCustomEdits.remove(id)
        onRemoveCustomItem?.invoke(id)
        if (editingItemId == id) editingItemId = null
        rebuildAndSubmit()
    }

    /** Called from a custom row's edit button. */
    private fun onCustomEditButtonTapped(id: String) {
        if (editingItemId == id) {
            // Save & exit
            saveEditingItem()
        } else {
            // Save previous, then enter edit on new
            if (editingItemId != null) saveEditingItem()
            editingItemId = id
            rebuildAndSubmit()
            // Focus title field of the now-editing row
            val pos = adapter.currentList.indexOfFirst {
                (it is PickerItem.CustomRow) && it.item.id == id
            }
            if (pos >= 0) {
                val holder = rvPickerGrid.findViewHolderForAdapterPosition(pos) as? PickerAdapter.CustomViewHolder
                holder?.let { h ->
                    h.etTitle.requestFocus()
                    (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                        .showSoftInput(h.etTitle, InputMethodManager.SHOW_IMPLICIT)
                }
            }
        }
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
                if (allApps.isEmpty()) {
                    result.add(PickerItem.EmptyState("No apps found"))
                } else {
                    val filtered = if (q.isEmpty()) allApps else allApps.filter { matchesQuery(it.appName, q) }
                    if (filtered.isEmpty()) {
                        result.add(PickerItem.EmptyState("No matches for \"$q\""))
                    } else {
                        filtered.forEach { result.add(PickerItem.AppRow(it)) }
                    }
                }
            }
            PickerTab.ACTIVITIES -> {
                if (activitiesByPackage.isEmpty()) {
                    result.add(PickerItem.EmptyState("No activities available"))
                } else {
                    var anyMatches = false
                    activitiesByPackage.forEach { (pkg, acts) ->
                        val matching = if (q.isEmpty()) acts else acts.filter { matchesQuery(it.appName, q) }
                        val expanded = expandedPackages.contains(pkg) || (!q.isEmpty() && matching.isNotEmpty())
                        if (q.isEmpty() || matching.isNotEmpty() || expandedPackages.contains(pkg)) {
                            anyMatches = true
                            val appName = acts.firstOrNull()?.let { resolveAppLabel(it.packageName) } ?: pkg
                            result.add(PickerItem.TreeHeader(pkg, appName, acts.size, expanded))
                            if (expanded) {
                                matching.forEach { result.add(PickerItem.TreeChild(it)) }
                            }
                        }
                    }
                    if (!anyMatches) result.add(PickerItem.EmptyState("No activities match \"$q\""))
                }
            }
            PickerTab.SHORTCUTS -> {
                if (shortcutsByPackage.isEmpty()) {
                    val msg = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) {
                        "Requires Android 7.1+"
                    } else {
                        "No shortcuts available. Set Smart Edge as default launcher to read app shortcuts."
                    }
                    result.add(PickerItem.EmptyState(msg))
                } else {
                    var anyMatches = false
                    shortcutsByPackage.forEach { (pkg, scs) ->
                        val matching = if (q.isEmpty()) scs else scs.filter { matchesQuery(it.appName, q) }
                        val expanded = expandedPackages.contains(pkg) || (!q.isEmpty() && matching.isNotEmpty())
                        if (q.isEmpty() || matching.isNotEmpty() || expandedPackages.contains(pkg)) {
                            anyMatches = true
                            val appName = scs.firstOrNull()?.let { resolveAppLabel(it.packageName) } ?: pkg
                            result.add(PickerItem.TreeHeader(pkg, appName, scs.size, expanded))
                            if (expanded) {
                                matching.forEach { result.add(PickerItem.TreeChild(it)) }
                            }
                        }
                    }
                    if (!anyMatches) result.add(PickerItem.EmptyState("No shortcuts match \"$q\""))
                }
            }
            PickerTab.CUSTOM -> {
                if (customItems.isEmpty()) {
                    result.add(PickerItem.EmptyState("Tap + ADD to create your first item"))
                } else {
                    val filtered = if (q.isEmpty()) customItems else customItems.filter {
                        matchesQuery(it.title, q) || matchesQuery(it.content, q)
                    }
                    if (filtered.isEmpty()) {
                        result.add(PickerItem.EmptyState("No matches for \"$q\""))
                    } else {
                        filtered.forEach { result.add(PickerItem.CustomRow(it, it.id == editingItemId)) }
                    }
                }
            }
        }
        return result
    }

    private fun resolveAppLabel(packageName: String): String {
        return try {
            val ai = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(ai).toString()
        } catch (e: Exception) {
            packageName
        }
    }

    private fun rebuildAndSubmit() {
        val items = buildFlattenedList()
        adapter.submitList(items) {
            updatePickerHeight()
        }
    }

    private fun updatePickerHeight() {
        if (lastMaxPx == -1) return
        val lp = rvPickerGrid.layoutParams
        val itemsCount = if (etSearch.text.isEmpty()) adapter.itemCount else adapter.itemCount
        if (itemsCount == 0) {
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            rvPickerGrid.layoutParams = lp
            return
        }
        val density = resources.displayMetrics.density
        val theme = panelPrefs.uiTheme
        val isRich = theme == PanelPreferences.THEME_RICH

        val itemHeightDp = when {
            activeTab != PickerTab.APPS -> 64  // header rows + custom rows are taller
            isRich -> 72
            else -> 100
        }
        val itemHeightPx = (itemHeightDp * density).toInt()
        val cols = when {
            activeTab != PickerTab.APPS -> 1
            isRich -> 1
            else -> 2
        }
        val rows = Math.ceil(itemsCount.toDouble() / cols).toInt()
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
                    .setDuration(400)
                    .setStartDelay(i * 30L)
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
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun isLongPressDragEnabled(): Boolean = false  // drag from handle only

            override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int {
                if (activeTab != PickerTab.CUSTOM) return 0
                if (editingItemId != null) return 0  // block gestures while editing
                if (vh !is PickerAdapter.CustomViewHolder) return 0
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
                // Swap in the underlying data + UI list
                val item = customItems.removeAt(fromPos)
                customItems.add(toPos, item)
                adapter.notifyItemMoved(fromPos, toPos)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val h = viewHolder as? PickerAdapter.CustomViewHolder ?: return
                val pos = h.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return
                val item = customItems.getOrNull(pos) ?: return
                removeCustomItem(item.id)
            }

            override fun clearView(rv: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(rv, viewHolder)
                // Persist the new order after a drag
                if (editingItemId == null) {
                    panelPrefs.reorderCustomItems(customItems)
                    // Audit L3 — mirror the new custom-list order into the sidebar
                    // (KEY_PANEL_APPS) so the user sees the same arrangement in both
                    // surfaces, not in only one of them.
                    panelPrefs.resyncPanelAppsOrderFromCustomItems(customItems)
                }
            }
        }
    }

    // ====================================================================================
    //                                PickerItem sealed class
    // ====================================================================================
    sealed class PickerItem {
        abstract val stableId: String

        data class AppRow(val app: AppInfo) : PickerItem() {
            override val stableId = "app_${app.identifier}"
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
            override val stableId = "child_${app.identifier}"
        }

        data class CustomRow(val item: CustomItem, val isEditing: Boolean) : PickerItem() {
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
                VT_APP -> {
                    val layoutId = if (panelPrefs.uiTheme == PanelPreferences.THEME_RICH)
                        R.layout.item_picker_app_rich else R.layout.item_picker_app_modern
                    AppViewHolder(inflater.inflate(layoutId, parent, false))
                }
                VT_HEADER -> HeaderViewHolder(inflater.inflate(R.layout.item_picker_app_header, parent, false))
                VT_CUSTOM -> CustomViewHolder(inflater.inflate(R.layout.item_picker_custom_url, parent, false))
                VT_EMPTY -> EmptyViewHolder(inflater.inflate(R.layout.item_picker_empty_state, parent, false))
                else -> error("Unknown viewType $viewType")
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = getItem(position)) {
                is PickerItem.AppRow -> bindApp(holder as AppViewHolder, item.app, isChild = false)
                is PickerItem.TreeChild -> bindApp(holder as AppViewHolder, item.app, isChild = true)
                is PickerItem.TreeHeader -> bindHeader(holder as HeaderViewHolder, item)
                is PickerItem.CustomRow -> bindCustom(holder as CustomViewHolder, item)
                is PickerItem.EmptyState -> bindEmpty(holder as EmptyViewHolder, item)
            }
        }

        // --- APP / CHILD ---
        private fun bindApp(holder: AppViewHolder, app: AppInfo, isChild: Boolean) {
            val scale = context.getAutoScalingFactor() * panelPrefs.scaleFactor
            val isRich = panelPrefs.uiTheme == PanelPreferences.THEME_RICH
            val baseIconSize = if (isRich) 48 else 44
            val baseTextSize = if (isRich) 11f else 10f
            val basePkgTextSize = if (isRich) 10f else 9f

            holder.ivIcon.layoutParams.let { lp ->
                lp.width = (context.dpToPx(baseIconSize) * scale).toInt()
                lp.height = (context.dpToPx(baseIconSize) * scale).toInt()
                holder.ivIcon.layoutParams = lp
            }
            holder.tvName.textSize = baseTextSize * scale
            holder.tvPackage?.textSize = basePkgTextSize * scale
            holder.tvName.text = app.appName
            holder.tvPackage?.text = app.packageName

            // Indent child rows to show tree structure
            holder.itemView.setPadding(
                if (isChild) (16 * resources.displayMetrics.density).toInt() else 0,
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

        private fun launchApp(app: AppInfo) {
            val pos = adapter.currentList.indexOfFirst {
                (it is PickerItem.AppRow && it.app.identifier == app.identifier) ||
                (it is PickerItem.TreeChild && it.app.identifier == app.identifier)
            }
            rvPickerGrid.findViewHolderForAdapterPosition(pos)?.itemView?.let { SpringAnimator.scalePulse(it) }

            // Audit S1: hoist the self-package component validation. Previously
            // only the CUSTOM branch had it; activities/shortcuts with `intent:`
            // uris bypassed the check, allowing `intent:#Intent;component=com.victim/.X`
            // to launch any exported=false activity from the sidebar.
            if (!context.isSafeIntentUri(app.intentUri)) {
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
            val scale = context.getAutoScalingFactor()
            holder.tvName.text = item.appName
            holder.tvCount.text = item.childCount.toString()
            holder.ivChevron.rotation = if (item.isExpanded) 90f else 0f
            Glide.with(holder.itemView.context.applicationContext).clear(holder.ivIcon)
            Glide.with(holder.itemView.context.applicationContext)
                .load(AppIconRequest(item.packageName, panelPrefs.appearanceKey))
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .placeholder(android.R.drawable.sym_def_app_icon)
                .error(android.R.drawable.sym_def_app_icon)
                .override((96 * scale).toInt(), (96 * scale).toInt())
                .into(holder.ivIcon)
            holder.itemView.setOnClickListener { toggleHeaderExpansion(item.packageName) }
        }

        // --- CUSTOM ---
        private fun bindCustom(holder: CustomViewHolder, item: PickerItem.CustomRow) {
            val ci = item.item
            val itemId = ci.id
            // Audit L5: freeze recyclability while this row's edit session owns the
            // live TextWatchers. If RecyclerView reused this holder while editing
            // was active, the old watchers (tagged on etTitle/etContent) would
            // reconstruct on rebind but a stale afterTextChanged could fire mid-bind
            // and pollute pendingCustomEdits[staleId]. Marking the holder
            // non-recyclable during editing guarantees its watchers stay in-sync.
            holder.setIsRecyclable(!item.isEditing)
            if (item.isEditing) {
                holder.readMode.visibility = View.GONE
                holder.editMode.visibility = View.VISIBLE

                // Attach TextWatchers BEFORE the setText below. Two reasons:
                //  (a) any user keystroke after this bind lands in pendingCustomEdits,
                //      so saveEditingItem() can recover text on recycled/off-screen rows.
                //  (b) the setText itself fires the watcher once, self-seeding the cache
                //      with the freshly-populated ci.title / ci.content values.
                val titleWatcher = object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        if (holder.isBinding) return
                        val t = holder.etTitle.text?.toString().orEmpty()
                        val c = holder.etContent.text?.toString().orEmpty()
                        pendingCustomEdits[itemId] = t to c
                    }
                }
                val contentWatcher = object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        if (holder.isBinding) return
                        val t = holder.etTitle.text?.toString().orEmpty()
                        val c = holder.etContent.text?.toString().orEmpty()
                        pendingCustomEdits[itemId] = t to c
                    }
                }
                // Detach any watcher left over from a prior bind of this same
                // ViewHolder (RecyclerView recycles holders) so old ids don't
                // leak into the cache on subsequent bindings.
                (holder.etTitle.getTag(R.id.etCustomTitle) as? TextWatcher)?.let {
                    holder.etTitle.removeTextChangedListener(it)
                }
                (holder.etContent.getTag(R.id.etCustomContent) as? TextWatcher)?.let {
                    holder.etContent.removeTextChangedListener(it)
                }
                holder.etTitle.addTextChangedListener(titleWatcher)
                holder.etTitle.setTag(R.id.etCustomTitle, titleWatcher)
                holder.etContent.addTextChangedListener(contentWatcher)
                holder.etContent.setTag(R.id.etCustomContent, contentWatcher)

                // Populate EditTexts. Watchers fire and self-seed pendingCustomEdits
                // with (ci.title, ci.content).
                holder.isBinding = true
                if (holder.etTitle.text.toString() != ci.title) holder.etTitle.setText(ci.title)
                if (holder.etContent.text.toString() != ci.content) holder.etContent.setText(ci.content)
                holder.isBinding = false
                // Edit-text hint: detect URL vs intent from content
                if (ci.content.isBlank()) {
                    holder.etContent.hint = "URL or intent:#Intent;…end"
                } else if (ci.content.startsWith("intent:")) {
                    holder.etContent.hint = ""
                } else {
                    holder.etContent.hint = ""
                }
                holder.btnEdit.setImageResource(R.drawable.ic_check)
                holder.btnEdit.contentDescription = "Save"
                // Drag handle dimmed while editing
                holder.dragHandle.alpha = 0.3f
            } else {
                holder.readMode.visibility = View.VISIBLE
                holder.editMode.visibility = View.GONE
                holder.tvTitle.text = ci.title.ifBlank { "Untitled" }
                holder.tvContent.text = ci.content.ifBlank { "(no URL / intent)" }
                holder.btnEdit.setImageResource(R.drawable.ic_edit)
                holder.btnEdit.contentDescription = "Edit"
                holder.dragHandle.alpha = 1.0f
            }

            // Edit button click
            holder.btnEdit.setOnClickListener {
                if (panelPrefs.hapticEnabled) it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                SpringAnimator.scalePulse(it)
                onCustomEditButtonTapped(ci.id)
            }

            // Drag handle: ACTION_DOWN → startDrag
            holder.dragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    if (editingItemId != null) {
                        // Editing → ignore drag
                        return@setOnTouchListener false
                    }
                    itemTouchHelper?.startDrag(holder)
                }
                false
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
            // Audit L6: gate that suppresses the TextWatcher self-seed during bind.
            // When set to true the watcher ignores the next afterTextChanged hit, which
            // is the synthetic echo from holder.etTitle.setText(ci.title); the watcher
            // is then re-armed for genuine user edits.
            var isBinding: Boolean = true
            val dragHandle: ImageView = view.findViewById(R.id.ivCustomDragHandle)
            val readMode: View = view.findViewById(R.id.customReadMode)
            val editMode: View = view.findViewById(R.id.customEditMode)
            val tvTitle: TextView = view.findViewById(R.id.tvCustomTitle)
            val tvContent: TextView = view.findViewById(R.id.tvCustomContent)
            val etTitle: EditText = view.findViewById(R.id.etCustomTitle)
            val etContent: EditText = view.findViewById(R.id.etCustomContent)
            val btnEdit: ImageButton = view.findViewById(R.id.btnCustomEdit)
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
        // Length caps to prevent an abusive pref string (custom-item JSON is
        // persisted to SharedPreferences; ungated text could OOM the read).
        const val MAX_CUSTOM_TITLE_LEN = 64
        const val MAX_CUSTOM_CONTENT_LEN = 2048
        // Allowlist of URI schemes the content may use. Anything else is
        // rejected on save — e.g. file://, content://, javascript: are blocked.
        // Power users can still reach those via raw intent:#Intent;… URIs.
        val ALLOWED_CUSTOM_SCHEMES = listOf("intent:", "http://", "https://")
    }

    /**
     * Scheme + length validation for a custom URL/intent the user is editing.
     * Returns true if the inputs are acceptable to persist.
     */
    // Audit U1: isValidCustom exposed as `internal` so unit tests in
    // app/src/test/.../IsValidCustomTest.kt can verify scheme allowlist + length caps
    // without needing Robolectric or an Activity.
    internal fun isValidCustom(title: String, content: String): Boolean {
        if (title.length > MAX_CUSTOM_TITLE_LEN) return false
        if (content.length > MAX_CUSTOM_CONTENT_LEN) return false
        val s = content.trim().lowercase()
        return ALLOWED_CUSTOM_SCHEMES.any { s.startsWith(it) }
    }

    /**
     * Audit S2 — expose the picker commit / discard decision so the hosting
     * service can drain pending edits before tearing the picker down.
     *
     * Wraps `saveEditingItem()` (which itself either saves valid inputs or
     * drops invalid ones + clears `editingItemId`). We keep the signature
     * `internal` so the intent stays contained: this is the *only* legitimate
     * cross-class surface for "I'm about to close the picker" so callers
     * don't reach into `editingItemId` themselves.
     */
    internal fun commitPendingEdits() {
        if (editingItemId != null) saveEditingItem()
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
            onCustomEditButtonTapped(customItemId)
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
            val scale = context.getAutoScalingFactor() * panelPrefs.scaleFactor
            val iconSize = (44 * scale).toInt()
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
