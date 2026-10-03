package org.ohmyloader.content

import org.ohmyloader.api.OmlLog
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

    private const val TAG = "Content"   // used as the OmlLog tag

    private val ID = Regex("[a-z0-9_.-]+")
    private val NAMESPACE = ID

    private val BLOCK_KEYS = setOf("destroy_time", "explosion_resistance", "requires_correct_tool")
    private val ITEM_KEYS = setOf(
        "max_damage", "attack_damage", "attack_speed", "mining_speed",
        "tool_damage_per_block", "can_destroy_blocks_in_creative",
        "mines_and_drops", "denies_drops", "override_speed",
    )

    /** What one pack contributed, for the loader's startup log and tests. */
    data class PackSummary(val namespace: String, val blocks: Int, val items: Int, val recipes: Int = 0)

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
     * Same as [load], for packs whose declaration is not a standalone file: a `.oml` archive's
     * `content.toml` entry. [namespace] is derived by the caller (the archive name), [source]
     * is only used in diagnostics.
     */
    fun load(namespace: String, source: String, toml: String, registry: ContentRegistry): PackSummary {
        require(NAMESPACE.matches(namespace)) {
            "$TAG $source: namespace '$namespace' is not a valid resource domain " +
                "(must match [a-z0-9_.-]+)"
        }
        val doc = try {
            MinimalToml.parse(toml)
        } catch (e: MinimalToml.TomlParseException) {
            throw IllegalStateException("$TAG $source: ${e.message}", e)
        }

        var blocks = 0
        var items = 0
        var recipes = 0
        for ([path, fields] in doc) {
            when {
                path.isEmpty() -> if (fields.isNotEmpty()) {
                    OmlLog.warn(
                        TAG,
                        "$source: ignoring root-level key(s) ${fields.keys} — " +
                            "content must live under [block.<id>] or [item.<id>]"
                    )
                }

                path.startsWith("block.") && path.count { it == '.' } == 1 -> {
                    declareBlock(path.removePrefix("block."), fields, source, registry)
                    blocks++
                }

                path.startsWith("item.") && path.count { it == '.' } == 1 -> {
                    declareItem(path.removePrefix("item."), fields, source, registry)
                    items++
                }

                path.startsWith("crafting.") && path.count { it == '.' } == 1 -> {
                    declareCrafting(path.removePrefix("crafting."), fields, source, registry)
                    recipes++
                }

                else -> OmlLog.warn(
                    TAG,
                    "$source: ignoring section [$path] — " +
                        "expected [block.<id>] or [item.<id>]"
                )
            }
        }
        return PackSummary(namespace, blocks, items, recipes)
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

    private val CRAFTING_KEYS = setOf("type", "count", "pattern", "key", "ingredients")

    /**
     * A `[crafting.<id>]` section: the section id is the result item's local id (bare; bound to the
     * pack's namespace), `type` selects shaped / shapeless. Validation (pattern shape, key
     * coverage, ingredient count) happens in the registry's own declarations — a malformed
     * section fails the pack with the registry's reason.
     */
    private fun declareCrafting(id: String, fields: Map<String, Any>, source: String, registry: ContentRegistry) {
        val type = when (val t = (fields["type"] as? String)?.lowercase()) {
            "shaped", "shapeless" -> t
            null -> fail(source, "crafting '$id' is missing the 'type' field (shaped or shapeless)")
            else -> fail(source, "crafting '$id': unknown type '$t' (expected shaped or shapeless)")
        }
        val count = intValue(fields, "count", source, "crafting '$id'") ?: 1

        reportUnknown(fields, CRAFTING_KEYS, source, "crafting '$id'")
        if (type == "shaped") {
            val pattern = listValue(fields, "pattern", source, "crafting '$id'")
                ?: fail(source, "crafting '$id': shaped recipes need a 'pattern' list")
            val keyMap = (fields["key"] as? Map<*, *>)
                ?: fail(source, "crafting '$id': shaped recipes need a 'key' inline table, e.g. key = { R = \"my_pack:ruby\" }")
            val key = keyMap.entries.associate { [k, v] ->
                val char = k as? String ?: fail(source, "crafting '$id': key '$k' must be a bare single character")
                if (char.length != 1) fail(source, "crafting '$id': key '$k' must be a single character")
                val item = v as? String
                    ?: fail(source, "crafting '$id': key '$k' must map to a string item id")
                char.single() to item
            }
            registry.declareShapedCrafting(result = id, pattern = pattern.map { it as String }, key = key, count = count)
        } else {
            val ingredients = listValue(fields, "ingredients", source, "crafting '$id'")
                ?: fail(source, "crafting '$id': shapeless recipes need an 'ingredients' list")
            registry.declareShapelessCrafting(
                result = id,
                ingredients = ingredients.map { it as? String ?: fail(source, "crafting '$id': ingredients must be strings") },
                count = count,
            )
        }
    }

    /**
     * Reload pass: re-dispatches only the data declarations (crafting recipes) from [toml] into
     * [registry]. Blocks and items are silently skipped — their registries are frozen, they cannot
     * be re-materialized, and reloading must not grow the declaration queues. Everything else
     * behaves like [load]: malformed crafting sections throw with the reason.
     */
    fun loadDataDeclarations(namespace: String, source: String, toml: String, registry: ContentRegistry) {
        require(NAMESPACE.matches(namespace)) {
            "$TAG $source: namespace '$namespace' is not a valid resource domain"
        }
        val doc = try {
            MinimalToml.parse(toml)
        } catch (e: MinimalToml.TomlParseException) {
            throw IllegalStateException("$TAG $source: ${e.message}", e)
        }
        for ([path, fields] in doc) {
            if (path.startsWith("crafting.") && path.count { it == '.' } == 1) {
                declareCrafting(path.removePrefix("crafting."), fields, source, registry)
            }
        }
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
            OmlLog.warn(
                TAG,
                "$source: $ctx has unknown field(s) $unknown — check for typos " +
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
