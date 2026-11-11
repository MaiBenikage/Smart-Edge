package com.imi.smartedge.sidebar.panel

/**
 * Pure JSON encode/decode for the persisted custom-items list
 * (`KEY_CUSTOM_ITEMS` in SharedPreferences).
 *
 * Extracted from `PanelPreferences` so the real production parse path
 * is unit-testable on the JVM (see CustomItemJsonTest) — the prefs
 * class delegates to [decode]/[encode] and only owns the storage.
 * No Android dependency (org.json is a plain JVM library in tests).
 *
 * Schema: a JSON array of objects with `id` (UUID), `isUrl`, `title`,
 * `content`. The decode path is defensive: it trims content (so legacy
 * rows with surrounding whitespace still match `startsWith("intent:")`),
 * rejects non-UUID ids, dedupes by id (first wins), and drops rows that
 * fail [CustomItemValidation.isValidCustom] — so an imported backup
 * cannot smuggle in a `file:` / `javascript:` / `content:` payload (or
 * an over-long row) that the picker's save-time validation would refuse.
 * A dropped row can neither render an unsaveable item nor produce
 * duplicate rows sharing one stableId.
 */
object CustomItemJson {

    /** Custom-item ids are UUIDs (see `PanelPreferences.addCustomItem`). */
    val UUID_PATTERN = Regex("""^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$""")

    /**
     * Parse a stored JSON array string into custom items.
     * Returns an empty list for a missing, blank, or malformed value —
     * including a single JSON object (not an array), which is not a
     * valid custom-items document.
     */
    fun decode(raw: String?): List<CustomItem> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = org.json.JSONArray(raw)
            val seen = HashSet<String>()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                if (!UUID_PATTERN.matches(id)) return@mapNotNull null
                if (!seen.add(id)) return@mapNotNull null
                val title = o.optString("title", "").trim()
                val content = o.optString("content", "").trim()
                // Same allowlist the picker's save path enforces: an
                // imported row with a disallowed scheme (file:, javascript:,
                // content:) or an over-long field is dropped, not stored.
                if (!CustomItemValidation.isValidCustom(title, content)) return@mapNotNull null
                CustomItem(
                    id = id,
                    isUrl = o.optBoolean("isUrl", false),
                    title = title,
                    content = content
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Serialise custom items to a JSON array string (insertion order preserved). */
    fun encode(items: List<CustomItem>): String {
        val arr = org.json.JSONArray()
        items.forEach { item ->
            val o = org.json.JSONObject()
            o.put("id", item.id)
            o.put("isUrl", item.isUrl)
            o.put("title", item.title)
            o.put("content", item.content)
            arr.put(o)
        }
        return arr.toString()
    }
}
