package org.ohmyloader.adapter

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.TabButton
import net.minecraft.client.gui.components.tabs.TabNavigationBar
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.world.item.CreativeModeTab
import net.minecraft.world.item.CreativeModeTabs
import org.ohmyloader.core.adapter.Refl

/**
 * The screen-facing half of the creative-tab takeover: owns the [CreativeTabStrip] (rebuilt on
 * every screen init from the registry, the tab manager's resolved order and the player's config)
 * and lays vanilla's own tab buttons onto the strip's slots:
 *
 * - [applyStripLayout] moves every `ItemTabButton` the screen's `TabNavigationBar`s own onto the
 *   slot the strip model gives its tab, and hides the buttons of tabs that have no slot on this
 *   page (hidden by the player, or on another page). Vanilla still draws, hit-tests and narrates
 *   the buttons, so their sprites, icons, tooltips and focus handling stay the game's own;
 * - [onScreenInit] rebuilds the strip and creates the two page arrows and the Manage Tabs entry
 *   when the strip spans multiple pages;
 * - [drawPageIndicator] draws the `i / n` page indicator at the labels tail.
 *
 * No vanilla layout state is read per frame: the panel geometry (`leftPos` / `topPos` /
 * `imageWidth` / `imageHeight`) is captured once per init - init also runs on resize, so the cache
 * stays fresh - and the render path is arithmetic only.
 */
object CreativeScreenPagination {

    /** The strip, rebuilt at every screen init (config edits apply when the screen reopens). */
    @Volatile
    private var strip: CreativeTabStrip? = null

    // Panel geometry, captured at every init (resize re-runs init) - the render path reads
    // these instead of reflecting into the screen three times per tab per frame.
    private var panelLeft = 0
    private var panelTop = 0
    private var panelWidth = 0
    private var panelHeight = 0

    /** Vanilla's tab pitch and the two rows' offsets from the panel, in screen pixels. */
    private const val COLUMN_WIDTH = 27
    private const val TOP_OFFSET = 28
    private const val BOTTOM_OFFSET = 4

    /** Where a button with no slot on this page is parked (the draw pass ignores visibility). */
    private const val HIDDEN = -10_000

    // The page indicator's text, rebuilt only when the page or page count changes.
    private var indicator: Component? = null
    private var indicatorPage = -1
    private var indicatorPageCount = -1

    @JvmStatic
    fun onScreenInit(screen: CreativeModeInventoryScreen) {
        // The player config is read first: the strip is built from the ordered flowing set it
        // produces, so config edits apply when the screen (re)opens.
        CreativeTabsPlayerConfig.onScreenInit(screen)
        panelLeft = readPos(screen, "leftPos")
        panelTop = readPos(screen, "topPos")
        panelWidth = readPos(screen, "imageWidth")
        panelHeight = readPos(screen, "imageHeight")
        val strip = CreativeTabStrip(CreativeModeTabs.tabs(), flowingOrder())
        this.strip = strip
        indicator = null
        if (strip.pageCount > 1) {
            // UnfocusedButton, not Button: the arrows must keep the hover-only border — a plain
            // button takes focus on press and keeps the white border after the mouse leaves.
            addWidget(screen, UnfocusedButton(panelLeft, panelTop - 50, 20, 20, Component.literal("<")) {
                strip.page = (strip.page - 1).coerceAtLeast(0)
            })
            addWidget(
                screen,
                UnfocusedButton(
                    panelLeft + panelWidth - 20, panelTop - 50, 20, 20, Component.literal(">"),
                ) { strip.page = (strip.page + 1).coerceAtMost(strip.pageCount - 1) },
            )
        }
        // The in-game tab manager (phase two of the player config): lists every flowing tab with
        // hide / reorder controls and writes oml.toml on Done.
        addWidget(
            screen,
            UnfocusedButton(
                panelLeft + panelWidth / 2 - 50, panelTop - 74, 100, 20, Component.literal("Manage Tabs"),
            ) { Minecraft.getInstance().setScreenAndShow(CreativeTabsManageScreen(screen)) },
        )
        // The bars were built and arranged earlier in init; put the buttons where the strip wants
        // them before the first frame, not one frame later.
        applyStripLayout(screen)
    }

    /**
     * The flowing-tab display order: the tab manager's constraint-resolved key order (mapped
     * back to tabs post-freeze) with the player's arrangement applied; the registry order when
     * no mod declared through the manager. The config's entries are the arrangement in display
     * order with per-entry hidden flags - a hidden entry is dropped from its slot (nothing can
     * resurrect it: the flag travels with the entry), unplaced tabs keep their default order
     * after the placed ones.
     */
    private fun flowingOrder(): List<CreativeModeTab> {
        val registryOrder = CreativeModeTabs.tabs().filter { !it.isAlignedRight() }
        val idOf = { tab: CreativeModeTab ->
            BuiltInRegistries.CREATIVE_MODE_TAB.getResourceKey(tab).orElseThrow().identifier()
        }
        val base = org.ohmyloader.api.OMLCreativeTabs.resolvedKeyOrder
            ?.mapNotNull { key -> registryOrder.firstOrNull { idOf(it) == key } }
            ?: registryOrder
        val config = CreativeTabsPlayerConfig.cached ?: return base
        val byId = base.associateBy(idOf)
        val placed = config.entries.mapNotNull { entry -> byId[entry.id]?.takeUnless { entry.hidden } }
        return placed + base.filter { idOf(it) !in config.placedIds }
    }

    /**
     * `extractBackground` head: put vanilla's tab buttons where the strip model wants them. The
     * screen draws the unselected buttons in that pass and the bars draw all of them again later,
     * so this runs before both and every frame (a resize re-arranges the bars behind our back).
     *
     * A tab with no slot on the current page is hidden *and* moved off-screen: the unselected pass
     * calls `TabButton.extractRenderState` directly, which ignores the widget's visibility flag.
     */
    @JvmStatic
    fun applyStripLayout(screen: CreativeModeInventoryScreen) {
        val currentStrip = strip ?: return
        for ((index, bar) in tabBars(screen).withIndex()) {
            val topBar = index == 0
            for (button in tabButtons(bar)) {
                val slot = slotOf(currentStrip, button)?.takeIf { it.top == topBar }
                if (slot == null) {
                    button.setVisible(false)
                    button.setX(HIDDEN)
                    button.setY(HIDDEN)
                    continue
                }
                button.setVisible(true)
                button.setX(panelLeft + COLUMN_WIDTH * slot.column)
                button.setY(if (slot.top) panelTop - TOP_OFFSET else panelTop + panelHeight - BOTTOM_OFFSET)
            }
        }
    }

    private fun tabBars(screen: CreativeModeInventoryScreen): List<TabNavigationBar> =
        listOfNotNull(tabBarTopField.get(screen) as TabNavigationBar?, tabBarBottomField.get(screen) as TabNavigationBar?)

    @Suppress("UNCHECKED_CAST")
    private fun tabButtons(bar: TabNavigationBar): List<TabButton> =
        tabButtonsField.get(bar) as List<TabButton>

    private fun slotOf(currentStrip: CreativeTabStrip, button: TabButton): CreativeTabStrip.Slot? {
        val tab = tabOfMethod.invoke(button.tab()) as CreativeModeTab
        return currentStrip.slotOf(tab)
    }

    private val tabBarTopField by lazy {
        CreativeModeInventoryScreen::class.java.getDeclaredField("tabBarTop").apply { isAccessible = true }
    }

    private val tabBarBottomField by lazy {
        CreativeModeInventoryScreen::class.java.getDeclaredField("tabBarBottom").apply { isAccessible = true }
    }

    private val tabButtonsField by lazy {
        TabNavigationBar::class.java.getDeclaredField("tabButtons").apply { isAccessible = true }
    }

    /** The tab behind a button: `CreativeModeTabAdapter` is package-private, its reader is public. */
    private val tabOfMethod by lazy {
        Class.forName("net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen\$CreativeModeTabAdapter")
            .getDeclaredMethod("tab")
            .apply { isAccessible = true }
    }

    /**
     * [CreativeModeInventoryScreen.extractLabels] tail: the `i / n` page indicator. The labels
     * pass draws in panel-relative coordinates (the pose is translated to the panel), so the
     * indicator centers on `imageWidth / 2` at `-44` — the same spot vanilla's absolute-space
     * draws (`leftPos + imageWidth / 2 …, topPos - 44`) land on screen. The text is rebuilt
     * only when the page or page count changes, not every frame.
     */
    @JvmStatic
    fun drawPageIndicator(
        screen: CreativeModeInventoryScreen,
        graphics: GuiGraphicsExtractor,
    ) {
        val currentStrip = strip ?: return
        if (currentStrip.pageCount <= 1) {
            return
        }
        if (indicator == null || indicatorPage != currentStrip.page || indicatorPageCount != currentStrip.pageCount) {
            indicator = Component.literal("${currentStrip.page + 1} / ${currentStrip.pageCount}")
            indicatorPage = currentStrip.page
            indicatorPageCount = currentStrip.pageCount
        }
        val font = screen.getFont()
        val text = indicator!!
        graphics.text(font, text, panelWidth / 2 - font.width(text) / 2, -44, -1, false)
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

    /** Init-time panel geometry reads - the render path never calls these. */
    private fun readPos(screen: CreativeModeInventoryScreen, name: String): Int =
        Refl.fieldOrNull(CreativeModeInventoryScreen::class.java, name, net.minecraft.client.gui.screens.Screen::class.java)!!.getInt(screen)
}
