package org.ohmyloader.adapter.v26_3

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen
import net.minecraft.client.gui.screens.inventory.PageButton
import net.minecraft.network.chat.Component
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.item.CreativeModeTab
import net.minecraft.world.item.CreativeModeTabs
import org.ohmyloader.core.adapter.Refl

/**
 * Creative-screen tab pagination (client only), modeled on NeoForge's
 * `CreativeTabsScreenPage` layout. 26.3's creative screen has no pages: tabs are laid out from
 * their (row, column) at a fixed 27px pitch, seven per row - a modded tab cannot fit, and one
 * placed past column 6 renders outside the panel.
 *
 * The model, matching NeoForge: tabs whose `isAlignedRight()` is set (vanilla's hotbar / search /
 * operator / inventory tabs) are pinned to their natural right-side slots on every page; every
 * other tab - vanilla categories and modded ones alike - flows through pages of five top + five
 * bottom slots. Pages beyond the first are navigated with the two arrow buttons
 * ([CreativeScreenPaginationTransformer] injects their creation and the `i / n` page indicator).
 *
 * The repositioning works by replacing what the screen *reads* (`CreativeModeTab.row()` /
 * `column()` become [pagedRow] / [pagedColumn] inside the patched methods), so the drawn tab and
 * its hit test can never disagree - the first revision moved only the hit test and the drawn tab
 * sat in a different strip than the clickable area.
 *
 * `leftPos` / `topPos` / `imageWidth` / `imageHeight` / `addRenderableWidget` are protected screen
 * members read through cached reflection - the injection DSL has no field operand, the same
 * reason [Refl] exists.
 */
object CreativeScreenPagination {

    private const val PAGE_TOP_SLOTS = 5
    private const val PAGE_BOTTOM_SLOTS = 5
    private const val PAGE_CAPACITY = PAGE_TOP_SLOTS + PAGE_BOTTOM_SLOTS

    /** The column another-page tabs read: 27 * (-37) puts their draw far off-screen. */
    private const val OFF_SCREEN_COLUMN = -37

    @Volatile
    private var currentPage = 0

    /**
     * The tabs that flow through the pages, in display order: everything except the right-aligned
     * pinned specials. Mods that declared their tabs through the tab manager
     * (org.ohmyloader.api26_3.OMLCreativeTabs) get the manager's constraint-resolved key order
     * (resolved at declare time on keys - the holders were unbound then - and mapped to tabs
     * here, post-freeze); raw registrations fall back to the registry order.
     */
    private fun flowingTabs(): List<CreativeModeTab> {
        val keys = org.ohmyloader.api26_3.OMLCreativeTabs.resolvedKeyOrder
            ?: return CreativeModeTabs.tabs().filter { !it.isAlignedRight() }
        return keys.map { BuiltInRegistries.CREATIVE_MODE_TAB.getValue(it)!! }
    }

    private fun pageCount(): Int = (flowingTabs().size + PAGE_CAPACITY - 1) / PAGE_CAPACITY

    /**
     * What the screen should read as the tab's row: the paged row for flowing tabs on the current
     * page, the natural row for the pinned specials, and TOP for another-page flowing tabs -
     * whose column is the off-screen sentinel ([pagedColumn]), so wherever they draw, nobody
     * sees them.
     */
    @JvmStatic
    fun pagedRow(tab: CreativeModeTab): CreativeModeTab.Row {
        val index = flowingTabs().indexOf(tab)
        if (index < 0) {
            return tab.row()
        }
        if (index / PAGE_CAPACITY != currentPage) {
            return CreativeModeTab.Row.TOP
        }
        return if (index % PAGE_CAPACITY < PAGE_TOP_SLOTS) CreativeModeTab.Row.TOP else CreativeModeTab.Row.BOTTOM
    }

    /**
     * What the screen should read as the tab's column: the paged column for flowing tabs on the
     * current page, the natural column for the pinned specials, and the off-screen sentinel
     * (-37, past the left edge of the world) for another-page flowing tabs - the extract loop
     * still visits them, and this is what keeps them invisible without a branch in the patched
     * drawing code.
     */
    @JvmStatic
    fun pagedColumn(tab: CreativeModeTab): Int {
        val index = flowingTabs().indexOf(tab)
        if (index < 0) {
            return tab.column()
        }
        if (index / PAGE_CAPACITY != currentPage) {
            return OFF_SCREEN_COLUMN
        }
        val on = index % PAGE_CAPACITY
        return if (on < PAGE_TOP_SLOTS) on else on - PAGE_TOP_SLOTS
    }

    /** Whether [tab] is on the current page's strip (pinned tabs always are). */
    private fun isOnCurrentPage(tab: CreativeModeTab): Boolean {
        val index = flowingTabs().indexOf(tab)
        return index < 0 || index / PAGE_CAPACITY == currentPage
    }

    /** [CreativeModeInventoryScreen.getTabX] replacement. */
    @JvmStatic
    fun tabX(screen: CreativeModeInventoryScreen, tab: CreativeModeTab): Int {
        val column = pagedColumn(tab)
        return if (tab.isAlignedRight()) imageWidth(screen) - 27 * (7 - column) + 1 else 27 * column
    }

    /** [CreativeModeInventoryScreen.getTabY] replacement. */
    @JvmStatic
    fun tabY(screen: CreativeModeInventoryScreen, tab: CreativeModeTab): Int {
        return if (pagedRow(tab) == CreativeModeTab.Row.TOP) -32 else imageHeight(screen)
    }

    /** [CreativeModeInventoryScreen.extractBackground] tail: the selected tab is only drawn when
     * it lives on the current page (its paged slot would otherwise ghost-draw over a page tab). */
    @JvmStatic
    fun drawSelectedTabIfVisible(
        screen: CreativeModeInventoryScreen,
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        tab: CreativeModeTab,
    ) {
        if (!isOnCurrentPage(tab)) {
            return
        }
        extractTabButton(screen, graphics, mouseX, mouseY, tab)
    }

    /** [CreativeModeInventoryScreen.extractLabels] tail: the `i / n` page indicator. */
    @JvmStatic
    fun drawPageIndicator(screen: CreativeModeInventoryScreen, graphics: GuiGraphicsExtractor) {
        if (pageCount() <= 1) {
            return
        }
        val font = screen.getFont()
        val text = Component.literal("${currentPage + 1} / ${pageCount()}")
        // The labels pass draws in panel-relative coordinates (the vanilla title sits at (8, 6)),
        // so the indicator is positioned relative to the panel too.
        graphics.text(font, text, imageWidth(screen) / 2 - font.width(text) / 2, -44, -1, false)
    }

    /**
     * [CreativeModeInventoryScreen.init] tail: the two page-arrow buttons, mirroring NeoForge's
     * (vanilla buttons above the panel, clamped at the page bounds).
     */
    @JvmStatic
    fun addPageButtons(screen: CreativeModeInventoryScreen) {
        if (pageCount() <= 1) {
            return
        }
        addWidget(screen, Button.builder(Component.literal("<"), Button.OnPress {
            currentPage--
            screen.setFocused(null)
        })
            .pos(leftPos(screen), topPos(screen) - 50)
            .size(20, 20)
            .build())
        addWidget(screen, Button.builder(Component.literal(">"), Button.OnPress {
            currentPage++
            screen.setFocused(null)
        })
            .pos(leftPos(screen) + imageWidth(screen) - 20, topPos(screen) - 50)
            .size(20, 20)
            .build())
    }

    private fun addWidget(screen: CreativeModeInventoryScreen, widget: Any) {
        addWidgetMethod.invoke(screen, widget)
    }

    private val addWidgetMethod by lazy {
        net.minecraft.client.gui.screens.Screen::class.java.getDeclaredMethod(
            "addRenderableWidget",
            net.minecraft.client.gui.components.events.GuiEventListener::class.java,
        ).apply { isAccessible = true }
    }

    private val extractTabButtonMethod by lazy {
        CreativeModeInventoryScreen::class.java.getDeclaredMethod(
            "extractTabButton",
            GuiGraphicsExtractor::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            CreativeModeTab::class.java,
        ).apply { isAccessible = true }
    }

    private fun extractTabButton(
        screen: CreativeModeInventoryScreen,
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        tab: CreativeModeTab,
    ) {
        extractTabButtonMethod.invoke(screen, graphics, mouseX, mouseY, tab)
    }

    private fun leftPos(screen: CreativeModeInventoryScreen): Int =
        Refl.fieldOrNull(CreativeModeInventoryScreen::class.java, "leftPos", net.minecraft.client.gui.screens.Screen::class.java)!!.getInt(screen)

    private fun topPos(screen: CreativeModeInventoryScreen): Int =
        Refl.fieldOrNull(CreativeModeInventoryScreen::class.java, "topPos", net.minecraft.client.gui.screens.Screen::class.java)!!.getInt(screen)

    private fun imageWidth(screen: CreativeModeInventoryScreen): Int =
        Refl.fieldOrNull(CreativeModeInventoryScreen::class.java, "imageWidth", net.minecraft.client.gui.screens.Screen::class.java)!!.getInt(screen)

    private fun imageHeight(screen: CreativeModeInventoryScreen): Int =
        Refl.fieldOrNull(CreativeModeInventoryScreen::class.java, "imageHeight", net.minecraft.client.gui.screens.Screen::class.java)!!.getInt(screen)
}
