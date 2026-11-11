package com.imi.smartedge.sidebar.panel

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.card.MaterialCardView
import android.graphics.Color
import android.content.res.ColorStateList
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import java.lang.reflect.Method

/**
 * Shared UI timing constants (ms). Used across multiple views so that
 * identical animations stay in sync (indicator show/fade, scale-reset).
 */
const val INDICATOR_SHOW_DURATION_MS = 1500L
const val INDICATOR_FADE_DURATION_MS = 300L
const val SCALE_RESET_DURATION_MS = 150L
const val LONG_PRESS_DRAG_MS = 600L

/**
 * Extension to show a modern, very compact "Toast" using Snackbar.
 * Uses Material You dynamic colors and a sleeker mini-pill shape.
 */
fun View.showModernToast(message: String, duration: Int = Snackbar.LENGTH_SHORT) {
    val snackbar = Snackbar.make(this, "", duration)
    // NB: do NOT cast to Snackbar.SnackbarLayout — it is @RestrictTo(LIBRARY_GROUP)
    // and lint fails the release build on it (RestrictedApi). SnackbarLayout is a
    // FrameLayout subclass and everything below only uses plain View/ViewGroup
    // calls, so a FrameLayout cast is behaviour-identical.
    val snackbarView = snackbar.view as FrameLayout

    snackbarView.setBackgroundColor(Color.TRANSPARENT)
    snackbarView.removeAllViews()
    snackbarView.setPadding(0, 0, 0, 0)

    val density = context.resources.displayMetrics.density
    val typedValue = android.util.TypedValue()

    // Resolve Material You colors
    context.theme.resolveAttribute(com.google.android.material.R.attr.colorSurfaceContainerHighest, typedValue, true)
    val bgColor = typedValue.data

    context.theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurface, typedValue, true)
    val textColor = typedValue.data

    // Ultra-Compact Card
    val card = MaterialCardView(context).apply {
        radius = 16f * density // Modern M3 rounded corner
        cardElevation = 2f * density
        useCompatPadding = false
        strokeWidth = (1f * density).toInt()
        strokeColor = (textColor and 0x00FFFFFF) or (0x1A shl 24) // 10% opacity border
        setCardBackgroundColor(ColorStateList.valueOf(bgColor))

        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
        }
    }

    // Small, readable text
    val textView = TextView(context).apply {
        text = message
        setTextColor(textColor)
        textSize = 13f
        setPadding((16 * density).toInt(), (6 * density).toInt(), (16 * density).toInt(), (6 * density).toInt())
        gravity = Gravity.CENTER
        maxWidth = (220 * density).toInt() // Very compact max width
    }

    card.addView(textView)
    snackbarView.addView(card)

    val params = snackbarView.layoutParams
    params.width = ViewGroup.LayoutParams.WRAP_CONTENT

    val marginPx = (90 * density).toInt()
    if (params is FrameLayout.LayoutParams) {
        params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        params.setMargins(0, 0, 0, marginPx)
    } else if (params is androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams) {
        params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        params.setMargins(0, 0, 0, marginPx)
    }
    snackbarView.layoutParams = params
    snackbar.show()
    }

/**
 * Highly visible modern highlight animation for search results.
 * Uses a prominent rounded foreground pill that fades out, plus a gentle bounce.
 */
fun View.highlightView() {
    val typedValue = android.util.TypedValue()
    // Use colorPrimary for maximum contrast and brand consistency
    context.theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, typedValue, true)
    val highlightColor = typedValue.data
    val bounceDurationMs = 200L
    val settleDurationMs = 300L

    // 1. Attention-grabbing Bounce
    this.animate()
        .scaleX(1.05f)
        .scaleY(1.05f)
        .setDuration(bounceDurationMs)
        .setInterpolator(android.view.animation.OvershootInterpolator())
        .withEndAction {
            this.animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(settleDurationMs)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }
        .start()

    // 2. High-visibility rounded flash overlay
    val flashDrawable = android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
        cornerRadius = 12f * resources.displayMetrics.density // Modern 12dp rounded corners
        setColor(highlightColor)
    }

    // minSdk is 26 (M), so the pre-M background-layer fallback is dead
    // code — the foreground path below always runs.
    val originalForeground = this.foreground
    this.foreground = flashDrawable

    android.animation.ValueAnimator.ofInt(120, 0).apply { // ~45% opacity
        duration = 1800
        startDelay = 200 // Hold peak color for a moment
        interpolator = android.view.animation.DecelerateInterpolator()
        addUpdateListener { animator ->
            flashDrawable.alpha = (animator.animatedValue as? Int) ?: 0
        }
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                this@highlightView.foreground = originalForeground
            }
        })
        start()
    }
}

    /**
    * Opens the accessibility settings. On Android 12+, it deep-links directly to
 * this app's specific service toggle. On older versions, it opens the general
 * list and attempts to highlight this app if the OEM supports it.
 */
fun Context.openAccessibilitySettings() {
    val componentName = android.content.ComponentName(this, PanelAccessibilityService::class.java)
    val flattenedName = componentName.flattenToString()

    // We'll try the most specific action first (Android 12+)
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").apply {
            putExtra(Intent.EXTRA_COMPONENT_NAME, flattenedName)
            // System highlight/flash tricks for modern Android
            putExtra(":settings:fragment_args_key", flattenedName)
            putExtra(":settings:show_fragment_args", Bundle().apply {
                putString(":settings:fragment_args_key", flattenedName)
            })
            // Force highlighting the specific preference
            putExtra("highlight_key", flattenedName)
        }
    } else {
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            putExtra(":settings:fragment_args_key", flattenedName)
            putExtra(":settings:show_fragment_args", Bundle().apply {
                putString(":settings:fragment_args_key", flattenedName)
            })
            putExtra("EXTRA_FRAGMENT_ARG_KEY", flattenedName)
            putExtra("EXTRA_SHOW_FRAGMENT_ARGUMENTS", Bundle().apply {
                putString(":settings:fragment_args_key", flattenedName)
            })
            // Legacy highlight key
            putExtra("highlight_key", flattenedName)
        }
    }

    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    try {
        startActivity(intent)
    } catch (e: Exception) {
        // Fallback 1: Specifically for Xiaomi/MIUI/HyperOS to open the "Downloaded Apps" section
        if (MIUIUtils.isMIUI()) {
            try {
                val miuiIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(":settings:show_fragment", "com.android.settings.accessibility.AccessibilitySettingsForUnderO")
                }
                startActivity(miuiIntent)
                return
            } catch (e2: Exception) {
                // Ignore and fall through to other fallbacks
            }
        }

        // Fallback 2: Specifically for Vivo/OriginOS/Chinese ROMs which often
        // have a dedicated "Downloaded Services" or "Installed Apps" list.
        try {
            val vivoIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // This extra often forces the "Downloaded Apps" section to open on Funtouch/OriginOS
                putExtra(":settings:show_fragment_args", Bundle().apply {
                    putString(":settings:fragment_args_key", flattenedName)
                })
            }
            startActivity(vivoIntent)
        } catch (e2: Exception) {
            // Ultimate fallback to general settings
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e3: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}

/**
 * Shared AppOps auto-start check: MIUI and Vivo differ only in the op
 * code (10008 vs 10002) — the reflection bodies were near-identical
 * duplicates.
 */
private fun isAutoStartOpAllowed(context: Context, opCode: Int): Boolean {
    val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    return try {
        val method: Method = AppOpsManager::class.java.getMethod(
            "checkOpNoThrow",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            String::class.java
        )
        val result = method.invoke(
            ops,
            opCode,
            // Our own uid: getCallingUid() returns the *caller's* uid when
            // this runs on a binder thread, which would check the wrong app.
            android.os.Process.myUid(),
            context.packageName
        ) as Int
        result == AppOpsManager.MODE_ALLOWED
    } catch (e: Exception) {
        false
    }
}

object MIUIUtils {
    private const val OP_AUTO_START = 10008

    fun isAutoStartEnabled(context: Context): Boolean =
        isAutoStartOpAllowed(context, OP_AUTO_START)

    fun isMIUI(): Boolean {
        return try {
            // Locale.ROOT: in a Turkish locale "XIAOMI".lowercase() is
            // "xıaomı", which fails contains("xiaomi") and breaks MIUI
            // detection (and the accessibility-settings fallback).
            val manufacturer = android.os.Build.MANUFACTURER.lowercase(java.util.Locale.ROOT)
            // `Method.invoke(...)` returns Any? on the JVM. Cast to String? and
            // fall back to empty — without this the Kotlin compiler flags the
            // raw `.toString()` call as an "unsafe use of a nullable receiver".
            val property = (Class.forName("android.os.SystemProperties")
                .getMethod("get", String::class.java)
                .invoke(null, "ro.miui.ui.version.name") as? String).orEmpty()
            manufacturer.contains("xiaomi") || property.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }
}

object VivoUtils {
    private const val OP_AUTO_START = 10002

    fun isAutoStartEnabled(context: Context): Boolean =
        isAutoStartOpAllowed(context, OP_AUTO_START)

    fun isVivo(): Boolean {
        // Locale.ROOT, as isMIUI(): "VIVO".lowercase() in a Turkish locale
        // is "vıvo" and fails contains("vivo").
        val manufacturer = android.os.Build.MANUFACTURER.lowercase(java.util.Locale.ROOT)
        return manufacturer.contains("vivo") || manufacturer.contains("iqoo")
    }
}

private val KNOWN_VIDEO_PLAYER_PACKAGES = setOf(
    "com.google.android.youtube",
    "com.netflix.mediaclient",
    "org.videolan.vlc",
    "com.mxtech.videoplayer.ad",
    "com.mxtech.videoplayer.pro",
    "com.ss.android.ugc.trill",        // TikTok
    "com.zhiliaoapp.musically",        // TikTok (alt)
    "com.twitch.android.app",
    "com.amazon.avod",
    "com.disney.disneyplus",
    "com.hbo.hbonow",
    "com.peacocktv.peacockandroid",
    "com.hulu.plus",
    "com.crunchyroll.crunchyroid",
    "com.youku.phone",
    "com.iqiyi.i18n",
    "com.tencent.qqlive",
    "com.ss.android.article.video"      // Toutiao video
)

// Music apps that must NOT count as video players: they share the
// "youtube" / "player" substrings with video apps, and counting them
// would put the panel into game/video mode while music plays.
private val KNOWN_MUSIC_PACKAGES = setOf(
    "com.google.android.apps.youtube.music",
    "com.spotify.music",
    "com.apple.android.music"
)

/**
 * Resolve a Material theme color attribute (e.g. `colorSurface`,
 * `colorOnSurface`) to its color int, so programmatic UIs follow the
 * app's Light/Dark/System theme instead of hardcoding dark-mode colors
 * (white text on a `#1F2732` sheet is invisible in Light mode).
 */
fun Context.resolveThemeColor(@androidx.annotation.AttrRes attr: Int): Int {
    val typedValue = android.util.TypedValue()
    theme.resolveAttribute(attr, typedValue, true)
    return typedValue.data
}

/**
 * Returns true if the given package is a known video player / streaming app.
 */
fun Context.isVideoPlayerPackage(packageName: String): Boolean {
    if (packageName.isBlank()) return false
    if (KNOWN_VIDEO_PLAYER_PACKAGES.contains(packageName)) return true
    if (KNOWN_MUSIC_PACKAGES.contains(packageName)) return false
    val lower = packageName.lowercase(java.util.Locale.ROOT)
    // Brand substrings are specific; the generic words only count as a
    // whole package segment — the old ".video" / ".player" / "vlc"
    // substrings also matched music players ("com.foo.musicplayer")
    // and remotes.
    if (lower.contains("youtube") || lower.contains("netflix")) return true
    return lower.split(".").any { it == "video" || it == "player" || it == "vlc" }
}

/**
 * Shared safety gate for `intent:#Intent;\u2026` URIs persisted to
 * SharedPreferences and round-tripped through the picker / sidebar.
 *
 * Policy (user-selected): any *parseable* `intent:` URI is safe — including
 * intents whose component targets another package and selector chains that
 * embed foreign components. Scheme allowlisting happens earlier at save time
 * (`isValidCustom` only admits `intent:` / `http://` / `https://`) and
 * again at decode time, so the only failure left to catch here for
 * `intent:` URIs is a malformed string that cannot be parsed into an
 * Intent at all. Non-`intent:` payloads (custom URL items) are held to
 * the same allowlist here as defence in depth: only `http(s)://` may
 * launch, so a `file:` / `javascript:` / `content:` URI that slipped
 * past validation is refused at launch time too.
 *
 * Pure `http(s)://` URIs are out of scope here — OS Intent resolution already
 * confines them to the registered browser / viewer.
 */
internal fun Context.isSafeIntentUri(uri: String?): Boolean {
    if (uri.isNullOrBlank()) return true
    if (!uri.startsWith("intent:")) {
        val s = uri.trim().lowercase(java.util.Locale.ROOT)
        return s.startsWith("http://") || s.startsWith("https://")
    }
    return try {
        // Cross-app components and SEL=\u2026 selector chains are permitted by
        // policy. Only an unparseable `intent:` URI is refused.
        Intent.parseUri(uri, Intent.URI_INTENT_SCHEME)
        true
    } catch (e: Exception) {
        // Unparseable `intent:` URI — refuse to launch as a paranoia step.
        false
    }
}

/**
 * Launch a shortcut via LauncherApps, which can target non-exported
 * activities that a raw startActivity(intent) cannot reach (dynamic
 * shortcuts often point at them). Returns true on success; false when
 * there is no shortcut id, no LauncherApps service, or the launch was
 * refused — the caller then falls back to the raw intent.
 */
internal fun Context.tryLaunchShortcut(packageName: String, shortcutId: String?): Boolean {
    if (shortcutId.isNullOrBlank()) return false
    return try {
        val launcherApps = getSystemService(android.content.pm.LauncherApps::class.java) ?: return false
        launcherApps.startShortcut(packageName, shortcutId, null, null, android.os.Process.myUserHandle())
        true
    } catch (_: Exception) {
        false
    }
}

/**
 * Switches the system brightness to manual mode, but only when it isn't
 * already: writing it on every brightness tick permanently turns off the
 * user's auto-brightness, which is never restored.
 */
fun ensureManualBrightnessMode(resolver: android.content.ContentResolver) {
    val current = try {
        android.provider.Settings.System.getInt(
            resolver,
            android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,
            android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        )
    } catch (_: Exception) {
        android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
    }
    if (current != android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) {
        try {
            android.provider.Settings.System.putInt(
                resolver,
                android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,
                android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
        } catch (_: Exception) {}
    }
}

/**
 * Extension to convert DP to PX.
 */
fun Context.dpToPx(dp: Int): Int {
    return (dp * resources.displayMetrics.density).toInt()
}

/**
 * Height a handle window may use without exceeding the screen (minus the
 * top/bottom safe margins). A handle taller than the screen makes the
 * offset range below empty, and `coerceIn` on an empty range throws
 * IllegalArgumentException.
 */
fun cappedHandleHeightPx(handleHeightPx: Int, screenHeightPx: Int, safeMarginPx: Int): Int =
    handleHeightPx.coerceAtMost((screenHeightPx - 2 * safeMarginPx).coerceAtLeast(0))

/**
 * Largest |y| offset that keeps a handle of [handleHeightPx] on screen
 * (the handle is centred vertically). Never negative, so
 * `coerceIn(-maxOffset, maxOffset)` can never throw even when the handle
 * is taller than the screen.
 */
fun maxHandleOffsetPx(screenHeightPx: Int, handleHeightPx: Int, safeMarginPx: Int): Int =
    ((screenHeightPx - handleHeightPx) / 2 - safeMarginPx).coerceAtLeast(0)

/**
 * Applies the selected theme mode (System, Light, Dark) to the application.
 */
fun applyAppTheme(context: Context) {
    val prefs = PanelPreferences(context)
    val mode = when (prefs.themeMode) {
        PanelPreferences.MODE_LIGHT -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
        PanelPreferences.MODE_DARK -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
        else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }
    androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(mode)
}

/**
 * Checks if the app's Accessibility Service is currently running.
 */
/**
 * One engine-availability check for the whole app: the sidebar works when
 * either the Accessibility service is enabled or the native automation
 * engine (Shizuku/root) is usable and the user opted into it. Previously
 * each call site re-derived this with a different rule — BootReceiver and
 * the QS tile required Accessibility outright, the a11y onUnbind stop
 * assumed it, while shouldShowHandle() allowed automation-only — so
 * automation-only users could not use boot or the tile, and the panel died
 * when Accessibility was turned off.
 */
fun Context.isEngineAvailable(): Boolean =
    isAccessibilityServiceEnabled() ||
        (PanelPreferences(this).useAutomationForGestures && AutomationManager.isAutomationPossible())

fun Context.isAccessibilityServiceEnabled(): Boolean {
    if (PanelAccessibilityService.isRunning) return true

    val enabledServices = Settings.Secure.getString(
        contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false

    val colonSplitter = android.text.TextUtils.SimpleStringSplitter(':')
    colonSplitter.setString(enabledServices)

    // Compare as ComponentNames, not strings: the enabled-services setting
    // also holds the short form "pkg/.PanelAccessibilityService" (written
    // by adb/Shizuku grant flows and some ROMs), which a string comparison
    // against the fully-qualified name misses.
    val expected = android.content.ComponentName(this, PanelAccessibilityService::class.java)
    while (colonSplitter.hasNext()) {
        val entry = colonSplitter.next()
        if (android.content.ComponentName.unflattenFromString(entry) == expected) {
            return true
        }
    }
    return false
}

/**
 * Opens a modern color picker with manual Hex input support and visual sliders.
 * Integrated into a seamless Material 3 Dialog using skydoves ColorPickerView.
 */
fun Context.openColorPicker(initialColor: Int, onPick: (Int) -> Unit) {
    val rootLayout = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    // 1. Hex Input and Preview
    val hexInputLayout = com.google.android.material.textfield.TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
        hint = getString(R.string.dialog_hex_color_hint)
        endIconMode = com.google.android.material.textfield.TextInputLayout.END_ICON_CLEAR_TEXT
        setBoxCornerRadii(dpToPx(12).toFloat(), dpToPx(12).toFloat(), dpToPx(12).toFloat(), dpToPx(12).toFloat())
        layoutParams = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            val p = dpToPx(20)
            setMargins(p, dpToPx(8), p, 0)
        }
    }

    val hexEditText = com.google.android.material.textfield.TextInputEditText(hexInputLayout.context).apply {
        setText(String.format("#%08X", initialColor))
        filters = arrayOf(android.text.InputFilter.LengthFilter(9))
    }
    hexInputLayout.addView(hexEditText)

    val previewCard = com.google.android.material.card.MaterialCardView(this).apply {
        layoutParams = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            dpToPx(60)
        ).apply {
            val p = dpToPx(20)
            setMargins(p, dpToPx(12), p, dpToPx(16))
        }
        radius = dpToPx(16).toFloat()
        setCardBackgroundColor(initialColor)
        strokeWidth = dpToPx(1)
        strokeColor = Color.parseColor("#33FFFFFF")
    }

    // 2. Skydoves ColorPickerView
    val colorPickerView = com.skydoves.colorpickerview.ColorPickerView.Builder(this)
        .setInitialColor(initialColor)
        .build()
        .apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(200) // Beautiful large circle
            ).apply {
                val p = dpToPx(20)
                setMargins(p, 0, p, 0)
            }
        }

    val alphaSlideBar = com.skydoves.colorpickerview.sliders.AlphaSlideBar(this).apply {
        layoutParams = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            dpToPx(24)
        ).apply {
            val p = dpToPx(20)
            setMargins(p, dpToPx(16), p, dpToPx(8))
        }
    }

    val brightnessSlideBar = com.skydoves.colorpickerview.sliders.BrightnessSlideBar(this).apply {
        layoutParams = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            dpToPx(24)
        ).apply {
            val p = dpToPx(20)
            setMargins(p, 0, p, dpToPx(8))
        }
    }

    // Attach sliders to picker
    colorPickerView.attachAlphaSlider(alphaSlideBar)
    colorPickerView.attachBrightnessSlider(brightnessSlideBar)

    // Assemble UI
    rootLayout.addView(hexInputLayout)
    rootLayout.addView(previewCard)
    rootLayout.addView(colorPickerView)
    rootLayout.addView(alphaSlideBar)
    rootLayout.addView(brightnessSlideBar)

    var isUpdatingFromPicker = false
    var isUpdatingFromHex = false
    var currentColor = initialColor

    // Setup Listeners
    colorPickerView.setColorListener(object : com.skydoves.colorpickerview.listeners.ColorEnvelopeListener {
        override fun onColorSelected(envelope: com.skydoves.colorpickerview.ColorEnvelope, fromUser: Boolean) {
            currentColor = envelope.color
            previewCard.setCardBackgroundColor(currentColor)

            if (fromUser && !isUpdatingFromHex) {
                isUpdatingFromPicker = true
                val hexStr = "#" + envelope.hexCode
                if (hexEditText.text.toString().uppercase() != hexStr) {
                    hexEditText.setText(hexStr)
                }
                isUpdatingFromPicker = false
            }
        }
    })

    hexEditText.addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) {
            if (isUpdatingFromPicker) return
            try {
                val colorStr = s.toString()
                val c = Color.parseColor(if (colorStr.startsWith("#")) colorStr else "#$colorStr")
                currentColor = c
                previewCard.setCardBackgroundColor(c)

                isUpdatingFromHex = true
                colorPickerView.setInitialColor(c) // This updates the cursor position in skydoves
                isUpdatingFromHex = false
            } catch (e: Exception) {
                // Ignore invalid hex while typing
            }
        }
    })

    com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
        .setTitle(R.string.dialog_choose_color)
        .setView(rootLayout)
        .setPositiveButton(R.string.dialog_select) { _, _ ->
            onPick(currentColor)
        }
        .setNegativeButton(R.string.dialog_cancel, null)
        .show()
}
