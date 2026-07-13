package com.imi.smartedge.sidebar.panel

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import org.xmlpull.v1.XmlPullParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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

    // Audit L3: cancel any in-flight icon preload jobs. Called from FloatingPanelService.onDestroy
    // so we don't leak a SupervisorJob past the service lifetime.
    // Safe to call multiple times — cancel() is idempotent.
    fun clear() {
        iconPreloadScope.cancel()
    }

    companion object {
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
     */
    suspend fun getAllApps(): List<AppInfo> = withContext(Dispatchers.IO) {
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
            addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        }

        val panelIdentifiers = panelPrefs.getPanelApps().toSet()

        val list = packageManager.queryIntentActivities(intent, 0)
            .distinctBy { it.activityInfo.packageName }
            .map { resolveInfo ->
                val pkg = resolveInfo.activityInfo.packageName
                    AppInfo(
                        packageName = pkg,
                        appName = resolveInfo.loadLabel(packageManager).toString(),
                        isInPanel = panelIdentifiers.contains(pkg),
                        type = AppInfo.Type.APP,
                        appearanceKey = panelPrefs.appearanceKey
                    )
            }
            .toMutableList()

        // Add Pseudo Shortcuts
        val oneHandPkg = "smartedge.shortcut.one_hand"
        list.add(AppInfo(oneHandPkg, "One-Handed Mode", panelIdentifiers.contains(oneHandPkg), AppInfo.Type.SHORTCUT))
        
        val sortedList = list.sortedBy { it.appName.lowercase() }

        // Aggressively pre-load icons into the memory cache in the background
        iconPreloadScope.launch {
            sortedList.take(50).forEach { appInfo ->
                getProcessedIcon(appInfo.packageName, appInfo.appearanceKey ?: "")
            }
        }

        sortedList
    }

    /**
     * Returns ALL exported activities for each installed app.
     */
    suspend fun getAllActivities(): List<AppInfo> = withContext(Dispatchers.IO) {
        val panelIdentifiers = panelPrefs.getPanelApps().toSet()
        val allActivities = mutableListOf<AppInfo>()

        try {
            val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(PackageManager.GET_ACTIVITIES.toLong()))
            } else {
                packageManager.getInstalledPackages(PackageManager.GET_ACTIVITIES)
            }

            for (pkg in packages) {
                val activities = pkg.activities ?: continue
                for (act in activities) {
                    try {
                        if (!act.exported) continue
                        
                        // Construct a URI for this specific activity
                        val intent = android.content.Intent().apply {
                            setClassName(pkg.packageName, act.name)
                        }
                        val uri = intent.toUri(android.content.Intent.URI_INTENT_SCHEME)
                        
                        allActivities.add(AppInfo(
                            packageName = pkg.packageName,
                            appName = act.loadLabel(packageManager).toString().takeIf { it.isNotBlank() } ?: act.name.substringAfterLast("."),
                            isInPanel = panelIdentifiers.contains(uri),
                            type = AppInfo.Type.ACTIVITY,
                            intentUri = uri,
                            activityName = act.name,
                            appearanceKey = panelPrefs.appearanceKey
                        ))
                    } catch (e: Exception) {
                        continue
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore
        }

        val sortedList = allActivities.sortedBy { it.appName.lowercase() }

        // Aggressively pre-load icons into the memory cache in the background
        iconPreloadScope.launch {
            sortedList.take(50).forEach { appInfo ->
                getProcessedIcon(appInfo.packageName, appInfo.appearanceKey ?: "")
            }
        }

        return@withContext sortedList
    }    /**
     * Resolves a list of identifiers into AppInfo objects.
     */
    suspend fun getAppsForIdentifiers(identifiers: List<String>): List<AppInfo> = withContext(Dispatchers.IO) {
        if (identifiers.isEmpty()) return@withContext emptyList()

        identifiers.mapNotNull { id ->
            when {
                id == "smartedge.shortcut.one_hand" -> {
                    AppInfo(id, "One-Handed Mode", true, AppInfo.Type.SHORTCUT, appearanceKey = panelPrefs.appearanceKey)
                }
                id == "smartedge.shortcut.reboot" -> {
                    AppInfo(id, "Power Menu", true, AppInfo.Type.SHORTCUT, appearanceKey = panelPrefs.appearanceKey)
                }
                id.startsWith("smartedge.folder.") -> {
                    val name = id.substringAfterLast(".").replaceFirstChar { it.uppercase() }
                    AppInfo(id, name, true, AppInfo.Type.FOLDER, appearanceKey = panelPrefs.appearanceKey)
                }
                id.startsWith("smartedge.tool.") -> {
                    val name = id.substringAfterLast(".").replaceFirstChar { it.uppercase() }
                    AppInfo(id, name, true, AppInfo.Type.TOOL, appearanceKey = panelPrefs.appearanceKey)
                }
                id.startsWith(PanelPreferences.CUSTOM_ID_PREFIX) -> {
                    // smartedge.custom.<uuid> — look up the CustomItem, synthesize an AppInfo.
                    val customId = id.removePrefix(PanelPreferences.CUSTOM_ID_PREFIX)
                    val item = panelPrefs.getCustomItems().firstOrNull { it.id == customId }
                    if (item != null) {
                        AppInfo(
                            packageName = PanelPreferences.CUSTOM_ID_PREFIX.removeSuffix("."),
                            appName = item.title.ifBlank { "Untitled" },
                            isInPanel = true,
                            type = AppInfo.Type.CUSTOM,
                            intentUri = item.content,
                            activityName = if (item.isUrl) "URL" else "INTENT",
                            appearanceKey = panelPrefs.appearanceKey
                        )
                    } else null
                }
                id.startsWith("intent:") -> {
                    try {
                        val intent = android.content.Intent.parseUri(id, android.content.Intent.URI_INTENT_SCHEME)
                        val pkg = intent.getPackage() ?: intent.component?.packageName ?: ""

                        val resolveInfo = packageManager.resolveActivity(intent, 0)
                        val name = resolveInfo?.loadLabel(packageManager)?.toString()
                                   ?: intent.component?.shortClassName?.substringAfterLast(".")
                                   ?: "Unknown Activity"

                        AppInfo(
                            packageName = pkg,
                            appName = name,
                            isInPanel = true,
                            type = AppInfo.Type.ACTIVITY,
                            intentUri = id,
                            activityName = intent.component?.className,
                            appearanceKey = panelPrefs.appearanceKey
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
                            isInPanel = true,
                            type = AppInfo.Type.APP,
                            appearanceKey = panelPrefs.appearanceKey
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
    suspend fun getActivitiesByPackage(): List<Pair<String, List<AppInfo>>> = withContext(Dispatchers.IO) {
        val panelIds = panelPrefs.getPanelApps().toSet()
        val grouped = linkedMapOf<String, MutableList<AppInfo>>()

        try {
            val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(PackageManager.GET_ACTIVITIES.toLong()))
            } else {
                packageManager.getInstalledPackages(PackageManager.GET_ACTIVITIES)
            }

            for (pkg in packages) {
                val activities = pkg.activities ?: continue
                val list = mutableListOf<AppInfo>()
                for (act in activities) {
                    if (!act.exported) continue
                    val intent = android.content.Intent().apply { setClassName(pkg.packageName, act.name) }
                    val uri = intent.toUri(android.content.Intent.URI_INTENT_SCHEME)
                    list.add(
                        AppInfo(
                            packageName = pkg.packageName,
                            appName = act.loadLabel(packageManager).toString().takeIf { it.isNotBlank() }
                                ?: act.name.substringAfterLast("."),
                            isInPanel = panelIds.contains(uri),
                            type = AppInfo.Type.ACTIVITY,
                            intentUri = uri,
                            activityName = act.name,
                            appearanceKey = panelPrefs.appearanceKey
                        )
                    )
                }
                if (list.isNotEmpty()) {
                    grouped[pkg.packageName] = list
                }
            }
        } catch (e: Exception) {
            // Ignore
        }

        // Sort by app label, then by activity label inside each group
        grouped.entries
            .map { (pkgName, acts) ->
                val appLabel = try {
                    val ai = packageManager.getApplicationInfo(pkgName, 0)
                    packageManager.getApplicationLabel(ai).toString()
                } catch (e: Exception) { pkgName }
                pkgName to acts.sortedBy { it.appName.lowercase() } to appLabel
            }
            .sortedBy { it.second.lowercase() }
            .map { (pair, _) -> pair }
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
    suspend fun getShortcutsByPackage(): List<Pair<String, List<AppInfo>>> = withContext(Dispatchers.IO) {
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
                Triple(pkgName, scs.sortedBy { it.appName.lowercase() }, appLabel)
            }
            .sortedBy { it.third.lowercase() }
            .map { (pkg, scs, _) -> pkg to scs }
    }

    /**
     * Static shortcuts read from each package's publicly-resolvable shortcuts.xml
     * (the resource referenced from its manifest's `<meta-data android:name="android.app.shortcuts">`
     * tag). [PackageManager.getResourcesForApplication] requires no special permission.
     */
    private suspend fun getStaticShortcutsByPackage(): List<Pair<String, List<AppInfo>>> = withContext(Dispatchers.IO) {
        val result = mutableListOf<Pair<String, List<AppInfo>>>()
        try {
            val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getInstalledPackages(
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_META_DATA.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getInstalledPackages(PackageManager.GET_META_DATA)
            }
            for (pkg in packages) {
                try {
                    val metaData = pkg.applicationInfo?.metaData ?: continue
                    val xmlResId = metaData.getInt("android.app.shortcuts", 0)
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
                            android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST
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
                            appearanceKey = panelPrefs.appearanceKey
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
     * Filter: shortcuts whose target intent can't be resolved by the current
     * [PackageManager] (target package uninstalled, action unsupported, etc.) are
     * dropped because they'd no-op on tap.
     */
    private fun parseStaticShortcutsXml(
        resources: android.content.res.Resources,
        xmlResId: Int,
        owningPackage: String
    ): List<AppInfo> {
        val items = mutableListOf<AppInfo>()
        val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        try {
            val parser = resources.getXml(xmlResId)
            val panelIds = panelPrefs.getPanelApps().toSet()

            // Scratch state shared across one <shortcut> element.
            var currentShortcutId: String? = null
            var currentLabel: String? = null
            var currentEnabled = true
            var icAction: String? = null
            var icData: String? = null
            var icTargetPackage: String? = null
            var icTargetClass: String? = null

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "shortcut" -> {
                                currentShortcutId = parser.getAttributeValue(null, "android:shortcutId")
                                currentEnabled = parser.getAttributeBooleanValue(null, "android:enabled", true)

                                // Label may be a string-resource ref (@string/foo) or a literal.
                                val labelResId = parser.getAttributeResourceValue(ANDROID_NS, "shortcutShortLabel", 0)
                                currentLabel = when {
                                    labelResId != 0 -> try { resources.getString(labelResId) } catch (e: Exception) { null }
                                    else -> parser.getAttributeValue(ANDROID_NS, "shortcutShortLabel")
                                }
                            }
                            "intent" -> {
                                icAction = parser.getAttributeValue(null, "android:action")
                                icData = parser.getAttributeValue(null, "android:data")
                                icTargetPackage = parser.getAttributeValue(null, "android:targetPackage")
                                icTargetClass = parser.getAttributeValue(null, "android:targetClass")
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
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
                                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                // Drop shortcuts whose target is no longer installed/actionable.
                                val resolved = try {
                                    packageManager.resolveActivity(intent, 0)
                                } catch (e: Exception) {
                                    null
                                }
                                if (resolved != null) {
                                    val uri = intent.toUri(android.content.Intent.URI_INTENT_SCHEME)
                                    items.add(
                                        AppInfo(
                                            packageName = owningPackage,
                                            appName = (currentLabel ?: currentShortcutId).takeIf { it.isNotBlank() } ?: currentShortcutId,
                                            isInPanel = panelIds.contains(uri),
                                            type = AppInfo.Type.SHORTCUT,
                                            intentUri = uri,
                                            activityName = currentShortcutId,
                                            appearanceKey = panelPrefs.appearanceKey
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
                            icAction = null
                            icData = null
                            icTargetPackage = null
                            icTargetClass = null
                        }
                    }
                }
                parser.next()
            }
            parser.close()
        } catch (e: Exception) {
            // Skip this resource if parsing fails.
        }
        return items
    }

    /**
     * Returns only the items currently pinned to the panel.
     */
    suspend fun getPanelApps(): List<AppInfo> = withContext(Dispatchers.IO) {
        val pinnedIdentifiers = panelPrefs.getPanelApps()
        val allIdentifiers = pinnedIdentifiers.toMutableList()

        if (panelPrefs.showNotificationApps) {
            val notifyApps = NotificationTrackingService.getActiveNotificationPackages()
            for (pkg in notifyApps.reversed()) {
                if (!allIdentifiers.contains(pkg)) {
                    allIdentifiers.add(0, pkg)
                }
            }
        }
        
        getAppsForIdentifiers(allIdentifiers)
    }

    suspend fun getTop5Apps(): List<String> = withContext(Dispatchers.IO) {
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
            addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        }
        packageManager.queryIntentActivities(intent, 0)
            .distinctBy { it.activityInfo.packageName }
            .take(5)
            .map { it.activityInfo.packageName }
    }
}
