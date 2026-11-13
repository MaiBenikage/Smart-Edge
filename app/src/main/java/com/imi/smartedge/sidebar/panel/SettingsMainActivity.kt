package com.imi.smartedge.sidebar.panel

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.imi.smartedge.sidebar.panel.databinding.ActivitySettingsMainBinding

class SettingsMainActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsMainBinding
    private lateinit var panelPrefs: PanelPreferences

    private data class SettingItem(
        val title: String,
        val description: String,
        val category: String,
        val keywords: String,
        val targetActivity: Class<*>,
        val targetId: String? = null
    )

    private val allSettings = mutableListOf<SettingItem>()
    private lateinit var searchAdapter: SearchResultsAdapter

    /**
     * Audit LOW (settings hub inflates ~300 views on every resume):
     * the search index is cached across resumes — a plain resume reuses
     * it and inflates nothing. It is rebuilt only when stale: first use,
     * or when something that changes the list has happened since it was
     * built. The rebuild triggers are the runtime gates in
     * isRuntimeHidden() (notchGesturesEnabled,
     * slideBrightnessEnabled/slideVolumeEnabled, gesturesEnabled —
     * toggled on InteractionSettingsActivity) and the effective language
     * (titles/descriptions come from getString() at build time). They
     * are snapshotted at build time and compared in onResume, so a
     * change made on any screen — including a settings import or a
     * reset — is caught without touching the toggle handlers. A
     * recreated Activity starts stale (recreate() after Reset, language
     * switch) and rebuilds on its first resume.
     */
    private var settingsIndexStale = true
    private var lastIndexSnapshot: IndexSnapshot? = null

    /** Everything that can change the built search index. */
    private data class IndexSnapshot(
        val notchGesturesEnabled: Boolean,
        val slideBrightnessEnabled: Boolean,
        val slideVolumeEnabled: Boolean,
        val gesturesEnabled: Boolean,
        val language: String
    )

    // Named property so expandSearch() can re-enable it after collapseSearch() disables it.
    private lateinit var searchBackCallback: androidx.activity.OnBackPressedCallback

    companion object {
        const val EXTRA_SCROLL_TO = "extra_scroll_to"
    }


    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsMainBinding.inflate(layoutInflater)
        setContentView(binding.root)



        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        panelPrefs = PanelPreferences(this)
        
        setupSearch()
        setupListeners()
        
        binding.btnGithubTop.setOnClickListener {
            openGithub()
        }

        // Handle System Back to close search. The callback starts
        // disabled: an always-enabled callback claims every back press,
        // which defeats predictive back on the main screen. expandSearch()
        // enables it; collapseSearch() disables it again.
        searchBackCallback = object : androidx.activity.OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                collapseSearch()
            }
        }
        onBackPressedDispatcher.addCallback(this, searchBackCallback)
    }

    override fun onResume() {
        super.onResume()
        // Audit LOW (settings hub inflates ~300 views on every resume):
        // rebuild the search index only when it is stale — first use, or
        // a gating toggle / the language changed since it was built (see
        // settingsIndexStale). A plain resume reuses the cached
        // descriptors and inflates nothing. Previously this rebuilt on
        // every resume because toggling a setting on a child screen
        // changes which rows are visible (a361e4f); the snapshot
        // comparison below preserves that without the rebuild.
        if (settingsIndexStale || indexSnapshotChanged()) {
            initializeSettingsList()
            settingsIndexStale = false
            lastIndexSnapshot = currentIndexSnapshot()
        }
        filterSettings(binding.etSettingsSearch.text.toString())
    }

    private fun initializeSettingsList() {
        allSettings.clear()
        
        // Map of Layouts to their corresponding Activities
        val layoutMap = mapOf(
            R.layout.activity_settings_appearance to AppearanceSettingsActivity::class.java,
            R.layout.activity_settings_interaction to InteractionSettingsActivity::class.java,
            R.layout.activity_settings_handle to HandleSettingsActivity::class.java,
            R.layout.activity_settings_tools to ToolsSettingsActivity::class.java,
            R.layout.activity_settings_text_actions to TextActionsSettingsActivity::class.java,
            R.layout.activity_settings_misc to MiscellaneousSettingsActivity::class.java
        )

        val inflater = LayoutInflater.from(this)
        
        layoutMap.forEach { (layoutId, activityClass) ->
            val root = inflater.inflate(layoutId, null) as ViewGroup
            discoverSettingsInView(root, activityClass, layoutId)
        }

        // Add static actions that aren't in standard layouts
        allSettings.add(SettingItem(getString(R.string.add_apps), getString(R.string.manage_apps_desc), getString(R.string.misc_section_general), "apps choose select picker manage add remove", AppPickerActivity::class.java))
        allSettings.add(SettingItem(getString(R.string.view_repo_title), getString(R.string.view_repo_desc), getString(R.string.category_project), "github source code open repo smartedge", SettingsMainActivity::class.java, "btnGithubTop"))
        allSettings.add(SettingItem(getString(R.string.btn_reset), getString(R.string.reset_defaults_desc), getString(R.string.misc_section_general), "reset all factory wipe restore settings", SettingsMainActivity::class.java, "btnReset"))
    }

    private fun discoverSettingsInView(view: View, activityClass: Class<*>, layoutId: Int) {
        if (view.visibility == View.GONE) return

        val viewId = view.id
        if (viewId != View.NO_ID) {
            val idName = resources.getResourceEntryName(viewId)

            // "Intelligent" Discovery: Any view starting with 'feature_' or 'layout_' is indexed
            if (idName.startsWith("feature_") || idName.startsWith("layout_")) {
                // Rows the owning screen hides at runtime must not be
                // indexed when their gate is off: the inflated layout
                // shows them all, so a result would deep-link to a row
                // the user can't see.
                if (isRuntimeHidden(idName)) return
                val category = getCategoryFromLayout(layoutId)
                val (title, description) = extractMetadata(view)

                if (title.isNotEmpty()) {
                    allSettings.add(SettingItem(title, description, category, "$title $description $idName".lowercase(), activityClass, idName))
                }
            }
        }

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                discoverSettingsInView(view.getChildAt(i), activityClass, layoutId)
            }
        }
    }

    /**
     * Rows hidden at runtime by the owning screen's loadCurrentSettings
     * (same gates as InteractionSettingsActivity). Only these three rows
     * are runtime-gated; every other indexed row is always visible.
     */
    private fun isRuntimeHidden(idName: String): Boolean {
        val prefs = PanelPreferences(this)
        return when (idName) {
            "layoutNotchTapGestures" -> !prefs.notchGesturesEnabled
            "layoutSlideSensitivity" -> !prefs.slideBrightnessEnabled && !prefs.slideVolumeEnabled
            "layout_swipe_sensitivity" -> !prefs.gesturesEnabled
            else -> false
        }
    }

    /** Snapshot of everything that can change the built search index. */
    private fun currentIndexSnapshot() = IndexSnapshot(
        notchGesturesEnabled = panelPrefs.notchGesturesEnabled,
        slideBrightnessEnabled = panelPrefs.slideBrightnessEnabled,
        slideVolumeEnabled = panelPrefs.slideVolumeEnabled,
        gesturesEnabled = panelPrefs.gesturesEnabled,
        language = LocaleHelper.getEffectiveLanguage(this)
    )

    private fun indexSnapshotChanged(): Boolean = lastIndexSnapshot != currentIndexSnapshot()

    private fun extractMetadata(view: View): Pair<String, String> {
        var title = ""
        var description = ""

        if (view is ViewGroup) {
            val textViews = mutableListOf<TextView>()
            findAllTextViews(view, textViews)
            
            if (textViews.isNotEmpty()) {
                // The first TextView is almost always the Title in our layouts
                title = textViews[0].text.toString()

                // The description is the first TextView after the title
                // that is not a live-value label: value TextViews
                // (tv*Value — "15", "English", "100%") still hold the
                // layout's placeholder text at index time, which used to
                // be indexed as the description.
                description = textViews.drop(1).firstOrNull { tv ->
                    val id = if (tv.id != View.NO_ID) {
                        runCatching { resources.getResourceEntryName(tv.id) }.getOrDefault("")
                    } else ""
                    !id.endsWith("Value")
                }?.text.toString().orEmpty()
            }
        } else if (view is com.google.android.material.materialswitch.MaterialSwitch) {
            title = view.text.toString()
        }

        return title to description
    }

    private fun findAllTextViews(view: View, list: MutableList<TextView>) {
        if (view is TextView && view !is com.google.android.material.materialswitch.MaterialSwitch) {
            list.add(view)
        } else if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findAllTextViews(view.getChildAt(i), list)
            }
        }
    }

    private fun getCategoryFromLayout(layoutId: Int): String = when (layoutId) {
        R.layout.activity_settings_appearance -> getString(R.string.section_appearance)
        R.layout.activity_settings_interaction -> getString(R.string.section_interaction)
        R.layout.activity_settings_handle -> getString(R.string.section_handle)
        R.layout.activity_settings_tools -> getString(R.string.section_tools)
        R.layout.activity_settings_text_actions -> getString(R.string.section_text_actions)
        R.layout.activity_settings_misc -> getString(R.string.section_misc)
        else -> getString(R.string.misc_section_general)
    }

    private fun setupSearch() {
        searchAdapter = SearchResultsAdapter { item ->
            collapseSearch()
            
            if (item.targetActivity == SettingsMainActivity::class.java) {
                if (item.targetId == "btnReset") {
                    binding.settingsMainContent.post {
                        binding.settingsMainContent.fullScroll(View.FOCUS_DOWN)
                        binding.btnReset.highlightView()
                    }
                } else if (item.targetId == "btnGithubTop") {
                    openGithub()
                }
                return@SearchResultsAdapter
            }

            val intent = Intent(this, item.targetActivity)
            if (item.targetId != null) {
                intent.putExtra(EXTRA_SCROLL_TO, item.targetId)
            }
            startActivity(intent)
        }
        binding.rvSettingsResults.layoutManager = LinearLayoutManager(this)
        binding.rvSettingsResults.adapter = searchAdapter

        binding.etSettingsSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterSettings(s.toString())
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        // Setup expansion animation
        binding.searchCard.setOnClickListener {
            if (binding.etSettingsSearch.visibility == View.GONE) {
                expandSearch()
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        if (binding.rvSettingsResults.visibility == View.VISIBLE || binding.etSettingsSearch.visibility == View.VISIBLE) {
            collapseSearch()
            return true
        }
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun setupListeners() {
        binding.btnAppearance.setOnClickListener {
            startActivity(Intent(this, AppearanceSettingsActivity::class.java))
        }

        binding.btnInteraction.setOnClickListener {
            startActivity(Intent(this, InteractionSettingsActivity::class.java))
        }

        binding.btnHandle.setOnClickListener {
            startActivity(Intent(this, HandleSettingsActivity::class.java))
        }

        binding.btnTools.setOnClickListener {
            startActivity(Intent(this, ToolsSettingsActivity::class.java))
        }

        binding.btnTextActions.setOnClickListener {
            startActivity(Intent(this, TextActionsSettingsActivity::class.java))
        }

        binding.btnReset.setOnClickListener {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_reset_title)
                .setMessage(R.string.dialog_reset_msg)
                .setPositiveButton(R.string.btn_reset) { _, _ ->
                    panelPrefs.resetToDefaults()
                    // Theme and language are part of the reset: re-apply
                    // them and recreate so the change is visible now,
                    // instead of only after the next process start.
                    applyAppTheme(this)
                    LocaleHelper.applyPersistedLocale(this)
                    applyGlobalRefresh()
                    binding.root.showModernToast(getString(R.string.toast_reset_success))
                    recreate()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        binding.btnMisc.setOnClickListener {
            startActivity(Intent(this, MiscellaneousSettingsActivity::class.java))
        }

        binding.btnManageApps.setOnClickListener {
            startActivity(Intent(this, AppPickerActivity::class.java))
        }
    }

    private fun openGithub() {
        try {
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/MaiBenikage/Smart-Edge"))
            startActivity(intent)
        } catch (e: Exception) {
            binding.root.showModernToast(getString(R.string.toast_browser_error))
        }
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    private fun expandSearch() {
        val transition = android.transition.TransitionSet().apply {
            addTransition(android.transition.ChangeBounds())
            addTransition(android.transition.Fade())
            duration = 300
            interpolator = android.view.animation.DecelerateInterpolator()
        }
        
        android.transition.TransitionManager.beginDelayedTransition(binding.searchCard.parent as ViewGroup, transition)
        
        val lp = binding.searchCard.layoutParams as FrameLayout.LayoutParams
        lp.width = FrameLayout.LayoutParams.MATCH_PARENT
        lp.setMargins(dpToPx(20), 0, dpToPx(20), 0)
        binding.searchCard.layoutParams = lp

        val containerLp = binding.searchContainer.layoutParams
        containerLp.width = ViewGroup.LayoutParams.MATCH_PARENT
        binding.searchContainer.layoutParams = containerLp
        
        binding.tvSearchHint.visibility = View.GONE
        binding.etSettingsSearch.visibility = View.VISIBLE
        binding.etSettingsSearch.requestFocus()

        // Re-enable the back callback so it can intercept future back presses
        // while the search is expanded (it starts disabled; collapseSearch()
        // disables it again).
        if (::searchBackCallback.isInitialized) {
            searchBackCallback.isEnabled = true
        }
        
        // Show keyboard
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(binding.etSettingsSearch, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    private fun collapseSearch() {
        val transition = android.transition.TransitionSet().apply {
            addTransition(android.transition.ChangeBounds())
            addTransition(android.transition.Fade())
            duration = 250
            interpolator = android.view.animation.AccelerateInterpolator()
        }
        
        android.transition.TransitionManager.beginDelayedTransition(binding.searchCard.parent as ViewGroup, transition)
        
        binding.etSettingsSearch.setText("")
        binding.etSettingsSearch.visibility = View.GONE
        binding.tvSearchHint.visibility = View.VISIBLE
        binding.etSettingsSearch.clearFocus()
        
        val lp = binding.searchCard.layoutParams as FrameLayout.LayoutParams
        lp.width = FrameLayout.LayoutParams.WRAP_CONTENT
        lp.setMargins(0, 0, 0, 0)
        binding.searchCard.layoutParams = lp

        val containerLp = binding.searchContainer.layoutParams
        containerLp.width = ViewGroup.LayoutParams.WRAP_CONTENT
        binding.searchContainer.layoutParams = containerLp

        // Hide keyboard
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(binding.etSettingsSearch.windowToken, 0)

        // Search is closed: stop intercepting Back so predictive back
        // works on the main screen again.
        if (::searchBackCallback.isInitialized) {
            searchBackCallback.isEnabled = false
        }
    }

    private fun filterSettings(query: String) {
        val lowerQuery = query.lowercase().trim()
        if (lowerQuery.isEmpty()) {
            binding.rvSettingsResults.visibility = View.GONE
            binding.settingsCategoriesContainer.visibility = View.VISIBLE
            return
        }

        val filtered = allSettings.filter { item ->
            item.title.lowercase().contains(lowerQuery) ||
            item.description.lowercase().contains(lowerQuery) ||
            item.keywords.lowercase().contains(lowerQuery)
        }

        binding.settingsCategoriesContainer.visibility = View.GONE
        binding.rvSettingsResults.visibility = View.VISIBLE
        searchAdapter.submitList(filtered)
    }

    private fun applyGlobalRefresh() {
        // Same guard as the other screens' applyOnly(): no refresh
        // while the service is off (it would flash its notification).
        if (!panelPrefs.serviceEnabled) return
        val intent = Intent(this, FloatingPanelService::class.java).apply {
            action = FloatingPanelService.ACTION_REFRESH
        }
        startService(intent)
    }

    // --- Search Adapter ---
    private inner class SearchResultsAdapter(private val onClick: (SettingItem) -> Unit) : 
        ListAdapter<SettingItem, SearchResultsAdapter.ViewHolder>(DiffCallback) {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val title = view.findViewById<TextView>(android.R.id.text1)
            private val desc = view.findViewById<TextView>(android.R.id.text2)

            fun bind(item: SettingItem) {
                title.text = item.title
                title.setTextColor(com.google.android.material.color.MaterialColors.getColor(title, com.google.android.material.R.attr.colorOnSurface))
                
                // Omit the separator when there is no description:
                // most rows (group, switch, colour) have none, and the
                // literal "Category • " reads as a dangling bullet.
                desc.text = if (item.description.isEmpty()) item.category
                    else "${item.category} • ${item.description}"
                desc.setTextColor(com.google.android.material.color.MaterialColors.getColor(desc, com.google.android.material.R.attr.colorOnSurfaceVariant))
                itemView.setOnClickListener { onClick(item) }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_2, parent, false)
            // dp, not raw px: 64px is 24dp on an xxhdpi screen but 64dp
            // on an mdpi one.
            view.setPadding(dpToPx(24), dpToPx(12), dpToPx(24), dpToPx(12))
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(getItem(position))
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<SettingItem>() {
        override fun areItemsTheSame(oldItem: SettingItem, newItem: SettingItem) = oldItem.title == newItem.title
        override fun areContentsTheSame(oldItem: SettingItem, newItem: SettingItem) = oldItem == newItem
    }
}
