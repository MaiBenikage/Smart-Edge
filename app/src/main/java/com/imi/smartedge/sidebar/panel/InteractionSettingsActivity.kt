package com.imi.smartedge.sidebar.panel

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.imi.smartedge.sidebar.panel.databinding.ActivitySettingsInteractionBinding
import com.google.android.material.snackbar.Snackbar
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class InteractionSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsInteractionBinding
    private lateinit var panelPrefs: PanelPreferences

    // Suppresses the automation switch's own listener while the switch is
    // set programmatically (revert-until-granted / grant result); without
    // this, setChecked re-fires the listener and re-enters the flow.
    private var suppressAutomationSwitchListener = false

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsInteractionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        panelPrefs = PanelPreferences(this)

        loadCurrentSettings()
        setupListeners()
        // Fragment-result listener for the secure-settings dialog:
        // registered here (not in the click handler) so a recreated
        // activity after rotation still receives the grant.
        SecureSettingsDialog.registerResultListener(this) {
            updateSecureSettingsUI()
            if (AutomationManager.isAutomationPossible()) {
                // A grant through the dialog must also opt the user in
                // (same as SetupActivity): the switch is set
                // programmatically here, so its listener is suppressed
                // and cannot set the pref itself.
                panelPrefs.useAutomationForGestures = true
                suppressAutomationSwitchListener = true
                binding.featureAutomationGestures.isChecked = true
                suppressAutomationSwitchListener = false
            }
        }
        // Favourite-app picker result: the pick carries the slot key,
        // so the right slot gets the app (see AppSinglePickerDialog).
        AppSinglePickerDialog.registerResultListener(this) { slotKey, pkg ->
            panelPrefs.setFavoriteAppPackage(slotKey, pkg)
            setSlotAction(slotKey, PanelPreferences.ACTION_OPEN_FAVORITE_APP)
            loadCurrentSettings()
            applyOnly()
        }
        handleDeepLink()
    }

    /** Set the gesture action for one slot, by its action pref key. */
    private fun setSlotAction(slotKey: String, action: Int) {
        when (slotKey) {
            PanelPreferences.KEY_TAP_ACTION -> panelPrefs.tapAction = action
            PanelPreferences.KEY_DOUBLE_TAP_ACTION -> panelPrefs.doubleTapAction = action
            PanelPreferences.KEY_TRIPLE_TAP_ACTION -> panelPrefs.tripleTapAction = action
            PanelPreferences.KEY_LONG_PRESS_ACTION -> panelPrefs.longPressAction = action
            PanelPreferences.KEY_NOTCH_TAP_ACTION -> panelPrefs.notchTapAction = action
            PanelPreferences.KEY_NOTCH_DOUBLE_TAP_ACTION -> panelPrefs.notchDoubleTapAction = action
            PanelPreferences.KEY_NOTCH_TRIPLE_TAP_ACTION -> panelPrefs.notchTripleTapAction = action
            PanelPreferences.KEY_NOTCH_LONG_PRESS_ACTION -> panelPrefs.notchLongPressAction = action
        }
    }

    override fun onResume() {
        super.onResume()
        updateSecureSettingsUI()
    }

    private fun handleDeepLink() {
        val targetId = intent.getStringExtra(SettingsMainActivity.EXTRA_SCROLL_TO) ?: return
        val viewId = resources.getIdentifier(targetId, "id", packageName)
        if (viewId != 0) {
            val targetView = findViewById<View>(viewId)
            targetView?.post {
                val rect = android.graphics.Rect()
                targetView.getDrawingRect(rect)
                binding.root.offsetDescendantRectToMyCoords(targetView, rect)
                binding.interactionScrollView.smoothScrollTo(0, rect.top - 200)
                targetView.highlightView()
            }
        }
    }

    private fun loadCurrentSettings() {
        updateSecureSettingsUI()

        // 1. MAIN TRIGGER & GESTURES
        if (panelPrefs.panelSide == PanelPreferences.SIDE_LEFT) {
            binding.togglePanelSide.check(R.id.btnSideLeft)
        } else {
            binding.togglePanelSide.check(R.id.btnSideRight)
        }

        binding.featureGestures.isChecked = panelPrefs.gesturesEnabled
        binding.featureOnlyOnHome.isChecked = panelPrefs.onlyOnHome
        binding.sbSwipeSensitivity.value = panelPrefs.swipeSensitivity.toFloat()
        binding.tvSwipeSensitivityValue.text = getString(R.string.fmt_percent, panelPrefs.swipeSensitivity)
        binding.layoutSwipeSensitivity.visibility = if (panelPrefs.gesturesEnabled) View.VISIBLE else View.GONE

        binding.tvTapGesturesValue.text = getString(R.string.tap_gestures_summary, actionLabel(panelPrefs.tapAction, PanelPreferences.KEY_TAP_ACTION), actionLabel(panelPrefs.doubleTapAction, PanelPreferences.KEY_DOUBLE_TAP_ACTION), actionLabel(panelPrefs.tripleTapAction, PanelPreferences.KEY_TRIPLE_TAP_ACTION), actionLabel(panelPrefs.longPressAction, PanelPreferences.KEY_LONG_PRESS_ACTION))
        binding.featureHaptic.isChecked = panelPrefs.hapticEnabled

        // 2. SPECIALIZED INTERACTION
        binding.featureSlideBrightness.isChecked = panelPrefs.slideBrightnessEnabled
        binding.featureSlideVolume.isChecked = panelPrefs.slideVolumeEnabled
        binding.sbSlideSensitivity.value = panelPrefs.slideSensitivity.toFloat()
        binding.tvSlideSensitivityValue.text = getString(R.string.fmt_percent, panelPrefs.slideSensitivity)
        updateSlideSeekUI()

        binding.featureNotchGestures.isChecked = panelPrefs.notchGesturesEnabled
        binding.tvNotchTapGesturesValue.text = getString(R.string.tap_gestures_summary, actionLabel(panelPrefs.notchTapAction, PanelPreferences.KEY_NOTCH_TAP_ACTION), actionLabel(panelPrefs.notchDoubleTapAction, PanelPreferences.KEY_NOTCH_DOUBLE_TAP_ACTION), actionLabel(panelPrefs.notchTripleTapAction, PanelPreferences.KEY_NOTCH_TRIPLE_TAP_ACTION), actionLabel(panelPrefs.notchLongPressAction, PanelPreferences.KEY_NOTCH_LONG_PRESS_ACTION))
        binding.layoutNotchTapGestures.visibility = if (panelPrefs.notchGesturesEnabled) View.VISIBLE else View.GONE

        // 3. PANEL EXPERIENCE
        binding.featureShowLandscape.isChecked = panelPrefs.showInLandscape
        binding.featureNotificationApps.isChecked = panelPrefs.showNotificationApps
        binding.featureRememberScroll.isChecked = panelPrefs.rememberScroll
        binding.featureAutoShowKeyboard.isChecked = panelPrefs.autoShowKeyboard

        // 4. RECENT APPS
        binding.featureRecentApps.isChecked = panelPrefs.recentEnabled
        updateRecentSettingsUI()

        // 5. SHORTCUTS & AUTOMATION (switch state lives in updateSecureSettingsUI)

        // 6. ADVANCED OPTIONS
        val whitelistCount = panelPrefs.getFullscreenWhitelist().size
        binding.tvFullscreenWhitelistValue.text = if (whitelistCount == 1) getString(R.string.apps_selected_singular) else getString(R.string.apps_selected_plural, whitelistCount)

        val gameAppsCount = panelPrefs.getGameApps().size
        binding.tvGameAppsValue.text = if (gameAppsCount == 1) getString(R.string.apps_selected_singular) else getString(R.string.apps_selected_plural, gameAppsCount)
        binding.featureGameMode.isChecked = panelPrefs.deliberateGestureInGames

        // 7. SYSTEM & BEHAVIOR
        binding.featureAutoStart.isChecked = panelPrefs.autoStart
        binding.featureShowLogs.isChecked = panelPrefs.showLogs

        binding.tvAnimFeelValue.text = when (panelPrefs.animSpeed) {
            200 -> getString(R.string.anim_feel_calm)
            400 -> getString(R.string.anim_feel_balanced)
            700 -> getString(R.string.anim_feel_snappy)
            1000 -> getString(R.string.anim_feel_instant)
            0 -> getString(R.string.anim_feel_disabled)
            else -> getString(R.string.anim_feel_balanced)
        }

        binding.sbPickerGap.value = panelPrefs.pickerGap.toFloat()
        binding.tvPickerGapValue.text = getString(R.string.fmt_dp, panelPrefs.pickerGap)
    }

    private fun updateSlideSeekUI() {
        val brightnessOn = panelPrefs.slideBrightnessEnabled
        val volumeOn = panelPrefs.slideVolumeEnabled
        val anyOn = brightnessOn || volumeOn
        binding.layoutSlideSensitivity.visibility = if (anyOn) View.VISIBLE else View.GONE
    }

    /** Refresh the Recent Apps section summary texts from prefs. */
    private fun updateRecentSettingsUI() {
        binding.tvRecentHistorySizeValue.text = panelPrefs.recentHistorySize.toString()
        binding.tvRecentMaxCountValue.text = panelPrefs.recentMaxCount.toString()
    }

    private fun updateSecureSettingsUI() {
        // Sync the automation switch with the stored preference. This is
        // the callback the secure-settings dialog fires after a grant, and
        // it runs from loadCurrentSettings() and onResume() too, so the
        // switch always reflects the current preference (not a stale grant
        // state). It previously was an empty stub while loadCurrentSettings
        // set the same switch in two places.
        binding.featureAutomationGestures.isChecked = panelPrefs.useAutomationForGestures
    }

    private fun setupListeners() {
        binding.togglePanelSide.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                panelPrefs.panelSide = if (checkedId == R.id.btnSideLeft)
                    PanelPreferences.SIDE_LEFT else PanelPreferences.SIDE_RIGHT
                applyOnly()
            }
        }

        binding.featureGestures.setOnCheckedChangeListener { _, isChecked ->
            panelPrefs.gesturesEnabled = isChecked
            binding.layoutSwipeSensitivity.visibility = if (isChecked) View.VISIBLE else View.GONE
            applyOnly()
        }

        binding.featureOnlyOnHome.setOnCheckedChangeListener { _, isChecked ->
            panelPrefs.onlyOnHome = isChecked
            applyOnly()
        }

        binding.featureAutomationGestures.setOnCheckedChangeListener { buttonView, isChecked ->
            // Guard: programmatic isChecked sets below (revert / grant
            // result) re-fire this listener; ignore those.
            if (suppressAutomationSwitchListener) return@setOnCheckedChangeListener
            if (isChecked && !AutomationManager.isAutomationPossible()) {
                // Revert the switch until the result arrives: the tap
                // already flipped it to checked, but the pref stays false
                // until the grant succeeds — leaving it showing ON with
                // the pref OFF. The callback below re-checks it on success.
                suppressAutomationSwitchListener = true
                buttonView.isChecked = panelPrefs.useAutomationForGestures
                suppressAutomationSwitchListener = false
                AutomationManager.checkRootAndRequestPermission(this) { success ->
                    runOnUiThread {
                        suppressAutomationSwitchListener = true
                        if (success) {
                            panelPrefs.useAutomationForGestures = true
                            buttonView.isChecked = true
                            applyOnly()
                        } else {
                            buttonView.isChecked = false
                            showAutomationSetupDialog()
                        }
                        suppressAutomationSwitchListener = false
                    }
                }
                return@setOnCheckedChangeListener
            }
            panelPrefs.useAutomationForGestures = isChecked
            applyOnly()
        }

        findViewById<android.view.View>(R.id.btnAutomationSettings)?.setOnClickListener {
            SecureSettingsDialog.show(this)
        }

        binding.sbSwipeSensitivity.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val sens = value.toInt()
                panelPrefs.swipeSensitivity = sens
                binding.tvSwipeSensitivityValue.text = getString(R.string.fmt_percent, sens)
            }
        }
        binding.sbSwipeSensitivity.addOnSliderTouchListener(object : com.google.android.material.slider.Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) {}
            override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) {
                applyOnly()
            }
        })

        binding.layoutTapGestures.setOnClickListener {
            val mainOptions = arrayOf(getString(R.string.action_single_tap_action), getString(R.string.action_double_tap_action), getString(R.string.action_triple_tap_action), getString(R.string.action_long_press_action))
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.tap_gestures)
                .setItems(mainOptions) { _, which ->
                    when (which) {
                        0 -> showActionPicker(getString(R.string.action_single_tap), panelPrefs.tapAction, PanelPreferences.KEY_TAP_ACTION) { panelPrefs.tapAction = it }
                        1 -> showActionPicker(getString(R.string.action_double_tap), panelPrefs.doubleTapAction, PanelPreferences.KEY_DOUBLE_TAP_ACTION) { panelPrefs.doubleTapAction = it }
                        2 -> showActionPicker(getString(R.string.action_triple_tap), panelPrefs.tripleTapAction, PanelPreferences.KEY_TRIPLE_TAP_ACTION) { panelPrefs.tripleTapAction = it }
                        3 -> showActionPicker(getString(R.string.action_long_press), panelPrefs.longPressAction, PanelPreferences.KEY_LONG_PRESS_ACTION) { panelPrefs.longPressAction = it }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        binding.featureNotchGestures.setOnCheckedChangeListener { _, isChecked ->
            panelPrefs.notchGesturesEnabled = isChecked
            binding.layoutNotchTapGestures.visibility = if (isChecked) View.VISIBLE else View.GONE
            applyOnly()
        }

        binding.layoutNotchTapGestures.setOnClickListener {
            val mainOptions = arrayOf(getString(R.string.action_single_tap_action), getString(R.string.action_double_tap_action), getString(R.string.action_triple_tap_action), getString(R.string.action_long_press_action))
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.notch_tap_gestures)
                .setItems(mainOptions) { _, which ->
                    when (which) {
                        0 -> showActionPicker(getString(R.string.notch_single_tap), panelPrefs.notchTapAction, PanelPreferences.KEY_NOTCH_TAP_ACTION) { panelPrefs.notchTapAction = it }
                        1 -> showActionPicker(getString(R.string.notch_double_tap), panelPrefs.notchDoubleTapAction, PanelPreferences.KEY_NOTCH_DOUBLE_TAP_ACTION) { panelPrefs.notchDoubleTapAction = it }
                        2 -> showActionPicker(getString(R.string.notch_triple_tap), panelPrefs.notchTripleTapAction, PanelPreferences.KEY_NOTCH_TRIPLE_TAP_ACTION) { panelPrefs.notchTripleTapAction = it }
                        3 -> showActionPicker(getString(R.string.notch_long_press), panelPrefs.notchLongPressAction, PanelPreferences.KEY_NOTCH_LONG_PRESS_ACTION) { panelPrefs.notchLongPressAction = it }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        binding.featureHaptic.setOnCheckedChangeListener { _, isChecked ->
            panelPrefs.hapticEnabled = isChecked
        }

        // Panel Experience Listeners
        binding.featureShowLandscape.setOnCheckedChangeListener { _, isChecked ->
            panelPrefs.showInLandscape = isChecked
            applyOnly()
        }
        binding.featureNotificationApps.setOnCheckedChangeListener { _, isChecked ->
            panelPrefs.showNotificationApps = isChecked
            applyOnly()
        }
        binding.featureRememberScroll.setOnCheckedChangeListener { _, isChecked ->
            panelPrefs.rememberScroll = isChecked
            applyOnly()
        }
        binding.featureAutoShowKeyboard.setOnCheckedChangeListener { _, isChecked ->
            panelPrefs.autoShowKeyboard = isChecked
            applyOnly()
        }

        // Recent Apps Listeners
        binding.featureRecentApps.setOnCheckedChangeListener { _, isChecked ->
            panelPrefs.recentEnabled = isChecked
            applyOnly()
        }
        binding.layoutRecentHistorySize.setOnClickListener {
            val values = intArrayOf(64, 128, 256)
            val options = values.map { it.toString() }.toTypedArray()
            var selectedIndex = values.indexOf(panelPrefs.recentHistorySize)
            if (selectedIndex == -1) selectedIndex = 1
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_recent_history_size_title)
                .setSingleChoiceItems(options, selectedIndex) { dialog, which ->
                    panelPrefs.recentHistorySize = values[which]
                    updateRecentSettingsUI()
                    applyOnly()
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        binding.layoutRecentMaxCount.setOnClickListener {
            val values = (4..20).toList()
            val options = values.map { it.toString() }.toTypedArray()
            var selectedIndex = values.indexOf(panelPrefs.recentMaxCount)
            if (selectedIndex == -1) selectedIndex = values.indexOf(8)
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_recent_max_count_title)
                .setSingleChoiceItems(options, selectedIndex) { dialog, which ->
                    panelPrefs.recentMaxCount = values[which]
                    updateRecentSettingsUI()
                    applyOnly()
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        binding.layoutRecentClear.setOnClickListener {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.recent_clear_history)
                .setPositiveButton(android.R.string.ok) { dialog, _ ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        RecentAppsManager(this@InteractionSettingsActivity).clearHistory()
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                this@InteractionSettingsActivity,
                                R.string.recent_history_cleared,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        // System & Behavior Listeners
        binding.featureAutoStart.setOnCheckedChangeListener { _, isChecked ->
            panelPrefs.autoStart = isChecked
        }
        binding.featureShowLogs.setOnCheckedChangeListener { _, isChecked ->
            panelPrefs.showLogs = isChecked
            applyOnly()
        }

        binding.featureAddShortcut.setOnClickListener {
            pinShortcut()
        }

        binding.featureAnimFeel.setOnClickListener {
            val options = arrayOf(getString(R.string.anim_feel_calm), getString(R.string.anim_feel_balanced), getString(R.string.anim_feel_snappy), getString(R.string.anim_feel_instant), getString(R.string.anim_feel_disabled))
            val values = intArrayOf(200, 400, 700, 1000, 0)
            var selectedIndex = values.indexOf(panelPrefs.animSpeed)
            if (selectedIndex == -1) selectedIndex = 1

            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.feature_anim_feel_label)
                .setSingleChoiceItems(options, selectedIndex) { dialog, which ->
                    panelPrefs.animSpeed = values[which]
                    binding.tvAnimFeelValue.text = options[which]
                    applyOnly()
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        binding.sbPickerGap.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val gap = value.toInt()
                panelPrefs.pickerGap = gap
                binding.tvPickerGapValue.text = getString(R.string.fmt_dp, gap)
            }
        }
        binding.sbPickerGap.addOnSliderTouchListener(object : com.google.android.material.slider.Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) {}
            override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) {
                applyOnly()
            }
        })

        binding.featureSlideBrightness.setOnCheckedChangeListener { _, isChecked ->
            panelPrefs.slideBrightnessEnabled = isChecked
            updateSlideSeekUI()
            applyOnly()
        }

        binding.featureSlideVolume.setOnCheckedChangeListener { _, isChecked ->
            panelPrefs.slideVolumeEnabled = isChecked
            updateSlideSeekUI()
            applyOnly()
        }

        binding.sbSlideSensitivity.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val sens = value.toInt()
                panelPrefs.slideSensitivity = sens
                binding.tvSlideSensitivityValue.text = getString(R.string.fmt_percent, sens)
            }
        }
        binding.sbSlideSensitivity.addOnSliderTouchListener(object : com.google.android.material.slider.Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) {}
            override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) {
                applyOnly()
            }
        })

        binding.featureGameMode.setOnCheckedChangeListener { _, isChecked ->
            panelPrefs.deliberateGestureInGames = isChecked
            applyOnly()
        }

        binding.layoutFullscreenWhitelist.setOnClickListener {
            showAppMultiPicker(getString(R.string.picker_title_hide), panelPrefs.getFullscreenWhitelist()) { newWhitelist ->
                panelPrefs.setFullscreenWhitelist(newWhitelist)
                binding.tvFullscreenWhitelistValue.text = if (newWhitelist.size == 1) getString(R.string.apps_selected_singular) else getString(R.string.apps_selected_plural, newWhitelist.size)
                applyOnly()
            }
        }

        binding.layoutGameApps.setOnClickListener {
            showAppMultiPicker(getString(R.string.picker_title_game), panelPrefs.getGameApps()) { newGames ->
                panelPrefs.setGameApps(newGames)
                binding.tvGameAppsValue.text = if (newGames.size == 1) getString(R.string.apps_selected_singular) else getString(R.string.apps_selected_plural, newGames.size)
                applyOnly()
            }
        }
    }

    private fun showAutomationSetupDialog() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_automation_title)
            .setMessage(R.string.dialog_automation_msg)
            .setPositiveButton(R.string.btn_setup) { _, _ ->
                SecureSettingsDialog.show(this)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAppMultiPicker(title: String, currentSelected: List<String>, onSave: (List<String>) -> Unit) {
        lifecycleScope.launch(Dispatchers.Main) {
            val loadingDialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this@InteractionSettingsActivity)
                .setTitle(R.string.loading_apps)
                .setMessage(R.string.please_wait_apps)
                .setCancelable(false)
                .show()

            // Dismiss in finally: if the activity is destroyed mid-load,
            // lifecycleScope is cancelled at the withContext point and the
            // dismiss below never runs, leaking the window.
            // A PackageManager throw must not crash the screen: fall back
            // to an empty list and tell the user the list could not load.
            val allApps = try {
                withContext(Dispatchers.IO) {
                    runCatching { AppRepository.shared(this@InteractionSettingsActivity).getAllApps(preloadIcons = false) }.getOrDefault(emptyList())
                }
            } finally {
                loadingDialog.dismiss()
            }
            if (allApps.isEmpty()) {
                binding.root.showModernToast(getString(R.string.toast_apps_load_failed))
                return@launch
            }

            val sortedApps = allApps.sortedBy { it.appName.lowercase(java.util.Locale.ROOT) }
            val selectedPkgs = currentSelected.toMutableSet()

            val density = resources.displayMetrics.density
            val container = android.widget.LinearLayout(this@InteractionSettingsActivity).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding((20 * density).toInt(), (10 * density).toInt(), (20 * density).toInt(), 0)
            }

            val searchBar = com.google.android.material.textfield.TextInputEditText(this@InteractionSettingsActivity).apply {
                hint = getString(R.string.hint_search_apps)
                setSingleLine()
            }
            container.addView(com.google.android.material.textfield.TextInputLayout(this@InteractionSettingsActivity).apply {
                boxBackgroundMode = com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE
                setBoxCornerRadii(12 * density, 12 * density, 12 * density, 12 * density)
                addView(searchBar)
            })

            val listView = android.widget.ListView(this@InteractionSettingsActivity).apply {
                choiceMode = android.widget.ListView.CHOICE_MODE_MULTIPLE
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    (400 * density).toInt()
                )
            }
            container.addView(listView)

            var displayApps = sortedApps
            fun updateList(query: String) {
                displayApps = if (query.isEmpty()) sortedApps
                else sortedApps.filter { it.appName.contains(query, ignoreCase = true) }

                listView.adapter = android.widget.ArrayAdapter(
                    this@InteractionSettingsActivity,
                    android.R.layout.simple_list_item_multiple_choice,
                    displayApps.map { it.appName }.toTypedArray()
                )

                displayApps.forEachIndexed { index, app ->
                    listView.setItemChecked(index, selectedPkgs.contains(app.packageName))
                }
            }

            updateList("")

            listView.setOnItemClickListener { _, _, position, _ ->
                val pkg = displayApps[position].packageName
                if (listView.isItemChecked(position)) selectedPkgs.add(pkg) else selectedPkgs.remove(pkg)
            }

            searchBar.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    updateList(s.toString())
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })

            com.google.android.material.dialog.MaterialAlertDialogBuilder(this@InteractionSettingsActivity)
                .setTitle(title)
                .setView(container)
                .setPositiveButton(R.string.btn_save) { _, _ -> onSave(selectedPkgs.toList()) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun applyOnly() {
        // The service is off: starting it just to refresh would flash
        // its foreground notification and stop again. The prefs are
        // already saved; the service reads them when re-enabled.
        if (!panelPrefs.serviceEnabled) return
        val intent = Intent(this, FloatingPanelService::class.java).apply {
            action = FloatingPanelService.ACTION_REFRESH
        }
        startService(intent)
    }

    // Intent.EXTRA_SHORTCUT_INTENT/NAME/ICON_RESOURCE were deprecated in API 26 but
    // remain the ONLY public surface for the pre-O `com.android.launcher.action.INSTALL_SHORTCUT`
    // broadcast that registers a home-screen launcher shortcut on legacy devices.
    // ShortcutManager.requestPinShortcut (the modern equivalent) requires the
    // caller to be the default launcher, which a side-bar app can't satisfy.
    // Suppress at the function level — pre-O branch is dead on minSdk=26 but
    // kept for older Android forks and OEM skins that still rely on it.
    @Suppress("DEPRECATION")
    private fun pinShortcut() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val shortcutManager = getSystemService(android.content.pm.ShortcutManager::class.java)
            if (shortcutManager.isRequestPinShortcutSupported) {
                val pinIntent = Intent(this, ToggleActivity::class.java).apply {
                    action = ToggleActivity.ACTION_TOGGLE
                    // Stamp the per-install shortcut token so ToggleActivity
                    // can tell our own shortcut from a spoofed intent (see
                    // PanelPreferences.shortcutToken).
                    putExtra("smartedge.shortcut_token", panelPrefs.shortcutToken)
                }
                val pinShortcutInfo = android.content.pm.ShortcutInfo.Builder(this, "toggle_sidebar_pinned")
                    .setShortLabel(getString(R.string.label_toggle_sidebar))
                    .setIcon(android.graphics.drawable.Icon.createWithResource(this, R.mipmap.ic_launcher))
                    .setIntent(pinIntent)
                    .build()

                shortcutManager.requestPinShortcut(pinShortcutInfo, null)
                Toast.makeText(this, R.string.toast_shortcut_sent, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, R.string.toast_launcher_no_shortcut, Toast.LENGTH_SHORT).show()
            }
        } else {
            // Legacy way for Android < 8.0
            val shortcutIntent = Intent(this, ToggleActivity::class.java).apply {
                action = ToggleActivity.ACTION_TOGGLE
            }
            val addIntent = Intent().apply {
                putExtra(Intent.EXTRA_SHORTCUT_INTENT, shortcutIntent)
                putExtra(Intent.EXTRA_SHORTCUT_NAME, getString(R.string.label_toggle_sidebar))
                val iconResource = Intent.ShortcutIconResource.fromContext(this@InteractionSettingsActivity, R.mipmap.ic_launcher)
                putExtra(Intent.EXTRA_SHORTCUT_ICON_RESOURCE, iconResource)
                action = "com.android.launcher.action.INSTALL_SHORTCUT"
            }
            sendBroadcast(addIntent)
            Toast.makeText(this, R.string.toast_shortcut_added, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showActionPicker(title: String, current: Int, slotKey: String, onSelect: (Int) -> Unit) {
        val options = arrayOf(
            getString(R.string.action_none),
            getString(R.string.action_launcher),
            getString(R.string.action_screenshot),
            getString(R.string.action_last_app),
            getString(R.string.action_back),
            getString(R.string.action_home),
            getString(R.string.action_recents),
            getString(R.string.action_notifications),
            getString(R.string.action_quick_settings),
            getString(R.string.action_lock_screen),
            getString(R.string.action_power_menu),
            getString(R.string.action_flashlight),
            getString(R.string.action_camera),
            getString(R.string.action_rotation),
            getString(R.string.action_fav_app),
            getString(R.string.action_move_handle)
        )
        val values = intArrayOf(
            PanelPreferences.ACTION_NONE,
            PanelPreferences.ACTION_OPEN_LAUNCHER,
            PanelPreferences.ACTION_SCREENSHOT,
            PanelPreferences.ACTION_PREVIOUS_APP,
            PanelPreferences.ACTION_BACK,
            PanelPreferences.ACTION_HOME,
            PanelPreferences.ACTION_RECENTS,
            PanelPreferences.ACTION_NOTIFICATIONS,
            PanelPreferences.ACTION_QUICK_SETTINGS,
            PanelPreferences.ACTION_LOCK_SCREEN,
            PanelPreferences.ACTION_POWER_MENU,
            PanelPreferences.ACTION_FLASHLIGHT,
            PanelPreferences.ACTION_CAMERA,
            PanelPreferences.ACTION_AUTO_ROTATION,
            PanelPreferences.ACTION_OPEN_FAVORITE_APP,
            PanelPreferences.ACTION_MOVE_HANDLE
        )
        val selectedIndex = values.indexOf(current).coerceAtLeast(0)

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setSingleChoiceItems(options, selectedIndex) { dialog, which ->
                val selectedAction = values[which]
                if (selectedAction == PanelPreferences.ACTION_OPEN_FAVORITE_APP) {
                    dialog.dismiss()
                    AppSinglePickerDialog.show(this@InteractionSettingsActivity, getString(R.string.picker_title_favorite), slotKey)
                } else {
                    onSelect(selectedAction)
                    loadCurrentSettings()
                    applyOnly()
                    dialog.dismiss()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }


    private fun actionLabel(action: Int, slotKey: String): String = when (action) {
        PanelPreferences.ACTION_OPEN_LAUNCHER -> getString(R.string.action_launcher)
        PanelPreferences.ACTION_SCREENSHOT -> getString(R.string.action_screenshot)
        PanelPreferences.ACTION_PREVIOUS_APP -> getString(R.string.action_last_app)
        PanelPreferences.ACTION_BACK -> getString(R.string.action_back)
        PanelPreferences.ACTION_HOME -> getString(R.string.action_home)
        PanelPreferences.ACTION_RECENTS -> getString(R.string.action_recents)
        PanelPreferences.ACTION_NOTIFICATIONS -> getString(R.string.action_notifications)
        PanelPreferences.ACTION_QUICK_SETTINGS -> getString(R.string.action_quick_settings)
        PanelPreferences.ACTION_LOCK_SCREEN -> getString(R.string.action_lock_screen)
        PanelPreferences.ACTION_POWER_MENU -> getString(R.string.action_power_menu)
        PanelPreferences.ACTION_FLASHLIGHT -> getString(R.string.action_flashlight)
        PanelPreferences.ACTION_CAMERA -> getString(R.string.action_camera)
        PanelPreferences.ACTION_AUTO_ROTATION -> getString(R.string.action_rotation)
        PanelPreferences.ACTION_OPEN_FAVORITE_APP -> {
            // Resolve the app name (not a truncated package fragment):
            // "Fav: com.example" tells the user nothing.
            val pkg = panelPrefs.favoriteAppPackageFor(slotKey)
            val label = try {
                val ai = packageManager.getApplicationInfo(pkg, 0)
                packageManager.getApplicationLabel(ai).toString()
            } catch (_: Exception) {
                pkg.substringAfterLast(".").take(10)
            }
            getString(R.string.action_favorite_app, label)
        }
        PanelPreferences.ACTION_MOVE_HANDLE -> getString(R.string.action_move_handle)
        else -> getString(R.string.action_none)
    }
}
