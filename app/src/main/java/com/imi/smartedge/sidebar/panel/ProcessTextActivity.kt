package com.imi.smartedge.sidebar.panel

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.net.URLEncoder

/**
 * The single entry point Smart Edge exposes in Android's text-selection menu.
 *
 * Manifest registers this activity for [Intent.ACTION_PROCESS_TEXT] with the
 * label "Smart Edge…". Android only allows static manifest registrations, so
 * user-configured shortcuts cannot each become a system menu item — instead,
 * tapping the entry opens this translucent activity which shows the user's
 * [TextAction] list (see [TextActionsSettingsActivity]) as a second-level
 * chooser, then executes the chosen action.
 *
 * Receives:
 * - [Intent.EXTRA_PROCESS_TEXT] the selected text (CharSequence, may be styled)
 * - [Intent.EXTRA_PROCESS_TEXT_READONLY] true when the source is not editable
 *   (read-only text cannot be replaced — replace-type actions are hidden then).
 */
class ProcessTextActivity : AppCompatActivity() {

    companion object {
        /** Max chars of the selected text embedded into URLs / intents. */
        private const val MAX_TEXT_LEN = 1500
    }

    private var dialogShown = false

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No UI of our own — translucent theme, the dialog is the whole UX.
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (dialogShown) return
        val raw = intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.trim()
        if (raw.isNullOrEmpty()) {
            finish()
            return
        }
        val readonly = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
        val text = raw.take(MAX_TEXT_LEN)

        val prefs = PanelPreferences(this)
        val actions = prefs.getTextActions()
            .filter { it.enabled && (!it.editableOnly || !readonly) }

        if (actions.isEmpty()) {
            Toast.makeText(this, R.string.text_action_none_available, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        dialogShown = true
        val names = actions.map { it.name }.toTypedArray()
        val descriptions = actions.map { describeAction(it) }.toTypedArray()
        val titleText = if (raw.length > 80) raw.take(80) + "…" else raw
        MaterialAlertDialogBuilder(this)
            .setTitle(titleText)
            .setAdapter(
                TwoLineListAdapter(this, names, descriptions)
            ) { _, which ->
                executeAction(actions[which], text, readonly)
            }
            .setOnCancelListener { finish() }
            .setOnDismissListener { finish() }
            .show()
    }

    private fun describeAction(action: TextAction): String {
        return when (action.type) {
            ActionType.OPEN_URL -> action.urlTemplate.ifBlank { getString(R.string.text_action_type_open_url) }
            ActionType.COPY -> getString(R.string.text_action_type_copy)
            ActionType.SHARE -> getString(R.string.text_action_type_share)
            ActionType.REPLACE_TEXT -> getString(R.string.text_action_type_replace)
            ActionType.LAUNCH_APP -> action.targetPackage.ifBlank { getString(R.string.text_action_type_launch_app) }
            ActionType.CUSTOM_INTENT -> action.customIntentAction.ifBlank { getString(R.string.text_action_type_custom_intent) }
        }
    }

    private fun executeAction(action: TextAction, text: String, readonly: Boolean) {
        try {
            when (action.type) {
                ActionType.OPEN_URL -> openUrl(buildFromTemplate(action.urlTemplate, text))
                ActionType.COPY -> copyToClipboard(text)
                ActionType.SHARE -> shareText(text)
                ActionType.REPLACE_TEXT -> replaceText(text, action, readonly)
                ActionType.LAUNCH_APP -> launchApp(action.targetPackage, text)
                ActionType.CUSTOM_INTENT -> fireCustomIntent(action, text)
            }
        } catch (e: Exception) {
            Toast.makeText(this, R.string.text_action_failed, Toast.LENGTH_SHORT).show()
        } finally {
            finish()
        }
    }

    private fun buildFromTemplate(template: String, text: String): String {
        if (template.isBlank()) return ""
        val encoded = try {
            URLEncoder.encode(text, "UTF-8")
        } catch (_: Exception) {
            text
        }
        return template.replace("{encoded}", encoded).replace("{text}", text)
    }

    private fun openUrl(url: String) {
        if (url.isBlank()) {
            Toast.makeText(this, R.string.text_action_empty_url, Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        // Launched from a translucent trampoline — a new task keeps the host app's
        // back stack untouched.
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(Intent.createChooser(intent, getString(R.string.text_action_open_with)))
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Smart Edge", text))
        Toast.makeText(this, R.string.text_action_copied, Toast.LENGTH_SHORT).show()
    }

    private fun shareText(text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.text_action_share_via)))
    }

    private fun replaceText(text: String, action: TextAction, readonly: Boolean) {
        if (readonly) {
            // Should not happen (filtered from the chooser), but never claim a
            // replacement succeeded when the platform will ignore the result.
            copyToClipboard(text)
            Toast.makeText(this, R.string.text_action_readonly_fallback, Toast.LENGTH_LONG).show()
            return
        }
        val transformed = when (action.replaceTransform) {
            ReplaceTransform.UPPERCASE -> text.uppercase()
            ReplaceTransform.LOWERCASE -> text.lowercase()
        }
        val result = Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, transformed)
        setResult(Activity.RESULT_OK, result)
        // finish() is called by the caller.
    }

    private fun launchApp(packageName: String, text: String) {
        if (packageName.isBlank()) {
            Toast.makeText(this, R.string.text_action_no_target_app, Toast.LENGTH_SHORT).show()
            return
        }
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        if (launch == null) {
            Toast.makeText(this, R.string.text_action_app_not_found, Toast.LENGTH_SHORT).show()
            return
        }
        launch.putExtra(Intent.EXTRA_PROCESS_TEXT, text)
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launch)
    }

    private fun fireCustomIntent(action: TextAction, text: String) {
        if (action.customIntentAction.isBlank()) {
            Toast.makeText(this, R.string.text_action_empty_intent, Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(action.customIntentAction).apply {
            val data = buildFromTemplate(action.customIntentData, text)
            if (data.isNotBlank()) {
                try {
                    this.data = Uri.parse(data)
                } catch (_: Exception) {
                    // Leave data unset; extras still carry the text.
                }
            }
            putExtra(Intent.EXTRA_PROCESS_TEXT, text)
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    /**
     * Minimal two-line adapter (name + description) for the action chooser.
     */
    private class TwoLineListAdapter(
        context: Context,
        private val names: Array<String>,
        private val descriptions: Array<String>
    ) : android.widget.BaseAdapter() {
        private val inflater = android.view.LayoutInflater.from(context)

        override fun getCount(): Int = names.size
        override fun getItem(position: Int): Any = names[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup?): android.view.View {
            val view = convertView
                ?: inflater.inflate(android.R.layout.simple_list_item_2, parent, false)
            view.findViewById<android.widget.TextView>(android.R.id.text1).text = names[position]
            val desc = view.findViewById<android.widget.TextView>(android.R.id.text2)
            if (descriptions[position].isBlank()) {
                desc.visibility = android.view.View.GONE
            } else {
                desc.visibility = android.view.View.VISIBLE
                desc.text = descriptions[position]
            }
            return view
        }
    }
}
