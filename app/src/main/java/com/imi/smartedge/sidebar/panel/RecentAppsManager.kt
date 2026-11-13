package com.imi.smartedge.sidebar.panel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

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

        /**
         * Cache of [isTrackableRecent] results, per package. Without it
         * getTopPackages paid one PackageManager binder call per distinct
         * history package (up to 256), twice per panel open. Static like
         * [cachedHomePackages]: every call site constructs a fresh
         * RecentAppsManager, so an instance map would never survive to
         * the next open. Cleared when packages change (see
         * [ensureLaunchIntentCacheReceiver]); a stale entry can only
         * survive until the next install/uninstall/replace.
         */
        private val launchIntentCache = ConcurrentHashMap<String, Boolean>()

        /** Guards the one-time registration in [ensureLaunchIntentCacheReceiver]. */
        private val launchIntentReceiverRegistered = AtomicBoolean(false)

        /**
         * Registers (once per process) a package-change receiver on the
         * application context that clears [launchIntentCache] — the same
         * invalidation the service's packageReceiver does for its
         * launcher cache. The application context lives for the process
         * lifetime, so there is nothing to unregister. A failed
         * registration (e.g. no real Context under test) leaves the
         * cache working but never invalidated until the process dies.
         */
        private fun ensureLaunchIntentCacheReceiver(context: Context) {
            if (!launchIntentReceiverRegistered.compareAndSet(false, true)) return
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    launchIntentCache.clear()
                }
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    @Suppress("DEPRECATION")
                    context.registerReceiver(receiver, filter)
                }
            } catch (e: Exception) {
                // Roll back so a later call can retry.
                launchIntentReceiverRegistered.set(false)
                Log.w(TAG, "launch-intent cache receiver not registered", e)
            }
        }

        /**
         * Static lock for the history. Every call site constructs a fresh
         * RecentAppsManager (sidebar click, accessibility tracker, service
         * refresh), so the old instance-level @Synchronized never actually
         * serialised two recorders — two concurrent launches could
         * interleave loadHistory/saveHistory and lose one entry.
         */
        private val LOCK = Any()

        /** History-size options offered in settings. */
        val HISTORY_SIZE_OPTIONS = intArrayOf(64, 128, 256)

        /** Bounds for the "how many recent apps to show" setting. */
        const val MIN_RECENT_COUNT = 4
        const val MAX_RECENT_COUNT = 20

        /** Record source: user tapped the app in the sidebar/picker. */
        const val SOURCE_CLICK = "click"

        /** Record source: accessibility service saw the app come to foreground. */
        const val SOURCE_A11Y = "a11y"

        // Home (launcher) packages, resolved once per process and cached:
        // the a11y recorder sees the launcher on almost every app switch,
        // and without this the launcher becomes the most "frequent" recent
        // app. The default launcher changes rarely; a process restart
        // re-resolves it.
        @Volatile
        private var cachedHomePackages: Set<String>? = null

        private fun homePackages(context: Context): Set<String> {
            cachedHomePackages?.let { return it }
            val home = try {
                val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
                    .addCategory(android.content.Intent.CATEGORY_HOME)
                context.packageManager.queryIntentActivities(intent, 0)
                    .map { it.activityInfo.packageName }.toSet()
            } catch (_: Exception) {
                emptySet()
            }
            cachedHomePackages = home
            return home
        }

        /**
         * Ignore a repeat record of the same package within this window, but
         * ONLY when it comes from a different source than the previous record.
         * A single launch fires both a click event and an accessibility
         * foreground event — the second one is the duplicate. Two genuine
         * launches from the same source (even < 3s apart) both count, per the
         * "repeat opens are recorded repeatedly" requirement.
         */
        private const val DEBOUNCE_MS = 3000L

        /**
         * Pure extraction of the owning package from an `intent:` URI string,
         * without calling [android.content.Intent.parseUri] (an Android stub
         * that throws on the JVM, so the old inline version could never be
         * unit-tested — the CUSTOM + `intent:` branch of
         * [excludedPackagesFor] was unexercised).
         *
         * Mirrors what the parsed Intent used to yield, in the same
         * precedence: the `package=` parameter wins, else the package part
         * of `component=<pkg>/<cls>`. Only parameters after the `#Intent;`
         * marker are read (a URI without it is not a valid intent URI), and
         * parameters after `;SEL;` belong to the selector intent, not this
         * one. Values must look like real package names (a relative
         * component such as `.Foo` carries no package). Returns "" when
         * nothing usable is present.
         */
        private val PACKAGE_NAME_REGEX =
            Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)*")
        private val INTENT_PACKAGE_PARAM = Regex("(?:^|;)package=([^;]+)")
        private val INTENT_COMPONENT_PARAM = Regex("(?:^|;)component=([^;/]+)/")

        internal fun packageFromIntentUri(payload: String): String {
            val params = payload.substringAfter("#Intent;", missingDelimiterValue = "")
            if (params.isEmpty()) return ""
            val main = params.substringBefore(";SEL;", params)
            val byPackage = INTENT_PACKAGE_PARAM.find(main)
                ?.groupValues?.getOrNull(1)?.trim().orEmpty()
            if (byPackage.isNotEmpty() && PACKAGE_NAME_REGEX.matches(byPackage)) return byPackage
            val byComponent = INTENT_COMPONENT_PARAM.find(main)
                ?.groupValues?.getOrNull(1)?.trim().orEmpty()
            return if (byComponent.isNotEmpty() && PACKAGE_NAME_REGEX.matches(byComponent)) byComponent else ""
        }

        /**
         * Packages that must never appear in the Recent section because the
         * app is already pinned in the sidebar app list.
         *
         * Sidebar membership is stored as identifiers, not packages:
         * a plain app's identifier *is* its package, but a pinned
         * activity/shortcut's identifier is an `intent:` URI whose owning
         * package lives in [AppInfo.packageName] (resolved by
         * `AppRepository.getAppsForIdentifiers`), and a custom item's
         * identifier is `smartedge.custom.<uuid>`. Comparing only
         * identifiers would therefore miss pinned activities/shortcuts
         * entirely. Package-level comparison is the correct level here:
         * the Recent section shows apps, and a pinned activity of an app
         * still means that app is already present in the sidebar.
         *
         * `smartedge.*` tokens (tools, folders, shortcuts, custom ids) are
         * not real packages and are skipped, except for custom items whose
         * payload is an `intent:` URI — that URI's package is extracted.
         */
        fun excludedPackagesFor(sidebarApps: List<AppInfo>): Set<String> {
            val out = LinkedHashSet<String>()
            for (app in sidebarApps) {
                val pkg = app.packageName.trim()
                if (pkg.isNotEmpty() && !pkg.startsWith("smartedge.")) {
                    out.add(pkg)
                    continue
                }
                // Custom item whose launch payload is an intent: resolve its package.
                val payload = app.intentUri
                if (payload != null && payload.startsWith("intent:")) {
                    val target = packageFromIntentUri(payload)
                    if (target.isNotEmpty()) out.add(target)
                }
            }
            return out
        }
    }

    /**
     * Record one app launch. Thread-safe; cheap enough to call from the
     * accessibility service's main thread.
     *
     * @param source [SOURCE_CLICK] or [SOURCE_A11Y] — used for duplicate
     * suppression (see [DEBOUNCE_MS]).
     */
    /**
     * Whether a package may appear in the Recent section: not a home
     * (launcher) package, and launchable (has a launch intent). The
     * launcher comes to the foreground between almost every app switch,
     * and non-launchable packages (permission controller, installer,
     * Play Services dialogs, OEM overlays) are not apps the user can open.
     */
    private fun isTrackableRecent(pkg: String): Boolean {
        if (pkg in homePackages(appContext)) return false
        // Audit (4th) M-F: the launch-intent result is cached per package
        // (see launchIntentCache) — getTopPackages used to pay one
        // PackageManager binder call per distinct history package on every
        // open. A cached false keeps an uninstalled package out without a
        // fresh binder call; the cache is cleared on package add/remove/
        // replace, so a newly installed app becomes trackable.
        launchIntentCache[pkg]?.let { return it }
        val trackable = try {
            appContext.packageManager.getLaunchIntentForPackage(pkg) != null
        } catch (_: Exception) {
            false
        }
        launchIntentCache[pkg] = trackable
        return trackable
    }

    fun recordLaunch(packageName: String, source: String) {
        // Audit (4th) M-F: the launch-intent cache (see
        // launchIntentCache) must be invalidated on package changes even
        // when only the recorder ever runs — register the receiver here
        // as well as in getTopPackages.
        ensureLaunchIntentCacheReceiver(appContext)
        // Static lock (see LOCK): two fresh instances must not interleave
        // loadHistory/saveHistory and lose a launch.
        synchronized(LOCK) {
            val pkg = packageName.trim()
            if (pkg.isEmpty()) return
            // Never track our own package.
            if (pkg == appContext.packageName) return
            // Respect the user toggle — don't accumulate history nobody asked for.
            if (!panelPrefs.recentEnabled) return
            // Debounce first: a duplicate record that will be dropped must
            // not pay the PackageManager binder call in isTrackableRecent().
            // Monotonic clock for the debounce: wall-clock time breaks when
            // the user (or the network) changes the clock. The persisted
            // lastTime from before this change is a wall-clock value, so
            // the first post-upgrade comparison can only skip one record.
            val now = android.os.SystemClock.elapsedRealtime()
            val lastPkg = panelPrefs.recentLastPackage
            val lastTime = panelPrefs.recentLastTime
            val lastSource = panelPrefs.recentLastSource
            if (pkg == lastPkg && source != lastSource && now - lastTime in 0..<DEBOUNCE_MS) return

            // Never track the launcher or non-launchable packages: the
            // launcher foregrounds between almost every app switch and
            // would dominate the frequency ranking.
            if (!isTrackableRecent(pkg)) return

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
                // One atomic prefs edit for history + debounce state (was four
                // separate edits per launch — see PanelPreferences.saveRecentLaunch).
                panelPrefs.saveRecentLaunch(history, pkg, now, source)
            } catch (e: Exception) {
                Log.w(TAG, "recordLaunch failed", e)
            }
        }
    }

    /**
     * Compute the most frequently used packages from the recorded history.
     * @param excludedPackages packages already in the sidebar app list —
     * they are removed *before* the [PanelPreferences.recentMaxCount]
     * limit is applied, so the Recent section still fills up with the
     * next most-used apps instead of shrinking.
     * @return package names ordered by frequency (most used first), at most
     * [PanelPreferences.recentMaxCount] entries.
     */
    suspend fun getTopPackages(excludedPackages: Set<String> = emptySet()): List<String> {
        // History read under the static lock (see LOCK) so a concurrent
        // record can't interleave with the load and produce a half-written
        // history. The PackageManager walk below runs outside the lock:
        // it suspends on Dispatchers.IO, and holding LOCK across a
        // suspension would block recorders for the whole scan.
        val history = synchronized(LOCK) {
            try {
                loadHistory()
            } catch (e: Exception) {
                Log.w(TAG, "getTopPackages failed", e)
                return emptyList()
            }
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

        // Rank first (pure CPU, no PackageManager): filtering before
        // sorting and sorting before filtering give the same order, so
        // ranking here and validating in rank order below returns exactly
        // what the old filter-then-sort-then-take returned — uninstalled,
        // launcher and non-launchable packages are still dropped before
        // the limit is applied, so the list still fills to N.
        val ranked = counts.keys
            .filter { it !in excludedPackages }
            .sortedWith(
                compareByDescending<String> { counts[it] ?: 0 }
                    .thenByDescending { lastSeen[it] ?: 0 }
            )
        if (ranked.isEmpty()) return emptyList()

        // Audit (4th) M-F: the PackageManager validation used to run on
        // the calling (main) thread — one getLaunchIntentForPackage binder
        // call per distinct history package, up to 256, twice per panel
        // open (handle tap + refresh). It now runs on Dispatchers.IO,
        // results are cached per package (see launchIntentCache), and the
        // walk stops as soon as `limit` trackable packages are found, so a
        // warm pass makes no PM calls at all and a cold pass makes ~limit.
        // Suspend so the main-thread caller (FloatingPanelService's
        // refreshApps, via getTopRecentApps) is unaffected.
        ensureLaunchIntentCacheReceiver(appContext)
        return withContext(Dispatchers.IO) {
            val out = ArrayList<String>(limit)
            for (pkg in ranked) {
                if (out.size >= limit) break
                if (isTrackableRecent(pkg)) out.add(pkg)
            }
            out
        }
    }

    /** Resolve the top packages to displayable [AppInfo] entries. */
    suspend fun getTopRecentApps(excludedPackages: Set<String> = emptySet()): List<AppInfo> {
        val packages = getTopPackages(excludedPackages)
        if (packages.isEmpty()) return emptyList()
        return try {
            // Shared repository (see AppRepository.shared): allocating a
            // fresh one per refresh rebuilt the picker cache each time.
            val repo = AppRepository.shared(appContext)
            // Defensive second filter: getAppsForIdentifiers resolves each
            // package to an AppInfo whose packageName should already be
            // clean, but never let a sidebar app through if resolution
            // ever maps a package differently.
            repo.getAppsForIdentifiers(packages)
                .filter { it.packageName !in excludedPackages }
        } catch (e: Exception) {
            Log.w(TAG, "getTopRecentApps failed", e)
            emptyList()
        }
    }

    fun clearHistory() {
        synchronized(LOCK) {
            try {
                saveHistory(emptyList())
                panelPrefs.recentLastPackage = ""
                panelPrefs.recentLastTime = 0L
                panelPrefs.recentLastSource = ""
            } catch (e: Exception) {
                Log.w(TAG, "clearHistory failed", e)
            }
        }
    }

    private fun loadHistory(): List<String> {
        val json = panelPrefs.recentHistoryJson
        if (json.isBlank()) return emptyList()
        val arr = try {
            JSONArray(json)
        } catch (_: Exception) {
            // Corrupt blob: repair it now (persist the empty list) instead
            // of leaving it in place, or every later launch would be
            // dropped too and Recents would stay dead.
            Log.w(TAG, "loadHistory: malformed history, clearing it")
            saveHistory(emptyList())
            return emptyList()
        }
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
