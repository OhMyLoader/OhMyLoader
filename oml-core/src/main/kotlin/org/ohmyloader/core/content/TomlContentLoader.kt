package org.ohmyloader.core.content

import org.ohmyloader.api.content.ContentRegistry
import java.io.File

/**
 * Loads flat TOML content packs into the [ContentRegistry]: a plain text file dropped into the mods directory
 * becomes real, data-component-backed blocks and items with no jar and no code. The file name (minus extension,
 * lowercased) is the resource namespace — `ruby_pack.toml` declares `ruby_pack:...` content. Field names are
 * snake_case mirrors of the [org.ohmyloader.api.content.OMLBlockDeclaration] /
 * [org.ohmyloader.api.content.OMLItemDeclaration] properties; every field is optional and falls back to the
 * same defaults as the code API. Unknown keys are reported loudly (a typo'd field would otherwise silently
 * produce vanilla defaults) but do not reject the pack. Packs are loaded by [org.ohmyloader.core.OMLCore] in
 * the content-declaration window (the same collection phase code mods go through), sharing the identical
 * freeze-point materialization; the asset injector synthesizes blockstates / models / item definitions like it does for jar mods.
 */
object TomlContentLoader {

    private const val TAG = "[TomlContent]"

    private val ID = Regex("[a-z0-9_.-]+")
    private val NAMESPACE = ID

    private val BLOCK_KEYS = setOf("destroy_time", "explosion_resistance", "requires_correct_tool")
    private val ITEM_KEYS = setOf(
        "max_damage", "attack_damage", "attack_speed", "mining_speed",
        "tool_damage_per_block", "can_destroy_blocks_in_creative",
        "mines_and_drops", "denies_drops", "override_speed",
    )

    /** What one pack contributed, for the loader's startup log and tests. */
    data class PackSummary(val namespace: String, val blocks: Int, val items: Int)

    /**
     * The resource namespace of a pack file: `ruby_pack.toml` loads as `ruby_pack`. Fails when the
     * derived name is not a valid resource domain (e.g. spaces, uppercase leftovers).
     */
    fun namespaceOf(file: File): String {
        val namespace = file.nameWithoutExtension.lowercase()
        require(NAMESPACE.matches(namespace)) {
            "$TAG ${file.name}: derived namespace '$namespace' is not a valid resource domain " +
                "(must match [a-z0-9_.-]+); rename the file"
        }
        return namespace
    }

    /**
     * Parses and declares one pack into [registry] (already bound to the pack's namespace via
     * [org.ohmyloader.api.content.ContentRegistryFactory]). Throws on malformed TOML or invalid
     * ids — callers catch per pack, so one broken file never takes down the others.
     */
    fun load(file: File, registry: ContentRegistry): PackSummary {
        val namespace = namespaceOf(file)
        val doc = try {
            MinimalToml.parse(file.readText())
        } catch (e: MinimalToml.TomlParseException) {
            throw IllegalStateException("$TAG ${file.name}: ${e.message}", e)
        }

        var blocks = 0
        var items = 0
        for ([path, fields] in doc) {
            when {
                path.isEmpty() -> if (fields.isNotEmpty()) {
                    println(
                        "$TAG ${file.name}: ignoring root-level key(s) ${fields.keys} — " +
                            "content must live under [block.<id>] or [item.<id>]"
                    )
                }

                path.startsWith("block.") && path.count { it == '.' } == 1 -> {
                    declareBlock(path.removePrefix("block."), fields, file.name, registry)
                    blocks++
                }

                path.startsWith("item.") && path.count { it == '.' } == 1 -> {
                    declareItem(path.removePrefix("item."), fields, file.name, registry)
                    items++
                }

                else -> println(
                    "$TAG ${file.name}: ignoring section [$path] — " +
                        "expected [block.<id>] or [item.<id>]"
                )
            }
        }
        return PackSummary(namespace, blocks, items)
    }

    // ---------------------------------------------------------------------------------------------
    // Section mapping
    // ---------------------------------------------------------------------------------------------

    private fun declareBlock(id: String, fields: Map<String, Any>, source: String, registry: ContentRegistry) {
        requireId(id, source, "block")
        registry.declareBlock(id) {
            destroyTime = floatValue(fields, "destroy_time", source, "block '$id'")
            explosionResistance = floatValue(fields, "explosion_resistance", source, "block '$id'")
            requiresCorrectToolForDrops =
                boolValue(fields, "requires_correct_tool", source, "block '$id'") ?: false
        }
        reportUnknown(fields, BLOCK_KEYS, source, "block '$id'")
    }

    private fun declareItem(id: String, fields: Map<String, Any>, source: String, registry: ContentRegistry) {
        requireId(id, source, "item")
        registry.declareItem(id) {
            maxDamage = intValue(fields, "max_damage", source, "item '$id'")
            attackDamage = doubleValue(fields, "attack_damage", source, "item '$id'")
            attackSpeed = doubleValue(fields, "attack_speed", source, "item '$id'")
            miningSpeed = floatValue(fields, "mining_speed", source, "item '$id'")
            toolDamagePerBlock =
                intValue(fields, "tool_damage_per_block", source, "item '$id'") ?: 1
            canDestroyBlocksInCreative =
                boolValue(fields, "can_destroy_blocks_in_creative", source, "item '$id'") ?: true
            for (rule in listValue(fields, "mines_and_drops", source, "item '$id'").orEmpty()) {
                minesAndDrops(ruleBlock(rule, "mines_and_drops", source), ruleSpeed(rule, "mines_and_drops", source))
            }
            for (rule in listValue(fields, "denies_drops", source, "item '$id'").orEmpty()) {
                deniesDrops(ruleBlock(rule, "denies_drops", source))
            }
            for (rule in listValue(fields, "override_speed", source, "item '$id'").orEmpty()) {
                overrideSpeed(ruleBlock(rule, "override_speed", source), ruleSpeed(rule, "override_speed", source))
            }
        }
        reportUnknown(fields, ITEM_KEYS, source, "item '$id'")
    }

    private fun requireId(id: String, source: String, kind: String) {
        require(ID.matches(id)) {
            "$TAG $source: '$id' is not a valid $kind id (must match [a-z0-9_.-]+)"
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Typed field accessors — wrong types are hard errors, not silent defaults
    // ---------------------------------------------------------------------------------------------

    private fun number(fields: Map<String, Any>, key: String, source: String, ctx: String): Number? =
        when (val v = fields[key]) {
            null -> null
            is Number -> v
            else -> fail(source, "$ctx.$key must be a number, got ${describe(v)}")
        }

    private fun floatValue(fields: Map<String, Any>, key: String, source: String, ctx: String): Float? =
        number(fields, key, source, ctx)?.toFloat()

    private fun doubleValue(fields: Map<String, Any>, key: String, source: String, ctx: String): Double? =
        number(fields, key, source, ctx)?.toDouble()

    private fun intValue(fields: Map<String, Any>, key: String, source: String, ctx: String): Int? =
        number(fields, key, source, ctx)?.let { n ->
            val d = n.toDouble()
            if (d.rem(1.0) != 0.0) fail(source, "$ctx.$key must be a whole number, got $n")
            d.toInt()
        }

    private fun boolValue(fields: Map<String, Any>, key: String, source: String, ctx: String): Boolean? =
        when (val v = fields[key]) {
            null -> null
            is Boolean -> v
            else -> fail(source, "$ctx.$key must be true or false, got ${describe(v)}")
        }

    private fun listValue(fields: Map<String, Any>, key: String, source: String, ctx: String): List<*>? =
        when (val v = fields[key]) {
            null -> null
            is List<*> -> v
            else -> fail(source, "$ctx.$key must be an array, got ${describe(v)}")
        }

    private fun ruleBlock(rule: Any?, listKey: String, source: String): String {
        val map = rule as? Map<*, *>
            ?: fail(source, "$listKey entries must be inline tables like { block = \"minecraft:stone\", speed = 8.0 }")
        return map["block"] as? String
            ?: fail(source, "$listKey entry is missing its 'block' string (got ${describe(map["block"])})")
    }

    private fun ruleSpeed(rule: Any?, listKey: String, source: String): Float {
        val map = rule as? Map<*, *> ?: return 1f // unreachable: ruleBlock already validated the shape
        val v = map["speed"] as? Number
            ?: fail(source, "$listKey entry for '${map["block"]}' is missing its numeric 'speed'")
        return v.toFloat()
    }

    private fun reportUnknown(fields: Map<String, Any>, known: Set<String>, source: String, ctx: String) {
        val unknown = fields.keys - known
        if (unknown.isNotEmpty()) {
            println(
                "$TAG $source: $ctx has unknown field(s) $unknown — check for typos " +
                    "(known: ${known.sorted()})"
            )
        }
    }

    private fun describe(v: Any?): String = when (v) {
        null -> "nothing"
        is String -> "the string \"$v\""
        else -> "${v::class.simpleName} '$v'"
    }

    private fun fail(source: String, message: String): Nothing =
        throw IllegalStateException("$TAG $source: $message")
}
