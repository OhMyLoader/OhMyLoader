package org.ohmyloader.adapter.v26_3

import net.minecraft.world.item.CreativeModeTab
import net.minecraft.world.item.CreativeModeTabs

/**
 * OML's own creative-tab strip model - the takeover of the vanilla tab strip. The screen's
 * original layout reads (row, column) off each tab and renders a fixed seven-per-row strip with
 * no pages; this model owns the whole strip instead:
 *
 * - tabs with `isAlignedRight()` (vanilla's hotbar / search / operator / inventory) are pinned to
 *   the rightmost slots of their row on every page;
 * - every other tab - vanilla categories and modded alike - flows through pages in display order
 *   (the tab manager's constraint-resolved order with the player's arrangement applied), five
 *   top + five bottom slots per page;
 * - a tab's slot is a property of this model, so the drawn position and the hit test can never
 *   disagree, and tabs that are hidden or on another page have no slot at all (nothing is drawn,
 *   nothing is clickable).
 *
 * Pure state - no game calls; the screen-facing handlers translate. The slot table is computed
 * once at construction: the strip is rebuilt at every screen init, so the render path's per-tab
 * slot lookup is a single map hit with no page scanning and no per-frame allocation.
 */
class CreativeTabStrip(
    allTabs: List<CreativeModeTab>,
    flowingOrder: List<CreativeModeTab>,
) {
    /** Where a tab sits: which page owns it, and its strip position. [PINNED_PAGE] = every page. */
    class Slot(val page: Int, val top: Boolean, val column: Int)

    /** The right-aligned specials, pinned to the rightmost slots of their row. */
    val pinned: List<CreativeModeTab> = allTabs.filter { it.isAlignedRight() }

    /** Columns each strip row offers to the flowing tabs (the pinned specials take the rest). */
    private val topColumnsForFlowing = TOP_COLUMNS - pinned.count { it.row() == CreativeModeTab.Row.TOP }
    private val bottomColumnsForFlowing = BOTTOM_COLUMNS - pinned.count { it.row() == CreativeModeTab.Row.BOTTOM }
    private val pageCapacity = topColumnsForFlowing + bottomColumnsForFlowing

    /** The flowing tabs, chunked into pages in display order. */
    val pages: List<List<CreativeModeTab>> = flowingOrder.chunked(pageCapacity)

    val pageCount: Int = pages.size

    /** Every tab's slot, computed once: pinned specials first, then each page's flowing tabs.
     * Identity keys — the per-frame lookups (draw, click, hover) should not pay hashCode. */
    private val slots: Map<CreativeModeTab, Slot> = java.util.IdentityHashMap<CreativeModeTab, Slot>().apply {
        var topColumn = TOP_COLUMNS
        var bottomColumn = BOTTOM_COLUMNS
        for (tab in pinned) {
            val slot = if (tab.row() == CreativeModeTab.Row.TOP) {
                Slot(PINNED_PAGE, true, --topColumn)
            } else {
                Slot(PINNED_PAGE, false, --bottomColumn)
            }
            put(tab, slot)
        }
        for ((pageIndex, pageTabs) in pages.withIndex()) {
            for ((index, tab) in pageTabs.withIndex()) {
                val top = index < topColumnsForFlowing
                put(tab, Slot(pageIndex, top, if (top) index else index - topColumnsForFlowing))
            }
        }
    }

    /** Current page, clamped whenever the page count shrinks (config edits, tab removal). */
    var page: Int = 0
        set(value) {
            field = value.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        }

    /**
     * The slot of [tab] on the **current** page, or `null` when the tab has no slot here: pinned
     * specials sit on every page; a flowing tab only on its own page - hidden tabs have none by
     * config (they are never placed in the flow), other pages' tabs none on this one.
     */
    fun slotOf(tab: CreativeModeTab): Slot? =
        slots[tab]?.takeIf { it.page == PINNED_PAGE || it.page == page }

    companion object {
        const val TOP_COLUMNS = 7
        const val BOTTOM_COLUMNS = 7

        /** Pinned specials live on every page; no flowing page can share the sentinel. */
        private const val PINNED_PAGE = -1
    }
}
