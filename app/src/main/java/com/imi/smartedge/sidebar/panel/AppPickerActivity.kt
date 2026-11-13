package com.imi.smartedge.sidebar.panel

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.imi.smartedge.sidebar.panel.databinding.ActivityAppPickerM3Binding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen activity to add/remove apps from the side panel.
 * Opens from Settings → "Manage Apps". Changes are saved to
 * PanelPreferences immediately on checkbox toggle.
 */
class AppPickerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppPickerM3Binding
    private lateinit var panelPrefs: PanelPreferences
    private lateinit var pickerAdapter: AppPickerAdapter
    private lateinit var repository: AppRepository
    private var allApps: List<AppInfo> = emptyList()
    /** Search debounce (200ms, like the floating picker). */
    private val searchHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var searchDebounceRunnable: Runnable? = null

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppPickerM3Binding.inflate(layoutInflater)
        setContentView(binding.root)

        panelPrefs = PanelPreferences(this)
        repository = AppRepository.shared(this)

        // Toolbar (same pattern as the settings screens).
        binding.toolbar.setNavigationOnClickListener { finish() }

        pickerAdapter = AppPickerAdapter { app, isChecked ->
            if (isChecked) {
                if (!panelPrefs.addApp(app.identifier)) {
                    android.widget.Toast.makeText(
                        this,
                        getString(R.string.panel_apps_limit_reached, PanelPreferences.MAX_PANEL_APPS),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    // Rebind this row directly: the checkbox widget is
                    // already visually checked (the tap checked it) but
                    // the add failed, so no data changed — DiffUtil would
                    // report no content change and onBindViewHolder would
                    // never run to reset it. notifyItemChanged forces the
                    // rebind, which reads the live prefs state (unchecked).
                    val pos = pickerAdapter.currentList.indexOfFirst { it.identifier == app.identifier }
                    if (pos >= 0) pickerAdapter.notifyItemChanged(pos)
                }
            } else {
                panelPrefs.removeApp(app.identifier)
            }
        }

        binding.rvAllApps.apply {
            layoutManager = LinearLayoutManager(this@AppPickerActivity)
            adapter = pickerAdapter
        }

        // Search — debounced 200ms like the floating picker, so fast
        // typing doesn't submitList (and re-run DiffUtil) per keystroke.
        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?) = false
            override fun onQueryTextChange(newText: String?): Boolean {
                searchDebounceRunnable?.let { searchHandler.removeCallbacks(it) }
                val runnable = Runnable { filterApps(newText.orEmpty()) }
                searchDebounceRunnable = runnable
                searchHandler.postDelayed(runnable, 200L)
                return true
            }
        })

        binding.btnDone.setOnClickListener { finish() }

        // No loadApps() here: onResume runs immediately after onCreate
        // and loads the list once (calling it here too loaded twice and
        // the onCreate load dropped the search query on resume).
    }

    override fun onResume() {
        super.onResume()
        // Reload (not just rebind): installs/uninstalls while this picker
        // was open never appeared before, because loadApps() only ran in
        // onCreate. getAllApps() is uncached, so this is always fresh.
        loadApps()
    }

    private fun loadApps() {
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) {
                repository.getAllApps()
            }
            allApps = apps
            // Submit copies with isInPanel synced from prefs, so the
            // checkbox reflects external panel changes (not just the state
            // captured when the list was first loaded). Re-applies the
            // search query: a reload on resume used to drop the query
            // and show the unfiltered list.
            filterApps(binding.searchView.query.toString())
        }
    }

    private fun filterApps(query: String) {
        val filtered = if (query.isBlank()) allApps
        else allApps.filter { it.appName.contains(query, ignoreCase = true) }
        pickerAdapter.submitList(filtered.map { it.copy(isInPanel = panelPrefs.isInPanel(it.identifier)) })
    }

    // ── Inner Adapter ────────────────────────────────────────────────────────

    inner class AppPickerAdapter(
        private val onToggle: (AppInfo, Boolean) -> Unit
    ) : ListAdapter<AppInfo, AppPickerAdapter.PickerViewHolder>(Diff) {

        inner class PickerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val ivIcon: ImageView = itemView.findViewById(R.id.ivPickerIcon)
            val tvName: TextView = itemView.findViewById(R.id.tvPickerName)
            val cbInPanel: CheckBox = itemView.findViewById(R.id.cbInPanel)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            PickerViewHolder(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_app_picker_m3, parent, false)
            )

        override fun onBindViewHolder(holder: PickerViewHolder, position: Int) {
            val app = getItem(position)
            
            val scale = panelPrefs.scaleFactor
            val baseIconSize = 44
            val baseTextSize = 14f
            // The Glide override below must match this size in px: a fixed
            // 120px bitmap is upscaled (blurry) on high-density screens.
            val iconPx = (resources.displayMetrics.density * baseIconSize * scale).toInt()

            holder.ivIcon.layoutParams.let { lp ->
                lp.width = iconPx
                lp.height = iconPx
                holder.ivIcon.layoutParams = lp
            }
            holder.tvName.textSize = baseTextSize * scale
            
            // --- OPTIMIZED ICON LOADING WITH GLIDE ---
            Glide.with(this@AppPickerActivity).clear(holder.ivIcon)
            Glide.with(this@AppPickerActivity)
                .load(AppIconRequest(app.packageName, panelPrefs.appearanceKey))
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .placeholder(android.R.drawable.sym_def_app_icon)
                .error(android.R.drawable.sym_def_app_icon)
                .override(iconPx, iconPx)
                .into(holder.ivIcon)
            IconShapeHelper.applyShape(holder.ivIcon, panelPrefs.iconShape)

            holder.tvName.text = app.appName

            // Reset listener before setting checked (avoid spurious callbacks)
            holder.cbInPanel.setOnCheckedChangeListener(null)
            holder.cbInPanel.isChecked = panelPrefs.isInPanel(app.identifier)
            holder.cbInPanel.setOnCheckedChangeListener { _, checked ->
                onToggle(app, checked)
            }

            holder.itemView.setOnClickListener {
                holder.cbInPanel.toggle()
            }
        }
    }
}

private object Diff : DiffUtil.ItemCallback<AppInfo>() {
    override fun areItemsTheSame(o: AppInfo, n: AppInfo) = o.identifier == n.identifier
    // Include the live panel state: without it, DiffUtil reports no
    // content change when an app is added/removed elsewhere, and the
    // checkbox never updates without a scroll/resume.
    override fun areContentsTheSame(o: AppInfo, n: AppInfo) =
        o.appName == n.appName && o.isInPanel == n.isInPanel
}
