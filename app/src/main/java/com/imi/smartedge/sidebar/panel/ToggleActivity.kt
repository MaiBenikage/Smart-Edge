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
        // SECURITY: the shortcutIntent carries only a per-shortcut ID
        // minted for this shortcut and recorded in prefs (see
        // PanelPreferences.issueShortcutId) — never the per-install
        // shortcut token. Audit (4th) Low: the result used to embed the
        // token, so any app could send ACTION_CREATE_SHORTCUT, harvest
        // the token from the result, and pass the service re-enable gate
        // below — exactly the spoofing the round-1 token was meant to
        // stop. A per-shortcut ID proves only that this install issued
        // this shortcut, and the token never leaves the app in this flow.
        if (intent.action == Intent.ACTION_CREATE_SHORTCUT) {
            val shortcutIntent = Intent(this, ToggleActivity::class.java).apply {
                action = ACTION_TOGGLE
                putExtra("smartedge.shortcut_id", PanelPreferences(this@ToggleActivity).issueShortcutId())
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

        // Accessibility OR usable automation both count as an engine (see
        // isEngineAvailable): an automation-only user (Shizuku/root, no
        // Accessibility) can run the panel, so requiring the a11y service
        // here locked them out of the home-screen shortcut, the static
        // launcher shortcut, and the notification open action.
        //
        // Audit (4th) M-A: isEngineAvailable() reads AutomationManager's
        // cache, which is cold (UNKNOWN) after a reboot until something
        // calls refresh() — a legitimate shortcut launch was refused until
        // the screen rotated. Kick off a refresh; while the state is still
        // UNKNOWN the probe has not reported yet, so do not refuse — proceed
        // and let the service's own re-gate settle it. A completed probe
        // that found no engine (NONE) still refuses below.
        if (!isEngineAvailable()) {
            if (AutomationManager.engineState.value == AutomationManager.EngineState.UNKNOWN) {
                AutomationManager.refresh()
            } else {
                Toast.makeText(this, getString(R.string.toast_accessibility_required), Toast.LENGTH_SHORT).show()
                finish()
                return
            }
        }

        // Ensure service is enabled in preferences ONLY when ToggleActivity was
        // launched from our own home-screen shortcut: either the per-shortcut
        // ID minted by the CREATE_SHORTCUT branch above, or the per-install
        // token stamped by the ShortcutManager pin flow (which goes to the
        // system launcher only — see InteractionSettingsActivity.pinShortcut).
        // Without this gate, any 3rd-party app could fire
        // `Intent(MAIN).setClassName(this, ToggleActivity)` and silently flip
        // the service back on after the user had disabled it for battery /
        // privacy reasons. A boolean extra is not a gate — any app can set one —
        // so the issued ID (recorded only in our prefs) or the token (a random
        // UUID only our prefs know) is checked instead.
        val prefs = PanelPreferences(this)
        if (intent.action == ACTION_TOGGLE &&
            (prefs.isIssuedShortcutId(intent.getStringExtra("smartedge.shortcut_id")) ||
                intent.getStringExtra("smartedge.shortcut_token") == prefs.shortcutToken)) {
            prefs.serviceEnabled = true
        }

        // 3. Trigger the Panel — but only if the service is enabled. The
        // ID/token gate above re-enables it for our own shortcuts; without
        // a valid ID or token (static manifest shortcut, which can't carry
        // either, or any other caller) the service stays off
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
