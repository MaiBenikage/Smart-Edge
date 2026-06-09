package com.imi.smartedge.sidebar.panel

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.imi.smartedge.sidebar.panel.databinding.ActivitySettingsHandleBinding

class HandleSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsHandleBinding
    private lateinit var panelPrefs: PanelPreferences

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsHandleBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        panelPrefs = PanelPreferences(this)
        
        loadCurrentSettings()
        setupListeners()
    }

    private fun loadCurrentSettings() {
        binding.featureShowPill.isChecked = panelPrefs.showPill
        
        binding.sbPillThickness.value = panelPrefs.pillWidth.toFloat()
        binding.tvThicknessValue.text = "${panelPrefs.pillWidth}dp"
        
        binding.sbTriggerWidth.value = panelPrefs.handleWidth.toFloat()
        binding.tvWidthValue.text = "${panelPrefs.handleWidth}dp"
        
        binding.sbHandleHeight.value = panelPrefs.handleHeight.toFloat()
        binding.tvHeightValue.text = "${panelPrefs.handleHeight}dp"
        
        binding.sbHandlePos.value = panelPrefs.handleVerticalOffset.toFloat()
        binding.tvPosValue.text = "${panelPrefs.handleVerticalOffset}dp"
    }

    private fun setupListeners() {
        binding.featureShowPill.setOnCheckedChangeListener { _, isChecked ->
            panelPrefs.showPill = isChecked
            applyOnly()
        }

        binding.btnPickPillColor.setOnClickListener {
            // Re-using current accent picker logic if needed, or keeping it simple
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Select Accent Color")
                .setMessage("Please use the 'Accent Color' setting in Appearance to change this.")
                .setPositiveButton("Go to Appearance") { _, _ ->
                    startActivity(Intent(this, AppearanceSettingsActivity::class.java))
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        binding.sbPillThickness.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                panelPrefs.pillWidth = value.toInt()
                binding.tvThicknessValue.text = "${value.toInt()}dp"
            }
        }
        binding.sbPillThickness.addOnSliderTouchListener(object : com.google.android.material.slider.Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) {}
            override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) { applyOnly() }
        })

        binding.btnResetThickness.setOnClickListener {
            val default = 2
            panelPrefs.pillWidth = default
            binding.sbPillThickness.value = default.toFloat()
            binding.tvThicknessValue.text = "${default}dp"
            applyOnly()
        }

        binding.sbTriggerWidth.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                panelPrefs.handleWidth = value.toInt()
                binding.tvWidthValue.text = "${value.toInt()}dp"
            }
        }
        binding.sbTriggerWidth.addOnSliderTouchListener(object : com.google.android.material.slider.Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) {}
            override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) { applyOnly() }
        })

        binding.btnResetWidth.setOnClickListener {
            val default = 15
            panelPrefs.handleWidth = default
            binding.sbTriggerWidth.value = default.toFloat()
            binding.tvWidthValue.text = "${default}dp"
            applyOnly()
        }

        binding.sbHandleHeight.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                panelPrefs.handleHeight = value.toInt()
                binding.tvHeightValue.text = "${value.toInt()}dp"
            }
        }
        binding.sbHandleHeight.addOnSliderTouchListener(object : com.google.android.material.slider.Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) {}
            override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) { applyOnly() }
        })

        binding.btnResetHeight.setOnClickListener {
            val default = 150
            panelPrefs.handleHeight = default
            binding.sbHandleHeight.value = default.toFloat()
            binding.tvHeightValue.text = "${default}dp"
            applyOnly()
        }

        binding.sbHandlePos.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                panelPrefs.handleVerticalOffset = value.toInt()
                binding.tvPosValue.text = "${value.toInt()}dp"
            }
        }
        binding.sbHandlePos.addOnSliderTouchListener(object : com.google.android.material.slider.Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) {}
            override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) { applyOnly() }
        })

        binding.btnResetPos.setOnClickListener {
            val default = 0
            panelPrefs.handleVerticalOffset = default
            binding.sbHandlePos.value = default.toFloat()
            binding.tvPosValue.text = "${default}dp"
            applyOnly()
        }
    }

    private fun applyOnly() {
        val intent = Intent(this, FloatingPanelService::class.java).apply {
            action = FloatingPanelService.ACTION_REFRESH
        }
        startService(intent)
    }
}
