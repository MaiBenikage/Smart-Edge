package com.imi.smartedge.sidebar.panel

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.imi.smartedge.sidebar.panel.databinding.ActivitySettingsToolsBinding
// import rikka.shizuku.Shizuku
import android.content.pm.PackageManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName

class ToolsSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsToolsBinding
    private lateinit var panelPrefs: PanelPreferences
    // private val SHIZUKU_CODE = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsToolsBinding.inflate(layoutInflater)
        setContentView(binding.root)



        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        panelPrefs = PanelPreferences(this)
        
        loadCurrentSettings()
        setupListeners()
        handleDeepLink()
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
                binding.toolsScrollView.smoothScrollTo(0, rect.top - 200)
                targetView.highlightView()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Shizuku.removeRequestPermissionResultListener(requestPermissionResultListener)
    }

    private fun loadCurrentSettings() {
        binding.featureToolsMaster.isChecked = panelPrefs.showTools
        binding.layoutToolsSubOptions.alpha = if (panelPrefs.showTools) 1.0f else 0.5f
        binding.layoutToolsSubOptions.isEnabled = panelPrefs.showTools
        // binding.divTools.visibility = if (panelPrefs.showTools) View.VISIBLE else View.GONE

        binding.featureSysInfo.isChecked = panelPrefs.showSysInfo
        binding.featurePowerMenu.isChecked = panelPrefs.showPowerMenu
        binding.featureVolumeKeys.isChecked = panelPrefs.showVolumeKeys
        binding.featureBrightnessKeys.isChecked = panelPrefs.showBrightnessKeys
        binding.featureScreenshot.isChecked = panelPrefs.showScreenshotTool
        binding.featureBlackScreen.isChecked = panelPrefs.showBlackScreenTool
        binding.featureLockScreen.isChecked = panelPrefs.showLockScreenTool
        binding.featureContentPicker.isChecked = panelPrefs.showContentPickerTool
        binding.featureToolsPanel.isChecked = panelPrefs.showToolsPanelButton
        // Device admin: reflect the REAL admin-active state (not just the pref),
        // so the switch truthfully shows whether Screen-Off-without-Lock works.
        binding.featureDeviceAdmin.isChecked = isDeviceAdminActive()
    }

    private fun isDeviceAdminActive(): Boolean {
        return try {
            val dpm = getSystemService(DevicePolicyManager::class.java)
            val cn = ComponentName(this, SmartEdgeDeviceAdminReceiver::class.java)
            dpm?.isAdminActive(cn) == true
        } catch (e: Exception) {
            false
        }
    }

    private fun requestDeviceAdmin() {
        try {
            val dpm = getSystemService(DevicePolicyManager::class.java)
            val cn = ComponentName(this, SmartEdgeDeviceAdminReceiver::class.java)
            if (dpm?.isAdminActive(cn) == true) return
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, cn)
                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, getString(R.string.xml_enable_device_admin_desc))
            }
            startActivity(intent)
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, R.string.xml_device_admin_desc, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupListeners() {
        binding.featureToolsMaster.setOnCheckedChangeListener { _, isChecked ->
            panelPrefs.showTools = isChecked
            binding.layoutToolsSubOptions.alpha = if (isChecked) 1.0f else 0.5f
            binding.layoutToolsSubOptions.isEnabled = isChecked
            // binding.divTools.visibility = if (isChecked) View.VISIBLE else View.GONE
            applyOnly()
        }

        binding.featureSysInfo.setOnCheckedChangeListener { _, isChecked ->
            panelPrefs.showSysInfo = isChecked
            applyOnly()
        }

        binding.featurePowerMenu.setOnCheckedChangeListener { _, isChecked ->
            panelPrefs.showPowerMenu = isChecked
            applyOnly()
        }

        binding.featureVolumeKeys.setOnCheckedChangeListener { _, isChecked ->
            panelPrefs.showVolumeKeys = isChecked
            applyOnly()
        }

        binding.featureBrightnessKeys.setOnCheckedChangeListener { _, isChecked ->
            panelPrefs.showBrightnessKeys = isChecked
            applyOnly()
        }

        binding.featureScreenshot.setOnCheckedChangeListener { _, isChecked ->
            panelPrefs.showScreenshotTool = isChecked
            applyOnly()
        }

        binding.featureBlackScreen.setOnCheckedChangeListener { _, isChecked ->
            panelPrefs.showBlackScreenTool = isChecked
            applyOnly()
        }

        binding.featureLockScreen.setOnCheckedChangeListener { _, isChecked ->
            panelPrefs.showLockScreenTool = isChecked
            applyOnly()
        }

        binding.featureContentPicker.setOnCheckedChangeListener { _, isChecked ->
            panelPrefs.showContentPickerTool = isChecked
            applyOnly()
        }

        binding.featureToolsPanel.setOnCheckedChangeListener { _, isChecked ->
            panelPrefs.showToolsPanelButton = isChecked
            applyOnly()
        }

        binding.featureDeviceAdmin.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                // Enabling requires the user to accept the Device Admin prompt.
                if (!isDeviceAdminActive()) {
                    // Revert the switch to actual state; activation happens via the
                    // system admin screen. onResume re-syncs the checkbox.
                    binding.featureDeviceAdmin.isChecked = false
                    requestDeviceAdmin()
                }
            } else {
                // Disabling: deactivate admin so the keyguard policy is removed.
                try {
                    val dpm = getSystemService(DevicePolicyManager::class.java)
                    val cn = ComponentName(this, SmartEdgeDeviceAdminReceiver::class.java)
                    dpm?.removeActiveAdmin(cn)
                } catch (e: Exception) {}
                panelPrefs.enableDeviceAdmin = false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // After returning from the Device Admin activation screen, sync the switch
        // with the real admin state.
        binding.featureDeviceAdmin.isChecked = isDeviceAdminActive()
        if (isDeviceAdminActive()) panelPrefs.enableDeviceAdmin = true
    }

    private fun applyOnly() {
        val intent = Intent(this, FloatingPanelService::class.java).apply {
            action = FloatingPanelService.ACTION_REFRESH
        }
        startService(intent)
    }
}
