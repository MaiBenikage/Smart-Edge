package com.imi.smartedge.sidebar.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests for the tools-section layout mapping between the
 * dashboard-settings switches and the sidebar's tools GridLayout.
 *
 * These tests call the real production implementation
 * ([ToolsLayout], which `SidePanelView.computeNonAppHeightDp()` and
 * `syncToolsGridColumnsInner()` delegate to) — they do not mirror the
 * formulas locally. If production changes, these tests fail.
 *
 * The seven dashboard tools, in `getToolCellList()` canonical order:
 * Power Menu, Volume, Brightness, Screenshot, Black Screen, Lock Screen,
 * Content Picker.
 */
class ToolsVisibilityMappingTest {

    // ─── Helpers ────────────────────────────────────────────────────

    /** Build a [ToolsLayout.ToolsVisibility] from seven booleans in canonical order. */
    private fun tools(
        power: Boolean = false,
        volume: Boolean = false,
        brightness: Boolean = false,
        screenshot: Boolean = false,
        blackScreen: Boolean = false,
        lockScreen: Boolean = false,
        contentPicker: Boolean = false
    ) = ToolsLayout.ToolsVisibility(
        showPower = power,
        showVolume = volume,
        showBrightness = brightness,
        showScreenshot = screenshot,
        showBlackScreen = blackScreen,
        showLockScreen = lockScreen,
        showContentPicker = contentPicker
    )

    /** Visibility as a list, for gridPositions. */
    private fun visList(
        power: Boolean = false,
        volume: Boolean = false,
        brightness: Boolean = false,
        screenshot: Boolean = false,
        blackScreen: Boolean = false,
        lockScreen: Boolean = false,
        contentPicker: Boolean = false
    ) = listOf(power, volume, brightness, screenshot, blackScreen, lockScreen, contentPicker)

    /** Verify that all column indices are within [0, gridCols). */
    private fun assertColumnsValid(positions: List<ToolsLayout.CellPos>, gridCols: Int) {
        for (p in positions) {
            assertTrue(
                "col=${p.col} must be < gridCols=$gridCols (row=${p.row})",
                p.col in 0 until gridCols
            )
        }
    }

    /** Verify that no two VISIBLE tools occupy the same cell. */
    private fun assertNoOverlaps(positions: List<ToolsLayout.CellPos>) {
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
    private fun assertConsecutiveRows(positions: List<ToolsLayout.CellPos>, rowOffset: Int, gridCols: Int) {
        if (positions.isEmpty()) return
        val expectedMaxRow = rowOffset + (positions.size - 1) / gridCols
        val actualMaxRow = positions.maxOf { it.row }
        assertEquals(
            "Tools should be packed into consecutive rows",
            expectedMaxRow.toLong(), actualMaxRow.toLong()
        )
    }

    /**
     * Generate all 128 toggle combinations (2^7) as lists of 7 booleans.
     * Index order matches getToolCellList(): power, volume, brightness,
     * screenshot, blackScreen, lockScreen, contentPicker.
     */
    private fun allToggleCombinations(): List<List<Boolean>> {
        return (0 until 128).map { bits ->
            List(7) { i -> (bits and (1 shl i)) != 0 }
        }
    }

    private fun height(
        showTools: Boolean = true,
        inFolder: Boolean = false,
        gridCols: Int = 2,
        showSysInfo: Boolean = false,
        t: ToolsLayout.ToolsVisibility = tools()
    ): Float = ToolsLayout.nonAppHeightDp(
        tools = t,
        showTools = showTools,
        inFolder = inFolder,
        gridCols = gridCols,
        showSysInfo = showSysInfo
    )

    // ════════════════════════════════════════════════════════════════
    //  hasStandardTools / toolsContainerVisible tests
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `all tools OFF — hasStandardTools false, container GONE`() {
        val t = tools()
        assertEquals(false, t.hasStandardTools)
        assertEquals(false, ToolsLayout.toolsContainerVisible(true, false, t))
    }

    @Test
    fun `showTools OFF hides container regardless of tools`() {
        assertEquals(false, ToolsLayout.toolsContainerVisible(false, false, tools(power = true, volume = true, brightness = true, screenshot = true, blackScreen = true, lockScreen = true, contentPicker = true)))
    }

    @Test
    fun `inFolder hides container regardless of tools`() {
        assertEquals(false, ToolsLayout.toolsContainerVisible(true, true, tools(power = true, volume = true, brightness = true, screenshot = true, blackScreen = true, lockScreen = true, contentPicker = true)))
    }

    @Test
    fun `only screenshot ON`() {
        assertEquals(true, tools(screenshot = true).hasStandardTools)
    }

    @Test
    fun `only black screen ON`() {
        assertEquals(true, tools(blackScreen = true).hasStandardTools)
    }

    @Test
    fun `only power menu ON`() {
        assertEquals(true, tools(power = true).hasStandardTools)
    }

    @Test
    fun `only volume keys ON`() {
        assertEquals(true, tools(volume = true).hasStandardTools)
    }

    @Test
    fun `only brightness keys ON`() {
        assertEquals(true, tools(brightness = true).hasStandardTools)
    }

    @Test
    fun `only lock screen ON`() {
        assertEquals(true, tools(lockScreen = true).hasStandardTools)
    }

    @Test
    fun `only content picker ON — the 7th tool counts`() {
        assertEquals(true, tools(contentPicker = true).hasStandardTools)
        assertEquals(1, tools(contentPicker = true).enabledCount)
    }

    @Test
    fun `all 7 tools ON`() {
        val t = tools(true, true, true, true, true, true, true)
        assertEquals(true, t.hasStandardTools)
        assertEquals(7, t.enabledCount)
    }

    // ════════════════════════════════════════════════════════════════
    //  Row-counting tests — 2-col mode
    // ════════════════════════════════════════════════════════════════

    @Test fun `2col 0 tools → 0 rows`() { assertEquals(0, ToolsLayout.toolRows(0, 2)) }
    @Test fun `2col 1 tool → 1 row`() { assertEquals(1, ToolsLayout.toolRows(1, 2)) }
    @Test fun `2col 2 tools → 1 row`() { assertEquals(1, ToolsLayout.toolRows(2, 2)) }
    @Test fun `2col 3 tools → 2 rows`() { assertEquals(2, ToolsLayout.toolRows(3, 2)) }
    @Test fun `2col 4 tools → 2 rows`() { assertEquals(2, ToolsLayout.toolRows(4, 2)) }
    @Test fun `2col 5 tools → 3 rows`() { assertEquals(3, ToolsLayout.toolRows(5, 2)) }
    @Test fun `2col 6 tools → 3 rows`() { assertEquals(3, ToolsLayout.toolRows(6, 2)) }
    @Test fun `2col 7 tools → 4 rows`() { assertEquals(4, ToolsLayout.toolRows(7, 2)) }

    // ════════════════════════════════════════════════════════════════
    //  Row-counting tests — 1-col mode
    // ════════════════════════════════════════════════════════════════

    @Test fun `1col 0 tools → 0 rows`() { assertEquals(0, ToolsLayout.toolRows(0, 1)) }
    @Test fun `1col 1 tool → 1 row`() { assertEquals(1, ToolsLayout.toolRows(1, 1)) }
    @Test fun `1col 2 tools → 2 rows`() { assertEquals(2, ToolsLayout.toolRows(2, 1)) }
    @Test fun `1col 3 tools → 3 rows`() { assertEquals(3, ToolsLayout.toolRows(3, 1)) }
    @Test fun `1col 4 tools → 4 rows`() { assertEquals(4, ToolsLayout.toolRows(4, 1)) }
    @Test fun `1col 5 tools → 5 rows`() { assertEquals(5, ToolsLayout.toolRows(5, 1)) }
    @Test fun `1col 6 tools → 6 rows`() { assertEquals(6, ToolsLayout.toolRows(6, 1)) }
    @Test fun `1col 7 tools → 7 rows`() { assertEquals(7, ToolsLayout.toolRows(7, 1)) }

    // ════════════════════════════════════════════════════════════════
    //  nonAppHeightDp tests — key scenarios
    //  Base 60dp + 9dp divider + 40dp per tool row + 30dp sysInfo.
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `no tools shown — base height only`() {
        assertEquals(60f, height())
    }

    @Test
    fun `inFolder — base height only`() {
        assertEquals(60f, height(inFolder = true, t = tools(screenshot = true, blackScreen = true)))
    }

    @Test
    fun `showTools OFF — base height only`() {
        assertEquals(60f, height(showTools = false, gridCols = 1, t = tools(true, true, true, true, true, true, true)))
    }

    // ── 2-col scenarios ──────────────────────────────────────────

    @Test
    fun `2col 1 tool — 1 row`() {
        // 60 base + 9 divider + 40*1 = 109
        assertEquals(109f, height(t = tools(power = true)))
    }

    @Test
    fun `2col 2 tools — 1 row`() {
        // 60 + 9 + 40*1 = 109
        assertEquals(109f, height(t = tools(screenshot = true, blackScreen = true)))
    }

    @Test
    fun `2col 3 tools — 2 rows`() {
        // 60 + 9 + 40*2 = 149
        assertEquals(149f, height(t = tools(power = true, volume = true, screenshot = true)))
    }

    @Test
    fun `2col 6 tools — 3 rows`() {
        // 60 + 9 + 40*3 = 189
        assertEquals(189f, height(t = tools(true, true, true, true, true, true, false)))
    }

    @Test
    fun `2col 7 tools — 4 rows`() {
        // 60 + 9 + 40*4 = 229 — the 7-tool case the old 6-tool suite never computed.
        assertEquals(229f, height(t = tools(true, true, true, true, true, true, true)))
    }

    @Test
    fun `2col 6 tools + sysInfo — 3 rows + sysInfo`() {
        // 60 + 9 + 40*3 + 30 = 219
        assertEquals(219f, height(showSysInfo = true, t = tools(true, true, true, true, true, true, false)))
    }

    @Test
    fun `2col sysInfo only — no divider`() {
        // 60 + 30 = 90
        assertEquals(90f, height(showSysInfo = true))
    }

    // ── 1-col scenarios ──────────────────────────────────────────

    @Test
    fun `1col 1 tool — 1 row`() {
        // 60 + 9 + 40*1 = 109
        assertEquals(109f, height(gridCols = 1, t = tools(screenshot = true)))
    }

    @Test
    fun `1col 2 tools — 2 rows`() {
        // 60 + 9 + 40*2 = 149
        assertEquals(149f, height(gridCols = 1, t = tools(power = true, volume = true)))
    }

    @Test
    fun `1col 6 tools — 6 rows`() {
        // 60 + 9 + 40*6 = 309
        assertEquals(309f, height(gridCols = 1, t = tools(true, true, true, true, true, true, false)))
    }

    @Test
    fun `1col 7 tools — 7 rows`() {
        // 60 + 9 + 40*7 = 349
        assertEquals(349f, height(gridCols = 1, t = tools(true, true, true, true, true, true, true)))
    }

    // ════════════════════════════════════════════════════════════════
    //  Height monotonicity
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `2col height is monotonic with tool count`() {
        val heights = (0..7).map { count ->
            val flags = List(7) { i -> i < count }
            height(t = ToolsLayout.ToolsVisibility(flags[0], flags[1], flags[2], flags[3], flags[4], flags[5], flags[6]))
        }
        for (i in 1 until heights.size) {
            assertTrue("height dropped: ${heights[i]} < ${heights[i - 1]} at count=$i",
                heights[i] >= heights[i - 1])
        }
    }

    @Test
    fun `1col height is monotonic with tool count`() {
        val heights = (0..7).map { count ->
            val flags = List(7) { i -> i < count }
            height(gridCols = 1, t = ToolsLayout.ToolsVisibility(flags[0], flags[1], flags[2], flags[3], flags[4], flags[5], flags[6]))
        }
        for (i in 1 until heights.size) {
            assertTrue("height dropped: ${heights[i]} < ${heights[i - 1]} at count=$i",
                heights[i] >= heights[i - 1])
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Grid positioning — ALL 128 toggle combinations × 2 column modes
    //
    //  This is the critical test suite that prevents the crash:
    //  "column indices (start + span) mustn't exceed the column count"
    //
    //  For each of the 128 toggle combinations, we verify:
    //  1. All column indices are within [0, gridCols)
    //  2. No two VISIBLE tools overlap
    //  3. VISIBLE tools are packed consecutively (no gaps)
    //  4. Divider is at row=0 when visible
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `2col grid — all 128 toggle combinations have valid positions`() {
        for (vis in allToggleCombinations()) {
            val count = vis.count { it }
            if (count == 0) continue  // no tools = no positions to check

            val (toolPositions, dividerPos) = ToolsLayout.gridPositions(vis, gridCols = 2, dividerVisible = true)

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
    fun `1col grid — all 128 toggle combinations have valid positions`() {
        for (vis in allToggleCombinations()) {
            val count = vis.count { it }
            if (count == 0) continue

            val (toolPositions, dividerPos) = ToolsLayout.gridPositions(vis, gridCols = 1, dividerVisible = true)

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
    fun `2col grid — all 128 combos WITHOUT divider`() {
        for (vis in allToggleCombinations()) {
            val count = vis.count { it }
            if (count == 0) continue

            val (toolPositions, dividerPos) = ToolsLayout.gridPositions(vis, gridCols = 2, dividerVisible = false)

            assertColumnsValid(toolPositions, 2)
            assertNoOverlaps(toolPositions)
            // Without divider, tools start at row 0
            assertConsecutiveRows(toolPositions, rowOffset = 0, gridCols = 2)
            assertEquals(null, dividerPos)
            assertEquals(count.toLong(), toolPositions.size.toLong())
        }
    }

    @Test
    fun `1col grid — all 128 combos WITHOUT divider`() {
        for (vis in allToggleCombinations()) {
            val count = vis.count { it }
            if (count == 0) continue

            val (toolPositions, dividerPos) = ToolsLayout.gridPositions(vis, gridCols = 1, dividerVisible = false)

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
    fun `2col 7 tools — 4 rows, 2 per row, no overlaps`() {
        val vis = visList(true, true, true, true, true, true, true) // all 7
        val (pos, _) = ToolsLayout.gridPositions(vis, gridCols = 2, dividerVisible = true)
        // Row 1: tool0(1,0), tool1(1,1)
        // Row 2: tool2(2,0), tool3(2,1)
        // Row 3: tool4(3,0), tool5(3,1)
        // Row 4: tool6(4,0) — content picker alone on the last row
        assertEquals(7, pos.size)
        assertEquals(ToolsLayout.CellPos(1, 0), pos[0])
        assertEquals(ToolsLayout.CellPos(1, 1), pos[1])
        assertEquals(ToolsLayout.CellPos(2, 0), pos[2])
        assertEquals(ToolsLayout.CellPos(2, 1), pos[3])
        assertEquals(ToolsLayout.CellPos(3, 0), pos[4])
        assertEquals(ToolsLayout.CellPos(3, 1), pos[5])
        assertEquals(ToolsLayout.CellPos(4, 0), pos[6])
    }

    @Test
    fun `1col 7 tools — 7 rows, all col=0`() {
        val vis = visList(true, true, true, true, true, true, true)
        val (pos, _) = ToolsLayout.gridPositions(vis, gridCols = 1, dividerVisible = true)
        assertEquals(7, pos.size)
        for (i in 0..6) {
            assertEquals(ToolsLayout.CellPos(1 + i, 0), pos[i])
        }
    }

    @Test
    fun `2col odd tools — tools packed correctly with last row having 1 tool`() {
        // 3 tools enabled (power, volume, brightness only)
        val vis = visList(true, true, true, false, false, false, false)
        val (pos, _) = ToolsLayout.gridPositions(vis, gridCols = 2, dividerVisible = true)
        assertEquals(3, pos.size)
        assertEquals(ToolsLayout.CellPos(1, 0), pos[0]) // power
        assertEquals(ToolsLayout.CellPos(1, 1), pos[1]) // volume
        assertEquals(ToolsLayout.CellPos(2, 0), pos[2]) // brightness — alone on row 2
    }

    @Test
    fun `1col sparse tools — gaps skipped, only visible tools get positions`() {
        // Only power (idx0) and lockScreen (idx5) enabled
        val vis = visList(true, false, false, false, false, true, false)
        val (pos, _) = ToolsLayout.gridPositions(vis, gridCols = 2, dividerVisible = true)
        assertEquals(2, pos.size)
        assertEquals(ToolsLayout.CellPos(1, 0), pos[0]) // power → toolIdx=0
        assertEquals(ToolsLayout.CellPos(1, 1), pos[1]) // lockScreen → toolIdx=1
    }

    @Test
    fun `only content picker — it packs as the first visible tool`() {
        // Only content picker (idx6) enabled: it is toolIdx=0, so row=1 col=0.
        val vis = visList(false, false, false, false, false, false, true)
        val (pos, _) = ToolsLayout.gridPositions(vis, gridCols = 2, dividerVisible = true)
        assertEquals(1, pos.size)
        assertEquals(ToolsLayout.CellPos(1, 0), pos[0])
    }

    @Test
    fun `single tool 2col — row=1 col=0`() {
        val vis = visList(false, false, false, true, false, false, false) // only screenshot
        val (pos, _) = ToolsLayout.gridPositions(vis, gridCols = 2, dividerVisible = true)
        assertEquals(1, pos.size)
        assertEquals(ToolsLayout.CellPos(1, 0), pos[0])
    }

    @Test
    fun `single tool 1col — row=1 col=0`() {
        val vis = visList(false, false, false, true, false, false, false)
        val (pos, _) = ToolsLayout.gridPositions(vis, gridCols = 1, dividerVisible = true)
        assertEquals(1, pos.size)
        assertEquals(ToolsLayout.CellPos(1, 0), pos[0])
    }
}
