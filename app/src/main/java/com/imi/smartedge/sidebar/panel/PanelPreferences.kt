package com.imi.smartedge.sidebar.panel

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import kotlin.math.roundToInt

/**
 * Manages the persistent list of apps pinned to the side panel,
 * and other panel settings, using SharedPreferences.
 */
class PanelPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        const val PREFS_NAME = "side_panel_prefs"

        /**
         * The foreground package, shared by every PanelPreferences instance.
         * The accessibility service writes it on every window change; the
         * panel service, EdgeHandleView and SidePanelView read it for
         * game-mode and auto-hide decisions. It used to be a per-instance
         * `var`, so the writer's instance and the readers' instances held
         * different values and game-mode / auto-hide never fired. Runtime
         * state only — deliberately not persisted (a stale value after a
         * reboot would hide the pill in the wrong app until the first
         * window event).
         */
        @Volatile
        var sharedForegroundPackage: String = ""

        /**
         * The selected app language, stored under this key in the same
         * `side_panel_prefs` file. Shared by PanelPreferences.appLanguage
         * and LocaleHelper — it used to be a string literal in three places
         * ("Locale.Helper.Selected.Language"), and the copy in
         * PanelPreferences.appLanguage was not the same key the
         * export/import used, so a backup silently dropped the language.
         */
        const val KEY_APP_LANGUAGE = "Locale.Helper.Selected.Language"

        /**
         * Last time (uptime ms) the WRITE_SETTINGS prompt was shown by a
         * brightness path. Shared across instances so both brightness paths
         * (FloatingPanelService and SidePanelView) throttle together: without
         * this, every brightness tick while the permission is missing
         * re-launches the settings screen. Runtime state only.
         */
        @Volatile
        var lastWriteSettingsPromptMs: Long = 0L

        /** Minimum gap between WRITE_SETTINGS prompts from the brightness paths. */
        const val WRITE_SETTINGS_PROMPT_COOLDOWN_MS = 60_000L
        private const val KEY_PANEL_APPS = "panel_apps"
        private const val KEY_PANEL_SEEDED = "panel_seeded"
        private const val KEY_SHORTCUT_TOKEN = "shortcut_token"
        private const val KEY_PANEL_LABELS = "panel_item_labels"
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
        private const val KEY_SHOW_BLACK_SCREEN_TOOL = "show_black_screen_tool"
        private const val KEY_SHOW_LOCK_SCREEN_TOOL = "show_lock_screen_tool"
        private const val KEY_SHOW_CONTENT_PICKER_TOOL = "show_content_picker_tool"
        private const val KEY_ENABLE_DEVICE_ADMIN = "enable_device_admin_for_black_screen"
        private const val KEY_HOME_BUTTON_STYLE = "home_button_style"
        private const val KEY_SCALE_FACTOR = "scale_factor"
        private const val KEY_PANEL_MAX_HEIGHT = "panel_max_height"
        private const val KEY_APPS_RECENT_SPLIT_PERCENT = "apps_recent_split_percent"
        private const val KEY_PICKER_MAX_HEIGHT = "picker_max_height"
        private const val KEY_SHOW_NOTIFICATION_APPS = "show_notification_apps"
        private const val KEY_REMEMBER_SCROLL = "remember_scroll"
        // --- Recent apps (sidebar "Recent" section) ---
        private const val KEY_RECENT_ENABLED = "recent_enabled"
        private const val KEY_RECENT_HISTORY_SIZE = "recent_history_size"
        private const val KEY_RECENT_MAX_COUNT = "recent_max_count"
        private const val KEY_RECENT_HISTORY = "recent_history_json"
        private const val KEY_RECENT_LAST_PACKAGE = "recent_last_package"
        private const val KEY_RECENT_LAST_TIME = "recent_last_time"
        private const val KEY_RECENT_LAST_SOURCE = "recent_last_source"
        private const val KEY_AUTO_SHOW_KEYBOARD = "auto_show_keyboard"
        private const val KEY_SIDEBAR_SCROLL = "last_sidebar_scroll"
        private const val KEY_SIDEBAR_SCROLL_POS = "last_sidebar_scroll_pos"
        private const val KEY_SIDEBAR_SCROLL_TOP = "last_sidebar_scroll_top"
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
        private const val KEY_FAVORITE_APP_PREFIX = "favorite_app_"

        private const val KEY_NOTCH_GESTURES_ENABLED = "notch_gestures_enabled"
        const val KEY_NOTCH_TAP_ACTION = "notch_tap_action"
        const val KEY_NOTCH_DOUBLE_TAP_ACTION = "notch_double_tap_action"
        const val KEY_NOTCH_TRIPLE_TAP_ACTION = "notch_triple_tap_action"
        const val KEY_NOTCH_LONG_PRESS_ACTION = "notch_long_press_action"

        const val KEY_TAP_ACTION = "tap_action"
        const val KEY_DOUBLE_TAP_ACTION = "double_tap_action"
        const val KEY_TRIPLE_TAP_ACTION = "triple_tap_action"
        const val KEY_LONG_PRESS_ACTION = "long_press_action"

        /** Gesture slots that can each have their own favourite app,
         *  keyed by their action pref key. NB: this must stay AFTER the
         *  KEY_*_ACTION consts above — a val initializer may not reference
         *  a const declared later in the same companion (kotlinc reports
         *  "variable must be initialized"). */
        val FAVORITE_SLOT_KEYS = listOf(
            KEY_TAP_ACTION, KEY_DOUBLE_TAP_ACTION, KEY_TRIPLE_TAP_ACTION, KEY_LONG_PRESS_ACTION,
            KEY_NOTCH_TAP_ACTION, KEY_NOTCH_DOUBLE_TAP_ACTION, KEY_NOTCH_TRIPLE_TAP_ACTION, KEY_NOTCH_LONG_PRESS_ACTION
        )

        // --- Custom Intents/URLs (user-added sidebar items) ---
        private const val KEY_CUSTOM_ITEMS = "custom_items_json"
        private const val KEY_TEXT_ACTIONS = "text_actions_json"
        const val CUSTOM_ID_PREFIX = "smartedge.custom."

        /** Maximum number of pinned sidebar entries (apps, activities, shortcuts, custom). */
        const val MAX_PANEL_APPS = 128

        /**
         * Maximum import size in chars (512 KiB). The cap exists so a
         * multi-MB backup is never read fully into RAM; the caller must
         * bound its read to this value (see MiscellaneousSettingsActivity).
         */
        const val IMPORT_MAX_CHARS = 524288

        /**
         * Shared lock for panel-list read-modify-write (setPanelApps /
         * addApp / removeApp). Must be a companion (static) lock: callers
         * each construct their own PanelPreferences instance, so an
         * instance lock would not serialize anything across them.
         */
        @JvmStatic
        val PANEL_APPS_LOCK = Any()

        // Audit U3: UUID v4 `xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx` (8-4-4-4-12 hex
        // with fixed-position hyphens). Anything else is rejected on addCustomItem
        // so corrupt prefs or future caller bugs can't smuggle arbitrary bytes
        // into sidebar identifiers.
        // Custom-item id pattern lives in CustomItemJson (single source of
        // truth for the UUID shape, shared by the JSON decode path).
        private val UUID_PATTERN get() = CustomItemJson.UUID_PATTERN

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
        const val DEFAULT_SHOW_CONTENT_PICKER_TOOL = false
        const val DEFAULT_SLIDE_BRIGHTNESS = true
        const val DEFAULT_SLIDE_VOLUME = true
        const val DEFAULT_SLIDE_SENSITIVITY = 100
        const        val DEFAULT_SWIPE_SENSITIVITY = 100
        const val DEFAULT_RECENT_ENABLED = true
        const val DEFAULT_RECENT_HISTORY_SIZE = 128
        const val DEFAULT_RECENT_MAX_COUNT = 8
        /** Default split of the shared apps/recent height budget: 50% apps, 50% recents. */
        const val DEFAULT_APPS_RECENT_SPLIT_PERCENT = 50
        /** Drag limits: neither list may be squeezed below 20% of the shared budget. */
        const val MIN_APPS_RECENT_SPLIT_PERCENT = 20
        const val MAX_APPS_RECENT_SPLIT_PERCENT = 80
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
            KEY_PANEL_APPS to getPanelApps().take(MAX_PANEL_APPS).joinToString(DELIMITER) { encodeDelim(it) },
            KEY_PANEL_LABELS to (prefs.getString(KEY_PANEL_LABELS, null) ?: ""),
            KEY_CUSTOM_ITEMS to (prefs.getString(KEY_CUSTOM_ITEMS, null) ?: ""),
            KEY_TEXT_ACTIONS to (prefs.getString(KEY_TEXT_ACTIONS, null) ?: ""),
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
            KEY_FULLSCREEN_WHITELIST to (prefs.getString(KEY_FULLSCREEN_WHITELIST, null) ?: ""),
            KEY_FAVORITE_APP to favoriteAppPackage,
            KEY_APP_LANGUAGE to appLanguage
        )
        strings.forEach { (k, v) -> obj.put(k, v) }
        // Per-slot favourites (only when set): a slot without its own key
        // falls back to the legacy global on import.
        for (key in FAVORITE_SLOT_KEYS) {
            prefs.getString(KEY_FAVORITE_APP_PREFIX + key, null)?.let {
                obj.put(KEY_FAVORITE_APP_PREFIX + key, it)
            }
        }
        // Never export a blank text-actions blob: when the presets were
        // never seeded the key is absent, and exporting "" would make the
        // next import write a present-but-blank key — after which
        // getTextActions() returns an empty list forever instead of
        // re-seeding the built-in presets.
        if (!prefs.contains(KEY_TEXT_ACTIONS)) {
            obj.remove(KEY_TEXT_ACTIONS)
        }
        // Never export a language the user never chose: appLanguage falls
        // back to the device default, so exporting it unconditionally pins
        // the source device's language into the backup, and importing it
        // then forces that language on the target device.
        if (!prefs.contains(KEY_APP_LANGUAGE)) {
            obj.remove(KEY_APP_LANGUAGE)
        }

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
            KEY_THEME_MODE to themeMode,
            KEY_TAP_ACTION to tapAction,
            KEY_DOUBLE_TAP_ACTION to doubleTapAction,
            KEY_TRIPLE_TAP_ACTION to tripleTapAction,
            KEY_LONG_PRESS_ACTION to longPressAction,
            KEY_NOTCH_TAP_ACTION to notchTapAction,
            KEY_NOTCH_DOUBLE_TAP_ACTION to notchDoubleTapAction,
            KEY_NOTCH_TRIPLE_TAP_ACTION to notchTripleTapAction,
            KEY_NOTCH_LONG_PRESS_ACTION to notchLongPressAction,
            KEY_RECENT_HISTORY_SIZE to recentHistorySize,
            KEY_RECENT_MAX_COUNT to recentMaxCount,
            KEY_APPS_RECENT_SPLIT_PERCENT to appsRecentSplitPercent
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
            KEY_SHOW_BLACK_SCREEN_TOOL to showBlackScreenTool,
            KEY_SHOW_LOCK_SCREEN_TOOL to showLockScreenTool,
            KEY_SHOW_CONTENT_PICKER_TOOL to showContentPickerTool,
            KEY_ENABLE_DEVICE_ADMIN to enableDeviceAdmin,
            KEY_SLIDE_BRIGHTNESS_ENABLED to slideBrightnessEnabled,
            KEY_SLIDE_VOLUME_ENABLED to slideVolumeEnabled,
            KEY_SHOW_NOTIFICATION_APPS to showNotificationApps,
            KEY_REMEMBER_SCROLL to rememberScroll,
            KEY_AUTO_SHOW_KEYBOARD to autoShowKeyboard,
            KEY_SHOW_SCREENSHOT_TOOL to showScreenshotTool,
            KEY_SHOW_TOOLS_PANEL_BUTTON to showToolsPanelButton,
            KEY_ONLY_ON_HOME to onlyOnHome,
            KEY_AUTO_HIDE_FULLSCREEN to autoHideInFullscreen,
            KEY_DELIBERATE_GESTURE_GAMES to deliberateGestureInGames,
            KEY_NOTCH_GESTURES_ENABLED to notchGesturesEnabled,
            KEY_USE_AUTOMATION_FOR_GESTURES to useAutomationForGestures,
            KEY_RECENT_ENABLED to recentEnabled
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
        if (json.length > IMPORT_MAX_CHARS) return false
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
            // Safe string import: only write when key exists, is non-null, and is a String.
            // Avoid JSONObject.optString(key, null) which can coerce non-strings.
            fun readString(key: String): String? {
                if (!obj.has(key) || obj.isNull(key)) return null
                return try { obj.getString(key) } catch (_: Exception) { null }
            }
            // Enum strings: only accept values from the companion
            // whitelist. An unknown panel_side silently becomes left, an
            // unknown theme falls through the branches — a hand-edited
            // backup must not be able to store a value no code path
            // understands.
            fun readEnum(key: String, allowed: Set<String>): String? {
                val s = readString(key) ?: return null
                return s.takeIf { it in allowed }
            }
            // Colour strings: only accept values Color.parseColor can parse.
            // An unparseable colour would crash AppearanceSettingsActivity
            // on open (it calls Color.parseColor in loadCurrentSettings).
            fun readColor(key: String): String? {
                val s = readString(key) ?: return null
                return try {
                    android.graphics.Color.parseColor(s)
                    s
                } catch (_: Exception) {
                    null
                }
            }
            prefs.edit {
                // Clamp every imported int to the range its settings control
                // accepts. Without this, a hand-edited or corrupt backup can
                // persist e.g. opacity 500 or scaleFactor 0, and the Material
                // Slider in the settings screen throws IllegalArgumentException
                // on open (its value must be within [valueFrom, valueTo]).
                // Ranges mirror the live settings layouts.
                fun putIntClamped(key: String, current: Int, min: Int, max: Int) {
                    if (obj.has(key) && !obj.isNull(key)) {
                        putInt(key, obj.optInt(key, current).coerceIn(min, max))
                    }
                }

                // Strings
                readString(KEY_PANEL_APPS)?.let { putString(KEY_PANEL_APPS, it) }
                readString(KEY_PANEL_LABELS)?.let { putString(KEY_PANEL_LABELS, it) }
                readString(KEY_CUSTOM_ITEMS)?.let { putString(KEY_CUSTOM_ITEMS, it) }
                // A blank blob (written by old exports when the presets were
                // never seeded) must not become a present-but-blank key:
                // remove it so the next getTextActions() re-seeds the
                // built-in presets instead of returning empty forever.
                readString(KEY_TEXT_ACTIONS)?.let {
                    if (it.isBlank()) remove(KEY_TEXT_ACTIONS) else putString(KEY_TEXT_ACTIONS, it)
                }
                readString(KEY_GAME_APPS)?.let { putString(KEY_GAME_APPS, it) }
                readEnum(KEY_PANEL_SIDE, setOf(SIDE_LEFT, SIDE_RIGHT))?.let { putString(KEY_PANEL_SIDE, it) }
                readColor(KEY_ACCENT_COLOR)?.let { putString(KEY_ACCENT_COLOR, it) }
                readColor(KEY_PANEL_BG_COLOR)?.let { putString(KEY_PANEL_BG_COLOR, it) }
                readEnum(KEY_UI_THEME, setOf(THEME_ORIGIN, THEME_HYPEROS, THEME_REALME, THEME_RICH))?.let { putString(KEY_UI_THEME, it) }
                readEnum(KEY_ICON_SHAPE, setOf(SHAPE_SYSTEM, SHAPE_CIRCLE, SHAPE_SQUIRCLE, SHAPE_SQUARE, SHAPE_ROUNDED))?.let { putString(KEY_ICON_SHAPE, it) }
                readColor(KEY_PILL_COLOR)?.let { putString(KEY_PILL_COLOR, it) }
                readString(KEY_ICON_PACK)?.let { putString(KEY_ICON_PACK, it) }
                readString(KEY_ICON_PACK_LABEL)?.let { putString(KEY_ICON_PACK_LABEL, it) }
                readEnum(KEY_HOME_BUTTON_STYLE, setOf(STYLE_POWER, STYLE_CLASSIC))?.let { putString(KEY_HOME_BUTTON_STYLE, it) }
                readEnum(KEY_PICKER_ANIM_TYPE, setOf(ANIM_TYPE_SLIDE, ANIM_TYPE_POPUP))?.let { putString(KEY_PICKER_ANIM_TYPE, it) }
                readString(KEY_FULLSCREEN_WHITELIST)?.let { putString(KEY_FULLSCREEN_WHITELIST, it) }
                readString(KEY_FAVORITE_APP)?.let { putString(KEY_FAVORITE_APP, it) }
                for (key in FAVORITE_SLOT_KEYS) {
                    readString(KEY_FAVORITE_APP_PREFIX + key)?.let {
                        putString(KEY_FAVORITE_APP_PREFIX + key, it)
                    }
                }
                readString(KEY_APP_LANGUAGE)?.let { putString(KEY_APP_LANGUAGE, it) }

                // Ints (clamped to each control's range)
                putIntClamped(KEY_PANEL_OPACITY, panelOpacity, 10, 100)
                putIntClamped(KEY_HANDLE_HEIGHT, handleHeight, 50, 400)
                putIntClamped(KEY_HANDLE_WIDTH, handleWidth, 5, 50)
                putIntClamped(KEY_HANDLE_OFFSET, handleVerticalOffset, -500, 500)
                putIntClamped(KEY_PANEL_COLUMNS, panelColumns, 1, 2)
                putIntClamped(KEY_PANEL_RADIUS, panelCornerRadius, 0, 60)
                putIntClamped(KEY_PILL_WIDTH, pillWidth, 1, 10)
                putIntClamped(KEY_BLUR_AMOUNT, blurAmount, 5, 50)
                putIntClamped(KEY_ANIM_SPEED, animSpeed, 0, 1000)
                putIntClamped(KEY_PICKER_GAP, pickerGap, 0, 100)
                putIntClamped(KEY_PANEL_MAX_HEIGHT, panelMaxHeight, 200, 800)
                putIntClamped(KEY_PICKER_MAX_HEIGHT, pickerMaxHeight, 300, 800)
                putIntClamped(KEY_APPS_RECENT_SPLIT_PERCENT, appsRecentSplitPercent, MIN_APPS_RECENT_SPLIT_PERCENT, MAX_APPS_RECENT_SPLIT_PERCENT)
                putIntClamped(KEY_SLIDE_SENSITIVITY, slideSensitivity, 1, 100)
                putIntClamped(KEY_SWIPE_SENSITIVITY, swipeSensitivity, 1, 100)
                putIntClamped(KEY_THEME_MODE, themeMode, MODE_SYSTEM, MODE_DARK)
                putIntClamped(KEY_TAP_ACTION, tapAction, ACTION_NONE, ACTION_MOVE_HANDLE)
                putIntClamped(KEY_DOUBLE_TAP_ACTION, doubleTapAction, ACTION_NONE, ACTION_MOVE_HANDLE)
                putIntClamped(KEY_TRIPLE_TAP_ACTION, tripleTapAction, ACTION_NONE, ACTION_MOVE_HANDLE)
                putIntClamped(KEY_LONG_PRESS_ACTION, longPressAction, ACTION_NONE, ACTION_MOVE_HANDLE)
                putIntClamped(KEY_NOTCH_TAP_ACTION, notchTapAction, ACTION_NONE, ACTION_MOVE_HANDLE)
                putIntClamped(KEY_NOTCH_DOUBLE_TAP_ACTION, notchDoubleTapAction, ACTION_NONE, ACTION_MOVE_HANDLE)
                putIntClamped(KEY_NOTCH_TRIPLE_TAP_ACTION, notchTripleTapAction, ACTION_NONE, ACTION_MOVE_HANDLE)
                putIntClamped(KEY_NOTCH_LONG_PRESS_ACTION, notchLongPressAction, ACTION_NONE, ACTION_MOVE_HANDLE)
                putIntClamped(KEY_RECENT_HISTORY_SIZE, recentHistorySize, 64, 256)
                putIntClamped(KEY_RECENT_MAX_COUNT, recentMaxCount, 4, 20)

                // Float
                // Snap to the slider's 0.1 grid: the import clamp alone is
                // not enough — a hand-edited 1.05 passes the 0.8..2.0 clamp
                // but makes the Material Slider throw ("value must be
                // valueFrom plus a multiple of stepSize") when the
                // Appearance screen sets it.
                if (obj.has(KEY_SCALE_FACTOR) && !obj.isNull(KEY_SCALE_FACTOR)) {
                    putFloat(KEY_SCALE_FACTOR, snapScaleFactor(obj.optDouble(KEY_SCALE_FACTOR, scaleFactor.toDouble()).toFloat()))
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
                putBoolIfPresent(KEY_SHOW_BLACK_SCREEN_TOOL, showBlackScreenTool)
            putBoolIfPresent(KEY_SHOW_LOCK_SCREEN_TOOL, showLockScreenTool)
                putBoolIfPresent(KEY_SHOW_CONTENT_PICKER_TOOL, showContentPickerTool)
                putBoolIfPresent(KEY_ENABLE_DEVICE_ADMIN, enableDeviceAdmin)
                putBoolIfPresent(KEY_SLIDE_BRIGHTNESS_ENABLED, slideBrightnessEnabled)
                putBoolIfPresent(KEY_SLIDE_VOLUME_ENABLED, slideVolumeEnabled)
                putBoolIfPresent(KEY_SHOW_NOTIFICATION_APPS, showNotificationApps)
                putBoolIfPresent(KEY_REMEMBER_SCROLL, rememberScroll)
                putBoolIfPresent(KEY_AUTO_SHOW_KEYBOARD, autoShowKeyboard)
                putBoolIfPresent(KEY_SHOW_SCREENSHOT_TOOL, showScreenshotTool)
                putBoolIfPresent(KEY_SHOW_TOOLS_PANEL_BUTTON, showToolsPanelButton)
                putBoolIfPresent(KEY_ONLY_ON_HOME, onlyOnHome)
                putBoolIfPresent(KEY_AUTO_HIDE_FULLSCREEN, autoHideInFullscreen)
                putBoolIfPresent(KEY_DELIBERATE_GESTURE_GAMES, deliberateGestureInGames)
                putBoolIfPresent(KEY_NOTCH_GESTURES_ENABLED, notchGesturesEnabled)
                putBoolIfPresent(KEY_USE_AUTOMATION_FOR_GESTURES, useAutomationForGestures)
                putBoolIfPresent(KEY_RECENT_ENABLED, recentEnabled)
            }
            // Enforce sidebar cap after restore (also drops excess custom pins).
            val capped = getPanelApps()
            if (capped.size > MAX_PANEL_APPS) {
                setPanelApps(capped.take(MAX_PANEL_APPS))
            } else {
                // Re-run through setPanelApps so delimiter encoding stays canonical.
                setPanelApps(capped)
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
            // "none" has no label to persist: the display sites resolve
            // the default string at display time instead.
            remove(KEY_ICON_PACK_LABEL)
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
            putBoolean(KEY_SHOW_BLACK_SCREEN_TOOL, true)
            putBoolean(KEY_SHOW_LOCK_SCREEN_TOOL, true)
            putBoolean(KEY_SHOW_CONTENT_PICKER_TOOL, DEFAULT_SHOW_CONTENT_PICKER_TOOL)
            putBoolean(KEY_ENABLE_DEVICE_ADMIN, false)
            putBoolean(KEY_SLIDE_BRIGHTNESS_ENABLED, DEFAULT_SLIDE_BRIGHTNESS)
            putBoolean(KEY_SLIDE_VOLUME_ENABLED, DEFAULT_SLIDE_VOLUME)
            putInt(KEY_SLIDE_SENSITIVITY, DEFAULT_SLIDE_SENSITIVITY)
            putInt(KEY_SWIPE_SENSITIVITY, DEFAULT_SWIPE_SENSITIVITY)
            putBoolean(KEY_USE_AUTOMATION_FOR_GESTURES, false)
            putString(KEY_HOME_BUTTON_STYLE, DEFAULT_HOME_BUTTON_STYLE)
            putInt(KEY_THEME_MODE, DEFAULT_THEME_MODE)
            putFloat(KEY_SCALE_FACTOR, 1.0f)
            putInt(KEY_PANEL_MAX_HEIGHT, 350)
            putInt(KEY_PICKER_MAX_HEIGHT, 450)
            putInt(KEY_APPS_RECENT_SPLIT_PERCENT, DEFAULT_APPS_RECENT_SPLIT_PERCENT)
            putBoolean(KEY_SHOW_NOTIFICATION_APPS, false)
            putBoolean(KEY_REMEMBER_SCROLL, false)
            putBoolean(KEY_AUTO_SHOW_KEYBOARD, false)
            putString(KEY_PANEL_APPS, "")
            putBoolean(KEY_PANEL_SEEDED, false)
            putString(KEY_PANEL_LABELS, "{}")
            putString(KEY_CUSTOM_ITEMS, "[]")
            remove(KEY_TEXT_ACTIONS) // next getTextActions() re-seeds the built-in presets
            putString(KEY_GAME_APPS, "")
            putBoolean(KEY_AUTO_HIDE_FULLSCREEN, false)
            putString(KEY_FULLSCREEN_WHITELIST, "")
            putBoolean(KEY_DELIBERATE_GESTURE_GAMES, true)
            putBoolean(KEY_ONLY_ON_HOME, false)
            putString(KEY_FAVORITE_APP, "")
            putBoolean(KEY_NOTCH_GESTURES_ENABLED, false)
            putBoolean(KEY_RECENT_ENABLED, DEFAULT_RECENT_ENABLED)
            putInt(KEY_RECENT_HISTORY_SIZE, DEFAULT_RECENT_HISTORY_SIZE)
            putInt(KEY_RECENT_MAX_COUNT, DEFAULT_RECENT_MAX_COUNT)
            remove(KEY_RECENT_HISTORY)
            remove(KEY_RECENT_LAST_PACKAGE)
            remove(KEY_RECENT_LAST_TIME)
            remove(KEY_RECENT_LAST_SOURCE)
            remove(KEY_NOTCH_TAP_ACTION)
            remove(KEY_NOTCH_DOUBLE_TAP_ACTION)
            remove(KEY_NOTCH_TRIPLE_TAP_ACTION)
            remove(KEY_NOTCH_LONG_PRESS_ACTION)
            putInt(KEY_SIDEBAR_SCROLL, 0)
            putInt(KEY_SIDEBAR_SCROLL_POS, 0)
            putInt(KEY_SIDEBAR_SCROLL_TOP, 0)
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

    // ── Recent apps (sidebar "Recent" section) ───────────────────────────────
    var recentEnabled: Boolean
        get() = prefs.getBoolean(KEY_RECENT_ENABLED, DEFAULT_RECENT_ENABLED)
        set(value) = prefs.edit { putBoolean(KEY_RECENT_ENABLED, value) }

    /** How many launch events to keep in history: 64 / 128 / 256. */
    var recentHistorySize: Int
        // One range everywhere (64..256): the getter/import used to allow
        // 1..1024 while recordLaunch enforced 64..256, so a stored 32 or
        // 1024 was shown one way and enforced another.
        get() = prefs.getInt(KEY_RECENT_HISTORY_SIZE, DEFAULT_RECENT_HISTORY_SIZE).coerceIn(64, 256)
        set(value) = prefs.edit { putInt(KEY_RECENT_HISTORY_SIZE, value.coerceIn(64, 256)) }

    /** How many top apps to show in the Recent section: 4..20. */
    var recentMaxCount: Int
        get() = prefs.getInt(KEY_RECENT_MAX_COUNT, DEFAULT_RECENT_MAX_COUNT).coerceIn(4, 20)
        set(value) = prefs.edit { putInt(KEY_RECENT_MAX_COUNT, value) }

    /** JSON array of package names (bounded FIFO history). Runtime data. */
    var recentHistoryJson: String
        get() = prefs.getString(KEY_RECENT_HISTORY, "[]") ?: "[]"
        set(value) = prefs.edit { putString(KEY_RECENT_HISTORY, value) }

    /** Debounce helpers so one launch isn't recorded twice. Runtime data. */
    var recentLastPackage: String
        get() = prefs.getString(KEY_RECENT_LAST_PACKAGE, "") ?: ""
        set(value) = prefs.edit { putString(KEY_RECENT_LAST_PACKAGE, value) }

    var recentLastTime: Long
        get() = prefs.getLong(KEY_RECENT_LAST_TIME, 0L)
        set(value) = prefs.edit { putLong(KEY_RECENT_LAST_TIME, value) }

    /** Which source recorded the last launch ("click" / "a11y"). Runtime data. */
    var recentLastSource: String
        get() = prefs.getString(KEY_RECENT_LAST_SOURCE, "") ?: ""
        set(value) = prefs.edit { putString(KEY_RECENT_LAST_SOURCE, value) }

    /**
     * Persist a recorded launch atomically in a single SharedPreferences
     * edit: history JSON + last package/time/source together. The old
     * path wrote each of the four values in its own `prefs.edit { }`
     * (four async disk-write enqueues per launch, on the accessibility
     * thread), so a kill between writes left history and debounce state
     * inconsistent. Use this from [RecentAppsManager.recordLaunch].
     */
    fun saveRecentLaunch(history: List<String>, pkg: String, time: Long, source: String) {
        val arr = org.json.JSONArray()
        for (item in history) arr.put(item)
        prefs.edit {
            putString(KEY_RECENT_HISTORY, arr.toString())
            putString(KEY_RECENT_LAST_PACKAGE, pkg)
            putLong(KEY_RECENT_LAST_TIME, time)
            putString(KEY_RECENT_LAST_SOURCE, source)
        }
    }

    var lastSidebarScroll: Int
        get() = prefs.getInt(KEY_SIDEBAR_SCROLL, 0)
        set(value) = prefs.edit { putInt(KEY_SIDEBAR_SCROLL, value) }

    /**
     * Remember-scroll v2: first visible adapter position + its top offset.
     * More robust than a raw pixel offset — RecyclerView.scrollTo() bypasses
     * the LayoutManager, and pixel offsets go stale when the list content
     * changes between save and restore.
     */
    var lastSidebarScrollPos: Int
        get() = prefs.getInt(KEY_SIDEBAR_SCROLL_POS, 0)
        set(value) = prefs.edit { putInt(KEY_SIDEBAR_SCROLL_POS, value) }

    var lastSidebarScrollTop: Int
        get() = prefs.getInt(KEY_SIDEBAR_SCROLL_TOP, 0)
        set(value) = prefs.edit { putInt(KEY_SIDEBAR_SCROLL_TOP, value) }

    var lastPickerScroll: Int
        get() = prefs.getInt(KEY_PICKER_SCROLL, 0)
        set(value) = prefs.edit { putInt(KEY_PICKER_SCROLL, value) }

    var showNotificationApps: Boolean
        get() = prefs.getBoolean(KEY_SHOW_NOTIFICATION_APPS, false)
        set(value) = prefs.edit { putBoolean(KEY_SHOW_NOTIFICATION_APPS, value) }

    var panelMaxHeight: Int
        get() = prefs.getInt(KEY_PANEL_MAX_HEIGHT, 350).coerceIn(200, 800)
        set(value) = prefs.edit { putInt(KEY_PANEL_MAX_HEIGHT, value) }

    /**
     * Percent of the shared apps+recents height budget given to the sidebar
     * app list; the recent list gets the remainder (100 - this value).
     * Default 50/50. Both getter and setter clamp to
     * [MIN_APPS_RECENT_SPLIT_PERCENT]..[MAX_APPS_RECENT_SPLIT_PERCENT] so a
     * corrupt / hand-edited value can never collapse either list to zero.
     */
    var appsRecentSplitPercent: Int
        get() = prefs.getInt(KEY_APPS_RECENT_SPLIT_PERCENT, DEFAULT_APPS_RECENT_SPLIT_PERCENT)
            .coerceIn(MIN_APPS_RECENT_SPLIT_PERCENT, MAX_APPS_RECENT_SPLIT_PERCENT)
        set(value) = prefs.edit {
            putInt(
                KEY_APPS_RECENT_SPLIT_PERCENT,
                value.coerceIn(MIN_APPS_RECENT_SPLIT_PERCENT, MAX_APPS_RECENT_SPLIT_PERCENT)
            )
        }

    var pickerMaxHeight: Int
        get() = prefs.getInt(KEY_PICKER_MAX_HEIGHT, 450).coerceIn(300, 800)
        set(value) = prefs.edit { putInt(KEY_PICKER_MAX_HEIGHT, value) }

    var pillColor: String
        get() = prefs.getString(KEY_PILL_COLOR, DEFAULT_PILL_COLOR) ?: DEFAULT_PILL_COLOR
        set(value) = prefs.edit { putString(KEY_PILL_COLOR, value) }

    /**
     * Snap a scale value to the Appearance slider's 0.1 grid and clamp it
     * to the slider's 0.8..2.0 range. The Material Slider throws
     * IllegalStateException when set to an off-grid value, so every path
     * that can store a scale (import, getter, setter) goes through this.
     */
    private fun snapScaleFactor(value: Float): Float =
        ((value * 10).roundToInt() / 10f).coerceIn(0.8f, 2.0f)

    var scaleFactor: Float
        // Snapped to the Appearance slider's 0.1 grid (see snapScaleFactor):
        // a stored off-grid value would crash the slider on open.
        get() = snapScaleFactor(prefs.getFloat(KEY_SCALE_FACTOR, 1.0f))
        set(value) = prefs.edit { putFloat(KEY_SCALE_FACTOR, snapScaleFactor(value)) }

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

    var showBlackScreenTool: Boolean
        get() = prefs.getBoolean(KEY_SHOW_BLACK_SCREEN_TOOL, true)
        set(value) = prefs.edit { putBoolean(KEY_SHOW_BLACK_SCREEN_TOOL, value) }

    var showLockScreenTool: Boolean
        get() = prefs.getBoolean(KEY_SHOW_LOCK_SCREEN_TOOL, true)
        set(value) = prefs.edit { putBoolean(KEY_SHOW_LOCK_SCREEN_TOOL, value) }

    var showContentPickerTool: Boolean
        get() = prefs.getBoolean(KEY_SHOW_CONTENT_PICKER_TOOL, DEFAULT_SHOW_CONTENT_PICKER_TOOL)
        set(value) = prefs.edit { putBoolean(KEY_SHOW_CONTENT_PICKER_TOOL, value) }

    /** Enable Device Admin so the Lock Screen tool can lock instantly
     *  (lockNow()) and Black Screen can turn the display off. The
     *  no-lock-screen behaviour (setKeyguardDisabled) is device-owner-only. */
    var enableDeviceAdmin: Boolean
        get() = prefs.getBoolean(KEY_ENABLE_DEVICE_ADMIN, false)
        set(value) = prefs.edit { putBoolean(KEY_ENABLE_DEVICE_ADMIN, value) }

    var homeButtonStyle: String
        get() = prefs.getString(KEY_HOME_BUTTON_STYLE, DEFAULT_HOME_BUTTON_STYLE) ?: DEFAULT_HOME_BUTTON_STYLE
        set(value) = prefs.edit { putString(KEY_HOME_BUTTON_STYLE, value) }

    var pickerGap: Int
        get() = prefs.getInt(KEY_PICKER_GAP, DEFAULT_PICKER_GAP).coerceIn(0, 100)
        set(value) = prefs.edit { putInt(KEY_PICKER_GAP, value) }

    var useCustomAccent: Boolean
        get() = prefs.getBoolean(KEY_USE_CUSTOM_ACCENT, DEFAULT_USE_CUSTOM_ACCENT)
        set(value) = prefs.edit { putBoolean(KEY_USE_CUSTOM_ACCENT, value) }

    var selectedIconPack: String
        get() = prefs.getString(KEY_ICON_PACK, DEFAULT_ICON_PACK) ?: DEFAULT_ICON_PACK
        set(value) = prefs.edit { putString(KEY_ICON_PACK, value) }

    var iconPackLabel: String
        // Empty means "none" (or never chosen): no literal "System
        // Default" is persisted — display sites resolve the localised
        // default string, and third-party labels, at display time.
        get() = prefs.getString(KEY_ICON_PACK_LABEL, "") ?: ""
        set(value) = prefs.edit {
            if (value.isEmpty()) remove(KEY_ICON_PACK_LABEL) else putString(KEY_ICON_PACK_LABEL, value)
        }

    /**
     * Label to show for the selected icon pack, resolved at display
     * time: the localised default for "none", the live PackageManager
     * label for a third-party pack (the stored label can be stale —
     * the pack may have been updated or renamed since it was picked),
     * and the stored label / package name only as a fallback when the
     * pack is gone. The legacy persisted literal "System Default" is
     * never shown for a real pack.
     */
    fun iconPackDisplayLabel(context: Context): String {
        val pack = selectedIconPack
        if (pack == DEFAULT_ICON_PACK || pack.isEmpty()) {
            return context.getString(R.string.icon_pack_default)
        }
        return try {
            val ai = context.packageManager.getApplicationInfo(pack, 0)
            context.packageManager.getApplicationLabel(ai).toString()
        } catch (_: Exception) {
            val stored = iconPackLabel
            if (stored.isNotEmpty() && stored != "System Default") stored else pack
        }
    }

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
        get() = prefs.getInt(KEY_BLUR_AMOUNT, DEFAULT_BLUR_AMOUNT).coerceIn(5, 50)
        set(value) = prefs.edit { putInt(KEY_BLUR_AMOUNT, value) }

    var panelOpacity: Int
        get() = prefs.getInt(KEY_PANEL_OPACITY, DEFAULT_OPACITY).coerceIn(10, 100)
        set(value) = prefs.edit { putInt(KEY_PANEL_OPACITY, value) }

    var handleHeight: Int
        get() = prefs.getInt(KEY_HANDLE_HEIGHT, DEFAULT_HANDLE_HEIGHT).coerceIn(50, 400)
        set(value) = prefs.edit { putInt(KEY_HANDLE_HEIGHT, value) }

    var handleWidth: Int
        get() = prefs.getInt(KEY_HANDLE_WIDTH, DEFAULT_HANDLE_WIDTH).coerceIn(5, 50)
        set(value) = prefs.edit { putInt(KEY_HANDLE_WIDTH, value) }

    var handleVerticalOffset: Int
        get() = prefs.getInt(KEY_HANDLE_OFFSET, DEFAULT_HANDLE_OFFSET).coerceIn(-500, 500)
        set(value) = prefs.edit { putInt(KEY_HANDLE_OFFSET, value.coerceIn(-500, 500)) }

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
        get() = prefs.getInt(KEY_PANEL_RADIUS, DEFAULT_PANEL_RADIUS).coerceIn(0, 60)
        set(value) = prefs.edit { putInt(KEY_PANEL_RADIUS, value) }

    var panelBackgroundColor: String
        get() = prefs.getString(KEY_PANEL_BG_COLOR, DEFAULT_PANEL_BG) ?: DEFAULT_PANEL_BG
        set(value) = prefs.edit { putString(KEY_PANEL_BG_COLOR, value) }

    var hideBackground: Boolean
        get() = prefs.getBoolean(KEY_HIDE_BG, DEFAULT_HIDE_BG)
        set(value) = prefs.edit { putBoolean(KEY_HIDE_BG, value) }

    var pillWidth: Int
        get() = prefs.getInt(KEY_PILL_WIDTH, DEFAULT_PILL_WIDTH).coerceIn(1, 10)
        set(value) = prefs.edit { putInt(KEY_PILL_WIDTH, value) }

    var showTools: Boolean
        get() = prefs.getBoolean(KEY_SHOW_TOOLS, DEFAULT_SHOW_TOOLS)
        set(value) = prefs.edit { putBoolean(KEY_SHOW_TOOLS, value) }

    var showLogs: Boolean
        get() = prefs.getBoolean(KEY_SHOW_LOGS, DEFAULT_SHOW_LOGS)
        set(value) = prefs.edit { putBoolean(KEY_SHOW_LOGS, value) }

    var animSpeed: Int
        get() = prefs.getInt(KEY_ANIM_SPEED, DEFAULT_ANIM_SPEED).coerceIn(0, 1000)
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
        get() = prefs.getInt(KEY_SLIDE_SENSITIVITY, DEFAULT_SLIDE_SENSITIVITY).coerceIn(1, 100)
        set(value) = prefs.edit { putInt(KEY_SLIDE_SENSITIVITY, value) }

    var swipeSensitivity: Int
        get() = prefs.getInt(KEY_SWIPE_SENSITIVITY, DEFAULT_SWIPE_SENSITIVITY).coerceIn(1, 100)
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

    /**
     * Favourite app per gesture slot, stored as
     * `favorite_app_<action key>` (e.g. `favorite_app_tap_action`).
     * The old single global favourite was shared by every slot that
     * picked "Open favourite app", so changing one slot silently
     * changed them all. A slot without its own key falls back to the
     * legacy global, so old backups and existing setups keep working.
     */
    fun favoriteAppPackageFor(slotKey: String): String =
        prefs.getString(KEY_FAVORITE_APP_PREFIX + slotKey, null) ?: favoriteAppPackage

    fun setFavoriteAppPackage(slotKey: String, pkg: String) {
        prefs.edit { putString(KEY_FAVORITE_APP_PREFIX + slotKey, pkg) }
    }

    /** Clear the favourite for every slot (and the legacy global) that
     *  points at an uninstalled package. */
    fun clearFavoriteAppIfPackage(pkg: String) {
        prefs.edit {
            if (favoriteAppPackage == pkg) putString(KEY_FAVORITE_APP, "")
            for (key in FAVORITE_SLOT_KEYS) {
                if (prefs.getString(KEY_FAVORITE_APP_PREFIX + key, null) == pkg) {
                    remove(KEY_FAVORITE_APP_PREFIX + key)
                }
            }
        }
    }

    var appLanguage: String
        get() = prefs.getString(KEY_APP_LANGUAGE, java.util.Locale.getDefault().language) ?: "en"
        set(value) = prefs.edit { putString(KEY_APP_LANGUAGE, value) }

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
     * Whether the first-run sidebar seeding has happened. The seed (top 5
     * apps) runs only once: without this, a user who deliberately unpins
     * everything gets 5 apps re-pinned on the next service start or
     * refresh, making an empty sidebar impossible.
     */
    var panelSeeded: Boolean
        get() = prefs.getBoolean(KEY_PANEL_SEEDED, false)
        set(value) = prefs.edit { putBoolean(KEY_PANEL_SEEDED, value) }

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
            Log.e("PanelPreferences", "Failed to start foreground service", e)
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
        // Audit fix: read-modify-write on the panel list must be atomic
        // across PanelPreferences instances. Every caller (service,
        // picker, settings) creates its own instance, so an instance
        // lock would not protect anything; PANEL_APPS_LOCK is shared
        // (companion object) and synchronized() is re-entrant, so addApp/
        // removeApp calling setPanelApps() under the same lock is safe.
        synchronized(PANEL_APPS_LOCK) {
            val unique = identifiers.filter { it.isNotBlank() }.distinct().take(MAX_PANEL_APPS)
            // Drop display labels for identifiers no longer pinned
            // (reorder / bulk replace / uninstall) — folded into the
            // same single edit as the list itself (was a second edit).
            var prunedLabels: String? = null
            val rawLabels = prefs.getString(KEY_PANEL_LABELS, null)
            if (!rawLabels.isNullOrBlank()) {
                try {
                    val obj = org.json.JSONObject(rawLabels)
                    val pinned = unique.toSet()
                    var changed = false
                    for (k in obj.keys().asSequence().toList()) {
                        if (k !in pinned) {
                            obj.remove(k)
                            changed = true
                        }
                    }
                    if (changed) prunedLabels = obj.toString()
                } catch (_: Exception) {}
            }
            prefs.edit {
                putString(KEY_PANEL_APPS, unique.joinToString(DELIMITER) { encodeDelim(it) })
                if (prunedLabels != null) putString(KEY_PANEL_LABELS, prunedLabels)
            }
        }
    }

    fun addApp(identifier: String): Boolean {
        if (identifier.isBlank()) return false
        synchronized(PANEL_APPS_LOCK) {
            val current = getPanelApps().toMutableList()
            if (current.contains(identifier)) return true
            if (current.size >= MAX_PANEL_APPS) return false
            current.add(identifier)
            setPanelApps(current)
            return true
        }
    }

    fun removeApp(identifier: String) {
        synchronized(PANEL_APPS_LOCK) {
            val current = getPanelApps().toMutableList()
            current.remove(identifier)
            setPanelApps(current)
        }
        // No separate removePanelItemLabel() call: setPanelApps() already
        // prunes labels for unpinned ids in the same edit.
    }

    fun isInPanel(identifier: String): Boolean = getPanelApps().contains(identifier)

    /**
     * Display labels for pinned intent:/activity/shortcut identifiers.
     * Re-resolving an intent URI via PackageManager often returns the *app*
     * label instead of the shortcut shortLabel the user selected in the picker.
     */
    fun getPanelItemLabel(identifier: String): String? {
        if (identifier.isBlank()) return null
        val raw = prefs.getString(KEY_PANEL_LABELS, null) ?: return null
        return try {
            val obj = org.json.JSONObject(raw)
            obj.optString(identifier, "").takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * All pinned-item display labels in one JSON parse. Prefer this over
     * calling [getPanelItemLabel] in a loop (e.g.
     * `AppRepository.getAppsForIdentifiers`): the per-identifier version
     * re-parses the whole labels JSON for every identifier (O(n²)).
     */
    fun getPanelItemLabels(): Map<String, String> {
        val raw = prefs.getString(KEY_PANEL_LABELS, null) ?: return emptyMap()
        return try {
            val obj = org.json.JSONObject(raw)
            val out = LinkedHashMap<String, String>()
            for (key in obj.keys()) {
                val value = obj.optString(key, "")
                if (value.isNotBlank()) out[key] = value
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun setPanelItemLabel(identifier: String, label: String) {
        if (identifier.isBlank() || label.isBlank()) return
        setPanelItemLabels(mapOf(identifier to label))
    }

    /**
     * Set several panel-item labels in one JSON parse + one edit. The
     * per-label [setPanelItemLabel] re-parses the whole labels JSON per
     * call; the label backfill (which can fill many at once) uses this.
     * Held under [PANEL_APPS_LOCK] like [setPanelApps]: the labels JSON is
     * a read-modify-write, and an unsynchronised write could interleave
     * with setPanelApps() and lose updates or resurrect pruned labels.
     */
    fun setPanelItemLabels(labels: Map<String, String>) {
        val valid = labels.filter { (id, label) -> id.isNotBlank() && label.isNotBlank() }
        if (valid.isEmpty()) return
        synchronized(PANEL_APPS_LOCK) {
            val raw = prefs.getString(KEY_PANEL_LABELS, null)
            val obj = try {
                if (raw.isNullOrBlank()) org.json.JSONObject() else org.json.JSONObject(raw)
            } catch (_: Exception) {
                org.json.JSONObject()
            }
            for ((id, label) in valid) obj.put(id, label)
            // Drop labels for identifiers no longer pinned (keep the ones just written).
            val pinned = getPanelApps().toSet()
            val keys = obj.keys().asSequence().toList()
            for (k in keys) {
                if (k !in pinned && k !in valid.keys) obj.remove(k)
            }
            prefs.edit { putString(KEY_PANEL_LABELS, obj.toString()) }
        }
    }

    fun removePanelItemLabel(identifier: String) {
        if (identifier.isBlank()) return
        synchronized(PANEL_APPS_LOCK) {
            val raw = prefs.getString(KEY_PANEL_LABELS, null) ?: return
            try {
                val obj = org.json.JSONObject(raw)
                if (!obj.has(identifier)) return
                obj.remove(identifier)
                prefs.edit { putString(KEY_PANEL_LABELS, obj.toString()) }
            } catch (_: Exception) {}
        }
    }



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
        return CustomItemJson.decode(raw)
    }

    /**
     * Atomically overwrite the custom items list.
     * The list order is preserved as the user-specified display order.
     */
    fun setCustomItems(items: List<CustomItem>) {
        prefs.edit { putString(KEY_CUSTOM_ITEMS, CustomItemJson.encode(items)) }
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
        // Read-modify-write under the shared lock (like setPanelApps):
        // the picker and an import can otherwise interleave and lose one.
        synchronized(PANEL_APPS_LOCK) {
            val current = getCustomItems().toMutableList()
            // Defensive: de-duplicate by id, then append.
            current.removeAll { it.id == item.id }
            current.add(item)
            setCustomItems(current)
        }
    }

    /** Replace an existing custom item in-place (matched by id).
     * Returns false instead of silently dropping the edit when the id is
     * not a valid UUID or no such item exists (previously it fell back to
     * addCustomItem, which silently rejects non-UUID ids). */
    fun updateCustomItem(item: CustomItem): Boolean {
        if (!UUID_PATTERN.matches(item.id)) return false
        synchronized(PANEL_APPS_LOCK) {
            val current = getCustomItems().toMutableList()
            val idx = current.indexOfFirst { it.id == item.id }
            if (idx < 0) return false
            current[idx] = item
            setCustomItems(current)
            return true
        }
    }

    /** Remove a custom item by id. Returns the removed item, or null if not found. */
    fun removeCustomItem(id: String): CustomItem? {
        synchronized(PANEL_APPS_LOCK) {
            val current = getCustomItems().toMutableList()
            val idx = current.indexOfFirst { it.id == id }
            if (idx < 0) return null
            val removed = current.removeAt(idx)
            setCustomItems(current)
            return removed
        }
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
        // The read (getPanelApps) and the write (setPanelApps) must be one
        // atomic step, or a concurrent panel edit between them is clobbered.
        synchronized(PANEL_APPS_LOCK) {
        val current = getPanelApps().toMutableList()
        val customIdsInSidebar = current.filter { it.startsWith(CUSTOM_ID_PREFIX) }.toSet()
        val orderedCustom = customItems.mapNotNull { item ->
            val id = CUSTOM_ID_PREFIX + item.id
            if (id in customIdsInSidebar) id else null
        }
        // Splice the newly ordered custom ids back into the slots the
        // custom items already occupy, instead of appending them at the
        // end: the old `nonCustom + orderedCustom` rebuild moved every
        // custom pin to the bottom of an interleaved sidebar (app, custom,
        // app) after any drag-reorder in the CUSTOM tab.
        val ordered = orderedCustom.toMutableList()
        val result = mutableListOf<String>()
        for (id in current) {
            if (id.startsWith(CUSTOM_ID_PREFIX)) {
                // Next custom id in the new order for this slot; keep an
                // orphaned pin (not in the custom list) rather than dropping it.
                result.add(ordered.removeFirstOrNull() ?: id)
            } else {
                result.add(id)
            }
        }
        // Any ordered ids not placed above (sidebar changed underneath)
        // go at the end rather than being dropped.
        result.addAll(ordered)
        // setPanelApps takes List<String> and handles dedup + delimiter join internally.
        setPanelApps(result)
        }
    }

    // =====================================================================================
    //                       Text-selection actions storage (ACTION_PROCESS_TEXT)
    // =====================================================================================
    // Stored as a JSON array under KEY_TEXT_ACTIONS. Each element is a TextAction
    // (see TextAction.kt). On first read (key absent) the built-in presets from
    // defaultTextActions() are seeded and persisted so the settings page and the
    // ProcessTextActivity chooser start from something useful.
    //
    // Platform note: Android only allows ONE static ACTION_PROCESS_TEXT entry per
    // app, so the manifest registers a single "Smart Edge…" activity. Tapping it
    // opens an in-app chooser listing these actions — that is why the user can
    // freely add/rename/reorder any number of them here.

    /**
     * Read the persisted list of [TextAction]s in user order.
     * Seeds the built-in presets on first run (key absent).
     */
    fun getTextActions(): List<TextAction> {
        if (!prefs.contains(KEY_TEXT_ACTIONS)) {
            val defaults = defaultTextActions()
            setTextActions(defaults)
            return defaults
        }
        val raw = prefs.getString(KEY_TEXT_ACTIONS, null) ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = org.json.JSONArray(raw)
            // Dedupe by id on read (covers imported rows): duplicate ids
            // produce duplicate chooser entries that can't be told apart.
            val seen = HashSet<String>()
            (0 until arr.length()).mapNotNull { i ->
                val item = arr.optJSONObject(i)?.let { TextAction.fromJson(it) }
                    ?: return@mapNotNull null
                if (!seen.add(item.id)) return@mapNotNull null
                item
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Atomically overwrite the text actions list. Order is the display order. */
    fun setTextActions(items: List<TextAction>) {
        val arr = org.json.JSONArray()
        items.forEach { arr.put(it.toJson()) }
        prefs.edit { putString(KEY_TEXT_ACTIONS, arr.toString()) }
    }

    /** Append a new action (id de-duplicated defensively). */
    fun addTextAction(item: TextAction) {
        synchronized(PANEL_APPS_LOCK) {
            val current = getTextActions().toMutableList()
            current.removeAll { it.id == item.id }
            current.add(item)
            setTextActions(current)
        }
    }

    /** Replace an existing action in-place (matched by id). */
    fun updateTextAction(item: TextAction) {
        synchronized(PANEL_APPS_LOCK) {
            val current = getTextActions().toMutableList()
            val idx = current.indexOfFirst { it.id == item.id }
            if (idx >= 0) {
                current[idx] = item
                setTextActions(current)
            } else {
                addTextAction(item)
            }
        }
    }

    /** Remove an action by id. Returns the removed action, or null if not found. */
    fun removeTextAction(id: String): TextAction? {
        synchronized(PANEL_APPS_LOCK) {
            val current = getTextActions().toMutableList()
            val idx = current.indexOfFirst { it.id == id }
            if (idx < 0) return null
            val removed = current.removeAt(idx)
            setTextActions(current)
            return removed
        }
    }

    /** Move an action from one position to another (drag/up-down reorder). */
    fun moveTextAction(from: Int, to: Int) {
        synchronized(PANEL_APPS_LOCK) {
            val current = getTextActions().toMutableList()
            if (from !in current.indices || to !in current.indices || from == to) return
            val item = current.removeAt(from)
            current.add(to, item)
            setTextActions(current)
        }
    }

    /** Restore the built-in preset list, discarding user edits. */
    fun resetTextActions() {
        setTextActions(defaultTextActions())
    }

    var panelSide: String
        get() = prefs.getString(KEY_PANEL_SIDE, DEFAULT_SIDE) ?: DEFAULT_SIDE
        set(value) = prefs.edit { putString(KEY_PANEL_SIDE, value) }

    var autoStart: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START, DEFAULT_AUTO_START)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_START, value) }

    // Runtime-only foreground package (not a user preference). Do not persist to
    // SharedPreferences — a11y fires this on every window change. Backed by the
    // companion's shared field so every PanelPreferences instance (a11y service,
    // panel service, EdgeHandleView, SidePanelView) sees the same value.
    var currentForegroundPackage: String
        get() = sharedForegroundPackage
        set(value) {
            sharedForegroundPackage = value
        }

    /**
     * True when the WRITE_SETTINGS prompt may be shown again: only once per
     * [WRITE_SETTINGS_PROMPT_COOLDOWN_MS]. Both brightness paths call this
     * before launching the settings screen, so a drag with the permission
     * missing prompts once and then stays silent until the cooldown elapses
     * (instead of re-launching the screen on every tick).
     */
    fun shouldPromptWriteSettings(): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        return if (now - lastWriteSettingsPromptMs >= WRITE_SETTINGS_PROMPT_COOLDOWN_MS) {
            lastWriteSettingsPromptMs = now
            true
        } else {
            false
        }
    }

    /**
     * The brightness 1%-step accumulator for this [PanelPreferences]
     * instance. The edge-slide path (FloatingPanelService.adjustBrightness)
     * and the tools long-press-drag path (SidePanelView) each build their
     * own PanelPreferences, so each has its own accumulator and the pending
     * remainder does NOT carry between the two paths. Runtime state only —
     * not persisted.
     */
    val brightnessStepAccumulator = PercentStepAccumulator()

    /**
     * Unguessable per-install token embedded in our home-screen shortcuts
     * (see ToggleActivity) so it can tell our own shortcut apart from a
     * spoofed intent: a plain boolean extra can be set by any app, a random
     * UUID stored only in our prefs cannot. Generated once, on first read.
     */
    val shortcutToken: String
        get() {
            var token = prefs.getString(KEY_SHORTCUT_TOKEN, null)
            if (token.isNullOrBlank()) {
                token = java.util.UUID.randomUUID().toString()
                prefs.edit().putString(KEY_SHORTCUT_TOKEN, token).apply()
            }
            return token
        }

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

    /**
     * True when [packageName] is in the user-selected "hide handle" list used by
     * Fullscreen Protection. Despite the historical name "whitelist", membership
     * means the handle SHOULD be hidden for this package when auto-hide is on.
     */
    fun isWhitelistedFromAutoHide(packageName: String): Boolean {
        return shouldHideHandleInPackage(packageName)
    }

    fun shouldHideHandleInPackage(packageName: String): Boolean {
        return getFullscreenWhitelist().contains(packageName)
    }

    fun getFullscreenHideList(): List<String> = getFullscreenWhitelist()

    fun setFullscreenHideList(packages: List<String>) = setFullscreenWhitelist(packages)
}
