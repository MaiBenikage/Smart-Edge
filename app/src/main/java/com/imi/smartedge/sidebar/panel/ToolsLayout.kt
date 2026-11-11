package com.imi.smartedge.sidebar.panel

/**
 * Pure tools-section layout math for the sidebar tools GridLayout.
 *
 * Extracted from [SidePanelView] (computeNonAppHeightDp,
 * syncToolsGridColumnsInner, applyTheme) so the mapping between the
 * dashboard-settings switches and the tools grid is unit-testable on
 * the JVM (see ToolsVisibilityMappingTest) instead of mirrored in the
 * test. No Android dependency.
 *
 * The seven dashboard tools, in `SidePanelView.getToolCellList()`
 * canonical order, are: Power Menu, Volume, Brightness, Screenshot,
 * Black Screen, Lock Screen, Content Picker.
 */
object ToolsLayout {

    /** Base chrome height in dp: top padding (12) + bottom padding (4) + tools margin (4) + close button (32 + 4 top + 4 bottom margin = 40). */
    const val BASE_HEIGHT_DP = 60f

    /** Divider: 1dp line + 8dp bottom margin. */
    const val DIVIDER_DP = 9f

    /** One tool row is one 40dp button: the tool cells contain only the ImageButton (no label), and the GridLayout has no row spacing. */
    const val TOOL_ROW_DP = 40f

    /** System-info overlay row. */
    const val SYS_INFO_DP = 30f

    /**
     * The seven dashboard-tool switches, in getToolCellList() order.
     */
    data class ToolsVisibility(
        val showPower: Boolean,
        val showVolume: Boolean,
        val showBrightness: Boolean,
        val showScreenshot: Boolean,
        val showBlackScreen: Boolean,
        val showLockScreen: Boolean,
        val showContentPicker: Boolean
    ) {
        /** Canonical order, matching `SidePanelView.getToolCellList()`. */
        fun asList(): List<Boolean> = listOf(
            showPower, showVolume, showBrightness, showScreenshot,
            showBlackScreen, showLockScreen, showContentPicker
        )

        val enabledCount: Int get() = asList().count { it }
        val hasStandardTools: Boolean get() = enabledCount > 0
    }

    /** Grid rows needed for [enabledCount] visible tools at [gridCols] columns (1 or 2). */
    fun toolRows(enabledCount: Int, gridCols: Int): Int =
        if (gridCols == 2) (enabledCount + 1) / 2 else enabledCount

    /**
     * Non-app chrome height in dp (the value `SidePanelView`
     * `computeNonAppHeightDp()` returns). The tools/sysInfo block is
     * skipped entirely when the master switch is off or the panel is
     * inside a folder — so SysInfo height is only counted when the tools
     * section itself is shown.
     */
    fun nonAppHeightDp(
        tools: ToolsVisibility,
        showTools: Boolean,
        inFolder: Boolean,
        gridCols: Int,
        showSysInfo: Boolean
    ): Float {
        if (!showTools || inFolder) return BASE_HEIGHT_DP
        var h = BASE_HEIGHT_DP
        if (tools.hasStandardTools || showSysInfo) {
            if (tools.hasStandardTools) {
                h += DIVIDER_DP
                h += TOOL_ROW_DP * toolRows(tools.enabledCount, gridCols)
            }
            if (showSysInfo) h += SYS_INFO_DP
        }
        return h
    }

    /** Whether the tools container is visible (master switch on, not in a folder, at least one tool on). */
    fun toolsContainerVisible(showTools: Boolean, inFolder: Boolean, tools: ToolsVisibility): Boolean =
        showTools && !inFolder && tools.hasStandardTools

    /** Grid position of one tool cell (row, col). */
    data class CellPos(val row: Int, val col: Int)

    /**
     * (row, col) for each VISIBLE tool cell, plus the divider position.
     * Mirrors `syncToolsGridColumnsInner`: the divider occupies row 0 when
     * visible (so tools start at rowOffset 1), then visible tools pack
     * consecutively from there (2-col: row = offset + idx/2, col = idx%2;
     * 1-col: row = offset + idx, col = 0).
     */
    fun gridPositions(
        visibility: List<Boolean>,
        gridCols: Int,
        dividerVisible: Boolean
    ): Pair<List<CellPos>, CellPos?> {
        val toolPositions = mutableListOf<CellPos>()
        val rowOffset = if (dividerVisible) 1 else 0
        var toolIdx = 0
        for (visible in visibility) {
            if (visible) {
                val row = rowOffset + if (gridCols == 2) toolIdx / 2 else toolIdx
                val col = if (gridCols == 2) toolIdx % 2 else 0
                toolPositions.add(CellPos(row, col))
                toolIdx++
            }
        }
        val dividerPos = if (dividerVisible) CellPos(0, 0) else null
        return toolPositions to dividerPos
    }
}
