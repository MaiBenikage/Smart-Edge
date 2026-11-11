package com.imi.smartedge.sidebar.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests for the apps/recent split and the no-duplication
 * rule for the Recent section. No Android Context or Robolectric is needed:
 * [RecentAppsManager.excludedPackagesFor] only touches Android's
 * `Intent.parseUri` for custom items whose payload is an `intent:` URI,
 * and that path is guarded by try/catch, so it degrades to "no exclusion"
 * under the JVM stubs used here.
 *
 * The split-height tests call the REAL implementation,
 * [AppsRecentSplit.splitHeights] — the same function SidePanelView uses —
 * so a formula change that breaks the tests is a real regression, not
 * test/production drift.
 */
class AppsRecentSplitAndRecentsTest {

    // ─── Split percent defaults / limits (PanelPreferences) ─────────────

    @Test
    fun `default split is 50 50`() {
        assertEquals(50, PanelPreferences.DEFAULT_APPS_RECENT_SPLIT_PERCENT)
    }

    @Test
    fun `split limits are 20 to 80`() {
        assertEquals(20, PanelPreferences.MIN_APPS_RECENT_SPLIT_PERCENT)
        assertEquals(80, PanelPreferences.MAX_APPS_RECENT_SPLIT_PERCENT)
        assertTrue(
            PanelPreferences.DEFAULT_APPS_RECENT_SPLIT_PERCENT in
                PanelPreferences.MIN_APPS_RECENT_SPLIT_PERCENT..
                PanelPreferences.MAX_APPS_RECENT_SPLIT_PERCENT
        )
    }

    // ─── Split-height formula (AppsRecentSplit.splitHeights) ─────────────

    private fun splitHeights(combinedDp: Float, splitPercent: Int): Pair<Float, Float> =
        AppsRecentSplit.splitHeights(combinedDp, splitPercent)

    @Test
    fun `50 50 split divides the budget equally`() {
        val (apps, recent) = splitHeights(486f, 50)
        assertEquals(243f, apps)
        assertEquals(243f, recent)
    }

    @Test
    fun `split preserves the total budget`() {
        for (combined in listOf(200f, 486f, 570f)) {
            for (split in listOf(20, 35, 50, 65, 80)) {
                val (apps, recent) = splitHeights(combined, split)
                assertEquals(combined, apps + recent, 0.001f)
            }
        }
    }

    @Test
    fun `dragging down grows apps and shrinks recents`() {
        val (appsBefore, recentBefore) = splitHeights(486f, 50)
        val (appsAfter, recentAfter) = splitHeights(486f, 70)
        assertTrue(appsAfter > appsBefore)
        assertTrue(recentAfter < recentBefore)
    }

    @Test
    fun `split clamp guarantees at least one partial row per list`() {
        // Even at the 20% extreme, neither list drops below 56dp on a
        // normal-size budget.
        val (apps, recent) = splitHeights(486f, 20)
        assertTrue(apps >= 56f)
        assertTrue(recent >= 56f)
        val (apps2, recent2) = splitHeights(486f, 80)
        assertTrue(apps2 >= 56f)
        assertTrue(recent2 >= 56f)
    }

    @Test
    fun `small budget falls back to 50 50 instead of collapsing a list`() {
        // A11: when the budget can't hold two 56dp minima (<= 112dp),
        // the split falls back to 50/50 so neither list collapses to ~0.
        // (The old code clamped one list to 56dp and left the other with
        // the remainder, e.g. 80/20 on a 100dp budget.)
        val (apps, recent) = splitHeights(100f, 80)
        assertEquals(50f, apps, 0.001f)
        assertEquals(50f, recent, 0.001f)
        val (apps2, recent2) = splitHeights(100f, 20)
        assertEquals(50f, apps2, 0.001f)
        assertEquals(50f, recent2, 0.001f)
        // Boundary: exactly two minima still splits 50/50.
        val (apps3, recent3) = splitHeights(112f, 80)
        assertEquals(56f, apps3, 0.001f)
        assertEquals(56f, recent3, 0.001f)
    }

    @Test
    fun `just above the small-budget boundary the minimum still holds`() {
        // 200dp at 80%: apps wants 160dp but is capped so recents keeps 56dp.
        val (apps, recent) = splitHeights(200f, 80)
        assertEquals(144f, apps, 0.001f)
        assertEquals(56f, recent, 0.001f)
        // 200dp at 20%: apps is floored to its 56dp minimum.
        val (apps2, recent2) = splitHeights(200f, 20)
        assertEquals(56f, apps2, 0.001f)
        assertEquals(144f, recent2, 0.001f)
    }

    @Test
    fun `split percent is clamped inside the function`() {
        // Out-of-range splits (e.g. from a corrupt pref) can't produce a
        // negative height: they clamp to 20..80.
        val (apps, recent) = splitHeights(400f, 5)
        assertEquals(80f, apps, 0.001f)
        assertEquals(320f, recent, 0.001f)
        val (apps2, recent2) = splitHeights(400f, 99)
        assertEquals(320f, apps2, 0.001f)
        assertEquals(80f, recent2, 0.001f)
    }

    // ─── excludedPackagesFor — no duplication in recents ────────────────

    @Test
    fun `plain pinned apps are excluded by package`() {
        val sidebar = listOf(
            AppInfo("com.example.mail", "Mail"),
            AppInfo("com.example.music", "Music")
        )
        assertEquals(
            setOf("com.example.mail", "com.example.music"),
            RecentAppsManager.excludedPackagesFor(sidebar)
        )
    }

    @Test
    fun `pinned activity is excluded by its owning package, not its intent uri`() {
        val sidebar = listOf(
            AppInfo(
                packageName = "com.example.mail",
                appName = "Compose",
                type = AppInfo.Type.ACTIVITY,
                intentUri = "intent:#Intent;component=com.example.mail/.ComposeActivity;end"
            )
        )
        assertEquals(
            setOf("com.example.mail"),
            RecentAppsManager.excludedPackagesFor(sidebar)
        )
    }

    @Test
    fun `tools folders and shortcuts are not real packages and are skipped`() {
        val sidebar = listOf(
            AppInfo("smartedge.tool.tools", "Tools", type = AppInfo.Type.TOOL),
            AppInfo("smartedge.folder.tools", "Tools", type = AppInfo.Type.FOLDER),
            AppInfo("smartedge.shortcut.reboot", "Power Menu", type = AppInfo.Type.SHORTCUT),
            AppInfo("com.example.mail", "Mail")
        )
        assertEquals(
            setOf("com.example.mail"),
            RecentAppsManager.excludedPackagesFor(sidebar)
        )
    }

    @Test
    fun `custom url item is skipped, duplicate packages are deduped`() {
        val sidebar = listOf(
            AppInfo(
                packageName = "smartedge.custom.11111111-1111-1111-1111-111111111111",
                appName = "My Site",
                type = AppInfo.Type.CUSTOM,
                intentUri = "https://example.com"
            ),
            AppInfo("com.example.mail", "Mail"),
            AppInfo(
                packageName = "com.example.mail",
                appName = "Compose",
                type = AppInfo.Type.ACTIVITY,
                intentUri = "intent:#Intent;component=com.example.mail/.ComposeActivity;end"
            )
        )
        assertEquals(
            setOf("com.example.mail"),
            RecentAppsManager.excludedPackagesFor(sidebar)
        )
    }

    @Test
    fun `custom intent item is excluded by its intent package`() {
        // The CUSTOM + intent: branch used to call Intent.parseUri (an
        // Android stub on the JVM), so it was never exercised. The package
        // now comes from the pure packageFromIntentUri extractor.
        val sidebar = listOf(
            AppInfo(
                packageName = "smartedge.custom.22222222-2222-2222-2222-222222222222",
                appName = "Maps Link",
                type = AppInfo.Type.CUSTOM,
                intentUri = "intent:#Intent;component=com.example.maps/.MapsActivity;end"
            )
        )
        assertEquals(
            setOf("com.example.maps"),
            RecentAppsManager.excludedPackagesFor(sidebar)
        )
    }

    @Test
    fun `custom intent item package param wins over component`() {
        val sidebar = listOf(
            AppInfo(
                packageName = "smartedge.custom.33333333-3333-3333-3333-333333333333",
                appName = "Scan Link",
                type = AppInfo.Type.CUSTOM,
                intentUri = "intent:#Intent;package=com.example.scan;component=com.other/.Main;end"
            )
        )
        assertEquals(
            setOf("com.example.scan"),
            RecentAppsManager.excludedPackagesFor(sidebar)
        )
    }

    @Test
    fun `malformed custom intent payload excludes nothing`() {
        val sidebar = listOf(
            AppInfo(
                packageName = "smartedge.custom.44444444-4444-4444-4444-444444444444",
                appName = "Broken",
                type = AppInfo.Type.CUSTOM,
                intentUri = "intent:garbage"
            ),
            AppInfo(
                packageName = "smartedge.custom.55555555-5555-5555-5555-555555555555",
                appName = "Relative",
                type = AppInfo.Type.CUSTOM,
                intentUri = "intent:#Intent;component=.RelativeActivity;end"
            )
        )
        assertTrue(RecentAppsManager.excludedPackagesFor(sidebar).isEmpty())
    }

    @Test
    fun `selector params do not leak into the main intent package`() {
        assertEquals(
            "com.example.main",
            RecentAppsManager.packageFromIntentUri(
                "intent:#Intent;component=com.example.main/.Main;SEL;component=com.example.sel/.Sel;end"
            )
        )
    }

    @Test
    fun `empty sidebar excludes nothing`() {
        assertTrue(RecentAppsManager.excludedPackagesFor(emptyList()).isEmpty())
    }
}
