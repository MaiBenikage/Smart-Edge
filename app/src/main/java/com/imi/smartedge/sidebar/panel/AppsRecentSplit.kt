package com.imi.smartedge.sidebar.panel

/**
 * Pure height-split math for the sidebar app list / Recent list.
 *
 * The two lists share one height budget (their historical combined height,
 * capped so the whole panel fits on screen). The split only redistributes
 * that budget — the panel's total height never changes.
 *
 * No Android dependency, so it is unit-tested directly against this real
 * implementation (see AppsRecentSplitAndRecentsTest) instead of a mirrored
 * copy of the formula. SidePanelView owns the budget computation
 * (panelMaxHeight + recent content height, screen cap) and calls
 * [splitHeights] for the division itself.
 */
object AppsRecentSplit {

    /** Minimum height of either list, so each keeps at least one partial row. */
    const val MIN_LIST_HEIGHT_DP = 56f

    /** Minimum / maximum app-list share, matching the persisted split's clamp. */
    const val MIN_SPLIT_PERCENT = 20
    const val MAX_SPLIT_PERCENT = 80

    /**
     * Divides [combinedDp] between the app list and the Recent list.
     * [splitPercent] is the app list's share (20..80, default 50).
     *
     * Returns (appsDp, recentDp). The two always sum to [combinedDp], and
     * when the budget is large enough each list is guaranteed at least
     * [MIN_LIST_HEIGHT_DP]. The split is clamped here (not just at the
     * callers) so an out-of-range value can never produce a negative height.
     * When the budget can't hold two minima, the split falls back to 50/50
     * so neither list collapses to ~0.
     */
    fun splitHeights(combinedDp: Float, splitPercent: Int): Pair<Float, Float> {
        val split = splitPercent.coerceIn(MIN_SPLIT_PERCENT, MAX_SPLIT_PERCENT)
        val appsDp = if (combinedDp > 2 * MIN_LIST_HEIGHT_DP) {
            // Guarantee at least one (partial) row for each list.
            (combinedDp * split / 100f)
                .coerceIn(MIN_LIST_HEIGHT_DP, combinedDp - MIN_LIST_HEIGHT_DP)
        } else {
            combinedDp / 2f
        }
        return appsDp to (combinedDp - appsDp)
    }
}
