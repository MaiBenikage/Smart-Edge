package com.imi.smartedge.sidebar.panel

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.imi.smartedge.sidebar.panel.databinding.ActivitySettingsTextActionsBinding

/**
 * Settings page where the user manually configures the shortcut actions shown
 * after tapping the single "Smart Edge…" entry in Android's text-selection menu.
 *
 * Because Android only allows static ACTION_PROCESS_TEXT registrations, the
 * system menu can only ever show ONE Smart Edge entry — this page manages the
 * second-level list ([TextAction]) behind it: add / rename / enable-disable /
 * reorder / delete, plus per-type parameters (URL templates, target packages,
 * custom intents, replace transforms).
 */
class TextActionsSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsTextActionsBinding
    private lateinit var panelPrefs: PanelPreferences
    private val actions = mutableListOf<TextAction>()
    private lateinit var adapter: TextActionAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsTextActionsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        panelPrefs = PanelPreferences(this)

        adapter = TextActionAdapter(
            actions,
            onToggle = { action, enabled ->
                panelPrefs.updateTextAction(action.copy(enabled = enabled))
                refreshList()
            },
            onMove = { from, to ->
                panelPrefs.moveTextAction(from, to)
                refreshList()
            },
            onEdit = { action -> showEditorDialog(action) },
            onDelete = { action -> confirmDelete(action) }
        )
        binding.rvTextActions.layoutManager = LinearLayoutManager(this)
        binding.rvTextActions.adapter = adapter

        binding.btnAddTextAction.setOnClickListener { showEditorDialog(null) }
        binding.btnResetTextActions.setOnClickListener { confirmReset() }

        refreshList()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun refreshList() {
        actions.clear()
        actions.addAll(panelPrefs.getTextActions())
        adapter.notifyDataSetChanged()
        binding.tvEmptyTextActions.visibility = if (actions.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun confirmDelete(action: TextAction) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.text_actions_delete_title)
            .setMessage(getString(R.string.text_actions_delete_message, action.name))
            .setPositiveButton(R.string.text_actions_delete) { _, _ ->
                panelPrefs.removeTextAction(action.id)
                refreshList()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmReset() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.text_actions_reset_title)
            .setMessage(R.string.text_actions_reset_message)
            .setPositiveButton(R.string.text_actions_reset) { _, _ ->
                panelPrefs.resetTextActions()
                refreshList()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------------------------------------------------------------- editor

    private fun showEditorDialog(existing: TextAction?) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_text_action_edit, null)
        val etName: EditText = view.findViewById(R.id.etActionName)
        val spinnerType: Spinner = view.findViewById(R.id.spinnerActionType)
        val layoutUrl: View = view.findViewById(R.id.layoutUrlTemplate)
        val etUrl: EditText = view.findViewById(R.id.etUrlTemplate)
        val layoutTransform: View = view.findViewById(R.id.layoutReplaceTransform)
        val spinnerTransform: Spinner = view.findViewById(R.id.spinnerReplaceTransform)
        val layoutPackage: View = view.findViewById(R.id.layoutTargetPackage)
        val etPackage: EditText = view.findViewById(R.id.etTargetPackage)
        val layoutIntent: View = view.findViewById(R.id.layoutCustomIntent)
        val etIntentAction: EditText = view.findViewById(R.id.etIntentAction)
        val etIntentData: EditText = view.findViewById(R.id.etIntentData)
        val switchEditableOnly: MaterialSwitch = view.findViewById(R.id.switchEditableOnly)
        val switchEnabled: MaterialSwitch = view.findViewById(R.id.switchActionEnabled)

        val types = ActionType.values()
        val typeLabels = types.map { typeLabel(it) }
        spinnerType.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, typeLabels)

        val transforms = ReplaceTransform.values()
        val transformLabels = transforms.map { transformLabel(it) }
        spinnerTransform.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, transformLabels)

        fun applyVisibility(type: ActionType) {
            layoutUrl.visibility = if (type == ActionType.OPEN_URL) View.VISIBLE else View.GONE
            layoutTransform.visibility = if (type == ActionType.REPLACE_TEXT) View.VISIBLE else View.GONE
            layoutPackage.visibility = if (type == ActionType.LAUNCH_APP) View.VISIBLE else View.GONE
            layoutIntent.visibility = if (type == ActionType.CUSTOM_INTENT) View.VISIBLE else View.GONE
        }

        spinnerType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                applyVisibility(types[position])
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        existing?.let {
            etName.setText(it.name)
            spinnerType.setSelection(types.indexOf(it.type).coerceAtLeast(0))
            etUrl.setText(it.urlTemplate)
            spinnerTransform.setSelection(transforms.indexOf(it.replaceTransform).coerceAtLeast(0))
            etPackage.setText(it.targetPackage)
            etIntentAction.setText(it.customIntentAction)
            etIntentData.setText(it.customIntentData)
            switchEditableOnly.isChecked = it.editableOnly
            switchEnabled.isChecked = it.enabled
            applyVisibility(it.type)
        } ?: applyVisibility(types[0])

        MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) R.string.text_actions_add_title else R.string.text_actions_edit_title)
            .setView(view)
            .setPositiveButton(R.string.text_actions_save) { _, _ ->
                val name = etName.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, R.string.text_actions_name_required, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val type = types[spinnerType.selectedItemPosition]
                val urlTemplate = etUrl.text.toString().trim()
                val targetPackage = etPackage.text.toString().trim()
                val intentAction = etIntentAction.text.toString().trim()
                when (type) {
                    ActionType.OPEN_URL -> if (urlTemplate.isEmpty()) {
                        Toast.makeText(this, R.string.text_actions_url_required, Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    ActionType.LAUNCH_APP -> if (targetPackage.isEmpty()) {
                        Toast.makeText(this, R.string.text_actions_package_required, Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    ActionType.CUSTOM_INTENT -> if (intentAction.isEmpty()) {
                        Toast.makeText(this, R.string.text_actions_intent_action_required, Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    else -> {}
                }
                val action = TextAction(
                    id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                    name = name,
                    type = type,
                    enabled = switchEnabled.isChecked,
                    urlTemplate = urlTemplate,
                    targetPackage = targetPackage,
                    customIntentAction = intentAction,
                    customIntentData = etIntentData.text.toString().trim(),
                    replaceTransform = transforms[spinnerTransform.selectedItemPosition],
                    editableOnly = switchEditableOnly.isChecked
                )
                if (existing == null) panelPrefs.addTextAction(action)
                else panelPrefs.updateTextAction(action)
                refreshList()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun typeLabel(type: ActionType): String = when (type) {
        ActionType.OPEN_URL -> getString(R.string.text_action_type_open_url)
        ActionType.COPY -> getString(R.string.text_action_type_copy)
        ActionType.SHARE -> getString(R.string.text_action_type_share)
        ActionType.REPLACE_TEXT -> getString(R.string.text_action_type_replace)
        ActionType.LAUNCH_APP -> getString(R.string.text_action_type_launch_app)
        ActionType.CUSTOM_INTENT -> getString(R.string.text_action_type_custom_intent)
    }

    private fun transformLabel(transform: ReplaceTransform): String = when (transform) {
        ReplaceTransform.UPPERCASE -> getString(R.string.text_actions_transform_uppercase)
        ReplaceTransform.LOWERCASE -> getString(R.string.text_actions_transform_lowercase)
    }

    // ---------------------------------------------------------------- adapter

    private inner class TextActionAdapter(
        private val items: List<TextAction>,
        private val onToggle: (TextAction, Boolean) -> Unit,
        private val onMove: (Int, Int) -> Unit,
        private val onEdit: (TextAction) -> Unit,
        private val onDelete: (TextAction) -> Unit
    ) : RecyclerView.Adapter<TextActionAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tvActionName)
            val tvType: TextView = view.findViewById(R.id.tvActionType)
            val switchEnabled: MaterialSwitch = view.findViewById(R.id.switchActionEnabled)
            val btnUp: ImageButton = view.findViewById(R.id.btnMoveUp)
            val btnDown: ImageButton = view.findViewById(R.id.btnMoveDown)
            val btnEdit: ImageButton = view.findViewById(R.id.btnEditAction)
            val btnDelete: ImageButton = view.findViewById(R.id.btnDeleteAction)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_text_action, parent, false)
            return VH(view)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val action = items[position]
            holder.tvName.text = action.name
            holder.tvType.text = typeSummary(action)

            holder.switchEnabled.setOnCheckedChangeListener(null)
            holder.switchEnabled.isChecked = action.enabled
            holder.switchEnabled.setOnCheckedChangeListener { _, isChecked ->
                onToggle(action, isChecked)
            }

            holder.btnUp.isEnabled = position > 0
            holder.btnUp.alpha = if (position > 0) 1f else 0.3f
            holder.btnDown.isEnabled = position < items.size - 1
            holder.btnDown.alpha = if (position < items.size - 1) 1f else 0.3f
            holder.btnUp.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos > 0) onMove(pos, pos - 1)
            }
            holder.btnDown.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos in 0 until items.size - 1) onMove(pos, pos + 1)
            }
            holder.btnEdit.setOnClickListener { onEdit(action) }
            holder.btnDelete.setOnClickListener { onDelete(action) }
        }

        private fun typeSummary(action: TextAction): String {
            val base = typeLabel(action.type)
            val detail = when (action.type) {
                ActionType.OPEN_URL -> action.urlTemplate
                ActionType.LAUNCH_APP -> action.targetPackage
                ActionType.CUSTOM_INTENT -> action.customIntentAction
                ActionType.REPLACE_TEXT -> transformLabel(action.replaceTransform) +
                    if (action.editableOnly) " · ${getString(R.string.text_actions_editable_only_badge)}" else ""
                else -> if (action.editableOnly) getString(R.string.text_actions_editable_only_badge) else ""
            }
            return if (detail.isBlank()) base else "$base · $detail"
        }
    }
}
