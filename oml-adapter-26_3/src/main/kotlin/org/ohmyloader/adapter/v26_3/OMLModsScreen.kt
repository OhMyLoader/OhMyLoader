package org.ohmyloader.adapter.v26_3

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.ContainerObjectSelectionList
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout
import net.minecraft.client.gui.layouts.LinearLayout
import net.minecraft.client.gui.narration.NarratableEntry
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.network.chat.Component
import org.ohmyloader.api26_3.OMLReboot
import org.ohmyloader.api26_3.closeScreen

/**
 * The Mods screen: what this session actually loaded, where it lives, and the two ways out of it.
 *
 * <p>Deliberately a list and four buttons — no per-mod config forms. A code mod (`.jar`) and a
 * declaration pack (`.oml`) are the two things a reload re-reads, so those are the two rows the
 * screen knows, each labelled with its source, name, id and version. The footer opens the `mods/`
 * folder, opens `config/<id>.toml` for the selected row, and triggers the same soft reboot the
 * pause menu's Reload button does.
 *
 * <p>Every open re-reads the directory once and compares it against the baseline the session took
 * at game ready: a file that was added, removed or touched since then is a change a reload would
 * pick up, so it is called out in red above the footer with the instruction to press Reload.
 */
class OMLModsScreen(private val parent: Screen?) : Screen(Component.literal("Mods")) {

    private val layout = HeaderAndFooterLayout(this, HEADER_HEIGHT, FOOTER_HEIGHT)
    private lateinit var modList: ModList
    private lateinit var openConfigButton: UnfocusedButton

    private var entries: List<ModsInventory.Entry> = emptyList()
    private var changes: List<String> = emptyList()
    private var selected: ModsInventory.Entry? = null

    /** What the last button did — a failure has to be visible, not only in the log. */
    private var status: String = ""

    override fun init() {
        entries = ModsInventory.entries()
        changes = ModsInventory.changes()
        selected = null
        status = ""

        val header = LinearLayout.vertical().spacing(4)
        header.addChild(
            StringWidget(title, font),
            header.newCellSettings().alignHorizontallyCenter(),
        )
        val packCount = entries.count { it.kind == ModsInventory.Kind.PACK }
        val modCount = entries.size - packCount
        header.addChild(
            StringWidget(
                Component.literal("$modCount code mod(s), $packCount content pack(s) — select a row for its config"),
                font,
            ),
            header.newCellSettings().alignHorizontallyCenter(),
        )
        layout.addToHeader(header)

        modList = ModList()
        layout.addToContents(modList)

        val openFolder = UnfocusedButton(0, 0, 150, 20, Component.literal("Open Mods Folder")) { openModsFolder() }
        openConfigButton = UnfocusedButton(0, 0, 140, 20, Component.literal("Open Config")) { openSelectedConfig() }
        val reload = UnfocusedButton(0, 0, 150, 20, Component.literal("Reload Mods")) { reloadMods() }
        val done = UnfocusedButton(0, 0, 100, 20, Component.translatable("gui.done")) { onClose() }
        val first = LinearLayout.horizontal().spacing(4).apply {
            addChild(openFolder)
            addChild(openConfigButton)
        }
        val second = LinearLayout.horizontal().spacing(4).apply {
            addChild(reload)
            addChild(done)
        }
        val footer = LinearLayout.vertical().spacing(4)
        footer.addChild(first, footer.newCellSettings().alignHorizontallyCenter())
        footer.addChild(second, footer.newCellSettings().alignHorizontallyCenter())
        layout.addToFooter(footer)

        layout.visitWidgets { addRenderableWidget(it) }
        updateButtons()
        repositionElements()
    }

    override fun repositionElements() {
        layout.arrangeElements()
        modList.updateSize(width, layout)
    }

    override fun onClose() {
        val minecraft = Minecraft.getInstance()
        // Opened over the game itself (a reload landed here in-world): closing returns to the HUD,
        // which vanilla does with a null screen. Over a menu, returning to that menu is the norm.
        val back = parent ?: if (minecraft.level != null) null else TitleScreen()
        if (back == null) {
            minecraft.closeScreen()
        } else {
            minecraft.setScreenAndShow(back)
        }
    }

    /**
     * The status/hint line above the footer. Drawn here rather than as a widget because it is one
     * line of text whose colour carries the meaning (red = changed, grey = what just happened).
     */
    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        val lineY = height - layout.footerHeight - 13
        if (changes.isNotEmpty()) {
            val summary = "${changes.size} file(s) under mods/ changed since startup — press Reload Mods"
            graphics.text(font, summary, 8, lineY, CHANGED_COLOR, true)
            val names = changes.take(3).joinToString(", ")
            graphics.text(font, font.plainSubstrByWidth(names, width - 16), 8, lineY + 10, CHANGED_COLOR, false)
        } else if (status.isNotEmpty()) {
            graphics.text(font, font.plainSubstrByWidth(status, width - 16), 8, lineY, STATUS_COLOR, false)
        }
    }

    /** The list: rows rebuilt from [entries] once per open; selection drives the Open Config button. */
    private inner class ModList : ContainerObjectSelectionList<Row>(
        Minecraft.getInstance(),
        this@OMLModsScreen.width,
        layout.contentHeight,
        layout.headerHeight,
        ROW_HEIGHT,
    ) {
        init {
            for (entry in entries) addEntry(Row(this@OMLModsScreen, entry))
        }

        override fun getRowWidth(): Int = MAX_ROW_WIDTH

        override fun setSelected(entry: Row?) {
            super.setSelected(entry)
            this@OMLModsScreen.selected = entry?.entry
            updateButtons()
        }
    }

    /** One row: source badge, then name and version on the first line, id and origin on the second. */
    private class Row(
        private val screen: OMLModsScreen,
        val entry: ModsInventory.Entry,
    ) : ContainerObjectSelectionList.Entry<Row>() {

        override fun children(): List<AbstractWidget> = emptyList()

        override fun narratables(): List<NarratableEntry> = emptyList()

        override fun extractContent(
            graphics: GuiGraphicsExtractor,
            mouseX: Int,
            mouseY: Int,
            hovered: Boolean,
            partialTick: Float,
        ) {
            val chosen = screen.selected === entry
            if (chosen) {
                graphics.fill(contentX - 2, contentY - 1, contentRight + 2, contentY + contentHeight, SELECTED_FILL)
            }
            val badge = entry.kind.badge
            graphics.text(
                screen.font,
                badge,
                contentX + 2,
                contentY + 3,
                if (entry.kind == ModsInventory.Kind.CODE) CODE_COLOR else PACK_COLOR,
                false,
            )
            val nameWidth = contentWidth - BADGE_WIDTH - 4
            val version = entry.version
            val versionWidth = screen.font.width(version)
            val name = screen.font.plainSubstrByWidth(entry.name, nameWidth - versionWidth - 6)
            graphics.text(screen.font, name, contentX + BADGE_WIDTH, contentY + 3, NAME_COLOR, false)
            graphics.text(
                screen.font,
                version,
                contentRight - versionWidth,
                contentY + 3,
                META_COLOR,
                false,
            )
            val meta = "${entry.id} · ${entry.origin}"
            graphics.text(
                screen.font,
                screen.font.plainSubstrByWidth(meta, contentWidth - BADGE_WIDTH - 4),
                contentX + BADGE_WIDTH,
                contentY + 14,
                META_COLOR,
                false,
            )
        }
    }

    private fun updateButtons() {
        openConfigButton.active = selected?.config?.isFile == true
    }

    private fun openModsFolder() {
        val dir = ModsInventory.modsDirectory()
        if (dir == null) {
            status = "The mods directory is not known yet."
            return
        }
        status = if (ModsInventory.open(dir)) "Opened ${dir.path}" else "Could not open ${dir.path} — see the log"
    }

    private fun openSelectedConfig() {
        val config = selected?.config
        if (config == null || !config.isFile) {
            status = "No config file for ${selected?.id ?: "the selected mod"}."
            return
        }
        status = if (ModsInventory.open(config)) "Opened ${config.path}" else "Could not open ${config.path} — see the log"
    }

    /**
     * The same entry point `/oml reload` and the pause menu use, with one addition: the next
     * session reopens this screen once its transition lifts, so the player lands back on the list
     * they were reading instead of on the title screen or in the world.
     */
    private fun reloadMods() {
        status = "Reloading…"
        OMLReboot.restart("OMLModsScreen", reopen = REOPEN_ID)
    }

    private companion object {
        const val HEADER_HEIGHT = 50
        const val FOOTER_HEIGHT = 52
        const val ROW_HEIGHT = 26
        const val MAX_ROW_WIDTH = 340
        const val BADGE_WIDTH = 26
        const val NAME_COLOR = 0xFFFFFFFF.toInt()
        const val META_COLOR = 0xFF9A9A9A.toInt()
        const val CODE_COLOR = 0xFFFFC46B.toInt()
        const val PACK_COLOR = 0xFF8FE388.toInt()
        const val CHANGED_COLOR = 0xFFFF5555.toInt()
        const val STATUS_COLOR = 0xFFAAAAAA.toInt()
        const val SELECTED_FILL = 0x30FFFFFF

        /** The id [ModsMenuHook] matches to reopen this screen in the next session. */
        const val REOPEN_ID = "mods"
    }
}
