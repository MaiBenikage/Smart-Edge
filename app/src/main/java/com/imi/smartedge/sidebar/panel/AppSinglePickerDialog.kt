package com.imi.smartedge.sidebar.panel

import android.app.Dialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Single-app picker (favourite app for a gesture slot) as a
 * DialogFragment. The old plain AlertDialog was lost on rotation —
 * mid-pick the dialog vanished and the favourite was never set. A
 * DialogFragment is re-created by the FragmentManager, and the pick is
 * delivered via FragmentResult, so rotation no longer drops it.
 */
class AppSinglePickerDialog : DialogFragment() {

    companion object {
        const val TAG = "AppSinglePickerDialog"
        const val REQUEST_KEY = "AppSinglePickerDialogResult"
        const val EXTRA_SLOT_KEY = "slot_key"
        const val EXTRA_PACKAGE = "package"
        private const val ARG_TITLE = "title"
        private const val ARG_SLOT_KEY = "slot_key"

        fun show(activity: FragmentActivity, title: String, slotKey: String) {
            AppSinglePickerDialog().apply {
                arguments = bundleOf(ARG_TITLE to title, ARG_SLOT_KEY to slotKey)
            }.show(activity.supportFragmentManager, TAG)
        }

        /** Register once in onCreate: a recreated activity still gets the pick. */
        fun registerResultListener(activity: FragmentActivity, onSelect: (slotKey: String, pkg: String) -> Unit) {
            activity.supportFragmentManager.setFragmentResultListener(REQUEST_KEY, activity) { _, bundle ->
                val slotKey = bundle.getString(EXTRA_SLOT_KEY) ?: return@setFragmentResultListener
                val pkg = bundle.getString(EXTRA_PACKAGE) ?: return@setFragmentResultListener
                onSelect(slotKey, pkg)
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx = requireContext()
        val density = ctx.resources.displayMetrics.density
        val title = requireArguments().getString(ARG_TITLE).orEmpty()
        val slotKey = requireArguments().getString(ARG_SLOT_KEY).orEmpty()

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * density).toInt(), (10 * density).toInt(), (20 * density).toInt(), 0)
        }
        val searchBar = TextInputEditText(ctx).apply {
            hint = getString(R.string.hint_search_apps)
            setSingleLine()
        }
        container.addView(TextInputLayout(ctx).apply {
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            setBoxCornerRadii(12 * density, 12 * density, 12 * density, 12 * density)
            addView(searchBar)
        })
        val listView = ListView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (400 * density).toInt()
            )
        }
        container.addView(listView)

        var sortedApps: List<AppInfo> = emptyList()
        var displayApps: List<AppInfo> = emptyList()
        fun updateList(query: String) {
            displayApps = if (query.isEmpty()) sortedApps
            else sortedApps.filter { it.appName.contains(query, ignoreCase = true) }
            listView.adapter = ArrayAdapter(ctx, android.R.layout.simple_list_item_1, displayApps.map { it.appName }.toTypedArray())
        }
        searchBar.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { updateList(s.toString()) }
            override fun afterTextChanged(s: Editable?) {}
        })
        listView.setOnItemClickListener { _, _, position, _ ->
            parentFragmentManager.setFragmentResult(
                REQUEST_KEY,
                bundleOf(EXTRA_SLOT_KEY to slotKey, EXTRA_PACKAGE to displayApps[position].packageName)
            )
            dismiss()
        }

        // Load off the main thread; the list shows "Loading…" until it
        // arrives. The old code blocked on a separate modal loading
        // dialog, which also died on rotation.
        listView.adapter = ArrayAdapter(ctx, android.R.layout.simple_list_item_1, arrayOf(getString(R.string.loading_apps)))
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) {
                runCatching { AppRepository.shared(ctx).getAllApps(preloadIcons = false) }.getOrDefault(emptyList())
            }
            if (!isAdded) return@launch
            sortedApps = apps.sortedBy { it.appName.lowercase(java.util.Locale.ROOT) }
            updateList(searchBar.text.toString())
        }

        return MaterialAlertDialogBuilder(ctx)
            .setTitle(title)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }
}
