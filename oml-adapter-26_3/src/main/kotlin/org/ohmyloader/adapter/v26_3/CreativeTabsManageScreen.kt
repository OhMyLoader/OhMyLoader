package org.ohmyloader.adapter.v26_3

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.ContainerObjectSelectionList
import net.minecraft.client.gui.components.CycleButton
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.MultiLineTextWidget
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout
import net.minecraft.client.gui.layouts.LinearLayout
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.item.CreativeModeTab
import net.minecraft.world.item.CreativeModeTabs
import org.ohmyloader.api26_3.OMLCreativeTabs

/**
 * The in-game creative-tab manager — phase two of the player config — modeled on the vanilla
 * F3+F6 [net.minecraft.client.gui.screens.debug.DebugOptionsScreen]: a `HeaderAndFooterLayout`
 * (title + search header, Done footer) over a scrolling [ContainerObjectSelectionList] whose
 * rows are the flowing tabs, each with a Hide/Show cycle button and Up/Down reorder buttons.
 * Replaces hand-editing `config/creative-tabs.toml`.
 *
 * The rows are a draft over the effective display arrangement (the manager's resolved order
 * with the player's saved hide/reorder applied); edits mutate the draft and refresh the list.
 * Done persists the draft through [CreativeTabsPlayerConfig.save] and returns to the creative
 * screen, whose init rebuilds the strip from the new file; ESC (or any other close) discards.
 */
class CreativeTabsManageScreen(private val parent: CreativeModeInventoryScreen) :
    Screen(Component.literal("Creative Tabs")) {

    /** One draft row: the tab, its registry id, and the player's hidden flag. */
    private class Row(val tab: CreativeModeTab, val id: Identifier, var hidden: Boolean)

    private val rows: MutableList<Row> = buildDraft()

    private val layout = HeaderAndFooterLayout(this, 61, 33)
    private lateinit var tabList: TabList
    private lateinit var searchBox: EditBox

    override fun init() {
        val header = LinearLayout.vertical().spacing(4)
        searchBox = EditBox(font, 0, 0, SEARCH_WIDTH, 16, Component.literal("Search"))
        searchBox.setHint(Component.literal("Search"))
        searchBox.setResponder { tabList.updateSearch(it) }
        header.addChild(
            StringWidget(title, font),
            header.newCellSettings().alignHorizontallyCenter(),
        )
        header.addChild(searchBox, header.newCellSettings().alignHorizontallyCenter())
        header.addChild(
            MultiLineTextWidget(
                Component.literal(
                    "Hide tabs, or reorder them for every page. Changes apply when the screen reopens.",
                ),
                font,
            ).setMaxWidth(MAX_ROW_W).setCentered(true),
            header.newCellSettings().alignHorizontallyCenter(),
        )
        layout.addToHeader(header)
        tabList = TabList()
        layout.addToContents(tabList)
        val footer = LinearLayout.horizontal().spacing(4)
        footer.addChild(
            UnfocusedButton(0, 0, 150, 20, Component.translatable("controls.reset")) { resetToDefaults() },
        )
        footer.addChild(
            UnfocusedButton(0, 0, 200, 20, Component.translatable("gui.done")) { saveAndClose() },
        )
        layout.addToFooter(footer)
        layout.visitWidgets { addRenderableWidget(it) }
        repositionElements()
    }

    override fun repositionElements() {
        layout.arrangeElements()
        tabList.updateSize(width, layout)
    }

    override fun onClose() {
        Minecraft.getInstance().setScreenAndShow(parent)
    }

    private fun saveAndClose() {
        val order = rows.filter { !it.hidden }.map { it.id }
        CreativeTabsPlayerConfig.save(rows.filter { it.hidden }.map { it.id }, order)
        onClose()
    }

    /** Swap [row] with its neighbor, then redraw the list from the draft. */
    private fun move(row: Row, direction: Int) {
        val index = rows.indexOf(row)
        val target = index + direction
        if (index < 0 || target !in rows.indices) {
            return
        }
        rows[index] = rows[target].also { rows[target] = rows[index] }
        tabList.refreshEntries()
    }

    /**
     * The list, sized and positioned like vanilla's (`updateSize` against the layout). Rows are
     * rebuilt from the draft on every change, so row-button states (visibility, edge disabling)
     * can never go stale.
     */
    private inner class TabList : ContainerObjectSelectionList<TabEntry>(
        Minecraft.getInstance(),
        this@CreativeTabsManageScreen.width,
        layout.contentHeight,
        layout.headerHeight,
        ITEM_HEIGHT,
    ) {
        init {
            refreshEntries()
        }

        override fun getRowWidth(): Int = MAX_ROW_W

        fun updateSearch(query: String) {
            refreshEntries(query.trim())
        }

        fun refreshEntries() {
            refreshEntries("")
        }

        private fun refreshEntries(query: String) {
            clearEntries()
            val visible = rows.asSequence()
                .withIndex()
                .filter { (_, row) -> query.isEmpty() || row.tab.displayName.string.contains(query, true) }
                .toList()
            for ((index, row) in visible) {
                addEntry(TabEntry(this@CreativeTabsManageScreen, row, index, index > 0, index < rows.size - 1))
            }
        }
    }

    /** One list row: icon + name drawn by [extractContent], with the row's own widgets. */
    private class TabEntry(
        private val screen: CreativeTabsManageScreen,
        private val row: Row,
        private val index: Int,
        private val hasUp: Boolean,
        private val hasDown: Boolean,
    ) : ContainerObjectSelectionList.Entry<TabEntry>() {
        private val hideButton: CycleButton<Boolean> =
            CycleButton.booleanBuilder(Component.literal("Hidden"), Component.literal("Shown"), row.hidden)
                .displayOnlyValue()
                .create(0, 0, HIDE_W, BUTTON_H, Component.literal("Hide tab")) { _, value ->
                    row.hidden = value
                    screen.tabList.refreshEntries()
                }
        private val upButton = UnfocusedButton(0, 0, MOVE_W, BUTTON_H, Component.literal("Up")) { screen.move(row, -1) }
        private val downButton = UnfocusedButton(0, 0, MOVE_W + 8, BUTTON_H, Component.literal("Down")) { screen.move(row, +1) }
        private val widgets = listOf(hideButton, upButton, downButton)

        init {
            upButton.active = !row.hidden && hasUp
            downButton.active = !row.hidden && hasDown
            hideButton.setValue(row.hidden)
        }

        override fun children(): List<AbstractWidget> = widgets

        override fun narratables(): List<net.minecraft.client.gui.narration.NarratableEntry> = widgets

        /**
         * The row's controls never hold focus, so the white border stays hover-only. Up/Down
         * already opt out via [UnfocusedButton.shouldTakeFocusAfterInteraction]; the cycle
         * button can't be subclassed (its constructor is private), so the entry refuses to
         * focus any of its widgets here.
         */
        override fun setFocused(listener: net.minecraft.client.gui.components.events.GuiEventListener?) {
            if (listener !in widgets) {
                super.setFocused(listener)
            }
        }

        /**
         * The vanilla entry pattern: the content is drawn at the content origin, then the row
         * widgets are right-aligned against it and extracted — their positions are recomputed
         * every frame, so the scroll offset can never leave them behind.
         */
        override fun extractContent(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, hovered: Boolean, partialTick: Float) {
            val iconY = contentY + (contentHeight - 16) / 2
            graphics.item(row.tab.iconItem, contentX + 2, iconY)
            val name = screen.font.plainSubstrByWidth(
                row.tab.displayName.string,
                contentWidth - HIDE_W - MOVE_W - MOVE_W - 8 - 32,
            )
            graphics.text(screen.font, name, contentX + 24, contentYMiddle - 4, if (row.hidden) HIDDEN_COLOR else -1, false)
            downButton.x = contentRight - downButton.width
            upButton.x = downButton.x - 4 - upButton.width
            hideButton.x = upButton.x - 4 - hideButton.width
            for (widget in widgets) {
                widget.y = contentYMiddle - widget.height / 2
                widget.extractRenderState(graphics, mouseX, mouseY, partialTick)
            }
        }
    }

    /**
     * The draft starts from what the player currently sees: the manager's resolved order (the
     * registry order when nothing declared through it) with the player's saved reorder applied,
     * and the saved hidden flags as the initial toggle states.
     */
    private fun buildDraft(): MutableList<Row> {
        val config = CreativeTabsPlayerConfig.cached
        val base = baseArrangement()
        val byId = base.associateBy(::idOf)
        val orderSet = config?.orderSet ?: emptySet()
        val arranged = (config?.order?.mapNotNull(byId::get).orEmpty()) +
            base.filter { idOf(it) !in orderSet }
        return arranged.map { Row(it, idOf(it), idOf(it) in (config?.hidden ?: emptySet())) }
            .toMutableList()
    }

    /** The flowing tabs in the manager's resolved (or registry) order — the default arrangement. */
    private fun baseArrangement(): List<CreativeModeTab> {
        val registryOrder = CreativeModeTabs.tabs().filter { !it.isAlignedRight() }
        return OMLCreativeTabs.resolvedKeyOrder
            ?.mapNotNull { key -> registryOrder.firstOrNull { idOf(it) == key } }
            ?: registryOrder
    }

    private fun idOf(tab: CreativeModeTab): Identifier =
        BuiltInRegistries.CREATIVE_MODE_TAB.getResourceKey(tab).orElseThrow().identifier()

    /** Throws the draft away and starts over from the default arrangement, nothing hidden. */
    private fun resetToDefaults() {
        rows.clear()
        rows.addAll(baseArrangement().map { Row(it, idOf(it), false) })
        tabList.refreshEntries()
    }

    private companion object {
        const val ITEM_HEIGHT = 24
        const val MAX_ROW_W = 310
        const val SEARCH_WIDTH = 200
        const val HIDE_W = 48
        const val MOVE_W = 34
        const val BUTTON_H = 20
        const val HIDDEN_COLOR = 0xFF808080.toInt()
    }
}
