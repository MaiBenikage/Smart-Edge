package com.imi.smartedge.sidebar.panel

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.imi.smartedge.sidebar.panel.databinding.ActivitySettingsAppearanceBinding

/**
 * Handles all UI styling settings:
 * - Theme selection (Origin, HyperOS, etc)
 * - Accent color
 * - Background color & opacity
 * - Corner radius
 * - Scale & Size
 */
class AppearanceSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsAppearanceBinding
    private lateinit var panelPrefs: PanelPreferences

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsAppearanceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        panelPrefs = PanelPreferences(this)

        setupToolbar()
        loadCurrentSettings()
        setupListeners()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun loadCurrentSettings() {
        binding.sbOpacity.value = panelPrefs.panelOpacity.toFloat()
        binding.tvOpacityValue.text = getString(R.string.fmt_percent, panelPrefs.panelOpacity)

        binding.sbPanelRadius.value = panelPrefs.panelCornerRadius.toFloat()
        binding.tvRadiusValue.text = getString(R.string.fmt_dp, panelPrefs.panelCornerRadius)

        binding.sbIconScale.value = panelPrefs.scaleFactor
        binding.tvIconScaleValue.text = String.format("%.1fx", panelPrefs.scaleFactor)

        binding.sbMaxHeight.value = panelPrefs.panelMaxHeight.toFloat()
        binding.tvMaxHeightValue.text = getString(R.string.fmt_dp, panelPrefs.panelMaxHeight)

        binding.sbPickerMaxHeight.value = panelPrefs.pickerMaxHeight.toFloat()
        binding.tvPickerMaxHeightValue.text = getString(R.string.fmt_dp, panelPrefs.pickerMaxHeight)

        binding.tvThemeModeValue.text = when (panelPrefs.themeMode) {
            PanelPreferences.MODE_LIGHT -> getString(R.string.theme_light)
            PanelPreferences.MODE_DARK -> getString(R.string.theme_dark)
            else -> getString(R.string.theme_follow_system)
        }

        binding.tvUIStyleValue.text = when (panelPrefs.uiTheme) {
            PanelPreferences.THEME_HYPEROS -> getString(R.string.theme_hyperos)
            PanelPreferences.THEME_REALME -> getString(R.string.theme_realme)
            PanelPreferences.THEME_RICH -> getString(R.string.theme_rich)
            else -> getString(R.string.theme_origin)
        }

        binding.tvIconShapeValue.text = when (panelPrefs.iconShape) {
            PanelPreferences.SHAPE_CIRCLE -> getString(R.string.xml_shape_circle)
            PanelPreferences.SHAPE_SQUARE -> getString(R.string.xml_shape_square)
            PanelPreferences.SHAPE_ROUNDED -> getString(R.string.xml_shape_rounded)
            PanelPreferences.SHAPE_SQUIRCLE -> getString(R.string.xml_shape_squircle)
            else -> getString(R.string.icon_pack_default)
        }

        binding.featureBlur.isChecked = panelPrefs.blurEnabled
        binding.sbBlurAmount.value = panelPrefs.blurAmount.toFloat()
        binding.tvBlurAmountValue.text = getString(R.string.fmt_number, panelPrefs.blurAmount)
        
        binding.featureHideBg.isChecked = panelPrefs.hideBackground
        
        binding.tvColumnsValue.text = if (panelPrefs.panelColumns > 1) getString(R.string.fmt_columns_plural, panelPrefs.panelColumns) else getString(R.string.fmt_columns, panelPrefs.panelColumns)
        
        binding.featureCustomAccent.isChecked = panelPrefs.useCustomAccent
        
        binding.tvCurrentIconPack.text = panelPrefs.iconPackLabel

        binding.btnPickAccent.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(panelPrefs.accentColor))
        binding.btnPickBg.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(panelPrefs.panelBackgroundColor))

        binding.tvHomeButtonStyleValue.text = when (panelPrefs.homeButtonStyle) {
            PanelPreferences.STYLE_POWER -> getString(R.string.button_style_power)
            else -> getString(R.string.button_style_logo)
        }
    }

    private fun setupListeners() {
        binding.sbOpacity.addOnChangeListener { _, value, _ ->
            panelPrefs.panelOpacity = value.toInt()
            binding.tvOpacityValue.text = getString(R.string.fmt_percent, value.toInt())
            applyOnly()
        }

        binding.sbPanelRadius.addOnChangeListener { _, value, _ ->
            panelPrefs.panelCornerRadius = value.toInt()
            binding.tvRadiusValue.text = getString(R.string.fmt_dp, value.toInt())
            applyOnly()
        }

        binding.sbIconScale.addOnChangeListener { _, value, _ ->
            panelPrefs.scaleFactor = value
            binding.tvIconScaleValue.text = String.format("%.1fx", value)
            applyOnly()
        }

        binding.sbMaxHeight.addOnChangeListener { _, value, _ ->
            panelPrefs.panelMaxHeight = value.toInt()
            binding.tvMaxHeightValue.text = getString(R.string.fmt_dp, value.toInt())
            applyOnly()
        }

        binding.sbPickerMaxHeight.addOnChangeListener { _, value, _ ->
            panelPrefs.pickerMaxHeight = value.toInt()
            binding.tvPickerMaxHeightValue.text = getString(R.string.fmt_dp, value.toInt())
            applyOnly()
        }

        binding.btnResetIconScale.setOnClickListener {
            panelPrefs.scaleFactor = 1.0f
            binding.sbIconScale.value = 1.0f
            binding.tvIconScaleValue.text = getString(R.string.fmt_scale_x, 1.0)
            applyOnly()
        }

        binding.btnResetMaxHeight.setOnClickListener {
            val default = 350
            panelPrefs.panelMaxHeight = default
            binding.sbMaxHeight.value = default.toFloat()
            binding.tvMaxHeightValue.text = getString(R.string.fmt_dp, default)
            applyOnly()
        }

        binding.btnResetPickerMaxHeight.setOnClickListener {
            val default = 450
            panelPrefs.pickerMaxHeight = default
            binding.sbPickerMaxHeight.value = default.toFloat()
            binding.tvPickerMaxHeightValue.text = getString(R.string.fmt_dp, default)
            applyOnly()
        }

        binding.btnResetOpacity.setOnClickListener {
            val default = 100
            panelPrefs.panelOpacity = default
            binding.sbOpacity.value = default.toFloat()
            binding.tvOpacityValue.text = getString(R.string.fmt_percent, default)
            applyOnly()
        }

        binding.btnResetRadius.setOnClickListener {
            val default = 20
            panelPrefs.panelCornerRadius = default
            binding.sbPanelRadius.value = default.toFloat()
            binding.tvRadiusValue.text = getString(R.string.fmt_dp, default)
            applyOnly()
        }

        binding.featureThemeMode.setOnClickListener {
            val options = arrayOf(getString(R.string.theme_follow_system), getString(R.string.theme_light), getString(R.string.theme_dark))
            val values = arrayOf(
                PanelPreferences.MODE_SYSTEM,
                PanelPreferences.MODE_LIGHT,
                PanelPreferences.MODE_DARK
            )
            
            val selectedIndex = values.indexOf(panelPrefs.themeMode).let { if (it == -1) 0 else it }

            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_app_theme)
                .setSingleChoiceItems(options, selectedIndex) { dialog, which ->
                    panelPrefs.themeMode = values[which]
                    binding.tvThemeModeValue.text = options[which]
                    applyAppTheme(this)
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.dialog_cancel, null)
                .show()
        }

        binding.layoutUIStyle.setOnClickListener {
            val options = arrayOf(getString(R.string.theme_origin), getString(R.string.theme_hyperos), getString(R.string.theme_realme), getString(R.string.theme_rich))
            val values = arrayOf(
                PanelPreferences.THEME_ORIGIN,
                PanelPreferences.THEME_HYPEROS,
                PanelPreferences.THEME_REALME,
                PanelPreferences.THEME_RICH
            )
            
            val selectedIndex = values.indexOf(panelPrefs.uiTheme).let { if (it == -1) 0 else it }

            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_panel_ui_style)
                .setSingleChoiceItems(options, selectedIndex) { dialog, which ->
                    panelPrefs.uiTheme = values[which]
                    binding.tvUIStyleValue.text = options[which]
                    applyOnly()
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.dialog_cancel, null)
                .show()
        }

        binding.featureIconShape.setOnClickListener {
            val options = arrayOf(getString(R.string.icon_pack_default), getString(R.string.xml_shape_circle), getString(R.string.xml_shape_square), getString(R.string.xml_shape_rounded), getString(R.string.xml_shape_squircle))
            val values = arrayOf(
                PanelPreferences.SHAPE_SYSTEM,
                PanelPreferences.SHAPE_CIRCLE,
                PanelPreferences.SHAPE_SQUARE,
                PanelPreferences.SHAPE_ROUNDED,
                PanelPreferences.SHAPE_SQUIRCLE
            )

            val selectedIndex = values.indexOf(panelPrefs.iconShape).let { if (it == -1) 0 else it }

            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_icon_shape)
                .setSingleChoiceItems(options, selectedIndex) { dialog, which ->
                    panelPrefs.iconShape = values[which]
                    binding.tvIconShapeValue.text = options[which]
                    applyOnly()
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.dialog_cancel, null)
                .show()
        }

        binding.featureBlur.setOnCheckedChangeListener { _, isChecked ->
            panelPrefs.blurEnabled = isChecked
            applyOnly()
        }

        binding.sbBlurAmount.addOnChangeListener { _, value, _ ->
            panelPrefs.blurAmount = value.toInt()
            binding.tvBlurAmountValue.text = getString(R.string.fmt_number, value.toInt())
            applyOnly()
        }

        binding.featureHideBg.setOnCheckedChangeListener { _, isChecked ->
            panelPrefs.hideBackground = isChecked
            applyOnly()
        }

        binding.featureColumns.setOnClickListener {
            val options = arrayOf(getString(R.string.fmt_columns, 1), getString(R.string.fmt_columns_plural, 2))
            val currentSelectedIndex = (panelPrefs.panelColumns - 1).coerceIn(0, 1)
            var newlySelectedIndex = currentSelectedIndex

            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_panel_columns)
                .setSingleChoiceItems(options, currentSelectedIndex) { _, which ->
                    newlySelectedIndex = which
                }
                .setPositiveButton(R.string.dialog_apply) { _, _ ->
                    val columns = newlySelectedIndex + 1
                    panelPrefs.panelColumns = columns
                    binding.tvColumnsValue.text = options[newlySelectedIndex]
                    applyOnly()
                }
                .setNegativeButton(R.string.dialog_cancel, null)
                .show()
        }

        binding.featureCustomAccent.setOnCheckedChangeListener { _, isChecked ->
            panelPrefs.useCustomAccent = isChecked
            applyOnly()
        }

        binding.btnSelectIconPack.setOnClickListener {
            IconPackPickerDialog.show(this) {
                binding.tvCurrentIconPack.text = panelPrefs.iconPackLabel
            }
        }

        binding.btnResetUIColors.setOnClickListener {
            panelPrefs.resetUIColors()
            loadCurrentSettings()
            applyOnly()
            binding.root.showModernToast(getString(R.string.toast_ui_colors_restored))
        }

        binding.btnPickAccent.setOnClickListener {
            if (panelPrefs.uiTheme == PanelPreferences.THEME_ORIGIN) {
                binding.root.showModernToast(getString(R.string.toast_accent_locked_origin))
                return@setOnClickListener
            }
            openColorPicker(Color.parseColor(panelPrefs.accentColor)) { newColor ->
                val hex = String.format("#%06X", (0xFFFFFF and newColor))
                panelPrefs.accentColor = hex
                loadCurrentSettings()
                applyOnly()
            }
        }

        binding.btnPickBg.setOnClickListener {
            if (panelPrefs.uiTheme == PanelPreferences.THEME_ORIGIN) {
                binding.root.showModernToast(getString(R.string.toast_bg_locked_origin))
                return@setOnClickListener
            }
            openColorPicker(Color.parseColor(panelPrefs.panelBackgroundColor)) { newColor ->
                val hex = String.format("#E6%06X", (0xFFFFFF and newColor))
                panelPrefs.panelBackgroundColor = hex
                loadCurrentSettings()
                applyOnly()
            }
        }

        binding.featureHomeButton.setOnClickListener {
            val options = arrayOf(getString(R.string.button_style_power), getString(R.string.button_style_logo))
            val values = arrayOf(PanelPreferences.STYLE_POWER, PanelPreferences.STYLE_CLASSIC)
            val selectedIndex = values.indexOf(panelPrefs.homeButtonStyle).let { if (it == -1) 0 else it }

            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_service_button_style)
                .setSingleChoiceItems(options, selectedIndex) { dialog, which ->
                    panelPrefs.homeButtonStyle = values[which]
                    binding.tvHomeButtonStyleValue.text = options[which]
                    applyOnly()
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.dialog_cancel, null)
                .show()
        }
    }

    private fun applyOnly() {
        val intent = Intent(this, FloatingPanelService::class.java).apply {
            action = FloatingPanelService.ACTION_REFRESH
        }
        startService(intent)
    }
}
