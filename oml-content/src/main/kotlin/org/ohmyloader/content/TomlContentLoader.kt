package org.ohmyloader.content

import org.ohmyloader.api.OmlLog
import org.ohmyloader.api.content.ContentRegistry
import org.ohmyloader.api.content.Furnace
import java.io.File

/**
 * Loads a flat-TOML content pack (`content.toml` in an `.oml` archive) into the [ContentRegistry]:
 * data becomes blocks and items with no jar and no code. The archive name (minus extension) is the
 * resource namespace; keys are snake_case mirrors of the code declarations, every one optional.
 * Unknown keys are reported loudly but never reject the pack — a typo'd field would otherwise
 * silently produce vanilla defaults.
 *
 * Sections: `[block.<id>]` (properties + optional `ore` table), `[item.<id>]`, `[crafting.<id>]`,
 * `[smelting.<result>]`, `[loot.<block>]`. Behavior hooks and block entities are mod code, so they
 * have no TOML form. Loading runs in the loader's content-declaration window, so the data track
 * shares the code track's freeze-point materialization.
 */
object TomlContentLoader {

    private const val TAG = "Content"

    private val ID = Regex("[a-z0-9_.-]+")
    private val NAMESPACE = ID

    private val BLOCK_KEYS = setOf("destroy_time", "explosion_resistance", "requires_correct_tool", "ore")
    private val ITEM_KEYS = setOf(
        "max_damage", "attack_damage", "attack_speed", "mining_speed",
        "tool_damage_per_block", "can_destroy_blocks_in_creative",
        "mines_and_drops", "denies_drops", "override_speed",
    )

    /** What one pack contributed, for the loader's startup log and tests. */
    data class PackSummary(
        val namespace: String,
        val blocks: Int,
        val items: Int,
        val recipes: Int = 0,
        val smelting: Int = 0,
        val loot: Int = 0,
    )

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
        var smelting = 0
        var loot = 0
        for ([path, fields] in doc) {
            when {
                path.isEmpty() -> if (fields.isNotEmpty()) {
                    OmlLog.warn(
                        TAG,
                        "$source: ignoring root-level key(s) ${fields.keys} — " +
                            "content must live under [block.<id>] or [item.<id>]",
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

                path.startsWith("smelting.") && path.count { it == '.' } == 1 -> {
                    declareSmeltingSection(path.removePrefix("smelting."), fields, source, registry)
                    smelting++
                }

                path.startsWith("loot.") && path.count { it == '.' } == 1 -> {
                    declareLootSection(path.removePrefix("loot."), fields, source, registry)
                    loot++
                }

                else -> OmlLog.warn(
                    TAG,
                    "$source: ignoring section [$path] — " +
                        "expected [block.<id>] / [item.<id>] / [crafting.<id>] / [smelting.<id>] / [loot.<block>]",
                )
            }
        }
        return PackSummary(namespace, blocks, items, recipes, smelting, loot)
    }

    // ---------------------------------------------------------------------------------------------
    // Section mapping
    // ---------------------------------------------------------------------------------------------

    private val ORE_KEYS = setOf("vein_size", "per_chunk", "min_y", "max_y", "biomes")

    private fun declareBlock(id: String, fields: Map<String, Any>, source: String, registry: ContentRegistry) {
        requireId(id, source, "block")
        val ore = fields["ore"] as? Map<*, *>
        if (fields.containsKey("ore") && ore == null) {
            fail(
                source,
                "block '$id'.ore must be an inline table, e.g. ore = { vein_size = 8, min_y = 16, max_y = 64 }",
            )
        }
        registry.declareBlock(id) {
            destroyTime = floatValue(fields, "destroy_time", source, "block '$id'")
            explosionResistance = floatValue(fields, "explosion_resistance", source, "block '$id'")
            requiresCorrectToolForDrops =
                boolValue(fields, "requires_correct_tool", source, "block '$id'") ?: false
            if (ore != null) {
                val oreFields = LinkedHashMap<String, Any>()
                for ([k, v] in ore) {
                    if (k is String && v != null) oreFields[k] = v
                }
                reportUnknown(oreFields, ORE_KEYS, source, "block '$id'.ore")
                generateAsOre {
                    veinSize = intValue(oreFields, "vein_size", source, "block '$id'.ore") ?: 9
                    perChunk = intValue(oreFields, "per_chunk", source, "block '$id'.ore") ?: 8
                    minY = intValue(oreFields, "min_y", source, "block '$id'.ore") ?: 16
                    maxY = intValue(oreFields, "max_y", source, "block '$id'.ore") ?: 64
                    for (biome in listValue(oreFields, "biomes", source, "block '$id'.ore").orEmpty()) {
                        biomes += biome as? String
                            ?: fail(source, "block '$id'.ore.biomes entries must be strings")
                    }
                }
            }
        }
        reportUnknown(fields, BLOCK_KEYS, source, "block '$id'")
    }

    private val SMELTING_KEYS = setOf("input", "furnace", "experience", "cooking_time")

    /** A `[smelting.<result>]` section: `input` is required; the section id is the result's local id. */
    private fun declareSmeltingSection(
        id: String,
        fields: Map<String, Any>,
        source: String,
        registry: ContentRegistry,
    ) {
        requireId(id, source, "smelting")
        val input = fields["input"] as? String
            ?: fail(source, "smelting '$id' is missing its 'input' string")
        val furnace = when (val f = (fields["furnace"] as? String)?.lowercase()) {
            null -> Furnace.SMELTING
            "smelting", "blasting", "smoking" -> Furnace.valueOf(f.uppercase())
            else -> fail(source, "smelting '$id': unknown furnace '$f' (expected smelting, blasting or smoking)")
        }
        val experience = doubleValue(fields, "experience", source, "smelting '$id'") ?: 0.0
        val cookingTime = intValue(fields, "cooking_time", source, "smelting '$id'") ?: 200
        reportUnknown(fields, SMELTING_KEYS, source, "smelting '$id'")
        registry.declareSmelting(
            input = input,
            result = id,
            furnace = furnace,
            experience = experience,
            cookingTime = cookingTime,
        )
    }

    private val LOOT_KEYS = setOf("drop", "drop_count_min", "drop_count_max")

    /** A `[loot.<block>]` section: breaking the block drops [drop] instead of the block itself. */
    private fun declareLootSection(id: String, fields: Map<String, Any>, source: String, registry: ContentRegistry) {
        requireId(id, source, "loot")
        val drop = fields["drop"] as? String
            ?: fail(source, "loot '$id' is missing its 'drop' string")
        val min = intValue(fields, "drop_count_min", source, "loot '$id'") ?: 1
        val max = intValue(fields, "drop_count_max", source, "loot '$id'") ?: 1
        reportUnknown(fields, LOOT_KEYS, source, "loot '$id'")
        registry.declareBlockDrop(block = id, drop = drop, dropCountMin = min, dropCountMax = max)
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
                ?: fail(
                    source,
                    "crafting '$id': shaped recipes need a 'key' inline table, e.g. key = { R = \"my_pack:ruby\" }",
                )
            val key = keyMap.entries.associate { [k, v] ->
                val char = k as? String ?: fail(source, "crafting '$id': key '$k' must be a bare single character")
                if (char.length != 1) fail(source, "crafting '$id': key '$k' must be a single character")
                val item = v as? String
                    ?: fail(source, "crafting '$id': key '$k' must map to a string item id")
                char.single() to item
            }
            registry.declareShapedCrafting(
                result = id,
                pattern = pattern.map { it as String },
                key = key,
                count = count,
            )
        } else {
            val ingredients = listValue(fields, "ingredients", source, "crafting '$id'")
                ?: fail(source, "crafting '$id': shapeless recipes need an 'ingredients' list")
            registry.declareShapelessCrafting(
                result = id,
                ingredients = ingredients.map {
                    it as? String ?: fail(
                        source,
                        "crafting '$id': ingredients must be strings",
                    )
                },
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
            when {
                path.startsWith("crafting.") && path.count { it == '.' } == 1 ->
                    declareCrafting(path.removePrefix("crafting."), fields, source, registry)

                path.startsWith("smelting.") && path.count { it == '.' } == 1 ->
                    declareSmeltingSection(path.removePrefix("smelting."), fields, source, registry)

                path.startsWith("loot.") && path.count { it == '.' } == 1 ->
                    declareLootSection(path.removePrefix("loot."), fields, source, registry)
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
                    "(known: ${known.sorted()})",
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
