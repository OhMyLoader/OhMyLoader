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
 * the manager's resolved order is the base, the player overrides hide/order on top, and nothing
 * here reaches the server.
 *
 * The file carries two root-table string arrays:
 *
 * ```toml
 * hidden = ["minecraft:food"]
 * order = ["ironchest:general", "minecraft:building_blocks"]
 * ```
 *
 * `hidden` removes tabs from the screen entirely; `order` places the listed tabs first, in this
 * exact relative order, with unlisted tabs keeping their default order after them. Right-aligned
 * special tabs (hotbar / search / operator / inventory) are functional entries of the screen and
 * are not configurable. A missing file means defaults; a broken file logs and means defaults -
 * a player typo must never take the creative screen down.
 */
object CreativeTabsPlayerConfig {

    class Config(val hidden: Set<Identifier>, val order: List<Identifier>, val orderSet: Set<Identifier>)

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
        val hidden = stringArray(root["hidden"]).mapNotNull(::parseId).toSet()
        val order = stringArray(root["order"]).mapNotNull(::parseId)
        return Config(hidden, order, order.toSet())
    }

    private fun stringArray(value: Any?): List<String> =
        (value as? List<*>)?.filterIsInstance<String>() ?: emptyList()

    private fun parseId(raw: String): Identifier? =
        runCatching { Identifier.parse(raw) }.getOrNull()

    /** The ids the player hid, empty when no config is in effect. */
    fun hiddenIds(): Set<Identifier> = cached?.hidden ?: emptySet()

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
            appendLine("# hidden: tabs removed from the creative screen entirely.")
            appendLine("# order: the listed tabs display first, in this exact relative order; tabs not")
            appendLine("#        listed keep their default position after them.")
            appendLine("# Every id is \"namespace:path\". The right-aligned special tabs (hotbar, search,")
            appendLine("# operator, inventory) are not configurable. Changes apply when the screen reopens.")
            appendLine("# Tabs currently registered:")
            ids.forEach { appendLine("#   $it") }
            appendLine("hidden = []")
            appendLine("order = []")
        }
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(body)
        }.onFailure {
            OmlLog.warn("OMLCreativeTabs", "could not write the creative-tabs.toml template (${it.message})")
        }
    }
}
