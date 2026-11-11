package com.imi.smartedge.sidebar.panel

import java.util.UUID

/**
 * Behavior types for a user-configured text-selection action.
 *
 * Android only allows ONE static entry per app in the system text-selection
 * menu (ACTION_PROCESS_TEXT), so Smart Edge registers a single "Smart Edge…"
 * entry. Tapping it opens an in-app chooser listing the user's [TextAction]s.
 */
enum class ActionType {
    /** Open a URL built from [TextAction.urlTemplate] ({text} / {encoded} placeholders). */
    OPEN_URL,
    /** Copy the selected text to the clipboard. */
    COPY,
    /** Share the selected text via the system share sheet. */
    SHARE,
    /** Transform the text and write it back (editable text only). */
    REPLACE_TEXT,
    /** Launch another app, passing the text as EXTRA_PROCESS_TEXT. */
    LAUNCH_APP,
    /** Fire a user-defined intent action + data URI template. */
    CUSTOM_INTENT
}

/** Text transformation used by [ActionType.REPLACE_TEXT]. */
enum class ReplaceTransform {
    UPPERCASE,
    LOWERCASE
}

/**
 * A single user-configured shortcut shown in the second-level chooser after
 * tapping "Smart Edge…" in the system text-selection menu.
 *
 * Persisted as a JSON array in PanelPreferences under KEY_TEXT_ACTIONS.
 */
data class TextAction(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: ActionType,
    val enabled: Boolean = true,
    /** Used by OPEN_URL. Supports {text} (raw) and {encoded} (URL-encoded) placeholders. */
    val urlTemplate: String = "",
    /** Used by LAUNCH_APP: target application package name. */
    val targetPackage: String = "",
    /** Used by CUSTOM_INTENT. */
    val customIntentAction: String = "",
    /** Used by CUSTOM_INTENT. Supports {text} / {encoded} placeholders. */
    val customIntentData: String = "",
    /** Used by REPLACE_TEXT. */
    val replaceTransform: ReplaceTransform = ReplaceTransform.UPPERCASE,
    /** If true, only shown when the selected text is editable (readonly == false). */
    val editableOnly: Boolean = false
) {
    fun toJson(): org.json.JSONObject {
        val o = org.json.JSONObject()
        o.put("id", id)
        o.put("name", name)
        o.put("type", type.name)
        o.put("enabled", enabled)
        o.put("urlTemplate", urlTemplate)
        o.put("targetPackage", targetPackage)
        o.put("customIntentAction", customIntentAction)
        o.put("customIntentData", customIntentData)
        o.put("replaceTransform", replaceTransform.name)
        o.put("editableOnly", editableOnly)
        return o
    }

    companion object {
        fun fromJson(o: org.json.JSONObject): TextAction? {
            return try {
                // Android org.json's optString returns the literal "null"
                // for a JSON null value, so an imported "urlTemplate": null
                // would become the URL "null". Treat JSON null as absent.
                fun optStr(key: String, fallback: String = ""): String =
                    if (o.isNull(key)) fallback else o.optString(key, fallback)
                val id = optStr("id").trim().takeIf { it.isNotBlank() } ?: return null
                val name = optStr("name").trim().takeIf { it.isNotBlank() } ?: return null
                val type = try {
                    ActionType.valueOf(optStr("type", ActionType.OPEN_URL.name))
                } catch (_: Exception) {
                    ActionType.OPEN_URL
                }
                val transform = try {
                    ReplaceTransform.valueOf(optStr("replaceTransform", ReplaceTransform.UPPERCASE.name))
                } catch (_: Exception) {
                    ReplaceTransform.UPPERCASE
                }
                TextAction(
                    id = id,
                    name = name,
                    type = type,
                    enabled = o.optBoolean("enabled", true),
                    urlTemplate = optStr("urlTemplate"),
                    targetPackage = optStr("targetPackage"),
                    customIntentAction = optStr("customIntentAction"),
                    customIntentData = optStr("customIntentData"),
                    replaceTransform = transform,
                    editableOnly = o.optBoolean("editableOnly", false)
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

/** Built-in presets seeded on first launch. Users can edit, disable, reorder or delete them. */
fun defaultTextActions(): List<TextAction> {
    // Translate target: the current app locale, not a hardcoded "en" —
    // the app ships a Spanish UI, so a Spanish user gets tl=es.
    val targetLang = java.util.Locale.getDefault().language.takeIf { it.isNotBlank() } ?: "en"
    return listOf(
    TextAction(
        name = "Search web",
        type = ActionType.OPEN_URL,
        urlTemplate = "https://www.google.com/search?q={encoded}"
    ),
    TextAction(
        name = "Translate",
        type = ActionType.OPEN_URL,
        urlTemplate = "https://translate.google.com/?sl=auto&tl=$targetLang&text={encoded}&op=translate"
    ),
    TextAction(
        name = "Copy",
        type = ActionType.COPY
    ),
    TextAction(
        name = "Share",
        type = ActionType.SHARE
    ),
    TextAction(
        name = "UPPERCASE",
        type = ActionType.REPLACE_TEXT,
        enabled = false,
        replaceTransform = ReplaceTransform.UPPERCASE,
        editableOnly = true
    )
    )
}
