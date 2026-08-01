package com.imi.smartedge.sidebar.panel

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * A bottom sheet dialog that shows OEM-specific step-by-step guidance
 * for enabling the SidePanel accessibility service.
 */
class AccessibilityGuideDialog : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "AccessibilityGuideDialog"

        fun newInstance() = AccessibilityGuideDialog()

        // OEM detection helpers
        private fun manufacturer() = Build.MANUFACTURER.lowercase()
        private fun brand() = Build.BRAND.lowercase()

        private fun isMiui(): Boolean {
            return try {
                val clazz = Class.forName("android.os.SystemProperties")
                val getMethod = clazz.getMethod("get", String::class.java)
                val propValue = getMethod.invoke(null, "ro.miui.ui.version.name") as? String
                val isMiuiDevice = propValue != null && propValue.isNotBlank()
                isMiuiDevice
            } catch (e: Exception) {
                val m = manufacturer(); val b = brand()
                m.contains("xiaomi") || b.contains("xiaomi") ||
                        b.contains("redmi") || b.contains("poco")
            }
        }

        private fun isSamsung() = manufacturer().contains("samsung")
        private fun isOppoColorOS(): Boolean {
            val m = manufacturer(); val b = brand()
            return m.contains("oppo") || b.contains("oppo") ||
                    b.contains("realme") || b.contains("oneplus")
        }
        private fun isVivo(): Boolean {
            val m = manufacturer(); val b = brand()
            return m.contains("vivo") || b.contains("vivo")
        }
        private fun isHuawei(): Boolean {
            val m = manufacturer(); val b = brand()
            return m.contains("huawei") || b.contains("huawei") || b.contains("honor")
        }
    }

    // Data class for each step
    private data class OemGuide(
        val title: String,
        val subtitle: String,
        val icon: Int,            // drawable res (android system icon)
        val steps: List<String>
    )

    private fun detectGuide(): OemGuide {
        return when {
            isMiui() -> OemGuide(
                title = getString(R.string.oem_miui_title),
                subtitle = getString(R.string.oem_miui_subtitle),
                icon = android.R.drawable.ic_menu_manage,
                steps = listOf(
                    getString(R.string.oem_step_open_settings),
                    getString(R.string.oem_step_miui_2),
                    getString(R.string.oem_step_miui_3),
                    getString(R.string.oem_step_miui_4),
                    getString(R.string.oem_step_miui_5)
                )
            )
            isSamsung() -> OemGuide(
                title = getString(R.string.oem_samsung_title),
                subtitle = getString(R.string.oem_samsung_subtitle),
                icon = android.R.drawable.ic_menu_manage,
                steps = listOf(
                    getString(R.string.oem_step_open_settings),
                    getString(R.string.oem_step_samsung_2),
                    getString(R.string.oem_step_samsung_3),
                    getString(R.string.oem_step_miui_4),
                    getString(R.string.oem_step_samsung_5)
                )
            )
            isOppoColorOS() -> OemGuide(
                title = getString(R.string.oem_oppo_title),
                subtitle = getString(R.string.oem_oppo_subtitle),
                icon = android.R.drawable.ic_menu_manage,
                steps = listOf(
                    getString(R.string.oem_step_open_settings),
                    getString(R.string.oem_step_oppo_2),
                    getString(R.string.oem_step_oppo_3),
                    getString(R.string.oem_step_miui_4),
                    getString(R.string.oem_step_oppo_5)
                )
            )
            isVivo() -> OemGuide(
                title = getString(R.string.oem_vivo_title),
                subtitle = getString(R.string.oem_vivo_subtitle),
                icon = android.R.drawable.ic_menu_manage,
                steps = listOf(
                    getString(R.string.oem_step_open_settings),
                    getString(R.string.oem_step_vivo_2),
                    getString(R.string.oem_step_vivo_3),
                    getString(R.string.oem_step_vivo_4),
                    getString(R.string.oem_step_vivo_5)
                )
            )
            isHuawei() -> OemGuide(
                title = getString(R.string.oem_huawei_title),
                subtitle = getString(R.string.oem_huawei_subtitle),
                icon = android.R.drawable.ic_menu_manage,
                steps = listOf(
                    getString(R.string.oem_step_open_settings),
                    getString(R.string.oem_step_samsung_2),
                    getString(R.string.oem_step_huawei_3),
                    getString(R.string.oem_step_huawei_4),
                    getString(R.string.oem_step_vivo_5)
                )
            )
            else -> OemGuide(
                title = getString(R.string.oem_generic_title),
                subtitle = getString(R.string.oem_generic_subtitle),
                icon = android.R.drawable.ic_menu_manage,
                steps = listOf(
                    getString(R.string.oem_step_open_settings),
                    getString(R.string.oem_step_generic_2),
                    getString(R.string.oem_step_miui_3),
                    getString(R.string.oem_step_miui_4),
                    getString(R.string.oem_step_miui_5)
                )
            )
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // Build the entire UI programmatically for zero layout dependency
        val ctx = requireContext()
        val density = ctx.resources.displayMetrics.density

        val guide = detectGuide()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (24 * density).toInt(),
                (20 * density).toInt(),
                (24 * density).toInt(),
                (28 * density).toInt()
            )
        }

        // Drag handle
        val handle = View(ctx).apply {
            val lp = LinearLayout.LayoutParams((40 * density).toInt(), (4 * density).toInt())
            lp.gravity = android.view.Gravity.CENTER_HORIZONTAL
            lp.bottomMargin = (20 * density).toInt()
            layoutParams = lp
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 8 * density
                setColor(android.graphics.Color.parseColor("#33FFFFFF"))
            }
        }
        root.addView(handle)

        // Header row: icon + title/subtitle
        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = (20 * density).toInt()
            layoutParams = lp
        }

        val iconBg = android.widget.FrameLayout(ctx).apply {
            val size = (48 * density).toInt()
            val lp = LinearLayout.LayoutParams(size, size)
            lp.marginEnd = (16 * density).toInt()
            layoutParams = lp
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 14 * density
                setColor(android.graphics.Color.parseColor("#1A4A9EFF"))
            }
        }
        val iconView = ImageView(ctx).apply {
            setImageResource(android.R.drawable.ic_menu_manage)
            imageTintList = android.content.res.ColorStateList.valueOf(
                android.graphics.Color.parseColor("#4A9EFF")
            )
            val pad = (10 * density).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        iconBg.addView(iconView)
        headerRow.addView(iconBg)

        val titleCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvTitle = TextView(ctx).apply {
            text = getString(R.string.dialog_enable_accessibility)
            textSize = 18f
            setTextColor(android.graphics.Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val tvSubtitle = TextView(ctx).apply {
            text = getString(R.string.dialog_steps_for, guide.title, guide.subtitle)
            textSize = 12f
            setTextColor(android.graphics.Color.parseColor("#99FFFFFF"))
        }
        titleCol.addView(tvTitle)
        titleCol.addView(tvSubtitle)
        headerRow.addView(titleCol)
        root.addView(headerRow)

        // Divider
        val divider = View(ctx).apply {
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (1 * density).toInt()
            )
            lp.bottomMargin = (20 * density).toInt()
            layoutParams = lp
            setBackgroundColor(android.graphics.Color.parseColor("#1AFFFFFF"))
        }
        root.addView(divider)

        // Step list
        guide.steps.forEachIndexed { index, stepText ->
            val stepRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = (14 * density).toInt()
                layoutParams = lp
            }

            // Number bubble
            val numBubble = TextView(ctx).apply {
                text = "${index + 1}"
                textSize = 13f
                setTextColor(android.graphics.Color.parseColor("#4A9EFF"))
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = android.view.Gravity.CENTER
                val size = (28 * density).toInt()
                val lp = LinearLayout.LayoutParams(size, size)
                lp.marginEnd = (14 * density).toInt()
                layoutParams = lp
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(android.graphics.Color.parseColor("#1A4A9EFF"))
                }
            }

            val tvStep = TextView(ctx).apply {
                text = stepText
                textSize = 14f
                setTextColor(android.graphics.Color.parseColor("#E6FFFFFF"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            stepRow.addView(numBubble)
            stepRow.addView(tvStep)
            root.addView(stepRow)
        }

        // Spacer
        root.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (8 * density).toInt()
            )
        })

        // "Open Settings" button
        val btnOpen = Button(ctx).apply {
            text = getString(R.string.dialog_open_accessibility_settings)
            setTextColor(android.graphics.Color.WHITE)
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 14 * density
                setColor(android.graphics.Color.parseColor("#4A9EFF"))
            }
            setPadding(0, (14 * density).toInt(), 0, (14 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                context.openAccessibilitySettings()
                dismiss()
            }
        }
        root.addView(btnOpen)

        // "Use System Automation instead" secondary button
        val btnAutomation = Button(ctx).apply {
            text = getString(R.string.dialog_use_system_automation_instead)
            setTextColor(android.graphics.Color.parseColor("#4A9EFF"))
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 14 * density
                setStroke((1 * density).toInt(), android.graphics.Color.parseColor("#4A9EFF"))
                setColor(android.graphics.Color.TRANSPARENT)
            }
            setPadding(0, (10 * density).toInt(), 0, (10 * density).toInt())
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = (12 * density).toInt()
            layoutParams = lp
            setOnClickListener {
                SecureSettingsDialog.show(ctx) {
                    // Update preference if automation becomes possible
                    if (AutomationManager.isAutomationPossible()) {
                        PanelPreferences(ctx).useAutomationForGestures = true
                    }
                }
                dismiss()
            }
        }
        root.addView(btnAutomation)

        val tvNote = TextView(ctx).apply {
            text = getString(R.string.dialog_automation_vs_a11y_msg)
            textSize = 11f
            setTextColor(android.graphics.Color.parseColor("#66FFFFFF"))
            gravity = android.view.Gravity.CENTER
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = (12 * density).toInt()
            layoutParams = lp
        }
        root.addView(tvNote)

        // Style the bottom sheet itself with dark background
        dialog?.setOnShowListener {
            val bottomSheet = (dialog as? com.google.android.material.bottomsheet.BottomSheetDialog)
                ?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            // Audit U10: half-expand prevents full-screen take-over on tall devices.
            // isFitToContents=false is required for STATE_HALF_EXPANDED to register visually on Material.
            (bottomSheet as? android.widget.FrameLayout)?.let { sheet ->
                val behavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(sheet)
                behavior.isFitToContents = false
                behavior.halfExpandedRatio = 0.6f
                // Audit U2: short screens (<700dp usable height) clip the bottom
                // "Use System Automation instead" button on STATE_HALF_EXPANDED.
                // Force STATE_EXPANDED + skipCollapsed on those devices so both
                // buttons stay reachable above the nav bar inset.
                val heightDp = ctx.resources.displayMetrics.heightPixels / ctx.resources.displayMetrics.density
                if (heightDp < 700f) {
                    behavior.skipCollapsed = true
                    behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
                } else {
                    behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_HALF_EXPANDED
                }
            }
            bottomSheet?.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadii = floatArrayOf(
                    24 * density, 24 * density,
                    24 * density, 24 * density,
                    0f, 0f, 0f, 0f
                )
                setColor(android.graphics.Color.parseColor("#1F2732"))
            }
        }

        return root
    }
}
