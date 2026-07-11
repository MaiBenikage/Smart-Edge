package com.imi.smartedge.sidebar.panel

/**
 * Represents an item that can be placed in the side panel.
 * Can be a standard App, a specific Activity, or a Shortcut.
 */
data class AppInfo(
    val packageName: String,
    val appName: String,
    var isInPanel: Boolean = false,
    val type: Type = Type.APP,
    val intentUri: String? = null,
    val activityName: String? = null,
    val subItems: List<String>? = null, // identifiers for items inside a folder
    val appearanceKey: String? = null // Forces redraw when shape/theme changes
) {
    enum class Type {
        APP, ACTIVITY, SHORTCUT, FOLDER, TOOL, CUSTOM
    }

    /**
     * Unique identifier for this item in preferences.
     * For apps, it's the package name.
     * For activities/shortcuts, it's the intent URI.
     * For custom items, it's the smartedge.custom.<uuid> token.
     */
    val identifier: String
        get() = intentUri ?: packageName
}

/**
 * A user-created sidebar item — either a bare URL (opened via ACTION_VIEW)
 * or a raw `intent:#Intent;...end` URI (started via Intent.parseUri).
 *
 * Stored in PanelPreferences as a JSON array. The sidebar identifier is
 * `"smartedge.custom." + id` and is registered in `panelApps` so the
 * standard `AppRepository.getAppsForIdentifiers` resolution path picks it up.
 */
data class CustomItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val isUrl: Boolean,
    val title: String,
    val content: String
)
