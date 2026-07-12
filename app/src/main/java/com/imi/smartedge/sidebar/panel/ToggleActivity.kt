package com.imi.smartedge.sidebar.panel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * A transparent activity that triggers the Side Panel to open.
 * Useful for mapping to hardware keys or 3rd party gesture apps.
 */
class ToggleActivity : AppCompatActivity() {

    companion object {
        const val ACTION_TOGGLE = "com.imi.smartedge.sidebar.panel.TOGGLE"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Handle Shortcut Creation request from Launcher
        // SECURITY: Embed smartedge.from_shortcut=true into the shortcutIntent so on
        // subsequent launches we can detect that ToggleActivity was triggered by
        // tapping our own home-screen shortcut (vs. being invoked by a 3rd-party
        // app via plain MAIN). This gates the silent service re-enable below.
        if (intent.action == Intent.ACTION_CREATE_SHORTCUT) {
            val shortcutIntent = Intent(this, ToggleActivity::class.java).apply {
                action = ACTION_TOGGLE
                putExtra("smartedge.from_shortcut", true)
            }

            val resultIntent = Intent().apply {
                putExtra(Intent.EXTRA_SHORTCUT_INTENT, shortcutIntent)
                putExtra(Intent.EXTRA_SHORTCUT_NAME, "Toggle Sidebar")
                val iconResource = Intent.ShortcutIconResource.fromContext(this@ToggleActivity, R.mipmap.ic_launcher)
                putExtra(Intent.EXTRA_SHORTCUT_ICON_RESOURCE, iconResource)
            }

            setResult(RESULT_OK, resultIntent)
            finish()
            return
        }

        // 2. Check basic permissions
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Overlay permission required", Toast.LENGTH_SHORT).show()
            val pIntent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivity(pIntent)
            finish()
            return
        }

        if (!PanelAccessibilityService.isRunning) {
            Toast.makeText(this, "Accessibility service required", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Ensure service is enabled in preferences ONLY when ToggleActivity was
        // launched from our own home-screen shortcut (the CREATE_SHORTCUT branch
        // above stamps smartedge.from_shortcut=true into the shortcut intent).
        // Without this gate, any 3rd-party app could fire `Intent(MAIN).setClassName(
        // this, ToggleActivity)` and silently flip the service back on after the
        // user had disabled it for battery / privacy reasons.
        if (intent.getBooleanExtra("smartedge.from_shortcut", false)) {
            PanelPreferences(this).serviceEnabled = true
        }

        // 3. Trigger the Panel
        val intent = Intent(this, FloatingPanelService::class.java).apply {
            action = FloatingPanelService.ACTION_OPEN
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 4. Close immediately
        finishWithNoAnim()
    }

    private fun finishWithNoAnim() {
        finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }
}
