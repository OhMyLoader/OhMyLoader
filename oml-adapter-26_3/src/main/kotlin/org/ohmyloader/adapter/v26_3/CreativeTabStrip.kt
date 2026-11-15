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
 *   (the tab manager's constraint-resolved order with the player's hide/reorder applied), five
 *   top + five bottom slots per page;
 * - a tab's slot is a property of this model, so the drawn position and the hit test can never
 *   disagree, and tabs that are hidden or on another page have no slot at all (nothing is drawn,
 *   nothing is clickable).
 *
 * Pure state - no game calls; the screen-facing handlers translate.
 */
class CreativeTabStrip(
    allTabs: List<CreativeModeTab>,
    flowingOrder: List<CreativeModeTab>,
) {
    class Slot(val top: Boolean, val column: Int)

    /** The right-aligned specials, pinned to the rightmost slots of their row. */
    val pinned: List<CreativeModeTab> = allTabs.filter { it.isAlignedRight() }

    private val pinnedSlots: Map<CreativeModeTab, Slot> = buildMap {
        var topColumn = TOP_COLUMNS
        var bottomColumn = BOTTOM_COLUMNS
        for (tab in pinned) {
            if (tab.row() == CreativeModeTab.Row.TOP) {
                put(tab, Slot(true, --topColumn))
            } else {
                put(tab, Slot(false, --bottomColumn))
            }
        }
    }

    /** Columns each strip row offers to the flowing tabs (the pinned specials take the rest). */
    private val topColumnsForFlowing = TOP_COLUMNS - pinned.count { it.row() == CreativeModeTab.Row.TOP }
    private val bottomColumnsForFlowing = BOTTOM_COLUMNS - pinned.count { it.row() == CreativeModeTab.Row.BOTTOM }
    private val pageCapacity = topColumnsForFlowing + bottomColumnsForFlowing

    /** The flowing tabs, chunked into pages in display order. */
    val pages: List<List<CreativeModeTab>> = flowingOrder.chunked(pageCapacity)

    val pageCount: Int = pages.size

    /** Current page, clamped whenever the page count shrinks (config edits, tab removal). */
    var page: Int = 0
        set(value) {
            field = value.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        }

    /**
     * The slot of [tab] on the **current** page, or `null` when the tab has no slot here: hidden
     * tabs have none by config, and tabs on another page have one only where they are not drawn.
     */
    fun slotOf(tab: CreativeModeTab): Slot? {
        pinnedSlots[tab]?.let { return it }
        val pageIndex = pages.indexOfFirst { it.contains(tab) }
        if (pageIndex != page) {
            return null
        }
        val onPage = pages[page].indexOf(tab)
        return if (onPage < topColumnsForFlowing) Slot(true, onPage) else Slot(false, onPage - topColumnsForFlowing)
    }

    /** The tabs drawn on the current page's strip: the page's own tabs plus the pinned specials. */
    fun visibleTabs(): List<CreativeModeTab> = pages.getOrNull(page).orEmpty() + pinned

    companion object {
        const val TOP_COLUMNS = 7
        const val BOTTOM_COLUMNS = 7
    }
}
