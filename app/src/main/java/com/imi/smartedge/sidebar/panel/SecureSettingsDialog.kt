// Shizuku 11/12's `newProcess(Array<String>, Array<String>?, String?)` overload
// is marked deprecated in Shizuku 13 in favor of a signature that refines the
// Kotlin nullability annotations on the platform types. The migration is gated
// on a major Shizuku version bump; for now suppress the file-wide DEPRECATION
// warnings at the three call sites (shizukuListener, onGrant, onRevoke).
@file:Suppress("DEPRECATION")

package com.imi.smartedge.sidebar.panel

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SecureSettingsDialog : BottomSheetDialogFragment() {

    private lateinit var tvStatus: TextView
    private lateinit var tvAutoStatus: TextView

    // Theme colors (resolved from the app theme, not hardcoded dark
    // values): the sheet must follow Light/Dark/System like the rest of
    // the app. Resolved lazily — requireContext() isn't available until
    // the fragment is attached.
    private val surfaceColor: Int
        get() = requireContext().resolveThemeColor(com.google.android.material.R.attr.colorSurface)
    private val onSurfaceColor: Int
        get() = requireContext().resolveThemeColor(com.google.android.material.R.attr.colorOnSurface)
    private val onSurfaceVariantColor: Int
        get() = requireContext().resolveThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
    private val outlineColor: Int
        get() = requireContext().resolveThemeColor(com.google.android.material.R.attr.colorOutline)
    private val surfaceContainerColor: Int
        get() = requireContext().resolveThemeColor(com.google.android.material.R.attr.colorSurfaceContainer)

    /** Last automation state seen by refreshUI: the grant result + dismiss fire only on a false -> true transition. */
    private var lastAutomationPossible: Boolean? = null

    private val shizukuListener = rikka.shizuku.Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == 1001 && grantResult == PackageManager.PERMISSION_GRANTED) {
            // The Shizuku permission grant runs on the main thread — the
            // blocking `pm grant` must not run here (it froze the sheet).
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val packageName = requireContext().packageName
                    val sh = "pm grant $packageName android.permission.WRITE_SECURE_SETTINGS"
                    val process = rikka.shizuku.Shizuku.newProcess(arrayOf("sh", "-c", sh), null, null)
                    process.waitFor()
                } catch (e: Exception) {}
                withContext(Dispatchers.Main) { refreshUI() }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            rikka.shizuku.Shizuku.addRequestPermissionResultListener(shizukuListener)
        } catch (e: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            rikka.shizuku.Shizuku.removeRequestPermissionResultListener(shizukuListener)
        } catch (e: Exception) {}
    }

    companion object {
        const val TAG = "SecureSettingsDialog"

        /**
         * Fragment-result key for the automation-granted event. Reported
         * via setFragmentResult (not a callback field): a plain field is
         * lost when the FragmentManager recreates the dialog on rotation,
         * so granting after a rotation never flipped the caller's switch.
         * Hosts register in onCreate via [registerResultListener] so a
         * recreated host picks up a pending result.
         */
        const val REQUEST_KEY = "SecureSettingsDialogResult"

        fun registerResultListener(activity: androidx.fragment.app.FragmentActivity, onPermissionGranted: () -> Unit) {
            activity.supportFragmentManager.setFragmentResultListener(REQUEST_KEY, activity) { _, _ ->
                onPermissionGranted()
            }
        }

        fun registerResultListener(fragment: androidx.fragment.app.Fragment, onPermissionGranted: () -> Unit) {
            fragment.parentFragmentManager.setFragmentResultListener(REQUEST_KEY, fragment) { _, _ ->
                onPermissionGranted()
            }
        }

        fun show(context: Context) {
            val activity = context as? androidx.fragment.app.FragmentActivity
                ?: throw IllegalArgumentException("SecureSettingsDialog.show requires a FragmentActivity context")
            SecureSettingsDialog().show(activity.supportFragmentManager, TAG)
        }
    }

    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val density = ctx.resources.displayMetrics.density
        val packageName = ctx.packageName
        val adbCommand = "adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS"

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (24 * density).toInt(),
                (12 * density).toInt(),
                (24 * density).toInt(),
                (28 * density).toInt()
            )
        }

        // Drag handle
        val handle = View(ctx).apply {
            val lp = LinearLayout.LayoutParams((40 * density).toInt(), (4 * density).toInt())
            lp.gravity = Gravity.CENTER_HORIZONTAL
            lp.bottomMargin = (20 * density).toInt()
            layoutParams = lp
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8 * density
                setColor(outlineColor)
            }
        }
        root.addView(handle)

        // Header
        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (24 * density).toInt() }
        }

        val iconBg = FrameLayout(ctx).apply {
            val size = (48 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = (16 * density).toInt() }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 14 * density
                setColor(Color.parseColor("#1A4A9EFF"))
            }
        }
        val iconView = ImageView(ctx).apply {
            setImageResource(android.R.drawable.ic_menu_manage)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#4A9EFF"))
            val pad = (12 * density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        iconBg.addView(iconView)
        headerRow.addView(iconBg)

        val titleCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvTitle = TextView(ctx).apply {
            text = getString(R.string.dialog_native_gesture_engine)
            textSize = 20f
            setTextColor(onSurfaceColor)
            typeface = Typeface.DEFAULT_BOLD
        }

        val isGranted = ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
        tvStatus = TextView(ctx).apply {
            text = if (isGranted) getString(R.string.permission_granted_status) else getString(R.string.permission_missing_status)
            textSize = 12f
            setTextColor(if (isGranted) Color.parseColor("#00FF00") else onSurfaceVariantColor)
            if (isGranted) typeface = Typeface.DEFAULT_BOLD
        }
        titleCol.addView(tvTitle)
        titleCol.addView(tvStatus)

        // Add Automation Status
        tvAutoStatus = TextView(ctx).apply {
            val autoStatus = when {
                AutomationManager.isRootAvailable() -> "Engine: Root Active"
                AutomationManager.isShizukuAvailable() -> "Engine: Shizuku Active"
                else -> "Engine: Service Not Running"
            }
            text = autoStatus
            textSize = 11f
            val isAutoActive = AutomationManager.isAutomationPossible()
            setTextColor(if (isAutoActive) Color.parseColor("#00FF00") else onSurfaceVariantColor)
            if (isAutoActive) typeface = Typeface.DEFAULT_BOLD
            setPadding(0, (2 * density).toInt(), 0, 0)
        }
        titleCol.addView(tvAutoStatus)
        headerRow.addView(titleCol)
        root.addView(headerRow)

        val tvDesc = TextView(ctx).apply {
            text = getString(R.string.dialog_native_gesture_engine_desc)
            textSize = 14f
            setTextColor(onSurfaceVariantColor)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (24 * density).toInt() }
        }
        root.addView(tvDesc)

        // Divider
        root.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (1 * density).toInt()).apply {
                bottomMargin = (24 * density).toInt()
            }
            setBackgroundColor(surfaceContainerColor)
        })

        // Audit S2: explanatory banner for WRITE_SECURE_SETTINGS. ADB-routed,
        // not app-level. Most users don't realise what this permission can touch.
        // Smart Edge uses it for rotation + system flags only — not user data.
        val tvExplainer = TextView(ctx).apply {
            text = getString(R.string.write_secure_settings_explainer)
            textSize = 11f
            setTextColor(onSurfaceVariantColor)
            setLineSpacing(0f, 1.15f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (16 * density).toInt()
            }
        }
        root.addView(tvExplainer)

        // Shizuku Row
        root.addView(createAutomationRow(ctx, density, "Shizuku", "Wireless ADB automation",
            onGrant = {
                // Blocking `pm grant` runs on Dispatchers.IO: this is a
                // button callback (main thread) and waitFor() froze the sheet.
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        if (rikka.shizuku.Shizuku.pingBinder()) {
                            if (rikka.shizuku.Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                                val sh = "pm grant $packageName android.permission.WRITE_SECURE_SETTINGS"
                                val process = rikka.shizuku.Shizuku.newProcess(arrayOf("sh", "-c", sh), null, null)
                                process.waitFor()
                            } else {
                                withContext(Dispatchers.Main) {
                                    rikka.shizuku.Shizuku.requestPermission(1001)
                                }
                                return@launch
                            }
                        }
                    } catch (e: Exception) {}
                    withContext(Dispatchers.Main) { refreshUI() }
                }
            },
            onRevoke = {
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        if (rikka.shizuku.Shizuku.pingBinder()) {
                            if (rikka.shizuku.Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                                val sh = "pm revoke $packageName android.permission.WRITE_SECURE_SETTINGS"
                                val process = rikka.shizuku.Shizuku.newProcess(arrayOf("sh", "-c", sh), null, null)
                                process.waitFor()
                            }
                        }
                    } catch (e: Exception) {}
                    withContext(Dispatchers.Main) { refreshUI() }
                }
            }
        ))

        // Root Row
        root.addView(createAutomationRow(ctx, density, "Root Access", "Direct system grant",
            onGrant = {
                AutomationManager.requestRootPermission { success ->
                    if (success) {
                        // Blocking `pm grant` runs on Dispatchers.IO: the
                        // requestRootPermission callback is on the main thread.
                        lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "pm grant $packageName android.permission.WRITE_SECURE_SETTINGS"))
                                p.waitFor()
                            } catch (e: Exception) {}
                            withContext(Dispatchers.Main) { refreshUI() }
                        }
                    }
                }
            },
            onRevoke = {
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "pm revoke $packageName android.permission.WRITE_SECURE_SETTINGS"))
                        p.waitFor()
                    } catch (e: Exception) {}
                    withContext(Dispatchers.Main) { refreshUI() }
                }
            }
        ))

        // ADB Section
        val adbHeaderRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (20 * density).toInt(); bottomMargin = (8 * density).toInt() }
        }

        val adbTitle = TextView(ctx).apply {
            text = getString(R.string.dialog_manual_setup_adb_upper)
            textSize = 11f
            setTextColor(onSurfaceVariantColor)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.1f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        adbHeaderRow.addView(adbTitle)

        val btnCopy = TextView(ctx).apply {
            text = getString(R.string.xml_copy)
            setTextColor(Color.parseColor("#4A9EFF"))
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 4 * density
                setColor(Color.parseColor("#1A4A9EFF"))
            }
            setOnClickListener {
                val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("ADB Command", adbCommand))
                root.showModernToast(getString(R.string.toast_command_copied))
            }
        }
        adbHeaderRow.addView(btnCopy)
        root.addView(adbHeaderRow)

        val adbBox = TextView(ctx).apply {
            text = adbCommand
            textSize = 10f
            setTextColor(onSurfaceColor)
            typeface = Typeface.MONOSPACE
            setPadding((16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12 * density
                setColor(surfaceContainerColor)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("ADB Command", adbCommand))
                root.showModernToast(getString(R.string.toast_command_copied))
            }
        }
        root.addView(adbBox)

        // Close Button
        val btnClose = Button(ctx).apply {
            text = ctx.getString(android.R.string.ok)
            setTextColor(onSurfaceColor)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 14 * density
                setColor(Color.parseColor("#4A9EFF"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (54 * density).toInt()
            ).apply { topMargin = (28 * density).toInt() }
            setOnClickListener { dismiss() }
        }
        root.addView(btnClose)

        return root
    }

    private fun createAutomationRow(
        ctx: Context,
        density: Float,
        title: String,
        subtitle: String,
        onGrant: () -> Unit,
        onRevoke: () -> Unit
    ): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (20 * density).toInt() }
        }

        val textCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(TextView(ctx).apply {
            text = title
            textSize = 16f
            setTextColor(onSurfaceColor)
            typeface = Typeface.DEFAULT_BOLD
        })
        textCol.addView(TextView(ctx).apply {
            text = subtitle
            textSize = 12f
            setTextColor(onSurfaceVariantColor)
        })
        row.addView(textCol)

        val btnRevoke = Button(ctx).apply {
            text = getString(R.string.xml_revoke)
            setTextColor(Color.parseColor("#FF5252"))
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 19 * density
                setStroke((1 * density).toInt(), Color.parseColor("#FF5252"))
                setColor(Color.TRANSPARENT)
            }
            layoutParams = LinearLayout.LayoutParams((80 * density).toInt(), (38 * density).toInt()).apply {
                marginEnd = (12 * density).toInt()
            }
            setOnClickListener { onRevoke() }
        }
        row.addView(btnRevoke)

        val btnGrant = Button(ctx).apply {
            text = getString(R.string.xml_grant)
            setTextColor(Color.parseColor("#4A9EFF"))
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 19 * density
                setStroke((1 * density).toInt(), Color.parseColor("#4A9EFF"))
                setColor(Color.TRANSPARENT)
            }
            layoutParams = LinearLayout.LayoutParams((80 * density).toInt(), (38 * density).toInt())
            setOnClickListener { onGrant() }
        }
        row.addView(btnGrant)
        return row
    }

    private fun refreshUI() {
        if (!::tvStatus.isInitialized || !::tvAutoStatus.isInitialized) return

        val ctx = requireContext()
        val isGranted = ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
        tvStatus.text = if (isGranted) getString(R.string.permission_granted_status) else getString(R.string.permission_missing_status)
        tvStatus.setTextColor(if (isGranted) Color.parseColor("#00FF00") else onSurfaceVariantColor)
        tvStatus.typeface = if (isGranted) Typeface.DEFAULT_BOLD else Typeface.DEFAULT

        val isAutomationPossible = AutomationManager.isAutomationPossible()
        tvAutoStatus.text = if (isAutomationPossible) {
            when {
                AutomationManager.isRootAvailable() -> "Engine: Root Active"
                AutomationManager.isShizukuAvailable() -> "Engine: Shizuku Active"
                else -> "Engine: Active"
            }
        } else {
            "Engine: Service Not Running"
        }
        tvAutoStatus.setTextColor(if (isAutomationPossible) Color.parseColor("#00FF00") else onSurfaceVariantColor)
        tvAutoStatus.typeface = if (isAutomationPossible) Typeface.DEFAULT_BOLD else Typeface.DEFAULT

        if (isAutomationPossible) {
            // Report the grant + dismiss, but only on a false -> true
            // transition: the first refreshUI call just records the
            // baseline, so opening the sheet when automation is already
            // possible neither fires a spurious result nor dismisses it.
            // Previously refreshUI() fired the callback but never
            // dismissed, so after a successful grant the user had to
            // notice the status change and tap Close themselves.
            if (lastAutomationPossible == false) {
                setFragmentResult(REQUEST_KEY, android.os.Bundle.EMPTY)
                dismiss()
            }
        }
        lastAutomationPossible = isAutomationPossible

        // Notify background service to re-evaluate engine status
        applyOnly()
    }

    private fun applyOnly() {
        try {
            val intent = Intent(requireContext(), FloatingPanelService::class.java).apply {
                action = FloatingPanelService.ACTION_REFRESH
            }
            requireContext().startService(intent)
        } catch (e: Exception) {}
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): android.app.Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                val density = resources.displayMetrics.density
                cornerRadii = floatArrayOf(
                    24 * density, 24 * density,
                    24 * density, 24 * density,
                    0f, 0f, 0f, 0f
                )
                setColor(surfaceColor)
            }
        }
        return dialog
    }
}