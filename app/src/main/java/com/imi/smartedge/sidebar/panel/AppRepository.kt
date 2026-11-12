package com.imi.smartedge.sidebar.panel

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import org.xmlpull.v1.XmlPullParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Loads metadata for all user-installed, launchable apps from the PackageManager.
 * Icons are handled separately by Glide using the package name.
 */
class AppRepository(context: Context) {

    private val appContext = context.applicationContext
    private val packageManager: PackageManager = appContext.packageManager
    private val panelPrefs = PanelPreferences(appContext)
    private val iconPackManager = IconPackManager(appContext)

    // Per-instance coroutine scope for icon preloading. Replaces the two
    // `GlobalScope.launch(...)` sites below, which were flagged as a
    // `Delicate API` warning because `GlobalScope` has no structured-concurrency
    // parent (no cancellation tracking vs. the host service lifetime).
    private val iconPreloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var activitiesCache: List<PickerGroup>? = null
    @Volatile private var activitiesCacheAtMs: Long = 0L

    // Single-flight guard for getActivitiesByPackage(): without it the
    // label backfill and the picker run duplicate full PM scans
    // concurrently instead of sharing one result.
    private val activitiesScanMutex = Mutex()

    /**
     * One package's picker group: its activities or shortcuts, plus the
     * display label the repository already resolved (via PackageManager)
     * while building the group. Carrying the label here means the picker
     * view never has to call `getApplicationInfo()` on the main thread
     * per group header (see resolveAppLabel, now deleted).
     */
    data class PickerGroup(
        val packageName: String,
        val appLabel: String,
        val items: List<AppInfo>
    )

    /**
     * Unified picker cache for the APPS / ACTIVITIES / SHORTCUTS tabs.
     * The three tabs share one list and one refresh path so they are always
     * consistent with each other ("one cache list, easy to maintain & refresh").
     * CUSTOM-tab items are user data from SharedPreferences and stay separate.
     */
    data class PickerData(
        val apps: List<AppInfo>,
        val activities: List<PickerGroup>,
        val shortcuts: List<PickerGroup>,
        val loadedAtMs: Long
    )

    @Volatile private var pickerCache: PickerData? = null

    /**
     * Serializes getPickerData() loads within this instance so two
     * concurrent callers cannot both miss the cache and each run the
     * three expensive PackageManager scans (check-then-act race).
     */
    private val pickerMutex = Mutex()

    /**
     * Load (or return the cached) picker data for all three tabs at once.
     * The three sources load in parallel — activities/shortcuts discovery is
     * slow while the app list is fast, so parallel loading shortens the wait.
     *
     * Audit fix: the cache previously never expired — `loadedAtMs` was
     * stored but never read, so installs/uninstalls never appeared until
     * a forced refresh. It now honours the same 60s TTL as the
     * activities cache. The load is also serialized with [pickerMutex]:
     * a second concurrent caller waits, then hits the freshly filled
     * cache instead of duplicating the scans.
     */
    /**
     * Refresh the icon/theme key on a cached [PickerData] without
     * re-running the scans. The cache-hit paths used to return the cached
     * items wholesale, so an icon-pack or theme change didn't show in the
     * picker until the 60 s TTL lapsed. The copy is cheap (data-class
     * copies, no I/O) and the cache itself is left untouched.
     */
    private fun refreshAppearanceKey(data: PickerData, appearanceKey: String): PickerData {
        return data.copy(
            apps = data.apps.map { it.copy(appearanceKey = appearanceKey) },
            activities = data.activities.map { group ->
                group.copy(items = group.items.map { it.copy(appearanceKey = appearanceKey) })
            },
            shortcuts = data.shortcuts.map { group ->
                group.copy(items = group.items.map { it.copy(appearanceKey = appearanceKey) })
            }
        )
    }

    suspend fun getPickerData(forceRefresh: Boolean = false): PickerData {
        val cached = pickerCache
        if (!forceRefresh && cached != null &&
            android.os.SystemClock.uptimeMillis() - cached.loadedAtMs < ACTIVITIES_CACHE_TTL_MS
        ) {
            return refreshAppearanceKey(cached, panelPrefs.appearanceKey)
        }
        return pickerMutex.withLock {
            // Re-check under the lock: another caller may have filled the
            // cache while this one was waiting.
            val fresh = pickerCache
            if (!forceRefresh && fresh != null &&
                android.os.SystemClock.uptimeMillis() - fresh.loadedAtMs < ACTIVITIES_CACHE_TTL_MS
            ) {
                return@withLock refreshAppearanceKey(fresh, panelPrefs.appearanceKey)
            }
            if (forceRefresh) {
                // getActivitiesByPackage() has its own 60s TTL cache — a forced
                // refresh must punch through it too, or the ACTIVITIES tab would
                // keep showing stale entries.
                activitiesCache = null
                activitiesCacheAtMs = 0L
            }
            coroutineScope {
                val appsDeferred = async { getAllApps() }
                val activitiesDeferred = async { getActivitiesByPackage() }
                val shortcutsDeferred = async { getShortcutsByPackage() }
                PickerData(
                    apps = appsDeferred.await(),
                    activities = activitiesDeferred.await(),
                    shortcuts = shortcutsDeferred.await(),
                    loadedAtMs = android.os.SystemClock.uptimeMillis()
                ).also { pickerCache = it }
            }
        }
    }

    /** Drop the unified picker cache AND the activities sub-cache, so an
     * invalidate really is an invalidate: previously only `pickerCache`
     * was nulled and the ACTIVITIES tab kept serving the stale
     * `activitiesCache` until its 60 s TTL lapsed. */
    fun clearPickerCache() {
        pickerCache = null
        activitiesCache = null
        activitiesCacheAtMs = 0L
    }

    // Audit L3: cancel any in-flight icon preload jobs. Called from FloatingPanelService.onDestroy
    // so we don't leak a SupervisorJob past the service lifetime.
    // Round-1 audit: cancel() destroys the scope's Job permanently — if AppRepository is reused
    // (e.g. service restart), future launches silently fail. Use cancelChildren() instead,
    // which cancels running jobs but leaves the scope alive for the next icon preload batch.
    fun clear() {
        iconPreloadScope.coroutineContext.cancelChildren()
        activitiesCache = null
        activitiesCacheAtMs = 0L
        pickerCache = null
    }

    companion object {
        private const val TAG = "AppRepository"

        // Round-12 audit L-High: original cache used item-COUNT sizing
        // (`LruCache<String, Drawable>(300)`), which means "300 entries
        // regardless of how big each icon is". A 256×256 ARGB_8888 BitmapDrawable
        // weighs ~256 KiB in heap; 300 entries could pin 75+ MiB on a
        // memory-constrained device and contribute to OOM when the sidebar
        // scrolls rapidly. Switch to byte-based sizing and override
        // sizeOf() so the cap scales with actual icon footprint rather than
        // entry count. 24 MiB covers ~96 fully-resolved 256×256 icons — more
        // than the working set for any realistic sidebar.
        private const val ICON_CACHE_BYTES = 24 * 1024 * 1024
        /** Cache TTL for activities/shortcuts discovery (ms). */
        private const val ACTIVITIES_CACHE_TTL_MS = 60_000L
        val iconCache = object : android.util.LruCache<String, android.graphics.drawable.Drawable>(ICON_CACHE_BYTES) {
            override fun sizeOf(key: String, value: android.graphics.drawable.Drawable): Int {
                if (value is android.graphics.drawable.BitmapDrawable) {
                    val bmp = value.bitmap
                    if (bmp != null && !bmp.isRecycled) {
                        return bmp.allocationByteCount.coerceAtLeast(1)
                    }
                }
                // Adaptive / non-bitmap drawables: estimate by intrinsic size,
                // or fall back to 64 KiB so a single entry can't monopolize
                // the cache through sizeOf() returning 0.
                val w = value.intrinsicWidth.coerceAtLeast(1)
                val h = value.intrinsicHeight.coerceAtLeast(1)
                return (w * h * 4).coerceAtLeast(64 * 1024)
            }
        }

        fun clearSystemIconCache() {
            iconCache.evictAll()
        }

        /**
         * Process-wide shared instance. AppRepository keeps only the
         * application context (never an Activity), so sharing is
         * leak-free. Call sites that used to allocate a fresh repository
         * per call (RecentAppsManager.getTopRecentApps, every
         * AppIconDataFetcher.loadData) now share this one instead of
         * rebuilding the picker cache and icon-pack state on every call.
         */
        @Volatile
        private var sharedInstance: AppRepository? = null

        fun shared(context: Context): AppRepository {
            return sharedInstance ?: synchronized(this) {
                sharedInstance ?: AppRepository(context.applicationContext)
                    .also { sharedInstance = it }
            }
        }

        /**
         * The label backfill is one-shot per *process*, not per instance:
         * it was an instance field, so every new AppRepository (new
         * picker/service lifetime) repeated the two full PM scans whenever
         * any pinned intent: label was unfillable (e.g. target app
         * uninstalled but pin kept). Static, so the scans run at most once.
         */
        @Volatile
        private var labelsBackfillAttempted = false
    }

    /**
     * Loads, processes, and caches the icon for a single app.
     * Uses the "Nuclear Option" to convert Adaptive Icons to static BitmapDrawables.
     */
    fun getProcessedIcon(packageName: String, appearanceKey: String): android.graphics.drawable.Drawable? {
        val cacheKey = "pkg:$packageName|state:$appearanceKey"
        iconCache.get(cacheKey)?.let { return it }

        val rawIcon = loadIconForAppSync(packageName) ?: return null

        val processedIcon = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && rawIcon is android.graphics.drawable.AdaptiveIconDrawable) {
            try {
                val density = appContext.resources.displayMetrics.density
                // Double the size for 2x super-sampling (ensures perfectly smooth edges)
                val size = (144 * density).toInt().coerceAtLeast(256)
                val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bitmap)

                // To draw 108dp unclipped layers into a 72dp bitmap:
                // The layers must be 1.5x larger than the bitmap.
                val layerSize = (size * 1.5f).toInt()
                val offset = (size - layerSize) / 2
                val layerBounds = android.graphics.Rect(offset, offset, offset + layerSize, offset + layerSize)

                rawIcon.background?.mutate()?.let {
                    it.bounds = layerBounds
                    it.draw(canvas)
                }
                rawIcon.foreground?.mutate()?.let {
                    it.bounds = layerBounds
                    it.draw(canvas)
                }

                android.graphics.drawable.BitmapDrawable(appContext.resources, bitmap)
            } catch (e: Exception) {
                rawIcon.mutate()
            }
        } else {
            rawIcon.mutate()
        }

        iconCache.put(cacheKey, processedIcon)
        return processedIcon
    }

    /**
     * Loads and returns the icon for a single app.
     * Synchronous version for background threads.
     */
    fun loadIconForAppSync(packageName: String): android.graphics.drawable.Drawable? {
        val selectedPack = panelPrefs.selectedIconPack
        
        // try getting the properly colored launcher icons using LauncherApps
        val systemIcon = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val launcherApps = appContext.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? android.content.pm.LauncherApps
                val activityList = launcherApps?.getActivityList(packageName, android.os.Process.myUserHandle())
                if (!activityList.isNullOrEmpty()) {
                    activityList[0].getIcon(0)
                } else {
                    val appInfo = packageManager.getApplicationInfo(packageName, 0)
                    appInfo.loadIcon(packageManager)
                }
            } else {
                val appInfo = packageManager.getApplicationInfo(packageName, 0)
                appInfo.loadIcon(packageManager)
            }
        } catch (e: Exception) {
            try {
                packageManager.getApplicationIcon(packageName)
            } catch (e2: Exception) {
                null
            }
        }

        if (systemIcon == null) return null

        // Apply icon pack (with masking support)
        return iconPackManager.getThemedIcon(packageName, systemIcon, selectedPack)
    }

    /**
     * Returns all launchable apps with metadata only.
     *
     * @param preloadIcons whether to pre-load the first 50 icons in the
     * background. Callers that only need the app list (e.g. the settings
     * dialogs) pass false so a simple list doesn't pay for 50 icon
     * processings.
     */
    suspend fun getAllApps(preloadIcons: Boolean = true): List<AppInfo> = withContext(Dispatchers.IO) {
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
            addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        }

        val panelIdentifiers = panelPrefs.getPanelApps().toSet()
        val appearanceKey = panelPrefs.appearanceKey

        // A transient PackageManager failure (e.g. system-server
        // RemoteException) used to crash the picker/service instead of
        // degrading to an empty list. It runs as an async child inside
        // getPickerData()'s coroutineScope, so a throw also cancelled the
        // sibling activities/shortcuts scans.
        val resolved = try {
            packageManager.queryIntentActivities(intent, 0)
        } catch (e: Exception) {
            Log.w(TAG, "getAllApps query failed", e)
            return@withContext emptyList()
        }

        val list = resolved
            .distinctBy { it.activityInfo.packageName }
            .filter { it.activityInfo.packageName != appContext.packageName }
            .map { resolveInfo ->
                val pkg = resolveInfo.activityInfo.packageName
                    AppInfo(
                        packageName = pkg,
                        appName = resolveInfo.loadLabel(packageManager).toString(),
                        isInPanel = panelIdentifiers.contains(pkg),
                        type = AppInfo.Type.APP,
                        appearanceKey = appearanceKey
                    )
            }
            .toMutableList()

        // Add Pseudo Shortcuts — both of them: the resolver
        // (getAppsForIdentifiers) handles one_hand and reboot, so the
        // picker should list both instead of hiding Power Menu.
        val oneHandPkg = "smartedge.shortcut.one_hand"
        list.add(AppInfo(oneHandPkg, "One-Handed Mode", panelIdentifiers.contains(oneHandPkg), AppInfo.Type.SHORTCUT, appearanceKey = appearanceKey))
        val rebootPkg = "smartedge.shortcut.reboot"
        list.add(AppInfo(rebootPkg, "Power Menu", panelIdentifiers.contains(rebootPkg), AppInfo.Type.SHORTCUT, appearanceKey = appearanceKey))
        
        val sortedList = list.sortedBy { it.appName.lowercase(java.util.Locale.ROOT) }

        // Aggressively pre-load icons into the memory cache in the background
        if (preloadIcons) {
            iconPreloadScope.launch {
                sortedList.take(50).forEach { appInfo ->
                    getProcessedIcon(appInfo.packageName, appInfo.appearanceKey ?: "")
                }
            }
        }

        sortedList
    }

    /**
     * Resolves a list of identifiers into AppInfo objects.
     */
    suspend fun getAppsForIdentifiers(identifiers: List<String>): List<AppInfo> = withContext(Dispatchers.IO) {
        if (identifiers.isEmpty()) return@withContext emptyList()

        // Audit fix: hoist the three per-identifier re-reads out of the
        // loop. Previously every custom id re-parsed the whole custom-items
        // JSON, every intent: id re-parsed the whole labels JSON, and every
        // AppInfo re-read three prefs for appearanceKey (3 SharedPreferences
        // reads per item). With N pinned items that was O(n²) JSON parsing.
        val appearanceKey = panelPrefs.appearanceKey
        val customItemsById = panelPrefs.getCustomItems().associateBy { it.id }
        val panelItemLabels = panelPrefs.getPanelItemLabels()
        // Pinned identifiers, for the plain-package branch below: a package
        // id here is not necessarily pinned — getPanelApps() also injects
        // notification apps, and getTopRecentApps() passes recents — so
        // isInPanel must be looked up, not hardcoded to true.
        val pinnedIdentifiers = panelPrefs.getPanelApps().toSet()

        identifiers.mapNotNull { id ->
            when {
                id == "smartedge.shortcut.one_hand" -> {
                    AppInfo(id, "One-Handed Mode", true, AppInfo.Type.SHORTCUT, appearanceKey = appearanceKey)
                }
                id == "smartedge.shortcut.reboot" -> {
                    AppInfo(id, "Power Menu", true, AppInfo.Type.SHORTCUT, appearanceKey = appearanceKey)
                }
                id.startsWith("smartedge.folder.") -> {
                    val name = id.substringAfterLast(".").replaceFirstChar { it.uppercase() }
                    AppInfo(id, name, true, AppInfo.Type.FOLDER, appearanceKey = appearanceKey)
                }
                id.startsWith("smartedge.tool.") -> {
                    val name = id.substringAfterLast(".").replaceFirstChar { it.uppercase() }
                    AppInfo(id, name, true, AppInfo.Type.TOOL, appearanceKey = appearanceKey)
                }
                id.startsWith(PanelPreferences.CUSTOM_ID_PREFIX) -> {
                    // smartedge.custom.<uuid> — look up the CustomItem, synthesize an AppInfo.
                    // packageName holds the full sidebar identifier so AppInfo.identifier is stable.
                    val customId = id.removePrefix(PanelPreferences.CUSTOM_ID_PREFIX)
                    val item = customItemsById[customId]
                    if (item != null) {
                        AppInfo(
                            packageName = id,
                            appName = item.title.ifBlank { "Untitled" },
                            isInPanel = true,
                            type = AppInfo.Type.CUSTOM,
                            intentUri = item.content,
                            activityName = if (item.isUrl) "URL" else "INTENT",
                            appearanceKey = appearanceKey
                        )
                    } else null
                }
                id.startsWith("intent:") -> {
                    try {
                        val intent = android.content.Intent.parseUri(id, android.content.Intent.URI_INTENT_SCHEME)
                        val resolveInfo = packageManager.resolveActivity(intent, 0)
                        // The owning package, for the icon and the exclusion
                        // set: static shortcuts with only action+data carry
                        // neither an explicit package nor a component, so
                        // fall back to the resolved activity's package
                        // instead of "" (which yields no icon).
                        val pkg = intent.`package` ?: intent.component?.packageName
                            ?: resolveInfo?.activityInfo?.packageName ?: ""

                        // Prefer the label captured when the user pinned the item
                        // (shortcut shortLabel / activity label from the picker).
                        // resolveActivity().loadLabel() often returns the *app* name
                        // for shortcut intents, which is wrong in the sidebar.
                        val savedLabel = panelItemLabels[id]
                        val resolvedName = resolveInfo?.loadLabel(packageManager)?.toString()
                        val fallback = intent.component?.shortClassName?.substringAfterLast('.')
                            ?: "Unknown Activity"
                        val name = savedLabel?.takeIf { it.isNotBlank() }
                            ?: resolvedName
                            ?: fallback

                        // If we stored a pin-time label that differs from the resolved
                        // app/activity label, treat as SHORTCUT for icon/launch paths
                        // that branch on type; otherwise ACTIVITY.
                        val resolvedType = if (savedLabel != null &&
                            resolvedName != null &&
                            !savedLabel.equals(resolvedName, ignoreCase = true)
                        ) {
                            AppInfo.Type.SHORTCUT
                        } else {
                            AppInfo.Type.ACTIVITY
                        }

                        AppInfo(
                            packageName = pkg,
                            appName = name,
                            isInPanel = true,
                            type = resolvedType,
                            intentUri = id,
                            activityName = intent.component?.className,
                            appearanceKey = appearanceKey
                        )
                    } catch (e: Exception) {
                        null
                    }
                }
                else -> {
                    try {
                        val appInfo = packageManager.getApplicationInfo(id, 0)
                        AppInfo(
                            packageName = id,
                            appName = packageManager.getApplicationLabel(appInfo).toString(),
                            isInPanel = id in pinnedIdentifiers,
                            type = AppInfo.Type.APP,
                            appearanceKey = appearanceKey
                        )
                    } catch (e: Exception) {
                        null
                    }
                }
            }
        }
    }

    /**
     * Activities grouped by their owning package, sorted by app label then activity label.
     * Returns pairs of (packageName, sorted-activity-list). Each list element's
     * `intentUri` is what callers should use as the unique identifier (matching the
     * `intent:` prefix handled by [getAppsForIdentifiers]).
     */
    suspend fun getActivitiesByPackage(): List<PickerGroup> = withContext(Dispatchers.IO) {
        // Single-flight: the backfill and the picker can otherwise run
        // duplicate full scans concurrently. The whole body (including the
        // cache check) runs under this mutex, so the second caller finds
        // the first scan's cached result instead of re-scanning.
        activitiesScanMutex.withLock {
        // Same clock as the picker TTL (uptimeMillis): a wall-clock jump
        // backwards used to make this cache never expire, forwards made it
        // expire instantly. Hoisted here so the cache-hit path and
        // addActivity() share one read (C24).
        val now = android.os.SystemClock.uptimeMillis()
        val appearanceKey = panelPrefs.appearanceKey
        activitiesCache?.let { cached ->
            if (now - activitiesCacheAtMs < ACTIVITIES_CACHE_TTL_MS) {
                val panelIdsCached = panelPrefs.getPanelApps().toSet()
                return@withContext cached.map { group ->
                    group.copy(items = group.items.map {
                        it.copy(
                            isInPanel = panelIdsCached.contains(it.identifier),
                            appearanceKey = appearanceKey
                        )
                    })
                }
            }
        }
        val panelIds = panelPrefs.getPanelApps().toSet()
        // packageName -> (activityName -> AppInfo) for de-dupe across discovery paths
        val byPkg = linkedMapOf<String, LinkedHashMap<String, AppInfo>>()

        fun addActivity(pkgName: String, activityName: String, label: String, uri: String) {
            val bag = byPkg.getOrPut(pkgName) { linkedMapOf() }
            if (bag.containsKey(activityName)) return
            bag[activityName] = AppInfo(
                packageName = pkgName,
                appName = label.ifBlank { activityName.substringAfterLast('.') },
                isInPanel = panelIds.contains(uri),
                type = AppInfo.Type.ACTIVITY,
                intentUri = uri,
                activityName = activityName,
                appearanceKey = appearanceKey
            )
        }

        // Path A: all exported+enabled activities from package manifests.
        try {
            val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(PackageManager.GET_ACTIVITIES.toLong()))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getInstalledPackages(PackageManager.GET_ACTIVITIES)
            }
            for (pkg in packages) {
                val activities = pkg.activities ?: continue
                for (act in activities) {
                    if (!act.exported || !act.enabled) continue
                    // Skip activities guarded by a permission we don't hold:
                    // listing one is pointless — tapping it throws
                    // SecurityException.
                    if (act.permission != null && androidx.core.content.ContextCompat.checkSelfPermission(appContext, act.permission) != PackageManager.PERMISSION_GRANTED) continue
                    val intent = android.content.Intent().apply { setClassName(pkg.packageName, act.name) }
                    val uri = intent.toUri(android.content.Intent.URI_INTENT_SCHEME)
                    val label = try {
                        act.loadLabel(packageManager).toString()
                    } catch (_: Exception) {
                        act.name.substringAfterLast('.')
                    }
                    addActivity(pkg.packageName, act.name, label, uri)
                }
            }
        } catch (_: Exception) {}

        // Path B: resolve activities handling common intents (share/view/edit/settings...).
        val commonActions = listOf(
            android.content.Intent.ACTION_MAIN,
            android.content.Intent.ACTION_VIEW,
            android.content.Intent.ACTION_SEND,
            android.content.Intent.ACTION_SENDTO,
            android.content.Intent.ACTION_EDIT,
            android.content.Intent.ACTION_DIAL,
            android.content.Intent.ACTION_WEB_SEARCH,
            android.content.Intent.ACTION_GET_CONTENT,
            android.content.Intent.ACTION_PICK,
            android.content.Intent.ACTION_CREATE_DOCUMENT,
            android.content.Intent.ACTION_OPEN_DOCUMENT,
            "android.intent.action.PROCESS_TEXT",
            "android.media.action.IMAGE_CAPTURE",
            "android.media.action.VIDEO_CAPTURE",
            "android.settings.SETTINGS"
        )
        for (action in commonActions) {
            try {
                val intent = android.content.Intent(action).apply {
                    if (action == android.content.Intent.ACTION_MAIN) {
                        addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                    }
                    if (action == android.content.Intent.ACTION_SEND ||
                        action == android.content.Intent.ACTION_SENDTO ||
                        action == android.content.Intent.ACTION_VIEW ||
                        action == android.content.Intent.ACTION_GET_CONTENT ||
                        action == android.content.Intent.ACTION_PICK ||
                        action == "android.intent.action.PROCESS_TEXT"
                    ) {
                        type = "*/*"
                    }
                }
                val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    packageManager.queryIntentActivities(
                        intent,
                        PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
                    )
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
                }
                for (ri in resolved) {
                    val ai = ri.activityInfo ?: continue
                    if (!ai.exported || !ai.enabled) continue
                    // Same permission guard as Path A.
                    if (ai.permission != null && androidx.core.content.ContextCompat.checkSelfPermission(appContext, ai.permission) != PackageManager.PERMISSION_GRANTED) continue
                    val componentIntent = android.content.Intent().apply {
                        setClassName(ai.packageName, ai.name)
                    }
                    val uri = componentIntent.toUri(android.content.Intent.URI_INTENT_SCHEME)
                    val label = try {
                        ri.loadLabel(packageManager).toString()
                    } catch (_: Exception) {
                        ai.name.substringAfterLast('.')
                    }
                    addActivity(ai.packageName, ai.name, label, uri)
                }
            } catch (_: Exception) {}
        }

        byPkg.entries
            .map { (pkgName, actsMap) ->
                val appLabel = try {
                    val ai = packageManager.getApplicationInfo(pkgName, 0)
                    packageManager.getApplicationLabel(ai).toString()
                } catch (_: Exception) { pkgName }
                PickerGroup(pkgName, appLabel, actsMap.values.sortedBy { it.appName.lowercase(java.util.Locale.ROOT) })
            }
            .sortedBy { it.appLabel.lowercase(java.util.Locale.ROOT) }
            .also {
                activitiesCache = it
                activitiesCacheAtMs = android.os.SystemClock.uptimeMillis()
            }
        }
    }

    /**
     * App Shortcuts grouped by owning package.
     *
     * Two parallel sources are queried and the union deduped by [AppInfo.intentUri]:
     *
     *   1. **Static** shortcuts published by each app in its manifest under
     *      `<meta-data android:name="android.app.shortcuts" android:resource="@xml/shortcuts" />`.
     *      Read via [PackageManager.getResourcesForApplication] — works **WITHOUT**
     *      Smart Edge being the default launcher. This is what covers Gmail, WhatsApp,
     *      Spotify etc. without prompting the user to switch launchers.
     *
     *   2. **Live** dynamic/pinned shortcuts via [android.content.pm.LauncherApps].
     *      Only available when the user has set Smart Edge as the default launcher and
     *      the system has granted us the privileged `hasShortcutHostPermission()` flag.
     *      Preserved for users who already opted into Smart Edge as their launcher
     *      and want their dynamic / runtime-pushed shortcuts.
     *
     * Both paths require API 25+ ([Build.VERSION_CODES.N_MR1]).
     * Sort order: groups by app label, items within a group by label.
     */
    suspend fun getShortcutsByPackage(): List<PickerGroup> = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return@withContext emptyList()

        // (1) Public path: static shortcuts via manifest meta-data. No permission.
        val staticGroups = getStaticShortcutsByPackage()

        // (2) Privileged path: only when we'll get past this check.
        val launcherApps = appContext.getSystemService(Context.LAUNCHER_APPS_SERVICE)
            as? android.content.pm.LauncherApps
        val liveGroups: List<Pair<String, List<AppInfo>>> =
            if (launcherApps?.hasShortcutHostPermission() == true) {
                getLiveShortcutsByPackage(launcherApps)
            } else emptyList()

        // Merge by package, dedupe by intentUri so a static shortcut that's also pinned
        // live doesn't appear twice in the picker.
        val merged = linkedMapOf<String, MutableList<AppInfo>>()
        (staticGroups + liveGroups).forEach { (pkg, items) ->
            val bag = merged.getOrPut(pkg) { mutableListOf() }
            items.forEach { incoming ->
                if (bag.none { it.intentUri == incoming.intentUri }) bag.add(incoming)
            }
        }

        // Sort groups by app label.
        merged.entries
            .map { (pkgName, scs) ->
                val appLabel = try {
                    packageManager.getApplicationLabel(
                        packageManager.getApplicationInfo(pkgName, 0)
                    ).toString()
                } catch (e: Exception) { pkgName }
                PickerGroup(pkgName, appLabel, scs.sortedBy { it.appName.lowercase(java.util.Locale.ROOT) })
            }
            .sortedBy { it.appLabel.lowercase(java.util.Locale.ROOT) }
    }

    /**
     * Static shortcuts read from each package's publicly-resolvable shortcuts.xml
     * (the resource referenced from its manifest's `<meta-data android:name="android.app.shortcuts">`
     * tag). [PackageManager.getResourcesForApplication] requires no special permission.
     */
    private suspend fun getStaticShortcutsByPackage(): List<Pair<String, List<AppInfo>>> = withContext(Dispatchers.IO) {
        // NB: no appearanceKey local here — static-shortcut AppInfo items are
        // built without one (the live path below hoists its own), and the
        // unused local this used to declare tripped a compiler warning.
        val result = mutableListOf<Pair<String, List<AppInfo>>>()
        try {
            // Round-17: must include GET_ACTIVITIES so pkg.activities is non-null.
            // android.app.shortcuts meta-data is declared on the MAIN/LAUNCHER
            // activity, not on the <application> tag, so we must iterate activities
            // to find the xmlResId.
            val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getInstalledPackages(
                    PackageManager.PackageInfoFlags.of(
                        (PackageManager.GET_META_DATA or PackageManager.GET_ACTIVITIES).toLong()
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getInstalledPackages(
                    PackageManager.GET_META_DATA or PackageManager.GET_ACTIVITIES
                )
            }
            for (pkg in packages) {
                try {
                    val shortcutMetaKeys = arrayOf(
                        "android.app.shortcuts",
                        "com.android.launcher.meta.APP_SHORTCUTS",
                        "android.app.shortcuts.dynamic"
                    )
                    // First check application-level meta-data (uncommon but possible).
                    var xmlResId = 0
                    val appMeta = pkg.applicationInfo?.metaData
                    if (appMeta != null) {
                        for (key in shortcutMetaKeys) {
                            xmlResId = appMeta.getInt(key, 0)
                            if (xmlResId > 0) break
                        }
                    }
                    // Fall back to activity-level meta-data (the standard location).
                    if (xmlResId <= 0) {
                        outer@ for (act in pkg.activities ?: emptyArray()) {
                            val md = act.metaData ?: continue
                            for (key in shortcutMetaKeys) {
                                val resId = md.getInt(key, 0)
                                if (resId > 0) {
                                    xmlResId = resId
                                    break@outer
                                }
                            }
                        }
                    }
                    if (xmlResId <= 0) continue
                    // getResourcesForApplication returns the app's own APK resources
                    // without elevating our privileges - this is the trick that lets us
                    // read another app's shortcuts.xml without being the default launcher.
                    val appResources = packageManager.getResourcesForApplication(pkg.packageName)
                    val items = parseStaticShortcutsXml(appResources, xmlResId, pkg.packageName)
                    if (items.isNotEmpty()) {
                        result.add(pkg.packageName to items)
                    }
                } catch (e: Exception) {
                    // Split APKs, missing resources, hidden metadata - skip silently.
                    continue
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
        result
    }

    /**
     * Live (dynamic + pinned) shortcuts via [android.content.pm.LauncherApps].
     * Requires `hasShortcutHostPermission()` — only true when we are the default
     * launcher. Same logic as the pre-v1.3.8 implementation, factored out so the
     * merged result below is easy to read.
     */
    private suspend fun getLiveShortcutsByPackage(
        launcherApps: android.content.pm.LauncherApps
    ): List<Pair<String, List<AppInfo>>> = withContext(Dispatchers.IO) {
        val appearanceKey = panelPrefs.appearanceKey
        val panelIds = panelPrefs.getPanelApps().toSet()
        val user = android.os.Process.myUserHandle()
        val result = mutableListOf<Pair<String, List<AppInfo>>>()
        try {
            val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getInstalledPackages(0)
            }
            for (pkg in packages) {
                try {
                    val query = android.content.pm.LauncherApps.ShortcutQuery().apply {
                        setPackage(pkg.packageName)
                        setQueryFlags(
                            android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                            android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED or
                            android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                            // MATCH_CACHED (API 30+) surfaces launcher-cached shortcuts.
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                                android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_CACHED
                            else 0
                        )
                    }
                    val shortcuts = launcherApps.getShortcuts(query, user) ?: continue
                    if (shortcuts.isEmpty()) continue

                    val items = shortcuts.mapNotNull { sc ->
                        val mainIntent: android.content.Intent = sc.intent ?: return@mapNotNull null
                        mainIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        val uri = mainIntent.toUri(android.content.Intent.URI_INTENT_SCHEME)
                        val label = sc.shortLabel?.toString()?.takeIf { it.isNotBlank() } ?: sc.id
                        AppInfo(
                            packageName = pkg.packageName,
                            appName = label,
                            isInPanel = panelIds.contains(uri),
                            type = AppInfo.Type.SHORTCUT,
                            intentUri = uri,
                            activityName = sc.id,
                            appearanceKey = appearanceKey
                        )
                    }
                    if (items.isNotEmpty()) {
                        result.add(pkg.packageName to items.sortedBy { it.appName.lowercase() })
                    }
                } catch (e: Exception) {
                    continue
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
        result
    }

    /**
     * Parse a single app's `res/xml/shortcuts.xml` for `<shortcut>` entries.
     *
     * Schema reference: https://developer.android.com/develop/ui/views/launch/shortcuts/creating-shortcuts#static
     *
     * Resource-ref handling: shortcut labels often appear as a string-resource
     * reference (`@string/foo`). We use [XmlPullParser.getAttributeResourceValue] which
     * returns 0 for non-resource attributes and the integer ID otherwise; the ID is
     * then resolved through the app's own [android.content.res.Resources], which works
     * without any permission (it's just an APK asset read).
     *
     * Filter: a shortcut is kept when its target resolves, or when it
     * names an explicit component/package or an action — deep links that
     * only resolve with runtime extras are kept too. Only shortcuts with
     * neither a resolvable target nor any explicit target/action are
     * dropped, because they'd no-op on tap.
     */
    private fun parseStaticShortcutsXml(
        resources: android.content.res.Resources,
        xmlResId: Int,
        owningPackage: String
    ): List<AppInfo> {
        val items = mutableListOf<AppInfo>()
        val appearanceKey = panelPrefs.appearanceKey
        val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        try {
            val parser = resources.getXml(xmlResId)
            try {
            val panelIds = panelPrefs.getPanelApps().toSet()

            // Scratch state shared across one <shortcut> element.
            var currentShortcutId: String? = null
            var currentLabel: String? = null
            var currentEnabled = true
            var icAction: String? = null
            var icData: String? = null
            var icTargetPackage: String? = null
            var icTargetClass: String? = null
            // Whether the parser is currently inside <intent>: <extra>
            // and <categories> are only valid as children of <intent>,
            // so the same tags elsewhere inside <shortcut> must not be
            // collected (a stray category breaks the launch intent and
            // changes the pin key). The START_TAG handler is a flat
            // `when (parser.name)` with no parent tracking, so this flag
            // is the parent.
            var inIntent = false
            // <extra android:name/value> children of <intent>, and
            // <categories android:name> children: without these, shortcuts
            // launch without the extras/categories their target requires.
            val icExtras = mutableListOf<Pair<String, String>>()
            val icCategories = mutableListOf<String>()

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "shortcut" -> {
                                // Round-17: null namespace → ANDROID_NS. The previous code passed `null`
                                // for the android: prefixed attributes, causing getAttributeValue() to
                                // return null on any parser that respects XML namespaces (i.e. most
                                // devices). Every static shortcut was silently dropped during parsing.
                                currentShortcutId = parser.getAttributeValue(ANDROID_NS, "shortcutId")
                                currentEnabled = parser.getAttributeBooleanValue(ANDROID_NS, "enabled", true)

                                // Label may be a string-resource ref (@string/foo) or a literal.
                                val labelResId = parser.getAttributeResourceValue(ANDROID_NS, "shortcutShortLabel", 0)
                                currentLabel = when {
                                    labelResId != 0 -> try { resources.getString(labelResId) } catch (e: Exception) { null }
                                    else -> parser.getAttributeValue(ANDROID_NS, "shortcutShortLabel")
                                }
                            }
                            "intent" -> {
                                inIntent = true
                                icAction = parser.getAttributeValue(ANDROID_NS, "action")
                                icData = parser.getAttributeValue(ANDROID_NS, "data")
                                icTargetPackage = parser.getAttributeValue(ANDROID_NS, "targetPackage")
                                icTargetClass = parser.getAttributeValue(ANDROID_NS, "targetClass")
                            }
                            // <extra android:name/value> and <categories
                            // android:name> children of <intent>: without
                            // these the shortcut launches without the
                            // extras/categories its target requires. Only
                            // inside <intent> — the same tags outside it
                            // are not part of the launch intent.
                            "extra" -> {
                                if (inIntent) {
                                    val name = parser.getAttributeValue(ANDROID_NS, "name")
                                    if (!name.isNullOrBlank()) {
                                        val valueResId = parser.getAttributeResourceValue(ANDROID_NS, "value", 0)
                                        val value = when {
                                            valueResId != 0 -> try { resources.getString(valueResId) } catch (_: Exception) { null }
                                            else -> parser.getAttributeValue(ANDROID_NS, "value")
                                        } ?: ""
                                        icExtras.add(name to value)
                                    }
                                }
                            }
                            "categories" -> {
                                if (inIntent) {
                                    val name = parser.getAttributeValue(ANDROID_NS, "name")
                                    if (!name.isNullOrBlank()) icCategories.add(name)
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "intent") {
                            inIntent = false
                        }
                        if (parser.name == "shortcut") {
                            if (currentEnabled && currentShortcutId != null) {
                                // Capture mutable vars into locals so Kotlin can smart-cast
                                // them inside the [apply] block (mutated-var access doesn't
                                // smart-cast in Kotlin 1.9).
                                val action = icAction
                                val data = icData
                                val targetPackage = icTargetPackage
                                val targetClass = icTargetClass
                                val intent = android.content.Intent().apply {
                                    if (!action.isNullOrBlank()) this.action = action
                                    if (!data.isNullOrBlank()) this.data = android.net.Uri.parse(data)
                                    if (!targetPackage.isNullOrBlank() && !targetClass.isNullOrBlank()) {
                                        setClassName(targetPackage, targetClass)
                                    } else if (!targetPackage.isNullOrBlank()) {
                                        `package` = targetPackage
                                    }
                                    for (category in icCategories) addCategory(category)
                                    for ((name, value) in icExtras) putExtra(name, value)
                                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                // Prefer resolvable targets; also keep explicit component / action
                                // shortcuts even when resolveActivity is null (deep links that only
                                // resolve with runtime extras).
                                val resolved = try {
                                    packageManager.resolveActivity(intent, 0)
                                } catch (_: Exception) {
                                    null
                                }
                                val hasExplicitTarget = !targetPackage.isNullOrBlank() || intent.component != null
                                if (resolved != null || hasExplicitTarget || !action.isNullOrBlank()) {
                                    val uri = intent.toUri(android.content.Intent.URI_INTENT_SCHEME)
                                    items.add(
                                        AppInfo(
                                            packageName = owningPackage,
                                            appName = (currentLabel ?: currentShortcutId).takeIf { it.isNotBlank() } ?: currentShortcutId,
                                            isInPanel = panelIds.contains(uri),
                                            type = AppInfo.Type.SHORTCUT,
                                            intentUri = uri,
                                            activityName = currentShortcutId,
                                            appearanceKey = appearanceKey
                                        )
                                    )
                                }
                            }
                            // Reset scratch state for the next <shortcut> sibling (defensive —
                            // XmlPullParser is event-driven so this mostly just keeps the
                            // state machine honest under malformed real-world XML).
                            currentShortcutId = null
                            currentLabel = null
                            currentEnabled = true
                            inIntent = false
                            icAction = null
                            icData = null
                            icTargetPackage = null
                            icTargetClass = null
                            icExtras.clear()
                            icCategories.clear()
                        }
                    }
                }
                parser.next()
            }
            } finally {
                // Close on every path: the old code closed only on success,
                // leaking the parser when parsing threw mid-document.
                parser.close()
            }
        } catch (e: Exception) {
            // Skip this resource if parsing fails.
        }
        return items
    }

    /**
     * Returns only the items currently pinned to the panel.
     */
    /**
     * One-shot / opportunistic backfill of display labels for pinned intent: URIs
     * that predate panel_item_labels. Matches static + live shortcut shortLabels
     * by intent URI so the sidebar shows the shortcut name, not the app name.
     */
    suspend fun backfillMissingPanelLabels(): Int = withContext(Dispatchers.IO) {
        val pinned = panelPrefs.getPanelApps().filter { it.startsWith("intent:") }
        if (pinned.isEmpty()) return@withContext 0
        // One JSON parse for all labels (was one getPanelItemLabel per id,
        // each re-parsing the whole labels JSON — O(n²)).
        val existingLabels = panelPrefs.getPanelItemLabels()
        val missing = pinned.filter { existingLabels[it].isNullOrBlank() }
        if (missing.isEmpty()) return@withContext 0

        val labelByUri = mutableMapOf<String, String>()
        try {
            for (group in getShortcutsByPackage()) {
                for (sc in group.items) {
                    val uri = sc.intentUri ?: continue
                    if (sc.appName.isNotBlank()) labelByUri[uri] = sc.appName
                }
            }
        } catch (_: Exception) {}

        // Also map activity labels from discovery cache / path for activity pins.
        try {
            for (group in getActivitiesByPackage()) {
                for (act in group.items) {
                    val uri = act.intentUri ?: continue
                    if (!labelByUri.containsKey(uri) && act.appName.isNotBlank()) {
                        labelByUri[uri] = act.appName
                    }
                }
            }
        } catch (_: Exception) {}

        var filled = 0
        val toWrite = mutableMapOf<String, String>()
        for (id in missing) {
            val label = labelByUri[id] ?: continue
            toWrite[id] = label
            filled++
        }
        // One JSON parse + one edit for all filled labels (was one
        // setPanelItemLabel per id, each re-parsing the whole labels JSON).
        if (toWrite.isNotEmpty()) panelPrefs.setPanelItemLabels(toWrite)
        filled
    }

    /** True if an intent: pin targets the given package (component or explicit package). */
    fun intentUriTargetsPackage(intentUri: String, packageName: String): Boolean {
        if (!intentUri.startsWith("intent:") || packageName.isBlank()) return false
        return try {
            val intent = android.content.Intent.parseUri(intentUri, android.content.Intent.URI_INTENT_SCHEME)
            val pkg = intent.`package` ?: intent.component?.packageName
            pkg == packageName
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Called (once, on a background thread) when the async label backfill
     * fills at least one label, so the host can refresh the list and show
     * the backfilled names. Set by the hosting service.
     */
    var onLabelsBackfilled: (() -> Unit)? = null

    suspend fun getPanelApps(): List<AppInfo> = withContext(Dispatchers.IO) {
        // Opportunistic one-shot label backfill for legacy intent: pins.
        // Runs on the preload scope WITHOUT blocking this call: the
        // backfill does two full PackageManager scans, which used to block
        // the first sidebar open. When it fills labels, the host refreshes
        // (see onLabelsBackfilled).
        if (!labelsBackfillAttempted) {
            labelsBackfillAttempted = true
            iconPreloadScope.launch {
                try {
                    if (backfillMissingPanelLabels() > 0) {
                        onLabelsBackfilled?.invoke()
                    }
                } catch (_: Exception) {}
            }
        }

        val pinnedIdentifiers = panelPrefs.getPanelApps()
        val allIdentifiers = pinnedIdentifiers.toMutableList()

        if (panelPrefs.showNotificationApps) {
            val notifyApps = NotificationTrackingService.getActiveNotificationPackages()
            for (pkg in notifyApps.reversed()) {
                if (allIdentifiers.contains(pkg)) continue
                // Never inject ourselves (our foreground-service
                // notification) or system / non-launchable packages
                // (USB/debug notices, SystemUI, dialogs): they have no
                // launch intent and are not apps the user can open.
                if (pkg == appContext.packageName) continue
                if (packageManager.getLaunchIntentForPackage(pkg) == null) continue
                allIdentifiers.add(0, pkg)
            }
        }

        getAppsForIdentifiers(allIdentifiers)
    }

    /**
     * First five launchable packages, sorted by app label. Used for
     * first-run sidebar seeding. (Was `distinctBy { packageName }.take(5)`
     * with no sorting — order was whatever PackageManager returned, so
     * "top" meant nothing.)
     */
    suspend fun getTop5Apps(): List<String> = withContext(Dispatchers.IO) {
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
            addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        }
        val resolved = try {
            packageManager.queryIntentActivities(intent, 0)
        } catch (e: Exception) {
            Log.w(TAG, "getTop5Apps query failed", e)
            return@withContext emptyList()
        }
        resolved
            .distinctBy { it.activityInfo.packageName }
            .filter { it.activityInfo.packageName != appContext.packageName }
            .map { ri ->
                val pkg = ri.activityInfo.packageName
                val label = try {
                    ri.loadLabel(packageManager).toString().takeIf { it.isNotBlank() } ?: pkg
                } catch (_: Exception) {
                    pkg
                }
                pkg to label
            }
            .sortedBy { (_, label) -> label.lowercase(java.util.Locale.ROOT) }
            .take(5)
            .map { (pkg, _) -> pkg }
    }
}
