package com.imi.smartedge.sidebar.panel

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.imi.smartedge.sidebar.panel.databinding.ActivitySetupBinding

class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding
    private lateinit var panelPrefs: PanelPreferences
    private var hasInteractedWithAutoStart = false

    /**
     * Set while "Grant All" is chaining through the missing permissions:
     * onResume advances to the next missing step (only when the previous
     * one was actually granted — if the user declined, the chain stops
     * instead of looping the same system screen).
     */
    private var grantAllInProgress = false
    private var grantAllLastStep: String? = null

    /**
     * The delayed "automation unavailable" Snackbar runnable (D18): kept
     * as a field so it can be cancelled in onDestroy and replaced when
     * the automation card is tapped again, instead of stacking one
     * runnable + Snackbar per tap.
     */
    private val automationUnavailableRunnable = Runnable {
        if (!AutomationManager.isAutomationPossible() && !panelPrefs.useAutomationForGestures) {
            com.google.android.material.snackbar.Snackbar.make(
                binding.root,
                R.string.automation_unavailable_tip,
                com.google.android.material.snackbar.Snackbar.LENGTH_LONG
            ).setAction(R.string.btn_setup) {
                SecureSettingsDialog.show(this)
            }.show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.root.removeCallbacks(automationUnavailableRunnable)
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updateUI()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        panelPrefs = PanelPreferences(this)

        supportActionBar?.hide()

        // Fragment-result listener for the secure-settings dialog:
        // registered here (not in the click handler) so a recreated
        // activity after rotation still receives the grant.
        SecureSettingsDialog.registerResultListener(this) { updateUI() }

        binding.cardOverlay.setOnClickListener { requestOverlayPermission() }
        binding.cardAccessibility.setOnClickListener { requestAccessibilityPermission() }
        binding.cardBattery.setOnClickListener { requestIgnoreBatteryOptimization() }
        binding.cardAutoStart.setOnClickListener {
            hasInteractedWithAutoStart = true
            requestAutoStartPermission()
        }
        binding.cardNotifications.setOnClickListener { requestNotificationAccess() }

        binding.cardAutomation.setOnClickListener {
            AutomationManager.checkRootAndRequestPermission(this) { success ->
                runOnUiThread {
                    if (success) {
                        panelPrefs.useAutomationForGestures = true
                        updateUI()
                    } else {
                        SecureSettingsDialog.show(this)
                        // Audit U3: without this follow-up the user dismisses the
                        // ADB-grant dialog and gets no signal about what to do
                        // next. After a 1.5s grace period (dialog open time), if
                        // automation still hasn't come up, surface an actionable
                        // Snackbar that re-opens the dialog if the user taps.
                        // The runnable is a field (cancelled in onDestroy and
                        // replaced on re-tap) so repeated taps don't stack
                        // multiple Snackbars.
                        binding.root.removeCallbacks(automationUnavailableRunnable)
                        binding.root.postDelayed(automationUnavailableRunnable, 1500)
                    }
                }
            }
        }

        binding.btnGrantAll.setOnClickListener {
            // "Grant All" chains through the missing permissions: each tap
            // used to request only the next one (else-if chain), so the
            // button needed up to 5 taps. Now it starts the chain and
            // onResume advances it as each permission lands.
            grantAllInProgress = true
            val next = nextMissingGrantStep()
            if (next == null) {
                grantAllInProgress = false
            } else {
                grantAllLastStep = next.first
                next.second()
            }
        }

        binding.btnContinue.setOnClickListener {
            panelPrefs.setupCompleted = true
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        // Audit L5/U11: re-probe Shizuku/Root engine state when SetupActivity is foregrounded
        // so cardAutomation reflects the current engine instead of a stale cached value.
        AutomationManager.refresh()
        updateUI()
        // Advance a "Grant All" chain: only when the previous step was
        // actually granted (the next missing step changed). If the user
        // declined/cancelled, the same step is still missing — stop the
        // chain instead of looping the same system screen.
        if (grantAllInProgress) {
            val next = nextMissingGrantStep()
            when {
                next == null -> grantAllInProgress = false
                next.first != grantAllLastStep -> {
                    grantAllLastStep = next.first
                    next.second()
                }
                else -> grantAllInProgress = false
            }
        }
    }

    /**
     * First still-missing setup step (in request order), with the action
     * that requests it. Notification access is intentionally NOT in the
     * chain: the feature defaults off and Continue doesn't require it.
     */
    private fun nextMissingGrantStep(): Pair<String, () -> Unit>? {
        return when {
            !hasOverlayPermission() -> "overlay" to ::requestOverlayPermission
            !isAccessibilityServiceEnabled() -> "accessibility" to ::requestAccessibilityPermission
            !isIgnoringBatteryOptimizations() -> "battery" to ::requestIgnoreBatteryOptimization
            !hasInteractedWithAutoStart -> "autostart" to {
                hasInteractedWithAutoStart = true
                requestAutoStartPermission()
            }
            else -> null
        }
    }

    private fun updateUI() {
        val hasOverlay = hasOverlayPermission()
        val hasAccessibility = isAccessibilityServiceEnabled()
        val hasBattery = isIgnoringBatteryOptimizations()
        val hasAutoStart = hasAutoStartPermission()
        val hasNotifications = isNotificationAccessEnabled()
        val hasAutomation = panelPrefs.useAutomationForGestures && AutomationManager.isAutomationPossible()

        updateCardState(binding.cardOverlay, binding.actionOverlay, binding.titleOverlay, binding.descOverlay, hasOverlay)
        updateCardState(binding.cardAccessibility, binding.actionAccessibility, binding.titleAccessibility, binding.descAccessibility, hasAccessibility)
        updateCardState(binding.cardBattery, binding.actionBattery, binding.titleBattery, binding.descBattery, hasBattery)
        updateCardState(binding.cardNotifications, binding.actionNotifications, binding.titleNotifications, binding.descNotifications, hasNotifications)

        // Update Native Gesture Label with status
        val status = when {
            AutomationManager.isRootAvailable() -> " (Root)"
            AutomationManager.isShizukuAvailable() -> " (Shizuku)"
            else -> ""
        }
        binding.titleAutomation.text = getString(R.string.setup_native_gesture_status, status)
        updateCardState(binding.cardAutomation, binding.actionAutomation, binding.titleAutomation, binding.descAutomation, hasAutomation)

        // Auto-start is hard to detect on most OEMs, but we can detect on MIUI
        updateCardState(binding.cardAutoStart, binding.actionAutoStart, binding.titleAutoStart, binding.descAutoStart, hasAutoStart)

        val requiredGranted = hasOverlay && (hasAccessibility || hasAutomation)
        binding.btnContinue.isEnabled = requiredGranted

        val typedValue = android.util.TypedValue()
        if (requiredGranted) {
            binding.btnContinue.alpha = 1.0f
            // Use Primary color when enabled instead of hardcoded neon green
            theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, typedValue, true)
            binding.btnContinue.setBackgroundColor(typedValue.data)
            theme.resolveAttribute(com.google.android.material.R.attr.colorOnPrimary, typedValue, true)
            binding.btnContinue.setTextColor(typedValue.data)
        } else {
            binding.btnContinue.alpha = 0.3f // "Blurred" / Faded effect
        }

        // Notification access is optional (the feature defaults off and
        // Continue doesn't require it), so it no longer blocks "All
        // granted". Auto-start is excluded too when it can't be detected
        // on this OEM (see hasAutoStartPermission).
        val allGranted = requiredGranted && hasBattery && (hasAutoStart || !isAutoStartDetectable())
        binding.btnGrantAll.isEnabled = !allGranted

        if (allGranted) {
            binding.btnGrantAll.text = getString(R.string.setup_all_granted)
            binding.btnGrantAll.alpha = 0.5f
        } else {
            binding.btnGrantAll.text = getString(R.string.xml_grant_all)
            binding.btnGrantAll.alpha = 1.0f
        }
    }

    private fun isNotificationAccessEnabled(): Boolean {
        val pkgName = packageName
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        if (!flat.isNullOrEmpty()) {
            val names = flat.split(":").toTypedArray()
            for (name in names) {
                val cn = android.content.ComponentName.unflattenFromString(name)
                if (cn != null) {
                    if (android.text.TextUtils.equals(pkgName, cn.packageName)) {
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun requestNotificationAccess() {
        try {
            val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
            startActivity(intent)
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun hasAutoStartPermission(): Boolean {
        return when {
            MIUIUtils.isMIUI() -> MIUIUtils.isAutoStartEnabled(this)
            VivoUtils.isVivo() -> VivoUtils.isAutoStartEnabled(this)
            // Undetectable OEMs: never report granted from a mere tap.
            // The old fallback returned hasInteractedWithAutoStart, so
            // one tap on the card faked a green check on phones where
            // autostart can't be enabled or detected at all.
            else -> false
        }
    }

    /** True when auto-start can actually be detected on this OEM (MIUI/Vivo). */
    private fun isAutoStartDetectable(): Boolean = MIUIUtils.isMIUI() || VivoUtils.isVivo()

    private fun updateCardState(
        card: com.google.android.material.card.MaterialCardView,
        action: android.widget.ImageView,
        title: android.widget.TextView?,
        desc: android.widget.TextView?,
        isGranted: Boolean
    ) {
        val typedValue = android.util.TypedValue()

        // Dim the title/description individually (by view, not by
        // positional child index — a layout reorder used to silently stop
        // the dimming because getChildAt(0)/getChildAt(1) no longer
        // pointed at the title/description).
        if (isGranted) {
            theme.resolveAttribute(com.google.android.material.R.attr.colorSurfaceContainerHigh, typedValue, true)
            card.setCardBackgroundColor(typedValue.data)

            action.setImageResource(android.R.drawable.checkbox_on_background)
            action.imageTintList = android.content.res.ColorStateList.valueOf(
                android.graphics.Color.parseColor("#00FF00") // ELECTRIC GREEN
            )
            // Allow clicking to toggle even when granted for internal preferences
            card.isClickable = card.id == binding.cardAutoStart.id ||
                             card.id == binding.cardAutomation.id
            card.alpha = 1.0f // Keep full opacity for the card
            title?.alpha = 1.0f // Set to normal
            desc?.alpha = 1.0f  // Set to normal
        } else {
            theme.resolveAttribute(com.google.android.material.R.attr.colorSurfaceContainerHigh, typedValue, true)
            card.setCardBackgroundColor(typedValue.data)

            action.setImageResource(R.drawable.ic_chevron_right)
            theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurfaceVariant, typedValue, true)
            action.imageTintList = android.content.res.ColorStateList.valueOf(typedValue.data)
            card.isClickable = true
            card.alpha = 1.0f
            title?.alpha = 1.0f
            desc?.alpha = 1.0f
        }
    }

    private fun hasOverlayPermission(): Boolean = Settings.canDrawOverlays(this)

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val powerManager = getSystemService(POWER_SERVICE) as android.os.PowerManager
        return powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        overlayPermissionLauncher.launch(intent)
    }

    private fun requestAccessibilityPermission() {
        AccessibilityGuideDialog.newInstance()
            .show(supportFragmentManager, AccessibilityGuideDialog.TAG)
    }

    private fun requestIgnoreBatteryOptimization() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {}
        }
    }

    private fun requestAutoStartPermission() {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val intent = Intent()
        var found = false

        when {
            manufacturer.contains("xiaomi") -> {
                intent.setClassName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
                found = true
            }
            manufacturer.contains("oppo") -> {
                intent.setClassName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")
                found = true
            }
            manufacturer.contains("vivo") -> {
                intent.setClassName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
                found = true
            }
            manufacturer.contains("samsung") -> {
                intent.setClassName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")
                found = true
            }
        }

        if (found) {
            try {
                startActivity(intent)
            } catch (e: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                } catch (e2: Exception) {}
            }
        } else {
            // Fallback for other OEMs
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (e: Exception) {}
        }
    }
}
