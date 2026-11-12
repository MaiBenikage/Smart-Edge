package com.imi.smartedge.sidebar.panel

import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy

class PanelAppsAdapter(
    private val context: Context,
    private val onAddClick: () -> Unit,
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
        submitList(list, null)
    }

    fun submitList(list: List<AppInfo>?, commitCallback: Runnable?) {
        // DiffUtil on identifier: only changed/moved rows rebind and
        // reload their icons, instead of every row on every refresh, and
        // change animations actually work. The commit is synchronous —
        // dispatchUpdatesTo applies the updates before returning — so the
        // callback runs with the new list already in place.
        val newList = list?.toList() ?: emptyList()
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = mutableApps.size
            override fun getNewListSize(): Int = newList.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                mutableApps[oldItemPosition].identifier == newList[newItemPosition].identifier
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                mutableApps[oldItemPosition] == newList[newItemPosition]
        })
        mutableApps = newList.toMutableList()
        diff.dispatchUpdatesTo(this)
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

    /** Live media-volume step count for this phone (e.g. 160, 15, 7). */
    private fun mediaMaxSteps(): Int {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        return audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
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

        // Audit fix: Color.parseColor() was called on every bind (per row,
        // per scroll frame). Parse once, reuse the ints.
        // #D9FFFFFF as a signed 32-bit int is -637534209 (-0x26000001);
        // #33FFFFFF and #4DFFFFFF fit in a positive Int as-is.
        private const val LABEL_COLOR = -0x26000001 // #D9FFFFFF
        private const val TOOL_BG_COLOR = 0x33FFFFFF // #33FFFFFF
        private const val ADD_BG_COLOR = 0x4DFFFFFF // #4DFFFFFF
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
        val scale = panelPrefs.scaleFactor
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
            // Mirror the Add holder's reset: RecyclerView reuses itemView
            // instances, and scalePulse() (click / drag-tool DOWN) leaves
            // the view mid-animation if it is rebound before the pulse
            // finishes — the new item would show shrunk (0.9) or faded
            // until the stale end-action fires. Cancel and restore identity.
            holder.itemView.animate().cancel()
            holder.itemView.alpha = 1f
            holder.itemView.scaleX = 1f
            holder.itemView.scaleY = 1f
            // Restore original sizes + scaling
            var baseIconSize = if (isRich) 44 else 40
            if (currentColumns == 2) baseIconSize = (baseIconSize * 1.1).toInt() // 10% larger in 2-col
            // The Glide override below must match this size in px: a fixed
            // 120px bitmap is upscaled (blurry) on high-density screens.
            val iconPx = (context.dpToPx(baseIconSize) * scale).toInt()
            val baseTextSize = if (isRich) 9f else 8f

            holder.ivIcon.layoutParams.let { lp ->
                lp.width = iconPx
                lp.height = iconPx
                holder.ivIcon.layoutParams = lp
            }
            holder.tvName.textSize = baseTextSize * scale
            
            // Keep app labels white for the dark floating panel
            holder.tvName.setTextColor(LABEL_COLOR)

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

                // Vector tool icons from drawables (ToolIconHelper maps ids
                // to drawable resources; no assets/SVG loading).
                val toolIcon = if (app.type == AppInfo.Type.TOOL || app.packageName == "smartedge.shortcut.reboot") {
                    ToolIconHelper.forToolId(context, app.packageName)
                } else null
                if (toolIcon != null) {
                    holder.ivIcon.setImageDrawable(toolIcon)
                    holder.ivIcon.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
                    holder.ivIcon.background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(TOOL_BG_COLOR)
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
                        setColor(TOOL_BG_COLOR)
                        cornerRadius = context.dpToPx(12).toFloat()
                    }
                    holder.ivIcon.setPadding(context.dpToPx(8), context.dpToPx(8), context.dpToPx(8), context.dpToPx(8))
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                    holder.ivIcon.clipToOutline = true
                }
                // Reset what the app branch sets on every bind: a holder
                // recycled from a real app keeps that app's circle/squircle
                // outline provider and CENTER_CROP, which would clip the
                // tool icon to the wrong shape. Tool cells use the rounded
                // GradientDrawable background as their outline, with the
                // vector fitted inside the padding.
                holder.ivIcon.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                holder.ivIcon.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
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
                    .override(iconPx, iconPx)
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
                    app.type == AppInfo.Type.CUSTOM -> {
                        // Custom sidebar rows: content is either an `intent:#Intent;...`
                        // URI or a plain URL. Match the picker's handling exactly —
                        // parse intent URIs, launch plain URLs with ACTION_VIEW.
                        // isSafeIntentUri refuses only unparseable intent: strings;
                        // cross-app components & selector chains are allowed by policy.
                        if (!context.isSafeIntentUri(app.intentUri)) {
                            android.widget.Toast.makeText(
                                context,
                                context.getString(R.string.toast_custom_item_blocked),
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                            null
                        } else {
                            try {
                                if (app.intentUri.orEmpty().startsWith("intent:")) {
                                    Intent.parseUri(app.intentUri, Intent.URI_INTENT_SCHEME)
                                } else {
                                    Intent(Intent.ACTION_VIEW, android.net.Uri.parse(app.intentUri))
                                }
                            } catch (e: Exception) {
                                null
                            }
                        }
                    }
                    app.intentUri != null -> {
                        // Aligned with AppPickerPanelView.launchApp(): ACTIVITY /
                        // SHORTCUT rows come from the picker enumeration (exported
                        // activities / PackageManager-resolved shortcuts), so their
                        // components are certified launchable — no gate. The safety
                        // gate applies ONLY to hand-authored CUSTOM rows above.
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
                        } else if (app.type == AppInfo.Type.SHORTCUT &&
                            context.tryLaunchShortcut(app.packageName, app.activityName)) {
                            // Launched via LauncherApps — nothing more to do.
                        } else {
                            context.startActivity(launchIntent)
                        }
                    } catch (e: Exception) {
                        Log.e("PanelAppsAdapter", "Failed to launch app", e)
                    }
                    // Record for the sidebar "Recent" section (real apps only;
                    // tools / custom rows / internal shortcuts are excluded).
                    // The accessibility-service foreground tracker also records
                    // launches; RecentAppsManager debounces the duplicate.
                    if ((app.type == AppInfo.Type.APP ||
                            app.type == AppInfo.Type.ACTIVITY ||
                            app.type == AppInfo.Type.SHORTCUT) &&
                        !app.packageName.startsWith("smartedge.")
                    ) {
                        try {
                            RecentAppsManager(context.applicationContext)
                                .recordLaunch(app.packageName, RecentAppsManager.SOURCE_CLICK)
                        } catch (_: Exception) {}
                    }
                    // Close panel AFTER initiating launch
                    onAppLaunched()
                }
            }

            // Long-press (600ms, LONG_PRESS_DRAG_MS) then drag to adjust volume/brightness in Tools folder.
            // Tap without long-press triggers the shared tool click (system volume UI /
            // auto-brightness toggle). Matches dashboard gesture semantics.
            if (app.type == AppInfo.Type.TOOL && DRAG_TOOLS.contains(app.packageName) && onToolDrag != null) {
                val dragState = FloatArray(3)
                val density = context.resources.displayMetrics.density
                // Distance per tick depends on the tool: volume ticks are
                // one hardware media step (14dp was the 1% tick distance,
                // and it now scales to the phone's own media step count,
                // see VolumeStepMath, so the full-range drag distance stays
                // the same), but brightness ticks are 1% each, so they use
                // the dashboard's fixed 3dp — using the volume distance for
                // brightness made one percent cost 93dp on a 15-step phone.
                // Both are scaled by slideSensitivity, like the dashboard
                // and edge-handle gestures (see handleDragTouch).
                val isBrightnessTool = app.packageName == "smartedge.tool.brightness_up"
                val baseDp = if (isBrightnessTool) 3f
                    else VolumeStepMath.dpPerStep(mediaMaxSteps(), 14f)
                val sensitivityMultiplier = 100f / panelPrefs.slideSensitivity.coerceIn(1, 200)
                // Clamp to ≥1px like the other callers: a sub-pixel tick
                // distance would make one pixel of movement fire hundreds
                // of ticks.
                val tickPx = (baseDp * density * sensitivityMultiplier).coerceAtLeast(1f)
                val tapSlopPx = 8f * density
                val longPressMs = LONG_PRESS_DRAG_MS
                holder.itemView.setOnTouchListener { v, event ->
                    when (event.actionMasked) {
                        android.view.MotionEvent.ACTION_DOWN -> {
                            // In edit mode the row is a reorder handle for
                            // ItemTouchHelper: don't consume the gesture or
                            // arm the drag-to-adjust, or ITH can never start
                            // a drag on these rows.
                            if (isEditMode) return@setOnTouchListener false
                            dragState[0] = event.rawY
                            dragState[1] = event.rawY
                            dragState[2] = event.rawY
                            v.setTag(R.id.tag_drag_armed, false)
                            (v.getTag(R.id.tag_drag_arm_runnable) as? Runnable)?.let { v.removeCallbacks(it) }
                            val arm = Runnable {
                                v.setTag(R.id.tag_drag_armed, true)
                                dragState[1] = dragState[0]
                                // Only now, once the long-press has fired
                                // and the drag-to-adjust is actually
                                // starting, stop the parent RecyclerView
                                // from intercepting the gesture. Doing it
                                // on DOWN blocked list scrolling and ITH
                                // reorder for the whole gesture.
                                v.parent?.requestDisallowInterceptTouchEvent(true)
                                if (panelPrefs.hapticEnabled) {
                                    v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                                }
                            }
                            v.setTag(R.id.tag_drag_arm_runnable, arm)
                            v.postDelayed(arm, longPressMs)
                            if (panelPrefs.hapticEnabled) {
                                v.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                            }
                            SpringAnimator.scalePulse(v)
                            true
                        }
                        android.view.MotionEvent.ACTION_MOVE -> {
                            dragState[0] = event.rawY
                            val armed = v.getTag(R.id.tag_drag_armed) as? Boolean ?: false
                            if (!armed) {
                                val travel = Math.abs(event.rawY - dragState[2])
                                if (travel > tapSlopPx * 2f) {
                                    (v.getTag(R.id.tag_drag_arm_runnable) as? Runnable)?.let { v.removeCallbacks(it) }
                                }
                                return@setOnTouchListener true
                            }
                            val sinceLastTick = dragState[1] - event.rawY
                            // Pacing (see SlideStepMath): at most MAX_STEPS_PER_MOVE
                            // per event — a fast flick or a coalesced MOVE used to
                            // dump a whole slide's worth of ticks in one frame, and
                            // the applier landed them as one absolute set (~10% in
                            // one jump). The remainder stays in the anchor offset
                            // and drains on later MOVEs and at gesture end (see
                            // ACTION_UP below).
                            val ticks = SlideStepMath.stepsToEmit(sinceLastTick, tickPx)
                            if (ticks != 0) {
                                val direction = if (ticks > 0) +1 else -1
                                repeat(Math.abs(ticks)) { onToolDrag.invoke(app.packageName, direction) }
                                // Keep the sub-tick remainder: advance the
                                // anchor by exactly ticks * tickPx instead of
                                // snapping it to rawY, so the leftover
                                // fraction still counts toward the next tick
                                // and a slow drag moves at the same rate as a
                                // fast one (instead of losing up to a tick per event).
                                dragState[1] -= ticks * tickPx
                            }
                            true
                        }
                        android.view.MotionEvent.ACTION_UP -> {
                            v.animate().scaleX(1f).scaleY(1f).setDuration(SCALE_RESET_DURATION_MS).start()
                            (v.getTag(R.id.tag_drag_arm_runnable) as? Runnable)?.let { v.removeCallbacks(it) }
                            val armed = v.getTag(R.id.tag_drag_armed) as? Boolean ?: false
                            val totalTravel = Math.abs(event.rawY - dragState[2])
                            if (!armed && totalTravel < tapSlopPx) {
                                onToolClick(app.packageName)
                            }
                            // Drain the ticks the per-MOVE cap held back (see
                            // SlideStepMath), so the value lands exactly where
                            // the finger stopped. Only when the drag was armed.
                            if (armed) {
                                val left = SlideStepMath.drainSteps(dragState[1] - event.rawY, tickPx)
                                if (left != 0) {
                                    val direction = if (left > 0) +1 else -1
                                    repeat(Math.abs(left)) { onToolDrag.invoke(app.packageName, direction) }
                                }
                            }
                            v.setTag(R.id.tag_drag_armed, false)
                            true
                        }
                        android.view.MotionEvent.ACTION_CANCEL -> {
                            v.animate().scaleX(1f).scaleY(1f).setDuration(SCALE_RESET_DURATION_MS).start()
                            (v.getTag(R.id.tag_drag_arm_runnable) as? Runnable)?.let { v.removeCallbacks(it) }
                            val armed = v.getTag(R.id.tag_drag_armed) as? Boolean ?: false
                            if (armed) {
                                val left = SlideStepMath.drainSteps(dragState[1] - event.rawY, tickPx)
                                if (left != 0) {
                                    val direction = if (left > 0) +1 else -1
                                    repeat(Math.abs(left)) { onToolDrag.invoke(app.packageName, direction) }
                                }
                            }
                            v.setTag(R.id.tag_drag_armed, false)
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
            val bgTint = ADD_BG_COLOR
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
                onAddClick()
            }
        }
    }
}
