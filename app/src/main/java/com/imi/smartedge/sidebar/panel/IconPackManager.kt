package com.imi.smartedge.sidebar.panel

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.util.Log
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStreamReader
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Advanced Icon Pack Manager.
 * Supports: appfilter.xml direct and heuristic mapping.
 */
class IconPackManager(private val context: Context) {

    private val packageManager: PackageManager = context.packageManager

    // Using a simple cache to avoid repeated XML parsing during a session.
    // Keyed by pack + versionCode, so a pack update re-parses instead of
    // serving the pre-update mapping until process death. Uninstall /
    // reinstall with the same versionCode is handled by invalidateCache(),
    // called from the service's package receiver.
    companion object {
        private val iconMapCache = ConcurrentHashMap<String, Map<String, String>>()

        /** Drop all cached mappings for a pack (install / update / remove). */
        fun invalidateCache(iconPackPackage: String) {
            val prefix = "$iconPackPackage|"
            iconMapCache.keys.removeIf { it == iconPackPackage || it.startsWith(prefix) }
        }
    }

    private fun cacheKey(iconPackPackage: String): String {
        if (iconPackPackage == "none") return "none"
        val version = try {
            androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(
                packageManager.getPackageInfo(iconPackPackage, 0)
            )
        } catch (_: Exception) {
            0L
        }
        return "$iconPackPackage|$version"
    }

    fun getInstalledIconPacks(): List<IconPackInfo> {
        val iconPacks = mutableListOf<IconPackInfo>()
        val intents = arrayOf("com.novalauncher.THEME", "org.adw.launcher.THEMES", "com.gau.go.launcherex.theme")
        val packages = mutableSetOf<String>()
        for (action in intents) {
            val list = packageManager.queryIntentActivities(Intent(action), PackageManager.GET_META_DATA)
            for (info in list) {
                val pkg = info.activityInfo.packageName
                if (packages.add(pkg)) {
                    iconPacks.add(IconPackInfo(pkg, info.loadLabel(packageManager).toString(), info.loadIcon(packageManager)))
                }
            }
        }
        return iconPacks.sortedBy { it.label.lowercase() }
    }

    private fun loadAppFilter(iconPackPackage: String): Map<String, String> {
        val key = cacheKey(iconPackPackage)
        val cached = iconMapCache[key]
        if (cached != null) return cached

        val newMap = mutableMapOf<String, String>()
        if (iconPackPackage == "none") {
            iconMapCache[key] = newMap
            return newMap
        }

        try {
            val iconPackRes = packageManager.getResourcesForApplication(iconPackPackage)
            // Assets first: assets/appfilter.xml is plain text XML and the
            // text pull parser handles it.
            val assetStream = try {
                iconPackRes.assets.open("appfilter.xml")
            } catch (_: Exception) {
                null
            }
            if (assetStream != null) {
                // use{} closes the stream even when the parser throws
                // mid-loop (previously close() ran only on the success path,
                // leaking the stream on a malformed appfilter.xml).
                assetStream.use { stream ->
                    val factory = XmlPullParserFactory.newInstance()
                    val parser = factory.newPullParser()
                    parser.setInput(InputStreamReader(stream))
                    parseAppFilter(parser, newMap)
                }
            } else {
                // Fallback: res/xml/appfilter.xml is COMPILED binary XML —
                // openRawResource + a text parser throws on it. getXml
                // returns an XmlResourceParser, which already IS a pull
                // parser, so the same loop parses it directly.
                val resId = iconPackRes.getIdentifier("appfilter", "xml", iconPackPackage)
                if (resId != 0) {
                    val parser = iconPackRes.getXml(resId)
                    try {
                        parseAppFilter(parser, newMap)
                    } finally {
                        parser.close()
                    }
                } else {
                    // No appfilter at all: cache the empty map so we don't
                    // re-probe the pack on every icon load.
                    iconMapCache[key] = newMap
                    return newMap
                }
            }
        } catch (e: Exception) {
            Log.e("IconPackManager", "Error parsing appfilter: ${e.message}")
        }
        // Cache even on failure (possibly partial/empty): a broken
        // appfilter was re-parsed on every getThemedIcon() call (once per
        // icon) because the empty map was never cached.
        iconMapCache[key] = newMap
        return newMap
    }

    /**
     * Shared <item component drawable> loop for both appfilter sources.
     * Both the package key and the full component key are stored: the
     * package key is the fallback getThemedIcon() looks up, and the
     * component key keeps per-activity entries from collapsing to
     * "last wins" for callers that resolve by component.
     */
    private fun parseAppFilter(parser: XmlPullParser, out: MutableMap<String, String>) {
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && parser.name == "item") {
                val component = parser.getAttributeValue(null, "component")
                val drawable = parser.getAttributeValue(null, "drawable")
                if (component != null && drawable != null) {
                    out[component] = drawable
                    val pkg = extractPackage(component)
                    if (pkg.isNotEmpty()) out[pkg] = drawable
                }
            }
            eventType = parser.next()
        }
    }

    private fun extractPackage(component: String): String {
        // Handle format: ComponentInfo{com.pkg/com.pkg.Activity}
        if (component.startsWith("ComponentInfo{")) {
            val start = component.indexOf("{") + 1
            val end = component.indexOf("/")
            if (start > 0 && end > start) {
                return component.substring(start, end)
            }
        }
        // Plain form: com.pkg/.Activity (or com.pkg/Activity) — split on "/".
        // Previously this returned "" and the mapping was discarded even
        // though the drawable exists.
        val slash = component.indexOf("/")
        if (slash > 0) {
            return component.substring(0, slash)
        }
        return ""
    }

    fun getThemedIcon(packageName: String, originalIcon: Drawable, iconPackPackage: String): Drawable {
        if (iconPackPackage == "none") return originalIcon

        val map = loadAppFilter(iconPackPackage)

        // Fetch the pack's Resources once per call (was called twice per
        // icon: once for the direct mapping, once for the heuristic).
        val iconPackRes = try {
            packageManager.getResourcesForApplication(iconPackPackage)
        } catch (e: Exception) {
            return originalIcon
        }

        // 1. Try direct mapping from appfilter.xml
        val drawableName = map[packageName]
        if (drawableName != null) {
            try {
                val resId = iconPackRes.getIdentifier(drawableName, "drawable", iconPackPackage)
                if (resId != 0) return iconPackRes.getDrawable(resId, null)
            } catch (e: Exception) {}
        }

        // 2. Try heuristic mapping (com.pkg.app -> com_pkg_app)
        try {
            val resName = packageName.replace(".", "_").lowercase(java.util.Locale.ROOT)
            val resId = iconPackRes.getIdentifier(resName, "drawable", iconPackPackage)
            if (resId != 0) return iconPackRes.getDrawable(resId, null)
        } catch (e: Exception) {}

        // Fallback to system icon (Masking is unreliable on some devices)
        return originalIcon
    }
}

data class IconPackInfo(val packageName: String, val label: String, val icon: Drawable)
