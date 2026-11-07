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
    fun `empty sidebar excludes nothing`() {
        assertTrue(RecentAppsManager.excludedPackagesFor(emptyList()).isEmpty())
    }
}
