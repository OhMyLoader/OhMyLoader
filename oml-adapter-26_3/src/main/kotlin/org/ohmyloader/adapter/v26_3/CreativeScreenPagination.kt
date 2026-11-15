package org.ohmyloader.adapter.v26_3

import com.mojang.blaze3d.platform.cursor.CursorTypes
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen
import net.minecraft.client.gui.components.Button
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.util.Mth
import net.minecraft.world.item.CreativeModeTab
import net.minecraft.world.item.CreativeModeTabs
import org.ohmyloader.core.adapter.Refl

/**
 * The screen-facing half of the creative-tab takeover: owns the [CreativeTabStrip] (rebuilt on
 * every screen init from the registry, the tab manager's resolved order and the player's config)
 * and answers, from the strip model alone, everything the vanilla strip code used to compute
 * from `tab.row()` / `tab.column()`:
 *
 * - [drawTab] replaces `extractTabButton`: draws a tab at its strip slot, or nothing when the
 *   tab has no slot on this page (hidden by the player, or on another page);
 * - [tabClicked] / [tabHovered] replace `checkTabClicked` / `checkTabHovering`: hit tests against
 *   the strip slot;
 * - [onScreenInit] also creates the two vanilla `PageButton`s when the strip spans multiple
 *   pages (the book UI's page-turn button: page sprites, turn sound, never takes focus after
 *   the interaction).
 *
 * The vanilla loops still drive all of this - they iterate every registered tab and hand each to
 * the handlers, which decide visibility from the strip. No vanilla layout state is read.
 *
 * `leftPos` / `topPos` / `imageWidth` / `imageHeight` / `addRenderableWidget` are protected screen
 * members read through cached reflection - the injection DSL has no field operand, the same
 * reason `Refl` exists.
 */
object CreativeScreenPagination {

    /** The strip, rebuilt at every screen init (config edits apply when the screen reopens). */
    @Volatile
    private var strip: CreativeTabStrip? = null

    @JvmStatic
    fun onScreenInit(screen: CreativeModeInventoryScreen) {
        // The player config is read first: the strip is built from the ordered flowing set it
        // produces, so config edits apply when the screen (re)opens.
        CreativeTabsPlayerConfig.onScreenInit(screen)
        val strip = CreativeTabStrip(CreativeModeTabs.tabs(), flowingOrder())
        this.strip = strip
        if (strip.pageCount > 1) {
            // UnfocusedButton, not Button: the arrows must keep the hover-only border — a plain
            // button takes focus on press and keeps the white border after the mouse leaves.
            addWidget(screen, UnfocusedButton(leftPos(screen), topPos(screen) - 50, 20, 20, Component.literal("<")) {
                strip.page = (strip.page - 1).coerceAtLeast(0)
            })
            addWidget(
                screen,
                UnfocusedButton(
                    leftPos(screen) + imageWidth(screen) - 20, topPos(screen) - 50, 20, 20, Component.literal(">"),
                ) { strip.page = (strip.page + 1).coerceAtMost(strip.pageCount - 1) },
            )
        }
        // The in-game tab manager (phase two of the player config): lists every flowing tab with
        // hide / reorder controls and writes creative-tabs.toml on Done.
        addWidget(
            screen,
            UnfocusedButton(
                leftPos(screen) + imageWidth(screen) / 2 - 50, topPos(screen) - 74, 100, 20, Component.literal("Manage Tabs"),
            ) { Minecraft.getInstance().setScreenAndShow(CreativeTabsManageScreen(screen)) },
        )
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
        val base = org.ohmyloader.api26_3.OMLCreativeTabs.resolvedKeyOrder
            ?.mapNotNull { key -> registryOrder.firstOrNull { idOf(it) == key } }
            ?: registryOrder
        val config = CreativeTabsPlayerConfig.cached ?: return base
        val byId = base.associateBy(idOf)
        val placed = config.entries.mapNotNull { entry -> byId[entry.id]?.takeUnless { entry.hidden } }
        return placed + base.filter { idOf(it) !in config.placedIds }
    }

    private fun selectedTab(): CreativeModeTab = selectedTabField.get(null) as CreativeModeTab

    private val selectedTabField by lazy {
        CreativeModeInventoryScreen::class.java.getDeclaredField("selectedTab").apply { isAccessible = true }
    }

    private val spriteArrays by lazy {
        val screen = CreativeModeInventoryScreen::class.java
        fun array(name: String): Array<out Any> {
            val field = screen.getDeclaredField(name)
            field.isAccessible = true
            return field.get(null) as Array<out Any>
        }
        arrayOf(
            array("SELECTED_TOP_TABS"), array("UNSELECTED_TOP_TABS"),
            array("SELECTED_BOTTOM_TABS"), array("UNSELECTED_BOTTOM_TABS"),
        )
    }

    /** [CreativeModeInventoryScreen.extractTabButton] replacement: draws the tab at its strip slot. */
    @JvmStatic
    fun drawTab(
        screen: CreativeModeInventoryScreen,
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        tab: CreativeModeTab,
    ) {
        val currentStrip = strip ?: return
        if (CreativeTabsPlayerConfig.hiddenIds().contains(
                BuiltInRegistries.CREATIVE_MODE_TAB.getResourceKey(tab).orElseThrow().identifier(),
            )
        ) {
            return
        }
        val slot = currentStrip.slotOf(tab) ?: return
        val selected = selectedTab() === tab
        val top = slot.top
        val x = leftPos(screen) + 27 * slot.column
        val y = topPos(screen) - if (top) 28 else -(imageHeight(screen) - 4)
        val sprites = when {
            top && selected -> spriteArrays[0]
            top -> spriteArrays[1]
            selected -> spriteArrays[2]
            else -> spriteArrays[3]
        }
        if (!selected && mouseX > x && mouseY > y && mouseX < x + 26 && mouseY < y + 32) {
            graphics.requestCursor(CursorTypes.POINTING_HAND)
        }
        graphics.blitSprite(
            RenderPipelines.GUI_TEXTURED,
            sprites[Mth.clamp(slot.column, 0, sprites.size - 1)] as net.minecraft.resources.Identifier,
            x,
            y,
            26,
            32,
        )
        graphics.item(tab.getIconItem(), x + 13 - 8, y + 16 - 8 + if (top) 1 else -1)
    }

    /** [CreativeModeInventoryScreen.checkTabClicked] replacement: strip-slot hit test. */
    @JvmStatic
    fun tabClicked(
        screen: CreativeModeInventoryScreen,
        tab: CreativeModeTab,
        xm: Double,
        ym: Double,
    ): Boolean {
        val currentStrip = strip ?: return false
        val slot = currentStrip.slotOf(tab) ?: return false
        val relY = if (slot.top) -32 else imageHeight(screen)
        val relX = 27 * slot.column
        return xm >= relX && xm <= relX + 26 && ym >= relY && ym <= relY + 32
    }

    /** [CreativeModeInventoryScreen.checkTabHovering] replacement: strip-slot hover + tooltip. */
    @JvmStatic
    fun tabHovered(
        screen: CreativeModeInventoryScreen,
        graphics: GuiGraphicsExtractor,
        tab: CreativeModeTab,
        xm: Int,
        ym: Int,
    ): Boolean {
        val currentStrip = strip ?: return false
        val slot = currentStrip.slotOf(tab) ?: return false
        val relX = 27 * slot.column
        val relY = if (slot.top) -32 else imageHeight(screen)
        if (xm >= relX + 3 && xm <= relX + 24 && ym >= relY + 3 && ym <= relY + 30) {
            graphics.setTooltipForNextFrame(screen.getFont(), tab.getDisplayName(), xm, ym)
            return true
        }
        return false
    }

    /**
     * [CreativeModeInventoryScreen.extractLabels] tail: the `i / n` page indicator. The labels
     * pass draws in panel-relative coordinates (the pose is translated to the panel), so the
     * indicator centers on `imageWidth / 2` at `-44` — the same spot vanilla's absolute-space
     * draws (`leftPos + imageWidth / 2 …, topPos - 44`) land on screen.
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
        val font = screen.getFont()
        val text = Component.literal("${currentStrip.page + 1} / ${currentStrip.pageCount}")
        graphics.text(font, text, imageWidth(screen) / 2 - font.width(text) / 2, -44, -1, false)
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

    private fun leftPos(screen: CreativeModeInventoryScreen): Int =
        Refl.fieldOrNull(CreativeModeInventoryScreen::class.java, "leftPos", net.minecraft.client.gui.screens.Screen::class.java)!!.getInt(screen)

    private fun topPos(screen: CreativeModeInventoryScreen): Int =
        Refl.fieldOrNull(CreativeModeInventoryScreen::class.java, "topPos", net.minecraft.client.gui.screens.Screen::class.java)!!.getInt(screen)

    private fun imageWidth(screen: CreativeModeInventoryScreen): Int =
        Refl.fieldOrNull(CreativeModeInventoryScreen::class.java, "imageWidth", net.minecraft.client.gui.screens.Screen::class.java)!!.getInt(screen)

    private fun imageHeight(screen: CreativeModeInventoryScreen): Int =
        Refl.fieldOrNull(CreativeModeInventoryScreen::class.java, "imageHeight", net.minecraft.client.gui.screens.Screen::class.java)!!.getInt(screen)
}
