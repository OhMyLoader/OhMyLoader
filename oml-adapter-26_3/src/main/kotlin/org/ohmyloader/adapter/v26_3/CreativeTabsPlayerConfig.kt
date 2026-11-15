package org.ohmyloader.adapter.v26_3

import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.world.item.CreativeModeTab
import net.minecraft.world.item.CreativeModeTabs
import org.ohmyloader.api.OmlLog
import org.ohmyloader.content.MinimalToml
import org.ohmyloader.core.OMLCore
import java.io.File

/**
 * The player's creative-tab preferences: `config/creative-tabs.toml`, read at creative-screen
 * init and cached until the next screen (re)init. Purely a client-side display preference -
 * the manager's resolved order is the base, the player overrides arrangement/hiding on top,
 * and nothing here reaches the server.
 *
 * The file carries one root-table string array, in display order, with `-` marking a hidden tab:
 *
 * ```toml
 * tabs = ["minecraft:building_blocks", "-minecraft:food", "ironchest:general"]
 * ```
 *
 * One collection carries both facts the manage screen edits: the entry order is the display
 * arrangement, and the `-` prefix is the Hide toggle. Hidden entries keep their slot so that
 * re-showing a tab puts it back where it was. Tabs not listed keep their default position
 * after the listed ones. Right-aligned special tabs (hotbar / search / operator / inventory)
 * are functional entries of the screen and are not configurable. A missing file means defaults;
 * a broken file logs and means defaults - a player typo must never take the creative screen
 * down. The pre-manager `hidden` / `order` pair is still read (hidden tabs land at the end of
 * the arrangement), so an old file survives the merge.
 */
object CreativeTabsPlayerConfig {

    /** One arrangement entry: where the tab sits, and whether it is hidden. */
    class Entry(val id: Identifier, val hidden: Boolean)

    class Config(val entries: List<Entry>) {
        /** The ids the player hid. */
        val hidden: Set<Identifier> = entries.filter { it.hidden }.map { it.id }.toSet()

        /** Every id the file places - hidden ones included, they keep their slot for re-showing. */
        val placedIds: Set<Identifier> = entries.mapTo(HashSet()) { it.id }
    }

    @Volatile
    internal var cached: Config? = null

    /**
     * Reads (and, on the first run, generates) `config/creative-tabs.toml`. Called from the
     * creative screen's init, so preferences apply when the screen opens; a config edit needs
     * the screen reopened.
     */
    fun onScreenInit(screen: CreativeModeInventoryScreen): Config? {
        val file = File(OMLCore.configDirectory(), "creative-tabs.toml")
        if (!file.isFile) {
            writeTemplate(screen)
            cached = null
            return null
        }
        cached = load(file) ?: run {
            cached = null
            null
        }
        return cached
    }

    private fun load(file: File): Config? {
        val doc = runCatching { MinimalToml.parse(file.readText()) }.getOrElse {
            OmlLog.warn("OMLCreativeTabs", "creative-tabs.toml is not valid TOML; using default tab layout (${it.message})")
            return null
        }
        val root = doc[""] ?: return null
        if (root.containsKey("tabs")) {
            val entries = stringArray(root["tabs"]).mapNotNull { raw ->
                val hidden = raw.startsWith(HIDDEN_PREFIX)
                parseId(raw.removePrefix(HIDDEN_PREFIX))?.let { Entry(it, hidden) }
            }
            return Config(entries)
        }
        // The legacy hidden/order pair: hidden entries carry no position, so they land at the
        // end of the arrangement (and order entries flagged hidden lose their position).
        val legacyHidden = stringArray(root["hidden"]).mapNotNull(::parseId).toSet()
        val legacyOrder = stringArray(root["order"]).mapNotNull(::parseId)
        val entries = legacyOrder.filter { it !in legacyHidden }.map { Entry(it, false) } +
            legacyHidden.map { Entry(it, true) }
        return Config(entries)
    }

    private fun stringArray(value: Any?): List<String> =
        (value as? List<*>)?.filterIsInstance<String>() ?: emptyList()

    private fun parseId(raw: String): Identifier? =
        runCatching { Identifier.parse(raw) }.getOrNull()

    /** The ids the player hid, empty when no config is in effect. */
    fun hiddenIds(): Set<Identifier> = cached?.hidden ?: emptySet()

    /**
     * Persists the player's arrangement: every row of the manage screen's draft in display
     * order, `-`-marked when hidden - hidden ones keep their slot, so re-showing puts them
     * back. Applies on the next creative screen init, which re-reads this file.
     */
    fun save(entries: List<Entry>) {
        val file = File(OMLCore.configDirectory(), "creative-tabs.toml")
        val body = buildString {
            appendLine("# Creative screen tab preferences (OML).")
            appendLine("#")
            appendLine("# One tabs array, in display order; \"-namespace:path\" hides that tab.")
            appendLine("# Tabs not listed keep their default position after the listed ones.")
            appendLine("# Editable by hand or through the in-game manage screen; changes apply")
            appendLine("# when the screen reopens.")
            append("tabs = [")
            append(entries.joinToString(", ") { "\"${if (it.hidden) HIDDEN_PREFIX else ""}${it.id}\"" })
            appendLine("]")
        }
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(body)
        }.onFailure {
            OmlLog.warn("OMLCreativeTabs", "could not save creative-tabs.toml (${it.message})")
        }
    }

    /** Writes a commented template naming every configurable tab id, once, if the file is absent. */
    private fun writeTemplate(screen: CreativeModeInventoryScreen) {
        val file = File(OMLCore.configDirectory(), "creative-tabs.toml")
        if (file.isFile) {
            return
        }
        val ids = CreativeModeTabs.tabs()
            .filter { !it.isAlignedRight() }
            .map { BuiltInRegistries.CREATIVE_MODE_TAB.getResourceKey(it).orElseThrow().identifier() }
        val body = buildString {
            appendLine("# Creative screen tab preferences (OML).")
            appendLine("#")
            appendLine("# One tabs array, in display order; \"-namespace:path\" hides that tab.")
            appendLine("# Tabs not listed keep their default position after the listed ones.")
            appendLine("# Every id is \"namespace:path\". The right-aligned special tabs (hotbar,")
            appendLine("# search, operator, inventory) are not configurable. Changes apply when")
            appendLine("# the screen reopens. Editable by hand or through the in-game manage screen.")
            appendLine("# Tabs currently registered:")
            ids.forEach { appendLine("#   $it") }
            appendLine("tabs = []")
        }
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(body)
        }.onFailure {
            OmlLog.warn("OMLCreativeTabs", "could not write the creative-tabs.toml template (${it.message})")
        }
    }

    private const val HIDDEN_PREFIX = "-"
}
