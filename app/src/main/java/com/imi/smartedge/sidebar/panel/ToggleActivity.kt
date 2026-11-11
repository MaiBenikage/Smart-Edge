package com.imi.smartedge.sidebar.panel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
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

    // Intent.EXTRA_SHORTCUT_INTENT/NAME/ICON_RESOURCE were deprecated in API 26 but
    // remain the ONLY public surface for the legacy Intent.ACTION_CREATE_SHORTCUT
    // flow that lets a 3rd-party app publish a home-screen launcher shortcut.
    // ShortcutManager.requestPinShortcut is gated on default-launcher / system-app
    // status and can't be used from a regular Android service/app. Suppress at the
    // function level — there are no other deprecated APIs touched here.
    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Handle Shortcut Creation request from Launcher
        // SECURITY: Embed the per-install shortcut token (see
        // PanelPreferences.shortcutToken) into the shortcutIntent so on
        // subsequent launches we can detect that ToggleActivity was triggered
        // by tapping our own home-screen shortcut (vs. being invoked by a
        // 3rd-party app via plain MAIN). A plain boolean extra can be set by
        // any app; the random token cannot be guessed. This gates the silent
        // service re-enable below.
        if (intent.action == Intent.ACTION_CREATE_SHORTCUT) {
            val shortcutIntent = Intent(this, ToggleActivity::class.java).apply {
                action = ACTION_TOGGLE
                putExtra("smartedge.shortcut_token", PanelPreferences(this@ToggleActivity).shortcutToken)
            }

            val resultIntent = Intent().apply {
                putExtra(Intent.EXTRA_SHORTCUT_INTENT, shortcutIntent)
                putExtra(Intent.EXTRA_SHORTCUT_NAME, getString(R.string.label_toggle_sidebar))
                val iconResource = Intent.ShortcutIconResource.fromContext(this@ToggleActivity, R.mipmap.ic_launcher)
                putExtra(Intent.EXTRA_SHORTCUT_ICON_RESOURCE, iconResource)
            }

            setResult(RESULT_OK, resultIntent)
            finish()
            return
        }

        // 2. Check basic permissions
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, getString(R.string.toast_overlay_required), Toast.LENGTH_SHORT).show()
            val pIntent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivity(pIntent)
            finish()
            return
        }

        if (!PanelAccessibilityService.isRunning) {
            Toast.makeText(this, getString(R.string.toast_accessibility_required), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Ensure service is enabled in preferences ONLY when ToggleActivity was
        // launched from our own home-screen shortcut (the CREATE_SHORTCUT branch
        // above and pinShortcut() stamp the per-install shortcut token into
        // the shortcut intent). Without this gate, any 3rd-party app could fire
        // `Intent(MAIN).setClassName(this, ToggleActivity)` and silently flip
        // the service back on after the user had disabled it for battery /
        // privacy reasons. A boolean extra is not a gate — any app can set one —
        // so the token (a random UUID only our prefs know) is checked instead.
        if (intent.action == ACTION_TOGGLE &&
            intent.getStringExtra("smartedge.shortcut_token") == PanelPreferences(this).shortcutToken) {
            PanelPreferences(this).serviceEnabled = true
        }

        // 3. Trigger the Panel — but only if the service is enabled. The
        // token gate above re-enables it for our own shortcuts; without a
        // valid token (static manifest shortcut, which can't carry the
        // per-install token, or any other caller) the service stays off
        // and ACTION_OPEN would silently do nothing. Tell the user and
        // route them to the main screen where they can start it.
        if (!PanelPreferences(this).serviceEnabled) {
            Toast.makeText(this, getString(R.string.toast_service_stopped_start_first), Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

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
            Log.e("ToggleActivity", "Failed to start service", e)
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
