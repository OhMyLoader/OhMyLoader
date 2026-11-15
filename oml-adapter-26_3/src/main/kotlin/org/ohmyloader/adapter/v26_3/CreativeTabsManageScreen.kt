package org.ohmyloader.adapter.v26_3

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.item.CreativeModeTab
import net.minecraft.world.item.CreativeModeTabs
import org.ohmyloader.api26_3.OMLCreativeTabs

/**
 * The in-game creative-tab manager — phase two of the player config: the list-plus-controls UI
 * that replaces hand-editing `config/creative-tabs.toml`. Opened from the creative screen's
 * "Manage Tabs" button; every flowing tab (vanilla categories and modded alike, no right-aligned
 * specials) is one row with a Hide/Show toggle and Up/Down reorder buttons over a draft of the
 * display arrangement. "Done" persists the draft through [CreativeTabsPlayerConfig.save] and
 * returns to the creative screen, whose init rebuilds the strip from the new file; ESC (or any
 * other close) discards the draft.
 *
 * A plain screen, drawn against the same 26.x extract pipeline everything else uses:
 * `extractRenderState` is where the rows are drawn (the wrapper draws the dimmed background
 * before it, and `super` renders the widgets after it), rows scroll by wheel with the row
 * widgets rebuilt against the new scroll offset.
 */
class CreativeTabsManageScreen(private val parent: CreativeModeInventoryScreen) :
    Screen(Component.literal("Creative Tabs")) {

    /** One draft row: the tab, its registry id, and the player's hidden flag. */
    private class Row(val tab: CreativeModeTab, val id: Identifier, var hidden: Boolean)

    private val rows: MutableList<Row> = buildDraft()

    // Screen metrics, recomputed at every init (resize).
    private var panelX = 0
    private var rowsTop = 0
    private var rowsBottom = 0
    private var visibleRows = 1
    private var scroll = 0

    override fun init() {
        panelX = (width - PANEL_W) / 2
        rowsTop = 36
        rowsBottom = height - 34
        visibleRows = ((rowsBottom - rowsTop) / ROW_H).coerceAtLeast(1)
        scroll = scroll.coerceIn(0, maxScroll())
        rebuildRowWidgets()
    }

    /** Rows are drawn first; `super` renders the row widgets (and the Done button) on top. */
    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        graphics.text(
            font, title,
            (width - font.width(title)) / 2, 14, -1, false,
        )
        graphics.fill(panelX - 8, rowsTop - 8, panelX + PANEL_W + 8, rowsBottom + 4, 0x5A000000.toInt())
        graphics.enableScissor(0, rowsTop, width, rowsBottom)
        for (i in 0 until visibleRows) {
            val row = rows.getOrNull(scroll + i) ?: break
            val rowY = rowsTop + i * ROW_H + 2
            graphics.item(row.tab.iconItem, panelX + 4, rowY + 2)
            val name = font.plainSubstrByWidth(row.tab.displayName.string, NAME_WIDTH)
            graphics.text(font, name, panelX + 26, rowY + 6, if (row.hidden) HIDDEN_COLOR else -1, false)
        }
        graphics.disableScissor()
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
    }

    override fun mouseScrolled(x: Double, y: Double, xAmount: Double, yAmount: Double): Boolean {
        val next = (scroll - yAmount.toInt()).coerceIn(0, maxScroll())
        if (next != scroll) {
            scroll = next
            rebuildRowWidgets()
            return true
        }
        return super.mouseScrolled(x, y, xAmount, yAmount)
    }

    override fun onClose() {
        Minecraft.getInstance().setScreenAndShow(parent)
    }

    /**
     * Every widget, rebuilt together: the row controls shift with the scroll offset, and the
     * hide toggle flips a row's label — cheaper to rebuild than to retrack fixed slots.
     */
    private fun rebuildRowWidgets() {
        clearWidgets()
        addRenderableWidget(
            Button.builder(Component.translatable("gui.done")) { saveAndClose() }
                .bounds(width / 2 - 100, height - 26, 200, 20)
                .build(),
        )
        for (i in 0 until visibleRows) {
            val index = scroll + i
            val row = rows.getOrNull(index) ?: break
            val rowY = rowsTop + i * ROW_H + 2
            val hide = Button.builder(if (row.hidden) Component.literal("Show") else Component.literal("Hide")) {
                row.hidden = !row.hidden
                rebuildRowWidgets()
            }.bounds(panelX + HIDE_X, rowY, HIDE_W, 20).build()
            val up = Button.builder(Component.literal("Up")) { move(index, -1) }
                .bounds(panelX + UP_X, rowY, MOVE_W, 20).build()
            val down = Button.builder(Component.literal("Down")) { move(index, +1) }
                .bounds(panelX + DOWN_X, rowY, MOVE_W + 8, 20).build()
            up.active = !row.hidden && index > 0
            down.active = !row.hidden && index < rows.size - 1
            addRenderableWidget(hide)
            addRenderableWidget(up)
            addRenderableWidget(down)
        }
    }

    private fun move(index: Int, direction: Int) {
        val target = index + direction
        if (index !in rows.indices || target !in rows.indices) {
            return
        }
        rows[index] = rows[target].also { rows[target] = rows[index] }
        rebuildRowWidgets()
    }

    private fun saveAndClose() {
        val order = rows.filter { !it.hidden }.map { it.id }
        CreativeTabsPlayerConfig.save(rows.filter { it.hidden }.map { it.id }, order)
        onClose()
    }

    private fun maxScroll(): Int = (rows.size - visibleRows).coerceAtLeast(0)

    /**
     * The draft starts from what the player currently sees: the manager's resolved order (the
     * registry order when nothing declared through it) with the player's saved reorder applied,
     * and the saved hidden flags as the initial toggle states.
     */
    private fun buildDraft(): MutableList<Row> {
        val registryOrder = CreativeModeTabs.tabs().filter { !it.isAlignedRight() }
        val idOf = { tab: CreativeModeTab ->
            BuiltInRegistries.CREATIVE_MODE_TAB.getResourceKey(tab).orElseThrow().identifier()
        }
        val config = CreativeTabsPlayerConfig.cached
        val base = OMLCreativeTabs.resolvedKeyOrder
            ?.mapNotNull { key -> registryOrder.firstOrNull { idOf(it) == key } }
            ?: registryOrder
        val byId = base.associateBy(idOf)
        val orderSet = config?.orderSet ?: emptySet()
        val arranged = (config?.order?.mapNotNull(byId::get).orEmpty()) +
            base.filter { idOf(it) !in orderSet }
        return arranged.map { Row(it, idOf(it), idOf(it) in (config?.hidden ?: emptySet())) }
            .toMutableList()
    }

    private companion object {
        const val PANEL_W = 300
        const val ROW_H = 24
        const val NAME_WIDTH = 134

        // The three row buttons, right-aligned inside the panel.
        const val HIDE_W = 48
        const val MOVE_W = 34
        const val DOWN_X = PANEL_W - 4 - MOVE_W - 8
        const val UP_X = DOWN_X - 4 - MOVE_W
        const val HIDE_X = UP_X - 4 - HIDE_W
        const val HIDDEN_COLOR = 0xFF808080.toInt()
    }
}
