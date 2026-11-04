package com.imi.smartedge.sidebar.panel

import android.content.Context
import android.util.Log
import org.json.JSONArray

/**
 * Tracks recently-used apps for the sidebar "Recent" section.
 *
 * Design (per user spec):
 * - Every app launch is recorded as one entry (the same app opened multiple
 *   times is recorded repeatedly — no dedup on insert).
 * - The history is a bounded FIFO list; its capacity is user-configurable
 *   (64 / 128 / 256). When full, the oldest entries are dropped.
 * - The "Recent" list shown in the sidebar is computed from the history:
 *   count occurrences of each package, sort by count (descending, ties broken
 *   by recency), and take the top N where N is user-configurable (4..20).
 * - A short cross-source debounce window prevents double-recording a single
 *   launch when both the sidebar click handler and the accessibility-service
 *   foreground tracker fire for the same app open.
 */
class RecentAppsManager(context: Context) {

    private val appContext = context.applicationContext
    private val panelPrefs = PanelPreferences(appContext)

    companion object {
        private const val TAG = "RecentAppsManager"

        /** History-size options offered in settings. */
        val HISTORY_SIZE_OPTIONS = intArrayOf(64, 128, 256)

        /** Bounds for the "how many recent apps to show" setting. */
        const val MIN_RECENT_COUNT = 4
        const val MAX_RECENT_COUNT = 20

        /** Record source: user tapped the app in the sidebar/picker. */
        const val SOURCE_CLICK = "click"

        /** Record source: accessibility service saw the app come to foreground. */
        const val SOURCE_A11Y = "a11y"

        /**
         * Ignore a repeat record of the same package within this window, but
         * ONLY when it comes from a different source than the previous record.
         * A single launch fires both a click event and an accessibility
         * foreground event — the second one is the duplicate. Two genuine
         * launches from the same source (even < 3s apart) both count, per the
         * "repeat opens are recorded repeatedly" requirement.
         */
        private const val DEBOUNCE_MS = 3000L
    }

    /**
     * Record one app launch. Thread-safe; cheap enough to call from the
     * accessibility service's main thread.
     *
     * @param source [SOURCE_CLICK] or [SOURCE_A11Y] — used for duplicate
     * suppression (see [DEBOUNCE_MS]).
     */
    @Synchronized
    fun recordLaunch(packageName: String, source: String) {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return
        // Never track our own package.
        if (pkg == appContext.packageName) return
        // Respect the user toggle — don't accumulate history nobody asked for.
        if (!panelPrefs.recentEnabled) return

        val now = System.currentTimeMillis()
        val lastPkg = panelPrefs.recentLastPackage
        val lastTime = panelPrefs.recentLastTime
        val lastSource = panelPrefs.recentLastSource
        if (pkg == lastPkg && source != lastSource && now - lastTime < DEBOUNCE_MS) return

        try {
            val capacity = panelPrefs.recentHistorySize.coerceIn(
                HISTORY_SIZE_OPTIONS.minOrElse(64),
                HISTORY_SIZE_OPTIONS.maxOrElse(256)
            )
            val history = loadHistory().toMutableList()
            history.add(pkg)
            while (history.size > capacity) {
                history.removeAt(0)
            }
            saveHistory(history)
            panelPrefs.recentLastPackage = pkg
            panelPrefs.recentLastTime = now
            panelPrefs.recentLastSource = source
        } catch (e: Exception) {
            Log.w(TAG, "recordLaunch failed", e)
        }
    }

    /**
     * Compute the most frequently used packages from the recorded history.
     * @return package names ordered by frequency (most used first), at most
     * [PanelPreferences.recentMaxCount] entries.
     */
    @Synchronized
    fun getTopPackages(): List<String> {
        val history = try {
            loadHistory()
        } catch (e: Exception) {
            Log.w(TAG, "getTopPackages failed", e)
            return emptyList()
        }
        if (history.isEmpty()) return emptyList()

        val limit = panelPrefs.recentMaxCount.coerceIn(MIN_RECENT_COUNT, MAX_RECENT_COUNT)

        // Frequency map + last-seen index for stable tie-breaking (more recent wins ties).
        val counts = LinkedHashMap<String, Int>()
        val lastSeen = HashMap<String, Int>()
        history.forEachIndexed { index, pkg ->
            counts[pkg] = (counts[pkg] ?: 0) + 1
            lastSeen[pkg] = index
        }

        return counts.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, Int>> { it.value }
                    .thenByDescending { lastSeen[it.key] ?: 0 }
            )
            .take(limit)
            .map { it.key }
    }

    /** Resolve the top packages to displayable [AppInfo] entries. */
    suspend fun getTopRecentApps(): List<AppInfo> {
        val packages = getTopPackages()
        if (packages.isEmpty()) return emptyList()
        return try {
            val repo = AppRepository(appContext)
            repo.getAppsForIdentifiers(packages)
        } catch (e: Exception) {
            Log.w(TAG, "getTopRecentApps failed", e)
            emptyList()
        }
    }

    @Synchronized
    fun clearHistory() {
        try {
            saveHistory(emptyList())
            panelPrefs.recentLastPackage = ""
            panelPrefs.recentLastTime = 0L
            panelPrefs.recentLastSource = ""
        } catch (e: Exception) {
            Log.w(TAG, "clearHistory failed", e)
        }
    }

    private fun loadHistory(): List<String> {
        val json = panelPrefs.recentHistoryJson
        if (json.isBlank()) return emptyList()
        val arr = JSONArray(json)
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val pkg = arr.optString(i, "").trim()
            if (pkg.isNotEmpty()) out.add(pkg)
        }
        return out
    }

    private fun saveHistory(history: List<String>) {
        val arr = JSONArray()
        for (pkg in history) arr.put(pkg)
        panelPrefs.recentHistoryJson = arr.toString()
    }

    private fun IntArray.minOrElse(fallback: Int): Int = this.minOrNull() ?: fallback
    private fun IntArray.maxOrElse(fallback: Int): Int = this.maxOrNull() ?: fallback
}
