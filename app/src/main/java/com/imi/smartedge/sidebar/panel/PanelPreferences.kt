package com.imi.smartedge.sidebar.panel

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Manages the persistent list of apps pinned to the side panel,
 * and other panel settings, using SharedPreferences.
 */
class PanelPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "side_panel_prefs"
        private const val KEY_PANEL_APPS = "panel_apps"
        private const val KEY_PANEL_SIDE = "panel_side"
        private const val KEY_AUTO_START = "auto_start"
        private const val KEY_SHOW_PILL = "show_pill"
        private const val KEY_HAPTIC_ENABLED = "haptic_enabled"
        private const val KEY_PANEL_OPACITY = "panel_opacity"
        private const val KEY_HANDLE_HEIGHT = "handle_height"
        private const val KEY_HANDLE_WIDTH = "handle_width"
        private const val KEY_HANDLE_OFFSET = "handle_offset"
        private const val KEY_ACCENT_COLOR = "accent_color"
        private const val KEY_USE_CUSTOM_ACCENT = "use_custom_accent"
        private const val KEY_PANEL_COLUMNS = "panel_columns"
        private const val KEY_UI_THEME = "ui_theme"

        private const val KEY_PANEL_RADIUS = "panel_radius"
        private const val KEY_PANEL_BG_COLOR = "panel_bg_color"
        private const val KEY_HIDE_BG = "hide_bg"
        private const val KEY_SHOW_TOOLS = "show_tools"
        private const val KEY_ICON_SHAPE = "icon_shape"
        private const val KEY_GESTURES_ENABLED = "gestures_enabled"
        private const val KEY_SHOW_IN_LANDSCAPE = "show_in_landscape"
        private const val KEY_THEME_MODE = "theme_mode"

        private const val KEY_PILL_WIDTH = "pill_width"
        private const val KEY_PILL_COLOR = "pill_color"
        private const val KEY_TAP_TO_OPEN = "tap_to_open"
        private const val KEY_DOUBLE_TAP_TO_OPEN = "double_tap_to_open"
        private const val KEY_TRIPLE_TAP_TO_OPEN = "triple_tap_to_open"
        private const val KEY_ICON_PACK = "selected_icon_pack"
        private const val KEY_ICON_PACK_LABEL = "selected_icon_pack_label"
        private const val KEY_BLUR_ENABLED = "blur_enabled"
        private const val KEY_BLUR_AMOUNT = "blur_amount"
        private const val KEY_SHOW_LOGS = "show_logs"
        private const val KEY_ANIM_SPEED = "animation_speed"
        private const val KEY_PICKER_ANIM_TYPE = "picker_anim_type"
        private const val KEY_PICKER_GAP = "picker_gap"
        private const val KEY_SHOW_SYS_INFO = "show_sys_info"
        private const val KEY_SHOW_SCREENSHOT_TOOL = "show_screenshot_tool"
        private const val KEY_SHOW_TOOLS_PANEL_BUTTON = "show_tools_panel_button"
        private const val KEY_SHOW_POWER_MENU = "show_power_menu"
        private const val KEY_SHOW_VOLUME_KEYS = "show_volume_keys"
        private const val KEY_SHOW_BRIGHTNESS_KEYS = "show_brightness_keys"
        private const val KEY_HOME_BUTTON_STYLE = "home_button_style"
        private const val KEY_FREEFORM_ENABLED = "freeform_enabled"
        private const val KEY_FREEFORM_WINDOW_MODE = "freeform_window_mode"
        private const val KEY_FREEFORM_CUSTOM_W = "freeform_custom_width"
        private const val KEY_FREEFORM_CUSTOM_H = "freeform_custom_height"
        private const val KEY_SCALE_FACTOR = "scale_factor"
        private const val KEY_PANEL_MAX_HEIGHT = "panel_max_height"
        private const val KEY_PICKER_MAX_HEIGHT = "picker_max_height"
        private const val KEY_SHOW_NOTIFICATION_APPS = "show_notification_apps"
        private const val KEY_DRAG_TO_SPLIT = "drag_to_split"
        private const val KEY_REMEMBER_SCROLL = "remember_scroll"
        private const val KEY_AUTO_SHOW_KEYBOARD = "auto_show_keyboard"
        private const val KEY_SIDEBAR_SCROLL = "last_sidebar_scroll"
        private const val KEY_PICKER_SCROLL = "last_picker_scroll"
        private const val KEY_GAME_APPS = "game_apps"
        private const val KEY_AUTO_HIDE_FULLSCREEN = "auto_hide_fullscreen"
        private const val KEY_FULLSCREEN_WHITELIST = "fullscreen_whitelist"
        private const val KEY_DELIBERATE_GESTURE_GAMES = "deliberate_gesture_games"
        private const val KEY_TOOLS_FOLDER_MIGRATED = "tools_folder_migrated"
        private const val KEY_SLIDE_BRIGHTNESS_ENABLED = "slide_brightness_enabled"
        private const val KEY_SLIDE_VOLUME_ENABLED = "slide_volume_enabled"
        private const val KEY_SLIDE_SENSITIVITY = "slide_sensitivity"
        private const val KEY_SWIPE_SENSITIVITY = "swipe_sensitivity"
        private const val KEY_USE_AUTOMATION_FOR_GESTURES = "use_automation_for_gestures"
        private const val KEY_ONLY_ON_HOME = "only_on_home"
        private const val KEY_FAVORITE_APP = "favorite_app_package"
        private const val KEY_NOTCH_GESTURES_ENABLED = "notch_gestures_enabled"
        private const val KEY_NOTCH_TAP_ACTION = "notch_tap_action"
        private const val KEY_NOTCH_DOUBLE_TAP_ACTION = "notch_double_tap_action"
        private const val KEY_NOTCH_TRIPLE_TAP_ACTION = "notch_triple_tap_action"
        private const val KEY_NOTCH_LONG_PRESS_ACTION = "notch_long_press_action"

        private const val KEY_TAP_ACTION = "tap_action"
        private const val KEY_DOUBLE_TAP_ACTION = "double_tap_action"
        private const val KEY_TRIPLE_TAP_ACTION = "triple_tap_action"
        private const val KEY_LONG_PRESS_ACTION = "long_press_action"

        // --- Custom Intents/URLs (user-added sidebar items) ---
        private const val KEY_CUSTOM_ITEMS = "custom_items_json"
        const val CUSTOM_ID_PREFIX = "smartedge.custom."

        // Audit U3: UUID v4 `xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx` (8-4-4-4-12 hex
        // with fixed-position hyphens). Anything else is rejected on addCustomItem
        // so corrupt prefs or future caller bugs can't smuggle arbitrary bytes
        // into sidebar identifiers.
        private val UUID_PATTERN = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\$")

        private const val DELIMITER = ","

        const val ACTION_NONE = 0
        const val ACTION_OPEN_LAUNCHER = 1
        const val ACTION_SCREENSHOT = 2
        const val ACTION_PREVIOUS_APP = 3
        const val ACTION_BACK = 4
        const val ACTION_HOME = 5
        const val ACTION_RECENTS = 6
        const val ACTION_NOTIFICATIONS = 7
        const val ACTION_QUICK_SETTINGS = 8
        const val ACTION_LOCK_SCREEN = 9
        const val ACTION_POWER_MENU = 10
        const val ACTION_FLASHLIGHT = 11
        const val ACTION_CAMERA = 12
        const val ACTION_AUTO_ROTATION = 13
        const val ACTION_OPEN_FAVORITE_APP = 14
        const val ACTION_MOVE_HANDLE = 15

        const val ANIM_TYPE_SLIDE = "slide"
        const val ANIM_TYPE_POPUP = "popup"

        const val SIDE_RIGHT = "right"
        const val SIDE_LEFT = "left"

        const val THEME_ORIGIN = "origin"
        const val THEME_HYPEROS = "hyperos"
        const val THEME_REALME = "realme"
        const val THEME_RICH = "rich"

        const val SHAPE_SYSTEM = "system"
        const val SHAPE_CIRCLE = "circle"
        const val SHAPE_SQUIRCLE = "squircle"
        const val SHAPE_SQUARE = "square"
        const val SHAPE_ROUNDED = "rounded"

        const val STYLE_POWER = "power"
        const val STYLE_CLASSIC = "classic"

        // Theme modes
        const val MODE_SYSTEM = 0
        const val MODE_LIGHT = 1
        const val MODE_DARK = 2

        // Freeform window size modes
        const val FREEFORM_MODE_STANDARD  = "standard"  // 80% screen, centered
        const val FREEFORM_MODE_PORTRAIT  = "portrait"  // Narrow tall window
        const val FREEFORM_MODE_MAXIMIZED = "maximized" // Full screen freeform
        const val FREEFORM_MODE_CUSTOM    = "custom"    // User-defined width & height %

        // Defaults
        val DEFAULT_SIDE = SIDE_RIGHT
        const val DEFAULT_AUTO_START = true
        const val DEFAULT_SHOW_PILL = true
        const val DEFAULT_HAPTIC = true
        const val DEFAULT_OPACITY = 100
        const val DEFAULT_HANDLE_HEIGHT = 80
        const val DEFAULT_HANDLE_WIDTH = 32
        const val DEFAULT_HANDLE_OFFSET = 0
        const val DEFAULT_ACCENT_COLOR = "#4A9EFF"
        const val DEFAULT_USE_CUSTOM_ACCENT = false
        const val DEFAULT_PANEL_COLS = 1
        const val DEFAULT_THEME = THEME_ORIGIN
        const val DEFAULT_PANEL_RADIUS = 20
        const val DEFAULT_PANEL_BG = "#E61A1C1E"
        const val DEFAULT_PILL_COLOR = "#FFFFFF"
        const val DEFAULT_HIDE_BG = false
        val DEFAULT_ICON_SHAPE = SHAPE_SQUIRCLE
        const val DEFAULT_GESTURES = true
        const val DEFAULT_SHOW_LANDSCAPE = true
        const val DEFAULT_PILL_WIDTH = 5
        const val DEFAULT_TAP_TO_OPEN = true
        const val DEFAULT_DOUBLE_TAP_TO_OPEN = false
        const val DEFAULT_TRIPLE_TAP_TO_OPEN = false
        const val DEFAULT_ICON_PACK = "none"
        const val DEFAULT_SHOW_LOGS = false
        const val DEFAULT_BLUR_AMOUNT = 15
        const val DEFAULT_ANIM_SPEED = 400
        const val DEFAULT_PICKER_ANIM = ANIM_TYPE_POPUP
        const val DEFAULT_PICKER_GAP = 20
        const val DEFAULT_HOME_BUTTON_STYLE = STYLE_POWER
        const val DEFAULT_THEME_MODE = MODE_SYSTEM
        const val DEFAULT_SHOW_TOOLS = true
        const val DEFAULT_SHOW_TOOLS_PANEL = true
        const val DEFAULT_SLIDE_BRIGHTNESS = true
        const val DEFAULT_SLIDE_VOLUME = true
        const val DEFAULT_SLIDE_SENSITIVITY = 100
        const        val DEFAULT_SWIPE_SENSITIVITY = 100
    }

    fun resetUIColors() {
        prefs.edit {
            putString(KEY_ACCENT_COLOR, DEFAULT_ACCENT_COLOR)
            putString(KEY_PANEL_BG_COLOR, DEFAULT_PANEL_BG)
            putBoolean(KEY_USE_CUSTOM_ACCENT, DEFAULT_USE_CUSTOM_ACCENT)
        }
    }

    /** Exports all settings (except runtime/session keys) to a JSON string. */
    fun exportToJson(): String {
        val obj = org.json.JSONObject()
        obj.put("_version", 1)
        obj.put("_app", "SmartEdge")

        // Strings
        //
        // Round-13 audit M3 — DELIMITER-ROUND-TRIP DEFENSE: the three lists
        // keyed below use `encodeDelim()` to escape `,` and `\` on write so
        // that Intent-URI extras like `tags=a,b,c` survive `split(",")` on
        // read. Calling `getPanelApps().joinToString(",")` here would discard
        // the escape sequences the prefs blob still carries, producing a
        // perfectly valid JSON export that silently corrupts the next
        // `importFromJson`. Read the persisted blob verbatim instead — this
        // is also what makes the export idempotent across multiple
        // export→import round-trips.
        val strings = mapOf(
            KEY_PANEL_APPS to (prefs.getString(KEY_PANEL_APPS, null) ?: ""),
            KEY_CUSTOM_ITEMS to (prefs.getString(KEY_CUSTOM_ITEMS, null) ?: ""),
            KEY_GAME_APPS to (prefs.getString(KEY_GAME_APPS, null) ?: ""),
            KEY_PANEL_SIDE to panelSide,
            KEY_ACCENT_COLOR to accentColor,
            KEY_PANEL_BG_COLOR to panelBackgroundColor,
            KEY_UI_THEME to uiTheme,
            KEY_ICON_SHAPE to iconShape,
            KEY_PILL_COLOR to pillColor,
            KEY_ICON_PACK to selectedIconPack,
            KEY_ICON_PACK_LABEL to iconPackLabel,
            KEY_HOME_BUTTON_STYLE to homeButtonStyle,
            KEY_FREEFORM_WINDOW_MODE to freeformWindowMode
        )
        strings.forEach { (k, v) -> obj.put(k, v) }

        // Ints
        val ints = mapOf(
            KEY_PANEL_OPACITY to panelOpacity,
            KEY_HANDLE_HEIGHT to handleHeight,
            KEY_HANDLE_WIDTH to handleWidth,
            KEY_HANDLE_OFFSET to handleVerticalOffset,
            KEY_PANEL_COLUMNS to panelColumns,
            KEY_PANEL_RADIUS to panelCornerRadius,
            KEY_PILL_WIDTH to pillWidth,
            KEY_BLUR_AMOUNT to blurAmount,
            KEY_ANIM_SPEED to animSpeed,
            KEY_PICKER_GAP to pickerGap,
            KEY_PANEL_MAX_HEIGHT to panelMaxHeight,
            KEY_PICKER_MAX_HEIGHT to pickerMaxHeight,
            KEY_SLIDE_SENSITIVITY to slideSensitivity,
            KEY_SWIPE_SENSITIVITY to swipeSensitivity,
            KEY_FREEFORM_CUSTOM_W to freeformCustomWidth,
            KEY_FREEFORM_CUSTOM_H to freeformCustomHeight,
            KEY_THEME_MODE to themeMode
        )
        ints.forEach { (k, v) -> obj.put(k, v) }
        
        obj.put(KEY_PICKER_ANIM_TYPE, pickerAnimType)

        // Floats
        obj.put(KEY_SCALE_FACTOR, scaleFactor.toDouble())

        // Booleans
        val bools = mapOf(
            KEY_AUTO_START to autoStart,
            KEY_SHOW_PILL to showPill,
            KEY_HAPTIC_ENABLED to hapticEnabled,
            KEY_USE_CUSTOM_ACCENT to useCustomAccent,
            KEY_HIDE_BG to hideBackground,
            KEY_SHOW_TOOLS to showTools,
            KEY_GESTURES_ENABLED to gesturesEnabled,
            KEY_SHOW_IN_LANDSCAPE to showInLandscape,
            KEY_TAP_TO_OPEN to tapToOpen,
            KEY_DOUBLE_TAP_TO_OPEN to doubleTapToOpen,
            KEY_TRIPLE_TAP_TO_OPEN to tripleTapToOpen,
            KEY_BLUR_ENABLED to blurEnabled,
            KEY_SHOW_LOGS to showLogs,
            KEY_SHOW_SYS_INFO to showSysInfo,
            KEY_SHOW_POWER_MENU to showPowerMenu,
            KEY_SHOW_VOLUME_KEYS to showVolumeKeys,
            KEY_SHOW_BRIGHTNESS_KEYS to showBrightnessKeys,
            KEY_SLIDE_BRIGHTNESS_ENABLED to slideBrightnessEnabled,
            KEY_SLIDE_VOLUME_ENABLED to slideVolumeEnabled,
            KEY_FREEFORM_ENABLED to freeformEnabled,
            KEY_SHOW_NOTIFICATION_APPS to showNotificationApps,
            KEY_DRAG_TO_SPLIT to dragToSplit,
            KEY_REMEMBER_SCROLL to rememberScroll,
            KEY_AUTO_SHOW_KEYBOARD to autoShowKeyboard
        )
        bools.forEach { (k, v) -> obj.put(k, v) }

        return obj.toString(2)
    }

    /**
     * Imports settings from a JSON string. Returns true on success, false on parse error.
     * Unknown keys are silently ignored for forward-compatibility.
     *
     * Audit U2: a malicious or corrupt Settings export could be arbitrarily large.
     * SharedPreferences has a per-key value cap, and reading a multi-MB blob into
     * RAM while parsing would jank or OOM. Cap incoming JSON at 512 KiB.
     */
    fun importFromJson(json: String): Boolean {
        if (json.length > 524288) return false
        return try {
            val obj = org.json.JSONObject(json)
            // Round-12 audit L-High: previous code used getString/getInt/
            // getBoolean which all throw JSONException on type mismatch
            // (e.g. a manually-edited export that has a bool where the schema
            // expects a string). One bad key would abort the entire import,
            // silently leaving the prefs in a half-imported state. Switching
            // to opt* makes every key best-effort: missing or wrong-type
            // values just keep the current pref, while every well-typed
            // sibling still gets applied.
            prefs.edit {
                // Strings
                obj.optString(KEY_PANEL_APPS, null)?.let { putString(KEY_PANEL_APPS, it) }
                obj.optString(KEY_CUSTOM_ITEMS, null)?.let { putString(KEY_CUSTOM_ITEMS, it) }
                obj.optString(KEY_GAME_APPS, null)?.let { putString(KEY_GAME_APPS, it) }
                obj.optString(KEY_PANEL_SIDE, null)?.let { putString(KEY_PANEL_SIDE, it) }
                obj.optString(KEY_ACCENT_COLOR, null)?.let { putString(KEY_ACCENT_COLOR, it) }
                obj.optString(KEY_PANEL_BG_COLOR, null)?.let { putString(KEY_PANEL_BG_COLOR, it) }
                obj.optString(KEY_UI_THEME, null)?.let { putString(KEY_UI_THEME, it) }
                obj.optString(KEY_ICON_SHAPE, null)?.let { putString(KEY_ICON_SHAPE, it) }
                obj.optString(KEY_PILL_COLOR, null)?.let { putString(KEY_PILL_COLOR, it) }
                obj.optString(KEY_ICON_PACK, null)?.let { putString(KEY_ICON_PACK, it) }
                obj.optString(KEY_ICON_PACK_LABEL, null)?.let { putString(KEY_ICON_PACK_LABEL, it) }
                obj.optString(KEY_HOME_BUTTON_STYLE, null)?.let { putString(KEY_HOME_BUTTON_STYLE, it) }
                obj.optString(KEY_FREEFORM_WINDOW_MODE, null)?.let { putString(KEY_FREEFORM_WINDOW_MODE, it) }
                obj.optString(KEY_PICKER_ANIM_TYPE, null)?.let { putString(KEY_PICKER_ANIM_TYPE, it) }

                // Ints
                if (obj.has(KEY_PANEL_OPACITY) && !obj.isNull(KEY_PANEL_OPACITY)) putInt(KEY_PANEL_OPACITY, obj.optInt(KEY_PANEL_OPACITY, panelOpacity))
                if (obj.has(KEY_HANDLE_HEIGHT) && !obj.isNull(KEY_HANDLE_HEIGHT)) putInt(KEY_HANDLE_HEIGHT, obj.optInt(KEY_HANDLE_HEIGHT, handleHeight))
                if (obj.has(KEY_HANDLE_WIDTH) && !obj.isNull(KEY_HANDLE_WIDTH)) putInt(KEY_HANDLE_WIDTH, obj.optInt(KEY_HANDLE_WIDTH, handleWidth))
                if (obj.has(KEY_HANDLE_OFFSET) && !obj.isNull(KEY_HANDLE_OFFSET)) putInt(KEY_HANDLE_OFFSET, obj.optInt(KEY_HANDLE_OFFSET, handleVerticalOffset))
                if (obj.has(KEY_PANEL_COLUMNS) && !obj.isNull(KEY_PANEL_COLUMNS)) putInt(KEY_PANEL_COLUMNS, obj.optInt(KEY_PANEL_COLUMNS, panelColumns).coerceIn(1, 2))
                if (obj.has(KEY_PANEL_RADIUS) && !obj.isNull(KEY_PANEL_RADIUS)) putInt(KEY_PANEL_RADIUS, obj.optInt(KEY_PANEL_RADIUS, panelCornerRadius))
                if (obj.has(KEY_PILL_WIDTH) && !obj.isNull(KEY_PILL_WIDTH)) putInt(KEY_PILL_WIDTH, obj.optInt(KEY_PILL_WIDTH, pillWidth))
                if (obj.has(KEY_BLUR_AMOUNT) && !obj.isNull(KEY_BLUR_AMOUNT)) putInt(KEY_BLUR_AMOUNT, obj.optInt(KEY_BLUR_AMOUNT, blurAmount))
                if (obj.has(KEY_ANIM_SPEED) && !obj.isNull(KEY_ANIM_SPEED)) putInt(KEY_ANIM_SPEED, obj.optInt(KEY_ANIM_SPEED, animSpeed))
                if (obj.has(KEY_PICKER_GAP) && !obj.isNull(KEY_PICKER_GAP)) putInt(KEY_PICKER_GAP, obj.optInt(KEY_PICKER_GAP, pickerGap))
                if (obj.has(KEY_PANEL_MAX_HEIGHT) && !obj.isNull(KEY_PANEL_MAX_HEIGHT)) putInt(KEY_PANEL_MAX_HEIGHT, obj.optInt(KEY_PANEL_MAX_HEIGHT, panelMaxHeight))
                if (obj.has(KEY_PICKER_MAX_HEIGHT) && !obj.isNull(KEY_PICKER_MAX_HEIGHT)) putInt(KEY_PICKER_MAX_HEIGHT, obj.optInt(KEY_PICKER_MAX_HEIGHT, pickerMaxHeight))
                if (obj.has(KEY_SLIDE_SENSITIVITY) && !obj.isNull(KEY_SLIDE_SENSITIVITY)) putInt(KEY_SLIDE_SENSITIVITY, obj.optInt(KEY_SLIDE_SENSITIVITY, slideSensitivity))
                if (obj.has(KEY_SWIPE_SENSITIVITY) && !obj.isNull(KEY_SWIPE_SENSITIVITY)) putInt(KEY_SWIPE_SENSITIVITY, obj.optInt(KEY_SWIPE_SENSITIVITY, swipeSensitivity))
                if (obj.has(KEY_FREEFORM_CUSTOM_W) && !obj.isNull(KEY_FREEFORM_CUSTOM_W)) putInt(KEY_FREEFORM_CUSTOM_W, obj.optInt(KEY_FREEFORM_CUSTOM_W, freeformCustomWidth))
                if (obj.has(KEY_FREEFORM_CUSTOM_H) && !obj.isNull(KEY_FREEFORM_CUSTOM_H)) putInt(KEY_FREEFORM_CUSTOM_H, obj.optInt(KEY_FREEFORM_CUSTOM_H, freeformCustomHeight))
                if (obj.has(KEY_THEME_MODE) && !obj.isNull(KEY_THEME_MODE)) putInt(KEY_THEME_MODE, obj.optInt(KEY_THEME_MODE, themeMode))

                // Float
                if (obj.has(KEY_SCALE_FACTOR) && !obj.isNull(KEY_SCALE_FACTOR)) {
                    putFloat(KEY_SCALE_FACTOR, obj.optDouble(KEY_SCALE_FACTOR, scaleFactor.toDouble()).toFloat())
                }

                // Booleans — optBoolean's signature is (key, fallback) so
                // we explicitly read the current value to keep the previous
                // behavior of "no write" on missing/typed-wrong keys.
                fun putBoolIfPresent(key: String, current: Boolean) {
                    if (!obj.has(key) || obj.isNull(key)) return
                    val v = obj.optBoolean(key, current)
                    putBoolean(key, v)
                }
                putBoolIfPresent(KEY_AUTO_START, autoStart)
                putBoolIfPresent(KEY_SHOW_PILL, showPill)
                putBoolIfPresent(KEY_HAPTIC_ENABLED, hapticEnabled)
                putBoolIfPresent(KEY_USE_CUSTOM_ACCENT, useCustomAccent)
                putBoolIfPresent(KEY_HIDE_BG, hideBackground)
                putBoolIfPresent(KEY_SHOW_TOOLS, showTools)
                putBoolIfPresent(KEY_GESTURES_ENABLED, gesturesEnabled)
                putBoolIfPresent(KEY_SHOW_IN_LANDSCAPE, showInLandscape)
                putBoolIfPresent(KEY_TAP_TO_OPEN, tapToOpen)
                putBoolIfPresent(KEY_DOUBLE_TAP_TO_OPEN, doubleTapToOpen)
                putBoolIfPresent(KEY_TRIPLE_TAP_TO_OPEN, tripleTapToOpen)
                putBoolIfPresent(KEY_BLUR_ENABLED, blurEnabled)
                putBoolIfPresent(KEY_SHOW_LOGS, showLogs)
                putBoolIfPresent(KEY_SHOW_SYS_INFO, showSysInfo)
                putBoolIfPresent(KEY_SHOW_POWER_MENU, showPowerMenu)
                putBoolIfPresent(KEY_SHOW_VOLUME_KEYS, showVolumeKeys)
                putBoolIfPresent(KEY_SHOW_BRIGHTNESS_KEYS, showBrightnessKeys)
                putBoolIfPresent(KEY_SLIDE_BRIGHTNESS_ENABLED, slideBrightnessEnabled)
                putBoolIfPresent(KEY_SLIDE_VOLUME_ENABLED, slideVolumeEnabled)
                putBoolIfPresent(KEY_FREEFORM_ENABLED, freeformEnabled)
                putBoolIfPresent(KEY_SHOW_NOTIFICATION_APPS, showNotificationApps)
                putBoolIfPresent(KEY_DRAG_TO_SPLIT, dragToSplit)
                putBoolIfPresent(KEY_REMEMBER_SCROLL, rememberScroll)
                putBoolIfPresent(KEY_AUTO_SHOW_KEYBOARD, autoShowKeyboard)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun resetToDefaults() {
        prefs.edit(commit = true) {
            putString(KEY_PANEL_SIDE, DEFAULT_SIDE)
            putBoolean(KEY_AUTO_START, DEFAULT_AUTO_START)
            putBoolean(KEY_SHOW_PILL, DEFAULT_SHOW_PILL)
            putBoolean(KEY_HAPTIC_ENABLED, DEFAULT_HAPTIC)
            putInt(KEY_PANEL_OPACITY, DEFAULT_OPACITY)
            putInt(KEY_HANDLE_HEIGHT, DEFAULT_HANDLE_HEIGHT)
            putInt(KEY_HANDLE_WIDTH, DEFAULT_HANDLE_WIDTH)
            putInt(KEY_HANDLE_OFFSET, DEFAULT_HANDLE_OFFSET)
            putString(KEY_ACCENT_COLOR, DEFAULT_ACCENT_COLOR)
            putBoolean(KEY_USE_CUSTOM_ACCENT, DEFAULT_USE_CUSTOM_ACCENT)
            putInt(KEY_PANEL_COLUMNS, DEFAULT_PANEL_COLS)
            putString(KEY_UI_THEME, DEFAULT_THEME)
            putInt(KEY_PANEL_RADIUS, DEFAULT_PANEL_RADIUS)
            putString(KEY_PANEL_BG_COLOR, DEFAULT_PANEL_BG)
            putBoolean(KEY_HIDE_BG, DEFAULT_HIDE_BG)
            putBoolean(KEY_SHOW_TOOLS, DEFAULT_SHOW_TOOLS)
            putBoolean(KEY_SHOW_TOOLS_PANEL_BUTTON, DEFAULT_SHOW_TOOLS_PANEL)
            putString(KEY_ICON_SHAPE, DEFAULT_ICON_SHAPE)
            putBoolean(KEY_GESTURES_ENABLED, DEFAULT_GESTURES)
            putBoolean(KEY_SHOW_IN_LANDSCAPE, DEFAULT_SHOW_LANDSCAPE)
            putInt(KEY_PILL_WIDTH, DEFAULT_PILL_WIDTH)
            putString(KEY_PILL_COLOR, DEFAULT_PILL_COLOR)
            putBoolean(KEY_TAP_TO_OPEN, DEFAULT_TAP_TO_OPEN)
            putBoolean(KEY_DOUBLE_TAP_TO_OPEN, DEFAULT_DOUBLE_TAP_TO_OPEN)
            putBoolean(KEY_TRIPLE_TAP_TO_OPEN, DEFAULT_TRIPLE_TAP_TO_OPEN)
            remove(KEY_TAP_ACTION)
            remove(KEY_DOUBLE_TAP_ACTION)
            remove(KEY_TRIPLE_TAP_ACTION)
            remove(KEY_LONG_PRESS_ACTION)
            putString(KEY_ICON_PACK, DEFAULT_ICON_PACK)
            putString(KEY_ICON_PACK_LABEL, "System Default")
            putInt(KEY_BLUR_AMOUNT, DEFAULT_BLUR_AMOUNT)
            putBoolean(KEY_BLUR_ENABLED, false)
            putBoolean(KEY_SHOW_LOGS, false)
            putInt(KEY_ANIM_SPEED, DEFAULT_ANIM_SPEED)
            putString(KEY_PICKER_ANIM_TYPE, DEFAULT_PICKER_ANIM)
            putInt(KEY_PICKER_GAP, DEFAULT_PICKER_GAP)
            putBoolean(KEY_SHOW_SYS_INFO, false)
            putBoolean(KEY_SHOW_SCREENSHOT_TOOL, true)
            putBoolean(KEY_SHOW_POWER_MENU, false)
            putBoolean(KEY_SHOW_VOLUME_KEYS, false)
            putBoolean(KEY_SHOW_BRIGHTNESS_KEYS, false)
            putBoolean(KEY_SLIDE_BRIGHTNESS_ENABLED, DEFAULT_SLIDE_BRIGHTNESS)
            putBoolean(KEY_SLIDE_VOLUME_ENABLED, DEFAULT_SLIDE_VOLUME)
            putInt(KEY_SLIDE_SENSITIVITY, DEFAULT_SLIDE_SENSITIVITY)
            putInt(KEY_SWIPE_SENSITIVITY, DEFAULT_SWIPE_SENSITIVITY)
            putBoolean(KEY_USE_AUTOMATION_FOR_GESTURES, false)
            putString(KEY_HOME_BUTTON_STYLE, DEFAULT_HOME_BUTTON_STYLE)
            putInt(KEY_THEME_MODE, DEFAULT_THEME_MODE)
            putBoolean(KEY_FREEFORM_ENABLED, false)
            putString(KEY_FREEFORM_WINDOW_MODE, FREEFORM_MODE_STANDARD)
            putInt(KEY_FREEFORM_CUSTOM_W, 80)
            putInt(KEY_FREEFORM_CUSTOM_H, 80)
            putFloat(KEY_SCALE_FACTOR, 1.0f)
            putInt(KEY_PANEL_MAX_HEIGHT, 350)
            putInt(KEY_PICKER_MAX_HEIGHT, 450)
            putBoolean(KEY_SHOW_NOTIFICATION_APPS, false)
            putBoolean(KEY_DRAG_TO_SPLIT, true)
            putBoolean(KEY_REMEMBER_SCROLL, false)
            putBoolean(KEY_AUTO_SHOW_KEYBOARD, false)
            putString(KEY_PANEL_APPS, "")
            putString(KEY_GAME_APPS, "")
            putBoolean(KEY_AUTO_HIDE_FULLSCREEN, false)
            putString(KEY_FULLSCREEN_WHITELIST, "")
            putBoolean(KEY_DELIBERATE_GESTURE_GAMES, true)
            putInt(KEY_SIDEBAR_SCROLL, 0)
            putInt(KEY_PICKER_SCROLL, 0)
        }
    }

    var themeMode: Int
        get() = prefs.getInt(KEY_THEME_MODE, DEFAULT_THEME_MODE)
        set(value) = prefs.edit { putInt(KEY_THEME_MODE, value) }

    var autoShowKeyboard: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SHOW_KEYBOARD, false)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_SHOW_KEYBOARD, value) }

    var rememberScroll: Boolean
        get() = prefs.getBoolean(KEY_REMEMBER_SCROLL, false)
        set(value) = prefs.edit { putBoolean(KEY_REMEMBER_SCROLL, value) }

    var lastSidebarScroll: Int
        get() = prefs.getInt(KEY_SIDEBAR_SCROLL, 0)
        set(value) = prefs.edit { putInt(KEY_SIDEBAR_SCROLL, value) }

    var lastPickerScroll: Int
        get() = prefs.getInt(KEY_PICKER_SCROLL, 0)
        set(value) = prefs.edit { putInt(KEY_PICKER_SCROLL, value) }

    var dragToSplit: Boolean
        get() = prefs.getBoolean(KEY_DRAG_TO_SPLIT, true)
        set(value) = prefs.edit { putBoolean(KEY_DRAG_TO_SPLIT, value) }

    var showNotificationApps: Boolean
        get() = prefs.getBoolean(KEY_SHOW_NOTIFICATION_APPS, false)
        set(value) = prefs.edit { putBoolean(KEY_SHOW_NOTIFICATION_APPS, value) }

    var panelMaxHeight: Int
        get() = prefs.getInt(KEY_PANEL_MAX_HEIGHT, 350)
        set(value) = prefs.edit { putInt(KEY_PANEL_MAX_HEIGHT, value) }

    var pickerMaxHeight: Int
        get() = prefs.getInt(KEY_PICKER_MAX_HEIGHT, 450)
        set(value) = prefs.edit { putInt(KEY_PICKER_MAX_HEIGHT, value) }

    var pillColor: String
        get() = prefs.getString(KEY_PILL_COLOR, DEFAULT_PILL_COLOR) ?: DEFAULT_PILL_COLOR
        set(value) = prefs.edit { putString(KEY_PILL_COLOR, value) }

    var freeformEnabled: Boolean
        get() = prefs.getBoolean(KEY_FREEFORM_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_FREEFORM_ENABLED, value) }

    var freeformWindowMode: String
        get() = prefs.getString(KEY_FREEFORM_WINDOW_MODE, FREEFORM_MODE_STANDARD) ?: FREEFORM_MODE_STANDARD
        set(value) = prefs.edit { putString(KEY_FREEFORM_WINDOW_MODE, value) }

    var freeformCustomWidth: Int
        get() = prefs.getInt(KEY_FREEFORM_CUSTOM_W, 80)
        set(value) = prefs.edit { putInt(KEY_FREEFORM_CUSTOM_W, value) }

    var freeformCustomHeight: Int
        get() = prefs.getInt(KEY_FREEFORM_CUSTOM_H, 80)
        set(value) = prefs.edit { putInt(KEY_FREEFORM_CUSTOM_H, value) }

    var scaleFactor: Float
        get() = prefs.getFloat(KEY_SCALE_FACTOR, 1.0f)
        set(value) = prefs.edit { putFloat(KEY_SCALE_FACTOR, value) }

    var showSysInfo: Boolean
        get() = prefs.getBoolean(KEY_SHOW_SYS_INFO, false)
        set(value) = prefs.edit { putBoolean(KEY_SHOW_SYS_INFO, value) }

    var showScreenshotTool: Boolean
        get() = prefs.getBoolean(KEY_SHOW_SCREENSHOT_TOOL, true)
        set(value) = prefs.edit { putBoolean(KEY_SHOW_SCREENSHOT_TOOL, value) }

    var showToolsPanelButton: Boolean
        get() = prefs.getBoolean(KEY_SHOW_TOOLS_PANEL_BUTTON, DEFAULT_SHOW_TOOLS_PANEL)
        set(value) = prefs.edit { putBoolean(KEY_SHOW_TOOLS_PANEL_BUTTON, value) }

    var showPowerMenu: Boolean
        get() = prefs.getBoolean(KEY_SHOW_POWER_MENU, false)
        set(value) = prefs.edit { putBoolean(KEY_SHOW_POWER_MENU, value) }

    var showVolumeKeys: Boolean
        get() = prefs.getBoolean(KEY_SHOW_VOLUME_KEYS, false)
        set(value) = prefs.edit { putBoolean(KEY_SHOW_VOLUME_KEYS, value) }

    var showBrightnessKeys: Boolean
        get() = prefs.getBoolean(KEY_SHOW_BRIGHTNESS_KEYS, false)
        set(value) = prefs.edit { putBoolean(KEY_SHOW_BRIGHTNESS_KEYS, value) }

    var homeButtonStyle: String
        get() = prefs.getString(KEY_HOME_BUTTON_STYLE, DEFAULT_HOME_BUTTON_STYLE) ?: DEFAULT_HOME_BUTTON_STYLE
        set(value) = prefs.edit { putString(KEY_HOME_BUTTON_STYLE, value) }

    var pickerGap: Int
        get() = prefs.getInt(KEY_PICKER_GAP, DEFAULT_PICKER_GAP)
        set(value) = prefs.edit { putInt(KEY_PICKER_GAP, value) }

    var useCustomAccent: Boolean
        get() = prefs.getBoolean(KEY_USE_CUSTOM_ACCENT, DEFAULT_USE_CUSTOM_ACCENT)
        set(value) = prefs.edit { putBoolean(KEY_USE_CUSTOM_ACCENT, value) }

    var selectedIconPack: String
        get() = prefs.getString(KEY_ICON_PACK, DEFAULT_ICON_PACK) ?: DEFAULT_ICON_PACK
        set(value) = prefs.edit { putString(KEY_ICON_PACK, value) }

    var iconPackLabel: String
        get() = prefs.getString(KEY_ICON_PACK_LABEL, "System Default") ?: "System Default"
        set(value) = prefs.edit { putString(KEY_ICON_PACK_LABEL, value) }

    var tapToOpen: Boolean
        get() = prefs.getBoolean(KEY_TAP_TO_OPEN, DEFAULT_TAP_TO_OPEN)
        set(value) = prefs.edit { putBoolean(KEY_TAP_TO_OPEN, value) }

    var doubleTapToOpen: Boolean
        get() = prefs.getBoolean(KEY_DOUBLE_TAP_TO_OPEN, DEFAULT_DOUBLE_TAP_TO_OPEN)
        set(value) = prefs.edit { putBoolean(KEY_DOUBLE_TAP_TO_OPEN, value) }

    var tripleTapToOpen: Boolean
        get() = prefs.getBoolean(KEY_TRIPLE_TAP_TO_OPEN, DEFAULT_TRIPLE_TAP_TO_OPEN)
        set(value) = prefs.edit { putBoolean(KEY_TRIPLE_TAP_TO_OPEN, value) }

    var tapAction: Int
        get() = prefs.getInt(KEY_TAP_ACTION, ACTION_NONE)
        set(value) = prefs.edit { putInt(KEY_TAP_ACTION, value) }

    var doubleTapAction: Int
        get() = prefs.getInt(KEY_DOUBLE_TAP_ACTION, ACTION_NONE)
        set(value) = prefs.edit { putInt(KEY_DOUBLE_TAP_ACTION, value) }

    var tripleTapAction: Int
        get() = prefs.getInt(KEY_TRIPLE_TAP_ACTION, ACTION_NONE)
        set(value) = prefs.edit { putInt(KEY_TRIPLE_TAP_ACTION, value) }

    var longPressAction: Int
        get() = prefs.getInt(KEY_LONG_PRESS_ACTION, ACTION_MOVE_HANDLE)
        set(value) = prefs.edit { putInt(KEY_LONG_PRESS_ACTION, value) }

    var gesturesEnabled: Boolean
        get() = prefs.getBoolean(KEY_GESTURES_ENABLED, DEFAULT_GESTURES)
        set(value) = prefs.edit { putBoolean(KEY_GESTURES_ENABLED, value) }

    var showInLandscape: Boolean
        get() = prefs.getBoolean(KEY_SHOW_IN_LANDSCAPE, DEFAULT_SHOW_LANDSCAPE)
        set(value) = prefs.edit { putBoolean(KEY_SHOW_IN_LANDSCAPE, value) }

    var iconShape: String
        get() = prefs.getString(KEY_ICON_SHAPE, DEFAULT_ICON_SHAPE) ?: DEFAULT_ICON_SHAPE
        set(value) = prefs.edit { putString(KEY_ICON_SHAPE, value) }

    var showPill: Boolean
        get() = prefs.getBoolean(KEY_SHOW_PILL, DEFAULT_SHOW_PILL)
        set(value) = prefs.edit { putBoolean(KEY_SHOW_PILL, value) }

    var hapticEnabled: Boolean
        get() = prefs.getBoolean(KEY_HAPTIC_ENABLED, DEFAULT_HAPTIC)
        set(value) = prefs.edit { putBoolean(KEY_HAPTIC_ENABLED, value) }

    var blurEnabled: Boolean
        get() = prefs.getBoolean(KEY_BLUR_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_BLUR_ENABLED, value) }

    var blurAmount: Int
        get() = prefs.getInt(KEY_BLUR_AMOUNT, DEFAULT_BLUR_AMOUNT)
        set(value) = prefs.edit { putInt(KEY_BLUR_AMOUNT, value) }

    var panelOpacity: Int
        get() = prefs.getInt(KEY_PANEL_OPACITY, DEFAULT_OPACITY)
        set(value) = prefs.edit { putInt(KEY_PANEL_OPACITY, value) }

    var handleHeight: Int
        get() = prefs.getInt(KEY_HANDLE_HEIGHT, DEFAULT_HANDLE_HEIGHT)
        set(value) = prefs.edit { putInt(KEY_HANDLE_HEIGHT, value) }

    var handleWidth: Int
        get() = prefs.getInt(KEY_HANDLE_WIDTH, DEFAULT_HANDLE_WIDTH)
        set(value) = prefs.edit { putInt(KEY_HANDLE_WIDTH, value) }

    var handleVerticalOffset: Int
        get() = prefs.getInt(KEY_HANDLE_OFFSET, DEFAULT_HANDLE_OFFSET)
        set(value) = prefs.edit { putInt(KEY_HANDLE_OFFSET, value) }

    var accentColor: String
        get() = prefs.getString(KEY_ACCENT_COLOR, DEFAULT_ACCENT_COLOR) ?: DEFAULT_ACCENT_COLOR
        set(value) = prefs.edit { putString(KEY_ACCENT_COLOR, value) }

    var panelColumns: Int
        get() = (prefs.getInt(KEY_PANEL_COLUMNS, DEFAULT_PANEL_COLS)).coerceIn(1, 2)
        set(value) = prefs.edit { putInt(KEY_PANEL_COLUMNS, value.coerceIn(1, 2)) }

    var uiTheme: String
        get() = prefs.getString(KEY_UI_THEME, DEFAULT_THEME) ?: DEFAULT_THEME
        set(value) = prefs.edit { putString(KEY_UI_THEME, value) }

    var panelCornerRadius: Int
        get() = prefs.getInt(KEY_PANEL_RADIUS, DEFAULT_PANEL_RADIUS)
        set(value) = prefs.edit { putInt(KEY_PANEL_RADIUS, value) }

    var panelBackgroundColor: String
        get() = prefs.getString(KEY_PANEL_BG_COLOR, DEFAULT_PANEL_BG) ?: DEFAULT_PANEL_BG
        set(value) = prefs.edit { putString(KEY_PANEL_BG_COLOR, value) }

    var hideBackground: Boolean
        get() = prefs.getBoolean(KEY_HIDE_BG, DEFAULT_HIDE_BG)
        set(value) = prefs.edit { putBoolean(KEY_HIDE_BG, value) }

    var pillWidth: Int
        get() = prefs.getInt(KEY_PILL_WIDTH, DEFAULT_PILL_WIDTH)
        set(value) = prefs.edit { putInt(KEY_PILL_WIDTH, value) }

    var showTools: Boolean
        get() = prefs.getBoolean(KEY_SHOW_TOOLS, DEFAULT_SHOW_TOOLS)
        set(value) = prefs.edit { putBoolean(KEY_SHOW_TOOLS, value) }

    var showLogs: Boolean
        get() = prefs.getBoolean(KEY_SHOW_LOGS, DEFAULT_SHOW_LOGS)
        set(value) = prefs.edit { putBoolean(KEY_SHOW_LOGS, value) }

    var animSpeed: Int
        get() = prefs.getInt(KEY_ANIM_SPEED, DEFAULT_ANIM_SPEED)
        set(value) = prefs.edit { putInt(KEY_ANIM_SPEED, value) }

    var pickerAnimType: String
        get() = prefs.getString(KEY_PICKER_ANIM_TYPE, DEFAULT_PICKER_ANIM) ?: DEFAULT_PICKER_ANIM
        set(value) = prefs.edit { putString(KEY_PICKER_ANIM_TYPE, value) }

    var setupCompleted: Boolean
        get() = prefs.getBoolean("setup_completed_v2", false)
        set(value) = prefs.edit { putBoolean("setup_completed_v2", value) }

    var toolsFolderMigrated: Boolean
        get() = prefs.getBoolean(KEY_TOOLS_FOLDER_MIGRATED, false)
        set(value) = prefs.edit { putBoolean(KEY_TOOLS_FOLDER_MIGRATED, value) }

    var slideBrightnessEnabled: Boolean
        get() = prefs.getBoolean(KEY_SLIDE_BRIGHTNESS_ENABLED, DEFAULT_SLIDE_BRIGHTNESS)
        set(value) = prefs.edit { putBoolean(KEY_SLIDE_BRIGHTNESS_ENABLED, value) }

    var slideVolumeEnabled: Boolean
        get() = prefs.getBoolean(KEY_SLIDE_VOLUME_ENABLED, DEFAULT_SLIDE_VOLUME)
        set(value) = prefs.edit { putBoolean(KEY_SLIDE_VOLUME_ENABLED, value) }

    var slideSensitivity: Int
        get() = prefs.getInt(KEY_SLIDE_SENSITIVITY, DEFAULT_SLIDE_SENSITIVITY)
        set(value) = prefs.edit { putInt(KEY_SLIDE_SENSITIVITY, value) }

    var swipeSensitivity: Int
        get() = prefs.getInt(KEY_SWIPE_SENSITIVITY, DEFAULT_SWIPE_SENSITIVITY)
        set(value) = prefs.edit { putInt(KEY_SWIPE_SENSITIVITY, value) }

    var useAutomationForGestures: Boolean
        get() = prefs.getBoolean(KEY_USE_AUTOMATION_FOR_GESTURES, false)
        set(value) = prefs.edit { putBoolean(KEY_USE_AUTOMATION_FOR_GESTURES, value) }

    var onlyOnHome: Boolean
        get() = prefs.getBoolean(KEY_ONLY_ON_HOME, false)
        set(value) = prefs.edit { putBoolean(KEY_ONLY_ON_HOME, value) }

    var favoriteAppPackage: String
        get() = prefs.getString(KEY_FAVORITE_APP, "") ?: ""
        set(value) = prefs.edit { putString(KEY_FAVORITE_APP, value) }

    var appLanguage: String
        get() = prefs.getString("Locale.Helper.Selected.Language", java.util.Locale.getDefault().language) ?: "en"
        set(value) = prefs.edit { putString("Locale.Helper.Selected.Language", value) }

    var notchGesturesEnabled: Boolean
        get() = prefs.getBoolean(KEY_NOTCH_GESTURES_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_NOTCH_GESTURES_ENABLED, value) }

    var notchTapAction: Int
        get() = prefs.getInt(KEY_NOTCH_TAP_ACTION, ACTION_NONE)
        set(value) = prefs.edit { putInt(KEY_NOTCH_TAP_ACTION, value) }

    var notchDoubleTapAction: Int
        get() = prefs.getInt(KEY_NOTCH_DOUBLE_TAP_ACTION, ACTION_NONE)
        set(value) = prefs.edit { putInt(KEY_NOTCH_DOUBLE_TAP_ACTION, value) }

    var notchTripleTapAction: Int
        get() = prefs.getInt(KEY_NOTCH_TRIPLE_TAP_ACTION, ACTION_NONE)
        set(value) = prefs.edit { putInt(KEY_NOTCH_TRIPLE_TAP_ACTION, value) }

    var notchLongPressAction: Int
        get() = prefs.getInt(KEY_NOTCH_LONG_PRESS_ACTION, ACTION_NONE)
        set(value) = prefs.edit { putInt(KEY_NOTCH_LONG_PRESS_ACTION, value) }

    var serviceEnabled: Boolean
        get() = prefs.getBoolean("service_enabled", true)
        // Audit M3: original setter used commit=false (async apply) while the
        // Quick-Settings Tile path uses commit=true. Asymmetric persist means a
        // process-kill between the write and the next read could leave the tile
        // and the actual service state out of sync. Use commit=true everywhere
        // — the write rate is tiny and the cost is negligible.
        set(value) = setServiceEnabled(value, true)

    fun setServiceEnabled(enabled: Boolean, commit: Boolean = false) {
        prefs.edit(commit = commit) {
            putBoolean("service_enabled", enabled)
        }
    }

    /**
     * Shared logic for starting/stopping the sidebar service.
     * Ensures UI consistency across App and Quick Tile by delegating the state
     * change to the main process service.
     */
    fun toggleService(context: Context, forcedState: Boolean? = null) {
        val intent = Intent(context, FloatingPanelService::class.java).apply {
            action = FloatingPanelService.Companion.ACTION_TOGGLE
            if (forcedState != null) {
                putExtra("target_state", forcedState)
            }
        }
        
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Generates a unique key representing the current visual state of icons.
     * Including this in Glide requests ensures everything refreshes automatically
     * when ANY appearance setting is changed.
     */
    val appearanceKey: String
        get() = "shape:$iconShape|pack:$selectedIconPack|theme:$uiTheme"

    // Audit D1 — DELIMITER = "," collision defense for the panel/games/whitelist lists.
    // Package names never contain commas, but intent-URI tokens emitted by
    // Intent.toUri(URI_INTENT_SCHEME) can carry raw unescaped commas inside array
    // extras (e.g. `i.tags=a,b,c;`). A bare `String.split(",")` would splice those
    // tokens in two on read and silently corrupt the sidebar. We escape the two
    // bytes that the format reserves ('\' and ',') on write, and reverse on read.
    private fun encodeDelim(s: String) = s.replace("\\", "\\\\").replace(",", "\\,")
    private fun decodeDelim(s: String) = s.replace("\\,", ",").replace("\\\\", "\\")

    // Audit R5-fix — state-machine tokenizer. The previous `raw.split(",")`
    // blindly severed the blob at every comma *including* commas inside the
    // escape sequence we just produced via encodeDelim — so `\,` got
    // destroyed before decodeDelim could ever see it. This walker honors the
    // backslash so only UN-escaped commas split, leaving `\,` and `\\`
    // intact inside each emitted token for decodeDelim to clean up.
    private fun splitDelim(raw: String): List<String> {
        val tokens = mutableListOf<String>()
        val sb = StringBuilder()
        var esc = false
        for (c in raw) {
            if (esc) { sb.append(c); esc = false }
            else when (c) {
                '\\' -> { sb.append(c); esc = true }
                ','  -> { tokens += sb.toString(); sb.clear() }
                else -> sb.append(c)
            }
        }
        tokens += sb.toString()
        return tokens
    }

    fun getPanelApps(): List<String> {
        val raw = prefs.getString(KEY_PANEL_APPS, "") ?: ""
        return if (raw.isBlank()) emptyList()
        else splitDelim(raw)
            .filter { it.isNotBlank() }
            .map { decodeDelim(it) }
            .distinct()
    }

    fun setPanelApps(identifiers: List<String>) {
        val unique = identifiers.filter { it.isNotBlank() }.distinct()
        prefs.edit { putString(KEY_PANEL_APPS, unique.joinToString(DELIMITER) { encodeDelim(it) }) }
    }

    fun addApp(identifier: String) {
        val current = getPanelApps().toMutableList()
        if (!current.contains(identifier)) {
            current.add(identifier)
            setPanelApps(current)
        }
    }

    fun removeApp(identifier: String) {
        val current = getPanelApps().toMutableList()
        current.remove(identifier)
        setPanelApps(current)
    }

    fun isInPanel(identifier: String): Boolean = getPanelApps().contains(identifier)

    // =====================================================================================
    //                                Custom Intents/URLs storage
    // =====================================================================================
    // Stored as a JSON array under KEY_CUSTOM_ITEMS. Each element is {id, isUrl, title, content}.
    // The corresponding sidebar identifier is "smartedge.custom.<id>" — added to `panelApps`
    // on creation and removed on delete. The sidebar resolves these via
    // AppRepository.getAppsForIdentifiers().

    /**
     * Read the persisted list of [CustomItem]s in insertion order.
     * Returns an empty list if the pref is missing, blank, or malformed.
     */
    fun getCustomItems(): List<CustomItem> {
        val raw = prefs.getString(KEY_CUSTOM_ITEMS, null) ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = org.json.JSONArray(raw)
            // Audit L1 — symmetric trim on the read path. saveEditingItem trims on
            // write, but legacy rows pre-dating this fix may still carry leading /
            // trailing whitespace which would defeat `app.intentUri.startsWith("intent:")`
            // downstream. Trim here too so all consumers see canonical content.
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                CustomItem(
                    id = id,
                    isUrl = o.optBoolean("isUrl", false),
                    title = o.optString("title", "").trim(),
                    content = o.optString("content", "").trim()
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Atomically overwrite the custom items list.
     * The list order is preserved as the user-specified display order.
     */
    fun setCustomItems(items: List<CustomItem>) {
        val arr = org.json.JSONArray()
        items.forEach { item ->
            val o = org.json.JSONObject()
            o.put("id", item.id)
            o.put("isUrl", item.isUrl)
            o.put("title", item.title)
            o.put("content", item.content)
            arr.put(o)
        }
        prefs.edit { putString(KEY_CUSTOM_ITEMS, arr.toString()) }
    }

    /** Add a new custom item. Caller is responsible for also adding the corresponding
     *  `smartedge.custom.<id>` identifier to the panel via [addApp].
     *
     *  Audit U3: validate that [item] id looks like an asset-safe identifier before
     *  persisting it. Picker-generated ids are [java.util.UUID.randomUUID] (36 chars
     *  with hyphens); reject anything else so a corrupt SharedPreferences blob or a
     *  future caller bug can't smuggle arbitrary bytes into the sidebar identifiers
     *  that show up in AppInfo lookups. */
    fun addCustomItem(item: CustomItem) {
        if (!UUID_PATTERN.matches(item.id)) return
        val current = getCustomItems().toMutableList()
        // Defensive: de-duplicate by id, then append.
        current.removeAll { it.id == item.id }
        current.add(item)
        setCustomItems(current)
    }

    /** Replace an existing custom item in-place (matched by id). */
    fun updateCustomItem(item: CustomItem) {
        val current = getCustomItems().toMutableList()
        val idx = current.indexOfFirst { it.id == item.id }
        if (idx >= 0) {
            current[idx] = item
            setCustomItems(current)
        } else {
            addCustomItem(item)
        }
    }

    /** Remove a custom item by id. Returns the removed item, or null if not found. */
    fun removeCustomItem(id: String): CustomItem? {
        val current = getCustomItems().toMutableList()
        val idx = current.indexOfFirst { it.id == id }
        if (idx < 0) return null
        val removed = current.removeAt(idx)
        setCustomItems(current)
        return removed
    }

    /** Reorder the list of custom items (must contain the same ids). */
    fun reorderCustomItems(items: List<CustomItem>) {
        setCustomItems(items)
    }

    /**
     * Audit L3 — after a custom-item reorder via the picker's drag handle,
     * `KEY_CUSTOM_ITEMS` is updated, but the sidebar order is governed by
     * `KEY_PANEL_APPS` and was completely unaware of the new positions.
     * The user reorders in the URLS tab, taps the sidebar, and sees the
     * pre-drag order — a silent UX bug.
     *
     * Strategy: rebuild `KEY_PANEL_APPS` keeping every non-custom entry in
     * its existing relative position, then append the custom entries in
     * the new drag order. Newly added items not yet pinned to the sidebar
     * are intentionally skipped — they aren't in the sidebar yet, so
     * they have nothing to reorder. Items removed from the custom list
     * also fall out cleanly because their `smartedge.custom.<id>` ids
     * are no longer in `customItems`.
     */
    fun resyncPanelAppsOrderFromCustomItems(customItems: List<CustomItem>) {
        val current = getPanelApps().toMutableList()
        val customIdsInSidebar = current.filter { it.startsWith(CUSTOM_ID_PREFIX) }.toSet()
        val nonCustom = current.filterNot { it.startsWith(CUSTOM_ID_PREFIX) }
        val orderedCustom = customItems.mapNotNull { item ->
            val id = CUSTOM_ID_PREFIX + item.id
            if (id in customIdsInSidebar) id else null
        }
        // setPanelApps takes List<String> and handles dedup + delimiter join internally.
        setPanelApps(nonCustom + orderedCustom)
    }

    var panelSide: String
        get() = prefs.getString(KEY_PANEL_SIDE, DEFAULT_SIDE) ?: DEFAULT_SIDE
        set(value) = prefs.edit { putString(KEY_PANEL_SIDE, value) }

    var autoStart: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START, DEFAULT_AUTO_START)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_START, value) }

    var currentForegroundPackage: String
        get() = prefs.getString("current_foreground", "") ?: ""
        set(value) = prefs.edit { putString("current_foreground", value) }

    fun getGameApps(): List<String> {
        val raw = prefs.getString(KEY_GAME_APPS, "") ?: ""
        return if (raw.isBlank()) emptyList()
        else splitDelim(raw)
            .filter { it.isNotBlank() }
            .map { decodeDelim(it) }
            .distinct()
    }

    fun setGameApps(packages: List<String>) {
        val uniquePackages = packages.filter { it.isNotBlank() }.distinct()
        prefs.edit { putString(KEY_GAME_APPS, uniquePackages.joinToString(DELIMITER) { encodeDelim(it) }) }
    }

    var autoHideInFullscreen: Boolean
        get() = prefs.getBoolean(KEY_AUTO_HIDE_FULLSCREEN, false)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_HIDE_FULLSCREEN, value) }

    var deliberateGestureInGames: Boolean
        get() = prefs.getBoolean(KEY_DELIBERATE_GESTURE_GAMES, true)
        set(value) = prefs.edit { putBoolean(KEY_DELIBERATE_GESTURE_GAMES, value) }

    fun getFullscreenWhitelist(): List<String> {
        val raw = prefs.getString(KEY_FULLSCREEN_WHITELIST, "") ?: ""
        return if (raw.isBlank()) emptyList()
        else splitDelim(raw)
            .filter { it.isNotBlank() }
            .map { decodeDelim(it) }
            .distinct()
    }

    fun setFullscreenWhitelist(packages: List<String>) {
        val unique = packages.filter { it.isNotBlank() }.distinct()
        prefs.edit { putString(KEY_FULLSCREEN_WHITELIST, unique.joinToString(DELIMITER) { encodeDelim(it) }) }
    }

    fun isWhitelistedFromAutoHide(packageName: String): Boolean {
        return getFullscreenWhitelist().contains(packageName)
    }
}
