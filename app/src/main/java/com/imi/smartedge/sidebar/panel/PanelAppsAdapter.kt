package com.imi.smartedge.sidebar.panel

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy

class PanelAppsAdapter(
    private val context: Context,
    private val onRemove: (AppInfo) -> Unit,
    private val onAddClick: (Boolean) -> Unit,
    private val onAppLaunched: () -> Unit,
    private val onFolderClick: (String) -> Unit,
    private val onToolClick: (String) -> Unit,
    private val onToolDrag: ((toolId: String, direction: Int) -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val panelPrefs = PanelPreferences(context)
    private var showAddButton: Boolean = false
    var isEditMode: Boolean = false // Expose to SidePanelView for ItemTouchHelper
    private var currentColumns: Int = 1
    
    private var mutableApps = mutableListOf<AppInfo>()
    val currentList: List<AppInfo> get() = mutableApps

    fun submitList(list: List<AppInfo>?) {
        mutableApps = list?.toMutableList() ?: mutableListOf()
        notifyDataSetChanged()
    }

    fun submitList(list: List<AppInfo>?, commitCallback: Runnable?) {
        mutableApps = list?.toMutableList() ?: mutableListOf()
        notifyDataSetChanged()
        commitCallback?.run()
    }

    fun moveItem(from: Int, to: Int) {
        if (from < 0 || to < 0 || from >= mutableApps.size || to >= mutableApps.size) return
        val item = mutableApps.removeAt(from)
        mutableApps.add(to, item)
        notifyItemMoved(from, to)
    }

    fun getApps(): List<AppInfo> = mutableApps

    fun setShowAddButton(show: Boolean) {
        if (showAddButton != show) {
            showAddButton = show
            isEditMode = show
            notifyDataSetChanged()
        }
    }

    fun setColumns(cols: Int) {
        if (currentColumns != cols) {
            currentColumns = cols
            notifyDataSetChanged()
        }
    }

    fun refreshIcons() {
        notifyDataSetChanged()
    }

    companion object {
        private const val VIEW_TYPE_APP = 0
        private const val VIEW_TYPE_ADD = 1
        private const val VIEW_TYPE_FOLDER = 2
        private const val VIEW_TYPE_TOOL = 3

        /** Tool IDs that support drag-to-adjust gesture. */
        private val DRAG_TOOLS = setOf(
            "smartedge.tool.volume_up",
            "smartedge.tool.brightness_up"
        )
    }

    inner class AppViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val ivIcon: ImageView = itemView.findViewById(R.id.ivAppIcon)
        val tvName: TextView = itemView.findViewById(R.id.tvAppName)
    }

    inner class AddViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val ivAdd: ImageView = itemView.findViewById(R.id.ivAddIcon)
    }

    override fun getItemViewType(position: Int): Int {
        if (position >= mutableApps.size) return VIEW_TYPE_ADD
        return when (mutableApps[position].type) {
            AppInfo.Type.FOLDER -> VIEW_TYPE_FOLDER
            AppInfo.Type.TOOL -> VIEW_TYPE_TOOL
            else -> VIEW_TYPE_APP
        }
    }

    override fun getItemCount(): Int {
        return if (showAddButton) mutableApps.size + 1 else mutableApps.size
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_APP, VIEW_TYPE_FOLDER, VIEW_TYPE_TOOL -> {
                val layoutId = if (panelPrefs.uiTheme == PanelPreferences.THEME_RICH)
                    R.layout.item_panel_app_rich else R.layout.item_panel_app

                // Use applicationContext for Glide — the service-context host
                // outlives the holder, and Glide tracks lifecycle via the app
                // anyway, so this avoids any chance of holding a reference past
                // the view's natural destruction.
                val view = LayoutInflater.from(parent.context)
                    .inflate(layoutId, parent, false)
                AppViewHolder(view)
            }
            else -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_panel_add, parent, false)
                AddViewHolder(view)
            }
        }
    }

    private var highlightIdentifier: String? = null

    fun highlightItem(identifier: String) {
        highlightIdentifier = identifier
        val index = currentList.indexOfFirst { it.identifier == identifier }
        if (index != -1) {
            notifyItemChanged(index)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val scale = context.getAutoScalingFactor() * panelPrefs.scaleFactor
        val isRich = panelPrefs.uiTheme == PanelPreferences.THEME_RICH
        
        if (holder is AppViewHolder) {
            // RecyclerView reuses itemView instances. A cell previously bound to a
            // drag-enabled tool (volume/brightness) carries its OnTouchListener into
            // the next bind. Without clearing it here, a recycled non-drag item
            // (screenshot, content_picker, an app, a folder) would keep the stale
            // touch listener: it swallows ACTION_DOWN (first tap does nothing) and
            // its captured `app` fires the WRONG tool on the next tap. Clear first,
            // then DRAG_TOOLS rebinding below re-installs the drag listener.
            holder.itemView.setOnTouchListener(null)
            // Restore original sizes + scaling
            var baseIconSize = if (isRich) 44 else 40
            if (currentColumns == 2) baseIconSize = (baseIconSize * 1.1).toInt() // 10% larger in 2-col
            
            val baseTextSize = if (isRich) 9f else 8f

            holder.ivIcon.layoutParams.let { lp ->
                lp.width = (context.dpToPx(baseIconSize) * scale).toInt()
                lp.height = (context.dpToPx(baseIconSize) * scale).toInt()
                holder.ivIcon.layoutParams = lp
            }
            holder.tvName.textSize = baseTextSize * scale
            
            // Keep app labels white for the dark floating panel
            holder.tvName.setTextColor(android.graphics.Color.parseColor("#D9FFFFFF"))

            // Adjust padding for 2-column mode to look more centered
            if (currentColumns == 2) {
                holder.itemView.setPadding(context.dpToPx(8), holder.itemView.paddingTop, context.dpToPx(8), holder.itemView.paddingBottom)
            } else {
                holder.itemView.setPadding(context.dpToPx(2), holder.itemView.paddingTop, context.dpToPx(2), holder.itemView.paddingBottom)
            }

            // Fetch from mutableApps so it stays synchronous with rapid dragging
            val app = if (position < mutableApps.size) mutableApps[position] else return
            
            if (app.type == AppInfo.Type.FOLDER || app.type == AppInfo.Type.TOOL || app.packageName.startsWith("smartedge.shortcut.")) {
                Glide.with(context.applicationContext).clear(holder.ivIcon)

                // SVG / vector tool icons (assets first, drawable fallback).
                val toolIcon = if (app.type == AppInfo.Type.TOOL || app.packageName == "smartedge.shortcut.reboot") {
                    ToolIconHelper.forToolId(context, app.packageName, (baseIconSize * scale).toInt().coerceIn(20, 40))
                } else null
                if (toolIcon != null) {
                    holder.ivIcon.setImageDrawable(toolIcon)
                    holder.ivIcon.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
                    holder.ivIcon.background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(android.graphics.Color.parseColor("#33FFFFFF"))
                        cornerRadius = context.dpToPx(12).toFloat()
                    }
                    holder.ivIcon.setPadding(context.dpToPx(6), context.dpToPx(6), context.dpToPx(6), context.dpToPx(6))
                } else {
                    val iconRes = when {
                        app.type == AppInfo.Type.FOLDER -> R.drawable.ic_section_tools
                        app.packageName == "smartedge.tool.tools" -> R.drawable.ic_section_tools
                        app.packageName == "smartedge.shortcut.one_hand" -> android.R.drawable.ic_menu_crop
                        else -> android.R.drawable.sym_def_app_icon
                    }
                    holder.ivIcon.setImageResource(iconRes)
                    holder.ivIcon.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
                    holder.ivIcon.background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(android.graphics.Color.parseColor("#33FFFFFF"))
                        cornerRadius = context.dpToPx(12).toFloat()
                    }
                    holder.ivIcon.setPadding(context.dpToPx(8), context.dpToPx(8), context.dpToPx(8), context.dpToPx(8))
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                    holder.ivIcon.clipToOutline = true
                }
            } else {
                Glide.with(context.applicationContext).clear(holder.ivIcon)
                holder.ivIcon.imageTintList = null
                holder.ivIcon.background = null
                holder.ivIcon.setPadding(0, 0, 0, 0)

                Glide.with(context.applicationContext)
                    .load(AppIconRequest(app.packageName, panelPrefs.appearanceKey))
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .placeholder(android.R.drawable.sym_def_app_icon)
                    .error(android.R.drawable.sym_def_app_icon)
                    .override((120 * scale).toInt(), (120 * scale).toInt())
                    .into(holder.ivIcon)

                IconShapeHelper.applyShape(holder.ivIcon, panelPrefs.iconShape)
            }
                
            holder.tvName.text = app.appName

            if (app.identifier == highlightIdentifier) {
                SpringAnimator.scalePulse(holder.itemView)
                highlightIdentifier = null
            }

            holder.itemView.setOnClickListener {
                if (panelPrefs.hapticEnabled) {
                    holder.itemView.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                }
                SpringAnimator.scalePulse(holder.itemView)

                if (app.type == AppInfo.Type.FOLDER) {
                    onFolderClick(app.identifier)
                    return@setOnClickListener
                }

                if (app.type == AppInfo.Type.TOOL) {
                    if (app.packageName == "smartedge.tool.tools") {
                        onFolderClick("smartedge.folder.tools")
                    } else {
                        onToolClick(app.packageName)
                    }
                    return@setOnClickListener
                }

                val launchIntent = when {
                    app.type == AppInfo.Type.SHORTCUT && app.packageName == "smartedge.shortcut.one_hand" -> {
                        Intent(context, PanelAccessibilityService::class.java).apply {
                            action = PanelAccessibilityService.ACTION_ONE_HANDED
                        }
                    }
                    app.type == AppInfo.Type.SHORTCUT && app.packageName == "smartedge.shortcut.reboot" -> {
                        Intent(context, PanelAccessibilityService::class.java).apply {
                            action = PanelAccessibilityService.ACTION_SHOW_POWER_MENU
                        }
                    }
                    app.intentUri != null -> {
                        // Audit S2 — same safety gate the picker enforces. Without
                        // this check, a custom `intent:` row that targets another
                        // package's unexported activity could be launched directly
                        // from the sidebar, even though the picker would have
                        // blocked the same row on its preview tap.
                        if (!context.isSafeIntentUri(app.intentUri)) {
                            android.widget.Toast.makeText(
                                context,
                                context.getString(R.string.toast_custom_item_blocked),
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                            return@setOnClickListener
                        }
                        try {
                            Intent.parseUri(app.intentUri, Intent.URI_INTENT_SCHEME)
                        } catch (e: Exception) {
                            context.packageManager.getLaunchIntentForPackage(app.packageName)
                        }
                    }
                    else -> context.packageManager.getLaunchIntentForPackage(app.packageName)
                }

                if (launchIntent != null) {
                    launchIntent.addFlags(
                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                    )
                    try {
                        if (app.type == AppInfo.Type.SHORTCUT &&
                            (app.packageName == "smartedge.shortcut.one_hand" ||
                             app.packageName == "smartedge.shortcut.reboot")) {
                            context.startService(launchIntent)
                        } else {
                            context.startActivity(launchIntent)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    // Close panel AFTER initiating launch
                    onAppLaunched()
                }
            }

            // Long-press (1s) then drag to adjust volume/brightness in Tools folder.
            // Tap without long-press triggers the shared tool click (system volume UI /
            // auto-brightness toggle). Matches dashboard gesture semantics.
            if (app.type == AppInfo.Type.TOOL && DRAG_TOOLS.contains(app.packageName) && onToolDrag != null) {
                val dragState = FloatArray(3)
                val density = context.resources.displayMetrics.density
                val tickPx = 14f * density
                val tapSlopPx = 8f * density
                val longPressMs = LONG_PRESS_DRAG_MS
                val armTag = 0x20F15EED
                holder.itemView.setOnTouchListener { v, event ->
                    when (event.actionMasked) {
                        android.view.MotionEvent.ACTION_DOWN -> {
                            dragState[0] = event.rawY
                            dragState[1] = event.rawY
                            dragState[2] = event.rawY
                            v.setTag(armTag, false)
                            (v.getTag(armTag + 1) as? Runnable)?.let { v.removeCallbacks(it) }
                            val arm = Runnable {
                                v.setTag(armTag, true)
                                dragState[1] = dragState[0]
                                if (panelPrefs.hapticEnabled) {
                                    v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                                }
                            }
                            v.setTag(armTag + 1, arm)
                            v.postDelayed(arm, longPressMs)
                            if (panelPrefs.hapticEnabled) {
                                v.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                            }
                            SpringAnimator.scalePulse(v)
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                            true
                        }
                        android.view.MotionEvent.ACTION_MOVE -> {
                            dragState[0] = event.rawY
                            val armed = v.getTag(armTag) as? Boolean ?: false
                            if (!armed) {
                                val travel = Math.abs(event.rawY - dragState[2])
                                if (travel > tapSlopPx * 2f) {
                                    (v.getTag(armTag + 1) as? Runnable)?.let { v.removeCallbacks(it) }
                                }
                                return@setOnTouchListener true
                            }
                            val sinceLastTick = dragState[1] - event.rawY
                            if (Math.abs(sinceLastTick) >= tickPx) {
                                val direction = if (sinceLastTick > 0f) +1 else -1
                                val ticks = (Math.abs(sinceLastTick) / tickPx).toInt().coerceAtLeast(1)
                                repeat(ticks) { onToolDrag?.invoke(app.packageName, direction) }
                                dragState[1] = event.rawY
                            }
                            true
                        }
                        android.view.MotionEvent.ACTION_UP -> {
                            v.animate().scaleX(1f).scaleY(1f).setDuration(SCALE_RESET_DURATION_MS).start()
                            (v.getTag(armTag + 1) as? Runnable)?.let { v.removeCallbacks(it) }
                            val armed = v.getTag(armTag) as? Boolean ?: false
                            val totalTravel = Math.abs(event.rawY - dragState[2])
                            if (!armed && totalTravel < tapSlopPx) {
                                onToolClick(app.packageName)
                            }
                            v.setTag(armTag, false)
                            true
                        }
                        android.view.MotionEvent.ACTION_CANCEL -> {
                            v.animate().scaleX(1f).scaleY(1f).setDuration(SCALE_RESET_DURATION_MS).start()
                            (v.getTag(armTag + 1) as? Runnable)?.let { v.removeCallbacks(it) }
                            v.setTag(armTag, false)
                            true
                        }
                        else -> false
                    }
                }
            }

            holder.itemView.setOnLongClickListener {
                // Edit-mode long-press is handled by ItemTouchHelper (drag to reorder).
                // Drag-to-split was removed (unreliable on Android 14+).
                return@setOnLongClickListener isEditMode
            }
        } else if (holder is AddViewHolder) {
            val baseIconSize = 40
            holder.ivAdd.layoutParams.let { lp ->
                lp.width = (context.dpToPx(baseIconSize) * scale).toInt()
                lp.height = (context.dpToPx(baseIconSize) * scale).toInt()
                holder.ivAdd.layoutParams = lp
            }

            // Revert back to original dark-centric tints for the add button
            val bgTint = android.graphics.Color.parseColor("#4DFFFFFF")
            val iconTint = android.graphics.Color.WHITE
            
            holder.ivAdd.backgroundTintList = android.content.res.ColorStateList.valueOf(bgTint)
            holder.ivAdd.imageTintList = android.content.res.ColorStateList.valueOf(iconTint)

            val tvEdit = holder.itemView.findViewById<TextView>(R.id.tvEdit)
            if (tvEdit != null) {
                tvEdit.setTextColor(android.graphics.Color.WHITE)
                tvEdit.textSize = 11f * scale
            }

            holder.itemView.animate().cancel()
            holder.itemView.alpha = 1f
            holder.itemView.scaleX = 1f
            holder.itemView.scaleY = 1f
            
            holder.itemView.setOnClickListener {
                if (panelPrefs.hapticEnabled) {
                    holder.itemView.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                }
                SpringAnimator.scalePulse(holder.itemView)
                onAddClick(true)
            }
        }
    }
}
