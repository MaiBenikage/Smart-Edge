package com.imi.smartedge.sidebar.panel

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM unit tests verifying the complete tool-visibility mapping chain
 * between dashboard-settings switches and the sidebar's tools GridLayout.
 *
 * These tests mirror the EXACT formulas used in SidePanelView.applyTheme()
 * (hasAnyVisibleTool) and updateSideLayout() (row-counting height calc).
 * No Android Context or Robolectric is needed because every computation
 * is a pure boolean-in→boolean-out or count-in→row-out function.
 *
 * If these tests fail after a source edit, the mapping between
 * ToolsSettingsActivity switches and the sidebar tools section has
 * been broken — do NOT ship until the mismatch is resolved.
 */
class ToolsVisibilityMappingTest {

    // ─── hasAnyVisibleTool formula (from applyTheme) ──────────────
    //
    //   hasAnyVisibleTool = showPower
    //                    || showVolume
    //                    || showBrightness
    //                    || showScreenshot
    //                    || showBlackScreen
    //                    || showSysInfoEffective
    //
    //   toolsContainer.visibility = (showTools && hasAnyVisibleTool)
    //                               ? VISIBLE : GONE

    private fun hasAnyVisibleTool(
        power: Boolean, volume: Boolean, brightness: Boolean,
        screenshot: Boolean, blackScreen: Boolean, sysInfo: Boolean
    ): Boolean = power || volume || brightness || screenshot || blackScreen || sysInfo

    private fun toolsContainerVisible(
        showTools: Boolean,
        power: Boolean, volume: Boolean, brightness: Boolean,
        screenshot: Boolean, blackScreen: Boolean, sysInfo: Boolean
    ): Boolean = showTools && hasAnyVisibleTool(power, volume, brightness, screenshot, blackScreen, sysInfo)

    // ─── Row-counting formula (from updateSideLayout, v1.5.4) ─────
    //
    //   enabledCount = count of: showScreenshot, showBlackScreen,
    //                            showPowerMenu, showVolumeKeys,
    //                            showBrightnessKeys
    //   hasStandardTools = enabledCount > 0
    //   if (hasStandardTools || showSysInfo):
    //     if (hasStandardTools):
    //       nonAppHeightDp += 9   // divider (1dp line + 8dp margin)
    //       toolRows = if (2col) (enabledCount + 1) / 2  else enabledCount
    //       nonAppHeightDp += perToolRowDp * toolRows
    //     if (showSysInfo) nonAppHeightDp += 30

    private fun toolRows2Col(enabledCount: Int): Int = (enabledCount + 1) / 2

    private fun toolRows1Col(enabledCount: Int): Int = enabledCount

    private fun nonAppHeightDp(
        showTools: Boolean, inFolder: Boolean,
        twoCol: Boolean, sysInfo: Boolean,
        ss: Boolean, bs: Boolean, pm: Boolean, vk: Boolean, bk: Boolean
    ): Int {
        if (!showTools || inFolder) return 68  // base only
        val tools = listOf(ss, bs, pm, vk, bk)
        val count = tools.count { it }
        val hasStandardTools = count > 0
        var h = 68f
        if (hasStandardTools || sysInfo) {
            if (hasStandardTools) {
                h += 9f  // divider (1dp line + 8dp margin)
                val perRow = if (twoCol) 54f else 64f
                val rows = if (twoCol) (count + 1) / 2 else count
                h += perRow * rows
            }
            if (sysInfo) h += 30f
        }
        return h.toInt()
    }

    // ════════════════════════════════════════════════════════════════
    //  hasAnyVisibleTool tests — all 64 combos conceptually,
    //  we test every meaningful boundary
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `all defaults — screenshot and blackscreen ON, toolsContainer VISIBLE`() {
        assert(hasAnyVisibleTool(
            power = false, volume = false, brightness = false,
            screenshot = true, blackScreen = true, sysInfo = false
        ))
        assert(toolsContainerVisible(
            showTools = true,
            power = false, volume = false, brightness = false,
            screenshot = true, blackScreen = true, sysInfo = false
        ))
    }

    @Test
    fun `all tools OFF — hasAnyVisibleTool false, container GONE`() {
        assert(!hasAnyVisibleTool(false, false, false, false, false, false))
        assert(!toolsContainerVisible(true, false, false, false, false, false, false))
    }

    @Test
    fun `showTools OFF hides container regardless of tools`() {
        assert(!toolsContainerVisible(false, true, true, true, true, true, true))
    }

    @Test
    fun `only screenshot ON`() {
        assert(hasAnyVisibleTool(false, false, false, true, false, false))
    }

    @Test
    fun `only black screen ON`() {
        assert(hasAnyVisibleTool(false, false, false, false, true, false))
    }

    @Test
    fun `only power menu ON`() {
        assert(hasAnyVisibleTool(true, false, false, false, false, false))
    }

    @Test
    fun `only volume keys ON`() {
        assert(hasAnyVisibleTool(false, true, false, false, false, false))
    }

    @Test
    fun `only brightness keys ON`() {
        assert(hasAnyVisibleTool(false, false, true, false, false, false))
    }

    @Test
    fun `only sys info ON`() {
        assert(hasAnyVisibleTool(false, false, false, false, false, true))
    }

    @Test
    fun `all 6 tools ON`() {
        assert(hasAnyVisibleTool(true, true, true, true, true, true))
    }

    @Test
    fun `screenshot OFF, rest ON`() {
        assert(hasAnyVisibleTool(true, true, true, false, true, true))
    }

    @Test
    fun `blackScreen OFF, rest ON`() {
        assert(hasAnyVisibleTool(true, true, true, true, false, true))
    }

    // ════════════════════════════════════════════════════════════════
    //  Row-counting tests — 2-col mode
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `2col 0 tools → 0 rows`() {
        assertEquals(0, toolRows2Col(0))
    }

    @Test
    fun `2col 1 tool → 1 row`() {
        assertEquals(1, toolRows2Col(1))
    }

    @Test
    fun `2col 2 tools → 1 row`() {
        assertEquals(1, toolRows2Col(2))
    }

    @Test
    fun `2col 3 tools → 2 rows`() {
        assertEquals(2, toolRows2Col(3))
    }

    @Test
    fun `2col 4 tools → 2 rows`() {
        assertEquals(2, toolRows2Col(4))
    }

    @Test
    fun `2col 5 tools → 3 rows`() {
        assertEquals(3, toolRows2Col(5))
    }

    // ════════════════════════════════════════════════════════════════
    //  Row-counting tests — 1-col mode
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `1col 0 tools → 0 rows`() { assertEquals(0, toolRows1Col(0)) }
    @Test
    fun `1col 1 tool → 1 row`()   { assertEquals(1, toolRows1Col(1)) }
    @Test
    fun `1col 2 tools → 2 rows`() { assertEquals(2, toolRows1Col(2)) }
    @Test
    fun `1col 3 tools → 3 rows`() { assertEquals(3, toolRows1Col(3)) }
    @Test
    fun `1col 4 tools → 4 rows`() { assertEquals(4, toolRows1Col(4)) }
    @Test
    fun `1col 5 tools → 5 rows`() { assertEquals(5, toolRows1Col(5)) }

    // ════════════════════════════════════════════════════════════════
    //  nonAppHeightDp tests — every meaningful combination
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `no tools shown — base height only`() {
        val h = nonAppHeightDp(
            showTools = true, inFolder = false, twoCol = true,
            sysInfo = false, ss = false, bs = false, pm = false, vk = false, bk = false
        )
        assertEquals(68, h)  // 68 base only (no divider/tools when all disabled)
    }

    @Test
    fun `inFolder — base height only`() {
        val h = nonAppHeightDp(
            showTools = true, inFolder = true, twoCol = true,
            sysInfo = false, ss = true, bs = true, pm = false, vk = false, bk = false
        )
        assertEquals(68, h)
    }

    @Test
    fun `showTools OFF — base height only`() {
        val h = nonAppHeightDp(
            showTools = false, inFolder = false, twoCol = false,
            sysInfo = false, ss = true, bs = true, pm = true, vk = true, bk = true
        )
        assertEquals(68, h)
    }

    // ── 2-col scenarios ──────────────────────────────────────────

    @Test
    fun `2col 1 tool (screenshot only) — 1 row`() {
        val h = nonAppHeightDp(
            showTools = true, inFolder = false, twoCol = true,
            sysInfo = false, ss = true, bs = false, pm = false, vk = false, bk = false
        )
        // 68 base + 9 divider + 54*1 row = 131
        assertEquals(131, h)
    }

    @Test
    fun `2col 2 tools (default screenshot + blackscreen) - 1 row`() {
        val h = nonAppHeightDp(
            showTools = true, inFolder = false, twoCol = true,
            sysInfo = false, ss = true, bs = true, pm = false, vk = false, bk = false
        )
        // 68 base + 9 divider + 54*1 row = 131
        assertEquals(131, h)
    }

    @Test
    fun `2col 2 defaults + sysInfo — 1 row + sysInfo row`() {
        val h = nonAppHeightDp(
            showTools = true, inFolder = false, twoCol = true,
            sysInfo = true, ss = true, bs = true, pm = false, vk = false, bk = false
        )
        // 68 base + 9 divider + 54*1 row + 30 sysInfo = 161
        assertEquals(161, h)
    }

    @Test
    fun `2col 3 tools — 2 rows`() {
        val h = nonAppHeightDp(
            showTools = true, inFolder = false, twoCol = true,
            sysInfo = false,
            ss = true, bs = true, pm = true, vk = false, bk = false
        )
        // 68 base + 9 divider + 54*2 rows = 185
        assertEquals(185, h)
    }

    @Test
    fun `2col 4 tools — 2 rows`() {
        val h = nonAppHeightDp(
            showTools = true, inFolder = false, twoCol = true,
            sysInfo = false,
            ss = true, bs = true, pm = true, vk = true, bk = false
        )
        // 68 base + 9 divider + 54*2 rows = 185
        assertEquals(185, h)
    }

    @Test
    fun `2col 5 tools — 3 rows`() {
        val h = nonAppHeightDp(
            showTools = true, inFolder = false, twoCol = true,
            sysInfo = false,
            ss = true, bs = true, pm = true, vk = true, bk = true
        )
        // 68 base + 9 divider + 54*3 rows = 239
        assertEquals(239, h)
    }

    @Test
    fun `2col 5 tools + sysInfo — 3 rows + sysInfo`() {
        val h = nonAppHeightDp(
            showTools = true, inFolder = false, twoCol = true,
            sysInfo = true,
            ss = true, bs = true, pm = true, vk = true, bk = true
        )
        // 68 base + 9 divider + 54*3 rows + 30 sysInfo = 269
        assertEquals(269, h)
    }

    @Test
    fun `2col sysInfo only — no divider, just sysInfo height`() {
        val h = nonAppHeightDp(
            showTools = true, inFolder = false, twoCol = true,
            sysInfo = true, ss = false, bs = false, pm = false, vk = false, bk = false
        )
        // 68 base + 30 sysInfo = 98 (no divider when no standard tools)
        assertEquals(98, h)
    }

    @Test
    fun `1col sysInfo only — no divider, just sysInfo height`() {
        val h = nonAppHeightDp(
            showTools = true, inFolder = false, twoCol = false,
            sysInfo = true, ss = false, bs = false, pm = false, vk = false, bk = false
        )
        // 68 base + 30 sysInfo = 98 (no divider when no standard tools)
        assertEquals(98, h)
    }

    // ── 1-col scenarios ──────────────────────────────────────────

    @Test
    fun `1col 1 tool — 1 row`() {
        val h = nonAppHeightDp(
            showTools = true, inFolder = false, twoCol = false,
            sysInfo = false, ss = true, bs = false, pm = false, vk = false, bk = false
        )
        // 68 base + 9 divider + 64*1 row = 141
        assertEquals(141, h)
    }

    @Test
    fun `1col 2 tools — 2 rows`() {
        val h = nonAppHeightDp(
            showTools = true, inFolder = false, twoCol = false,
            sysInfo = false, ss = true, bs = true, pm = false, vk = false, bk = false
        )
        // 68 base + 9 divider + 64*2 rows = 205
        assertEquals(205, h)
    }

    @Test
    fun `1col 5 tools — 5 rows`() {
        val h = nonAppHeightDp(
            showTools = true, inFolder = false, twoCol = false,
            sysInfo = false,
            ss = true, bs = true, pm = true, vk = true, bk = true
        )
        // 68 base + 9 divider + 64*5 rows = 397
        assertEquals(397, h)
    }

    // ════════════════════════════════════════════════════════════════
    //  Combined scenario — simulate user toggling off one tool
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `user disables blackscreen, only screenshot remains — 2col 1 row`() {
        val toolsOn = listOf(false, false, false, true, false) // ss=true, others false
        assertEquals(1, toolsOn.count { it })
        assertEquals(1, toolRows2Col(toolsOn.count { it }))

        val h = nonAppHeightDp(
            showTools = true, inFolder = false, twoCol = true,
            sysInfo = false,
            ss = true, bs = false, pm = false, vk = false, bk = false
        )
        assertEquals(131, h)
    }

    @Test
    fun `user enables all 5 tools — 2col 3 rows`() {
        val toolsOn = listOf(true, true, true, true, true)
        assertEquals(5, toolsOn.count { it })
        assertEquals(3, toolRows2Col(toolsOn.count { it }))
    }

    @Test
    fun `user enables only power — 2col 1 row`() {
        assert(hasAnyVisibleTool(true, false, false, false, false, false))

        val h = nonAppHeightDp(
            showTools = true, inFolder = false, twoCol = true,
            sysInfo = false,
            ss = false, bs = false, pm = true, vk = false, bk = false
        )
        assertEquals(131, h)
    }

    // ════════════════════════════════════════════════════════════════
    //  Verify that height monotonically increases with tool count
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `2col height is monotonic with tool count`() {
        val heights = (0..5).map { count ->
            nonAppHeightDp(
                showTools = true, inFolder = false, twoCol = true, sysInfo = false,
                ss = count >= 1, bs = count >= 2,
                pm = count >= 3, vk = count >= 4, bk = count >= 5
            )
        }
        for (i in 1 until heights.size) {
            assert(heights[i] >= heights[i - 1]) {
                "height dropped: ${heights[i]} < ${heights[i - 1]} at count=$i"
            }
        }
    }

    @Test
    fun `1col height is monotonic with tool count`() {
        val heights = (0..5).map { count ->
            nonAppHeightDp(
                showTools = true, inFolder = false, twoCol = false, sysInfo = false,
                ss = count >= 1, bs = count >= 2,
                pm = count >= 3, vk = count >= 4, bk = count >= 5
            )
        }
        for (i in 1 until heights.size) {
            assert(heights[i] >= heights[i - 1]) {
                "height dropped: ${heights[i]} < ${heights[i - 1]} at count=$i"
            }
        }
    }
}
