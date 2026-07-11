package com.imi.smartedge.sidebar.panel

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * Loads metadata for all user-installed, launchable apps from the PackageManager.
 * Icons are handled separately by Glide using the package name.
 */
class AppRepository(context: Context) {

    private val appContext = context.applicationContext
    private val packageManager: PackageManager = appContext.packageManager
    private val panelPrefs = PanelPreferences(appContext)
    private val iconPackManager = IconPackManager(appContext)

    companion object {
        // Aggressive memory cache for fully processed static bitmap icons to ensure buttery smooth scrolling
        val iconCache = android.util.LruCache<String, android.graphics.drawable.Drawable>(300)

        fun clearSystemIconCache(packageName: String? = null) {
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
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
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
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
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
     * App Shortcuts (Android 7.1+, API 25) grouped by owning package.
     * Returns an empty list on devices that don't support LauncherApps shortcuts,
     * or when the app doesn't have shortcut-host permission (most common case
     * unless Smart Edge is the default launcher). Each entry's `intentUri` is
     * the primary intent's URI, used as the unique identifier.
     */
    suspend fun getShortcutsByPackage(): List<Pair<String, List<AppInfo>>> = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return@withContext emptyList()
        val launcherApps = appContext.getSystemService(Context.LAUNCHER_APPS_SERVICE)
            as? android.content.pm.LauncherApps ?: return@withContext emptyList()
        if (!launcherApps.hasShortcutHostPermission()) return@withContext emptyList()

        val panelIds = panelPrefs.getPanelApps().toSet()
        val user = android.os.Process.myUserHandle()
        val result = mutableListOf<Pair<String, List<AppInfo>>>()

        try {
            val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(0L))
            } else {
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
                        // Disable any auto-launch behavior so the URL/intent fires fresh
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

        // Sort groups by app label
        result
            .map { (pkgName, scs) ->
                val appLabel = try {
                    val ai = packageManager.getApplicationInfo(pkgName, 0)
                    packageManager.getApplicationLabel(ai).toString()
                } catch (e: Exception) { pkgName }
                pkgName to scs to appLabel
            }
            .sortedBy { it.second.lowercase() }
            .map { (pair, _) -> pair }
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
