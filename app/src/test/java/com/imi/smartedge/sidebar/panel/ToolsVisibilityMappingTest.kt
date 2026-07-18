package com.imi.smartedge.sidebar.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    // ─── Tool indices (matching getToolCellList() canonical order) ──
    // Index 0: PowerMenu
    // Index 1: Volume
    // Index 2: Brightness
    // Index 3: Screenshot
    // Index 4: BlackScreen
    // Index 5: LockScreen
    //
    // hasAnyVisibleTool = showPower || showVolume || showBrightness
    //                  || showScreenshot || showBlackScreen || showLockScreen
    //
    // toolsContainer.visibility = (showTools && hasAnyVisibleTool && !inFolder)
    //                             ? VISIBLE : GONE
    //
    // hasStandardTools = showPower || showVolume || showBrightness
    //                 || showScreenshot || showBlackScreen || showLockScreen

    private fun hasStandardTools(
        power: Boolean, volume: Boolean, brightness: Boolean,
        screenshot: Boolean, blackScreen: Boolean, lockScreen: Boolean
    ): Boolean = power || volume || brightness || screenshot || blackScreen || lockScreen

    private fun toolsContainerVisible(
        showTools: Boolean, inFolder: Boolean,
        power: Boolean, volume: Boolean, brightness: Boolean,
        screenshot: Boolean, blackScreen: Boolean, lockScreen: Boolean
    ): Boolean = showTools && !inFolder && hasStandardTools(power, volume, brightness, screenshot, blackScreen, lockScreen)

    // ─── Row-counting formula (from updateSideLayout, v1.8.x) ─────
    //
    //   enabledCount = count of: showScreenshot, showBlackScreen,
    //                            showPowerMenu, showVolumeKeys,
    //                            showBrightnessKeys, showLockScreen
    //   hasStandardTools = enabledCount > 0
    //   if (hasStandardTools || showSysInfo):
    //     if (hasStandardTools):
    //       nonAppHeightDp += 9   // divider (1dp line + 8dp margin)
    //       toolRows = if (2col) (enabledCount + 1) / 2  else enabledCount
    //       nonAppHeightDp += 72 * toolRows    // v1.8.x: unified 72dp
    //     if (showSysInfo) nonAppHeightDp += 30

    private fun toolRows2Col(enabledCount: Int): Int = (enabledCount + 1) / 2
    private fun toolRows1Col(enabledCount: Int): Int = enabledCount

    private fun nonAppHeightDp(
        showTools: Boolean, inFolder: Boolean,
        twoCol: Boolean, sysInfo: Boolean,
        pm: Boolean, vk: Boolean, bk: Boolean,
        ss: Boolean, bs: Boolean, ls: Boolean
    ): Int {
        if (!showTools || inFolder) return 68  // base only
        val count = listOf(pm, vk, bk, ss, bs, ls).count { it }
        val hasStdTools = count > 0
        var h = 68f
        if (hasStdTools || sysInfo) {
            if (hasStdTools) {
                h += 9f  // divider (1dp line + 8dp margin)
                val rows = if (twoCol) (count + 1) / 2 else count
                h += 72f * rows  // unified 72dp per row (v1.8.x)
            }
            if (sysInfo) h += 30f
        }
        return h.toInt()
    }

    // ─── Grid positioning logic (from syncToolsGridColumns) ───────
    //
    // For each VISIBLE tool cell (in getToolCellList() order):
    //   dividerVisible → rowOffset = 1, else 0
    //   2-col: row = rowOffset + toolIdx / 2,  col = toolIdx % 2
    //   1-col: row = rowOffset + toolIdx,       col = 0
    //
    // This is the exact formula that caused the crash when rowSpec
    // and columnSpec were swapped. Tests verify correct positions
    // for ALL 64 toggle combinations.

    data class CellPos(val row: Int, val col: Int)

    /**
     * Mirror of syncToolsGridColumns() positioning logic.
     * Returns list of (row, col) for each VISIBLE tool cell, plus the divider.
     * @param visibility array of 6 booleans: [power, volume, brightness, screenshot, blackScreen, lockScreen]
     * @param gridCols 1 or 2
     * @param dividerVisible whether the divider is shown
     */
    private fun computeGridPositions(
        visibility: BooleanArray,
        gridCols: Int,
        dividerVisible: Boolean
    ): Pair<List<CellPos>, CellPos?> {
        val toolPositions = mutableListOf<CellPos>()
        val rowOffset = if (dividerVisible) 1 else 0
        var toolIdx = 0
        for (i in visibility.indices) {
            if (visibility[i]) {
                val row = rowOffset + if (gridCols == 2) toolIdx / 2 else toolIdx
                val col = if (gridCols == 2) toolIdx % 2 else 0
                toolPositions.add(CellPos(row, col))
                toolIdx++
            }
        }
        val dividerPos = if (dividerVisible) CellPos(0, 0) else null
        return Pair(toolPositions, dividerPos)
    }

    /** Verify that all column indices are within [0, gridCols). */
    private fun assertColumnsValid(positions: List<CellPos>, gridCols: Int) {
        for (p in positions) {
            assertTrue(
                "col=${p.col} must be < gridCols=$gridCols (row=${p.row})",
                p.col in 0 until gridCols
            )
        }
    }

    /** Verify that no two VISIBLE tools occupy the same cell. */
    private fun assertNoOverlaps(positions: List<CellPos>) {
        val seen = mutableSetOf<Pair<Int, Int>>()
        for (p in positions) {
            val key = Pair(p.row, p.col)
            assertTrue(
                "Duplicate cell at row=${p.row}, col=${p.col}",
                seen.add(key)
            )
        }
    }

    /** Verify that VISIBLE tools are packed consecutively (no gaps). */
    private fun assertConsecutiveRows(positions: List<CellPos>, rowOffset: Int, gridCols: Int) {
        if (positions.isEmpty()) return
        val expectedMaxRow = rowOffset + (positions.size - 1) / gridCols
        val actualMaxRow = positions.maxOf { it.row }
        assertEquals(
            "Tools should be packed into consecutive rows",
            expectedMaxRow.toLong(), actualMaxRow.toLong()
        )
    }

    /**
     * Generate all 64 toggle combinations (2^6) as arrays of 6 booleans.
     * Index order matches getToolCellList(): power, volume, brightness,
     * screenshot, blackScreen, lockScreen.
     */
    private fun allToggleCombinations(): List<BooleanArray> {
        return (0 until 64).map { bits ->
            BooleanArray(6) { i -> (bits and (1 shl i)) != 0 }
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  hasStandardTools / toolsContainerVisible tests
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `all tools OFF — hasStandardTools false, container GONE`() {
        assertEquals(false, hasStandardTools(false, false, false, false, false, false))
        assertEquals(false, toolsContainerVisible(true, false, false, false, false, false, false, false))
    }

    @Test
    fun `showTools OFF hides container regardless of tools`() {
        assertEquals(false, toolsContainerVisible(false, false, true, true, true, true, true, true))
    }

    @Test
    fun `inFolder hides container regardless of tools`() {
        assertEquals(false, toolsContainerVisible(true, true, true, true, true, true, true, true))
    }

    @Test
    fun `only screenshot ON`() {
        assertEquals(true, hasStandardTools(false, false, false, true, false, false))
    }

    @Test
    fun `only black screen ON`() {
        assertEquals(true, hasStandardTools(false, false, false, false, true, false))
    }

    @Test
    fun `only power menu ON`() {
        assertEquals(true, hasStandardTools(true, false, false, false, false, false))
    }

    @Test
    fun `only volume keys ON`() {
        assertEquals(true, hasStandardTools(false, true, false, false, false, false))
    }

    @Test
    fun `only brightness keys ON`() {
        assertEquals(true, hasStandardTools(false, false, true, false, false, false))
    }

    @Test
    fun `only lock screen ON`() {
        assertEquals(true, hasStandardTools(false, false, false, false, false, true))
    }

    @Test
    fun `all 6 tools ON`() {
        assertEquals(true, hasStandardTools(true, true, true, true, true, true))
    }

    // ════════════════════════════════════════════════════════════════
    //  Row-counting tests — 2-col mode
    // ════════════════════════════════════════════════════════════════

    @Test fun `2col 0 tools → 0 rows`() { assertEquals(0, toolRows2Col(0)) }
    @Test fun `2col 1 tool → 1 row`() { assertEquals(1, toolRows2Col(1)) }
    @Test fun `2col 2 tools → 1 row`() { assertEquals(1, toolRows2Col(2)) }
    @Test fun `2col 3 tools → 2 rows`() { assertEquals(2, toolRows2Col(3)) }
    @Test fun `2col 4 tools → 2 rows`() { assertEquals(2, toolRows2Col(4)) }
    @Test fun `2col 5 tools → 3 rows`() { assertEquals(3, toolRows2Col(5)) }
    @Test fun `2col 6 tools → 3 rows`() { assertEquals(3, toolRows2Col(6)) }

    // ════════════════════════════════════════════════════════════════
    //  Row-counting tests — 1-col mode
    // ════════════════════════════════════════════════════════════════

    @Test fun `1col 0 tools → 0 rows`() { assertEquals(0, toolRows1Col(0)) }
    @Test fun `1col 1 tool → 1 row`()   { assertEquals(1, toolRows1Col(1)) }
    @Test fun `1col 2 tools → 2 rows`() { assertEquals(2, toolRows1Col(2)) }
    @Test fun `1col 3 tools → 3 rows`() { assertEquals(3, toolRows1Col(3)) }
    @Test fun `1col 4 tools → 4 rows`() { assertEquals(4, toolRows1Col(4)) }
    @Test fun `1col 5 tools → 5 rows`() { assertEquals(5, toolRows1Col(5)) }
    @Test fun `1col 6 tools → 6 rows`() { assertEquals(6, toolRows1Col(6)) }

    // ════════════════════════════════════════════════════════════════
    //  nonAppHeightDp tests — key scenarios
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `no tools shown — base height only`() {
        val h = nonAppHeightDp(
            showTools = true, inFolder = false, twoCol = true,
            sysInfo = false, pm = false, vk = false, bk = false,
            ss = false, bs = false, ls = false
        )
        assertEquals(68, h)
    }

    @Test
    fun `inFolder — base height only`() {
        val h = nonAppHeightDp(
            showTools = true, inFolder = true, twoCol = true,
            sysInfo = false, pm = false, vk = false, bk = false,
            ss = true, bs = true, ls = false
        )
        assertEquals(68, h)
    }

    @Test
    fun `showTools OFF — base height only`() {
        val h = nonAppHeightDp(
            showTools = false, inFolder = false, twoCol = false,
            sysInfo = false, pm = true, vk = true, bk = true,
            ss = true, bs = true, ls = true
        )
        assertEquals(68, h)
    }

    // ── 2-col scenarios ──────────────────────────────────────────

    @Test
    fun `2col 1 tool — 1 row`() {
        val h = nonAppHeightDp(
            showTools = true, inFolder = false, twoCol = true,
            sysInfo = false, pm = true, vk = false, bk = false,
            ss = false, bs = false, ls = false
        )
        // 68 base + 9 divider + 72*1 = 149
        assertEquals(149, h)
    }

    @Test
    fun `2col 2 tools — 1 row`() {
        val h = nonAppHeightDp(
            showTools = true, inFolder = false, twoCol = true,
            sysInfo = false, pm = false, vk = false, bk = false,
            ss = true, bs = true, ls = false
        )
        // 68 + 9 + 72*1 = 149
        assertEquals(149, h)
    }

    @Test
    fun `2col 3 tools — 2 rows`() {
        val h = nonAppHeightDp(
            showTools = true, inFolder = false, twoCol = true,
            sysInfo = false, pm = true, vk = true, bk = false,
            ss = true, bs = false, ls = false
        )
        // 68 + 9 + 72*2 = 221
        assertEquals(221, h)
    }

    @Test
    fun `2col 6 tools — 3 rows`() {
        val h = nonAppHeightDp(
            showTools = true, inFolder = false, twoCol = true,
            sysInfo = false, pm = true, vk = true, bk = true,
            ss = true, bs = true, ls = true
        )
        // 68 + 9 + 72*3 = 293
        assertEquals(293, h)
    }

    @Test
    fun `2col 6 tools + sysInfo — 3 rows + sysInfo`() {
        val h = nonAppHeightDp(
            showTools = true, inFolder = false, twoCol = true,
            sysInfo = true, pm = true, vk = true, bk = true,
            ss = true, bs = true, ls = true
        )
        // 68 + 9 + 72*3 + 30 = 323
        assertEquals(323, h)
    }

    @Test
    fun `2col sysInfo only — no divider`() {
        val h = nonAppHeightDp(
            showTools = true, inFolder = false, twoCol = true,
            sysInfo = true, pm = false, vk = false, bk = false,
            ss = false, bs = false, ls = false
        )
        // 68 + 30 = 98
        assertEquals(98, h)
    }

    // ── 1-col scenarios ──────────────────────────────────────────

    @Test
    fun `1col 1 tool — 1 row`() {
        val h = nonAppHeightDp(
            showTools = true, inFolder = false, twoCol = false,
            sysInfo = false, pm = false, vk = false, bk = false,
            ss = true, bs = false, ls = false
        )
        // 68 + 9 + 72*1 = 149
        assertEquals(149, h)
    }

    @Test
    fun `1col 2 tools — 2 rows`() {
        val h = nonAppHeightDp(
            showTools = true, inFolder = false, twoCol = false,
            sysInfo = false, pm = true, vk = true, bk = false,
            ss = false, bs = false, ls = false
        )
        // 68 + 9 + 72*2 = 221
        assertEquals(221, h)
    }

    @Test
    fun `1col 6 tools — 6 rows`() {
        val h = nonAppHeightDp(
            showTools = true, inFolder = false, twoCol = false,
            sysInfo = false, pm = true, vk = true, bk = true,
            ss = true, bs = true, ls = true
        )
        // 68 + 9 + 72*6 = 509
        assertEquals(509, h)
    }

    // ════════════════════════════════════════════════════════════════
    //  Height monotonicity
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `2col height is monotonic with tool count`() {
        val heights = (0..6).map { count ->
            nonAppHeightDp(
                showTools = true, inFolder = false, twoCol = true, sysInfo = false,
                pm = count >= 1, vk = count >= 2, bk = count >= 3,
                ss = count >= 4, bs = count >= 5, ls = count >= 6
            )
        }
        for (i in 1 until heights.size) {
            assertTrue("height dropped: ${heights[i]} < ${heights[i - 1]} at count=$i",
                heights[i] >= heights[i - 1])
        }
    }

    @Test
    fun `1col height is monotonic with tool count`() {
        val heights = (0..6).map { count ->
            nonAppHeightDp(
                showTools = true, inFolder = false, twoCol = false, sysInfo = false,
                pm = count >= 1, vk = count >= 2, bk = count >= 3,
                ss = count >= 4, bs = count >= 5, ls = count >= 6
            )
        }
        for (i in 1 until heights.size) {
            assertTrue("height dropped: ${heights[i]} < ${heights[i - 1]} at count=$i",
                heights[i] >= heights[i - 1])
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Grid positioning — ALL 64 toggle combinations × 2 column modes
    //
    //  This is the critical test suite that prevents the crash:
    //  "column indices (start + span) mustn't exceed the column count"
    //
    //  For each of the 64 toggle combinations, we verify:
    //  1. All column indices are within [0, gridCols)
    //  2. No two VISIBLE tools overlap
    //  3. VISIBLE tools are packed consecutively (no gaps)
    //  4. Divider is at row=0 when visible
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `2col grid — all 64 toggle combinations have valid positions`() {
        for ((_, vis) in allToggleCombinations().withIndex()) {
            val count = vis.count { it }
            if (count == 0) continue  // no tools = no positions to check

            val (toolPositions, dividerPos) = computeGridPositions(vis, gridCols = 2, dividerVisible = true)

            // All columns must be 0 or 1
            assertColumnsValid(toolPositions, 2)

            // No two tools at the same cell
            assertNoOverlaps(toolPositions)

            // Tools packed into consecutive rows (offset by divider at row 0)
            assertConsecutiveRows(toolPositions, rowOffset = 1, gridCols = 2)

            // Correct number of visible tools
            assertEquals(count.toLong(), toolPositions.size.toLong())

            // Divider at row=0
            assertEquals(0L, dividerPos!!.row.toLong())
        }
    }

    @Test
    fun `1col grid — all 64 toggle combinations have valid positions`() {
        for ((_, vis) in allToggleCombinations().withIndex()) {
            val count = vis.count { it }
            if (count == 0) continue

            val (toolPositions, dividerPos) = computeGridPositions(vis, gridCols = 1, dividerVisible = true)

            // All columns must be 0
            assertColumnsValid(toolPositions, 1)

            // No overlaps
            assertNoOverlaps(toolPositions)

            // Consecutive rows
            assertConsecutiveRows(toolPositions, rowOffset = 1, gridCols = 1)

            // Correct count
            assertEquals(count.toLong(), toolPositions.size.toLong())

            // Divider at row=0
            assertEquals(0L, dividerPos!!.row.toLong())
        }
    }

    @Test
    fun `2col grid — all 64 combos WITHOUT divider`() {
        for ((_, vis) in allToggleCombinations().withIndex()) {
            val count = vis.count { it }
            if (count == 0) continue

            val (toolPositions, dividerPos) = computeGridPositions(vis, gridCols = 2, dividerVisible = false)

            assertColumnsValid(toolPositions, 2)
            assertNoOverlaps(toolPositions)
            // Without divider, tools start at row 0
            assertConsecutiveRows(toolPositions, rowOffset = 0, gridCols = 2)
            assertEquals(null, dividerPos)
            assertEquals(count.toLong(), toolPositions.size.toLong())
        }
    }

    @Test
    fun `1col grid — all 64 combos WITHOUT divider`() {
        for ((_, vis) in allToggleCombinations().withIndex()) {
            val count = vis.count { it }
            if (count == 0) continue

            val (toolPositions, dividerPos) = computeGridPositions(vis, gridCols = 1, dividerVisible = false)

            assertColumnsValid(toolPositions, 1)
            assertNoOverlaps(toolPositions)
            assertConsecutiveRows(toolPositions, rowOffset = 0, gridCols = 1)
            assertEquals(null, dividerPos)
            assertEquals(count.toLong(), toolPositions.size.toLong())
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Grid positioning — specific regression tests
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `2col 6 tools — 3 rows, 2 per row, no overlaps`() {
        val vis = booleanArrayOf(true, true, true, true, true, true) // all 6
        val (pos, _) = computeGridPositions(vis, gridCols = 2, dividerVisible = true)
        // Row 1: tool0(0,0), tool1(0,1)
        // Row 2: tool2(1,0), tool3(1,1)
        // Row 3: tool4(2,0), tool5(2,1)
        assertEquals(6, pos.size)
        assertEquals(CellPos(1, 0), pos[0])
        assertEquals(CellPos(1, 1), pos[1])
        assertEquals(CellPos(2, 0), pos[2])
        assertEquals(CellPos(2, 1), pos[3])
        assertEquals(CellPos(3, 0), pos[4])
        assertEquals(CellPos(3, 1), pos[5])
    }

    @Test
    fun `1col 6 tools — 6 rows, all col=0`() {
        val vis = booleanArrayOf(true, true, true, true, true, true)
        val (pos, _) = computeGridPositions(vis, gridCols = 1, dividerVisible = true)
        assertEquals(6, pos.size)
        for (i in 0..5) {
            assertEquals(CellPos(1 + i, 0), pos[i])
        }
    }

    @Test
    fun `2col odd tools — tools packed correctly with last row having 1 tool`() {
        // 3 tools enabled (power, volume, brightness only)
        val vis = booleanArrayOf(true, true, true, false, false, false)
        val (pos, _) = computeGridPositions(vis, gridCols = 2, dividerVisible = true)
        assertEquals(3, pos.size)
        assertEquals(CellPos(1, 0), pos[0]) // power
        assertEquals(CellPos(1, 1), pos[1]) // volume
        assertEquals(CellPos(2, 0), pos[2]) // brightness — alone on row 2
    }

    @Test
    fun `1col sparse tools — gaps skipped, only visible tools get positions`() {
        // Only power (idx0) and lockScreen (idx5) enabled
        val vis = booleanArrayOf(true, false, false, false, false, true)
        val (pos, _) = computeGridPositions(vis, gridCols = 2, dividerVisible = true)
        assertEquals(2, pos.size)
        assertEquals(CellPos(1, 0), pos[0]) // power → toolIdx=0
        assertEquals(CellPos(1, 1), pos[1]) // lockScreen → toolIdx=1
    }

    @Test
    fun `single tool 2col — row=1 col=0`() {
        val vis = booleanArrayOf(false, false, false, true, false, false) // only screenshot
        val (pos, _) = computeGridPositions(vis, gridCols = 2, dividerVisible = true)
        assertEquals(1, pos.size)
        assertEquals(CellPos(1, 0), pos[0])
    }

    @Test
    fun `single tool 1col — row=1 col=0`() {
        val vis = booleanArrayOf(false, false, false, true, false, false)
        val (pos, _) = computeGridPositions(vis, gridCols = 1, dividerVisible = true)
        assertEquals(1, pos.size)
        assertEquals(CellPos(1, 0), pos[0])
    }
}
