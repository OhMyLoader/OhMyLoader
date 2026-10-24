package org.ohmyloader.content

import org.ohmyloader.api.OmlLog
import org.ohmyloader.api.content.ContentRegistry
import org.ohmyloader.api.content.Furnace
import org.ohmyloader.api.content.OMLTagDeclaration
import java.io.File

/**
 * Loads a flat-TOML content pack (`content.toml` in an `.oml` archive) into the [ContentRegistry]:
 * data becomes blocks and items with no jar and no code. The archive name (minus extension) is the
 * resource namespace; keys are snake_case mirrors of the code declarations, every one optional.
 * Unknown keys are reported loudly but never reject the pack — a typo'd field would otherwise
 * silently produce vanilla defaults.
 *
 * Sections: `[block.<id>]` (properties + optional `ore` table), `[item.<id>]`, `[crafting.<id>]`,
 * `[smelting.<result>]`, `[loot.<block>]`, `[damage_type.<id>]`, `[clock.<id>]` (no fields),
 * `[timeline.<id>]` (`clock` + optional `period_ticks` + `markers`), `[dimension_type.<id>]`,
 * `[dimension.<id>]`, `[tag.<label>]` (`registry` + `tag` + `values` + optional `replace`) — the tag section is
 * the one whose ids are fields, because a TOML bare key cannot spell the `:` of `minecraft:is_fire` or the `/`
 * of `worldgen/biome`. Behavior hooks and block entities are mod code, so they have no TOML form. Loading runs
 * in the loader's content-declaration window, so the data track shares the code track's freeze-point
 * materialization.
 */
object TomlContentLoader {

    private const val TAG = "Content"

    private val ID = Regex("[a-z0-9_.-]+")
    private val NAMESPACE = ID

    private val BLOCK_KEYS = setOf("hardness", "blast_resistance", "drops_require_correct_tool", "ore")
    private val ITEM_KEYS = setOf(
        "max_durability", "attack_damage", "attack_speed", "mining_speed",
        "tool_damage_per_block", "breaks_blocks_in_creative",
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
        val damageTypes: Int = 0,
        val clocks: Int = 0,
        val timelines: Int = 0,
        val dimensionTypes: Int = 0,
        val dimensions: Int = 0,
        val tags: Int = 0,
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
        var damageTypes = 0
        var clocks = 0
        var timelines = 0
        var dimensionTypes = 0
        var dimensions = 0
        var tags = 0
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

                path.startsWith("damage_type.") && path.count { it == '.' } == 1 -> {
                    declareDamageTypeSection(path.removePrefix("damage_type."), fields, source, registry)
                    damageTypes++
                }

                path.startsWith("clock.") && path.count { it == '.' } == 1 -> {
                    declareClockSection(path.removePrefix("clock."), fields, source, registry)
                    clocks++
                }

                path.startsWith("timeline.") && path.count { it == '.' } == 1 -> {
                    declareTimelineSection(path.removePrefix("timeline."), fields, source, registry)
                    timelines++
                }

                path.startsWith("dimension_type.") && path.count { it == '.' } == 1 -> {
                    declareDimensionTypeSection(
                        path.removePrefix("dimension_type."), fields, source, registry,
                    )
                    dimensionTypes++
                }

                path.startsWith("dimension.") && path.count { it == '.' } == 1 -> {
                    declareDimensionSection(path.removePrefix("dimension."), fields, source, registry)
                    dimensions++
                }

                path.startsWith("tag.") && path.count { it == '.' } == 1 -> {
                    declareTagSection(path.removePrefix("tag."), fields, source, registry)
                    tags++
                }

                else -> OmlLog.warn(
                    TAG,
                    "$source: ignoring section [$path] — " +
                        "expected [block.<id>] / [item.<id>] / [crafting.<id>] / [smelting.<id>] / " +
                        "[loot.<block>] / [damage_type.<id>] / [clock.<id>] / [timeline.<id>] / " +
                        "[dimension_type.<id>] / [dimension.<id>] / [tag.<label>]",
                )
            }
        }
        return PackSummary(
            namespace, blocks, items, recipes, smelting, loot, damageTypes, clocks, timelines,
            dimensionTypes, dimensions, tags,
        )
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
            hardness = floatValue(fields, "hardness", source, "block '$id'")
            blastResistance = floatValue(fields, "blast_resistance", source, "block '$id'")
            dropsRequireCorrectTool =
                boolValue(fields, "drops_require_correct_tool", source, "block '$id'") ?: false
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

    private val DAMAGE_TYPE_KEYS =
        setOf("message_id", "scaling", "effects", "exhaustion", "death_message_type")

    /** A `[damage_type.<id>]` section: the pack's namespace binds the id `hurt()` later takes. */
    private fun declareDamageTypeSection(
        id: String,
        fields: Map<String, Any>,
        source: String,
        registry: ContentRegistry,
    ) {
        requireId(id, source, "damage_type")
        val messageId = fields["message_id"] as? String
            ?: fail(source, "damage_type '$id' is missing its 'message_id' string")
        reportUnknown(fields, DAMAGE_TYPE_KEYS, source, "damage_type '$id'")
        registry.declareDamageType(
            id = id,
            messageId = messageId,
            scaling = fields["scaling"] as? String ?: "when_caused_by_living_non_player",
            effects = fields["effects"] as? String,
            exhaustion = floatValue(fields, "exhaustion", source, "damage_type '$id'") ?: 0f,
            deathMessageType = fields["death_message_type"] as? String,
        )
    }

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

    /** A `[clock.<id>]` section: a 26.3 clock has no fields, so the section exists only to name it. */
    private fun declareClockSection(id: String, fields: Map<String, Any>, source: String, registry: ContentRegistry) {
        requireId(id, source, "clock")
        if (fields.isNotEmpty()) {
            fail(source, "clock '$id' takes no fields (a 26.3 clock declares nothing but its id)")
        }
        registry.declareClock(id)
    }

    private val TIMELINE_KEYS = setOf("clock", "period_ticks", "markers")

    /**
     * A `[timeline.<id>]` section: `clock` is required, `period_ticks` optional, and `markers` an inline table
     * whose value is either a tick number or `{ ticks = .., in_commands = .. }` — the two forms the game's own
     * codec accepts.
     */
    private fun declareTimelineSection(
        id: String,
        fields: Map<String, Any>,
        source: String,
        registry: ContentRegistry,
    ) {
        requireId(id, source, "timeline")
        val clock = fields["clock"] as? String ?: fail(source, "timeline '$id' is missing its 'clock' string")
        val periodTicks = intValue(fields, "period_ticks", source, "timeline '$id'")
        val markers = fields["markers"]
        if (markers != null && markers !is Map<*, *>) {
            fail(source, "timeline '$id'.markers must be an inline table, e.g. markers = { dusk = 12000 }")
        }
        reportUnknown(fields, TIMELINE_KEYS, source, "timeline '$id'")
        registry.declareTimeline(id) {
            this.clock = clock
            this.periodTicks = periodTicks
            for ([name, value] in (markers ?: emptyMap<Any?, Any?>())) {
                val markerName = name as? String
                    ?: fail(source, "timeline '$id': marker names must be strings")
                val ctx = "timeline '$id' marker '$markerName'"
                when (value) {
                    is Number -> marker(markerName, value.toInt())

                    is Map<*, *> -> marker(
                        markerName,
                        (value["ticks"] as? Number ?: fail(source, "$ctx is missing its numeric 'ticks'")).toInt(),
                        value["in_commands"] as? Boolean ?: false,
                    )

                    else -> fail(
                        source,
                        "$ctx must be a tick number or a table like " +
                            "{ ticks = 12000, in_commands = true }",
                    )
                }
            }
        }
    }

    private val TAG_KEYS = setOf("registry", "tag", "values", "replace")

    /**
     * A `[tag.<label>]` section: `registry` names the registry whose tag folder receives the file
     * (`minecraft:damage_type`, `minecraft:worldgen/biome`) and `tag` the tag it writes — which may live in
     * another namespace, since adding to `#minecraft:is_fire` is the ordinary case. `values` holds ids, each
     * either a bare string or `{ id = .., required = .. }` (the two forms the game's own tag codec reads), a
     * leading `#` making an entry a reference to another tag.
     */
    private fun declareTagSection(
        label: String,
        fields: Map<String, Any>,
        source: String,
        registry: ContentRegistry,
    ) {
        requireId(label, source, "tag")
        val registryId = fields["registry"] as? String
            ?: fail(source, "tag '$label' is missing its 'registry' string")
        val tagId = fields["tag"] as? String
            ?: fail(source, "tag '$label' is missing its 'tag' string")
        val replace = boolValue(fields, "replace", source, "tag '$label'") ?: false
        val values = listValue(fields, "values", source, "tag '$label'").orEmpty()
        reportUnknown(fields, TAG_KEYS, source, "tag '$label'")
        registry.declareTag(registryId, tagId) {
            this.replace = replace
            for (value in values) {
                when (value) {
                    is String -> tagMember(value)

                    is Map<*, *> -> tagMember(
                        value["id"] as? String
                            ?: fail(source, "tag '$label' entry is missing its 'id' string"),
                        value["required"] as? Boolean ?: true,
                    )

                    else -> fail(
                        source,
                        "tag '$label' entries must be id strings or tables like " +
                            "{ id = \"minecraft:cactus\", required = false }",
                    )
                }
            }
        }
    }

    /** One `values` entry in either of the game's forms: a leading `#` spells a reference to another tag. */
    private fun OMLTagDeclaration.tagMember(id: String, required: Boolean = true) {
        if (id.startsWith("#")) tag(id.removePrefix("#"), required) else element(id, required)
    }

    private fun declareItem(id: String, fields: Map<String, Any>, source: String, registry: ContentRegistry) {
        requireId(id, source, "item")
        registry.declareItem(id) {
            maxDurability = intValue(fields, "max_durability", source, "item '$id'")
            attackDamage = doubleValue(fields, "attack_damage", source, "item '$id'")
            attackSpeed = doubleValue(fields, "attack_speed", source, "item '$id'")
            miningSpeed = floatValue(fields, "mining_speed", source, "item '$id'")
            toolDamagePerBlock =
                intValue(fields, "tool_damage_per_block", source, "item '$id'") ?: 1
            breaksBlocksInCreative =
                boolValue(fields, "breaks_blocks_in_creative", source, "item '$id'") ?: true
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
     * Reload pass: re-dispatches only the datapack declarations (recipes, loot tables, damage types, clocks,
     * timelines, dimensions, tags) from [toml] into [registry]. Blocks and items are silently skipped — their
     * registries are
     * frozen, they cannot be re-materialized, and reloading must not grow the declaration queues. Everything
     * else behaves like [load]: a malformed section throws with the reason, and an unknown section is warned
     * about instead of being swallowed.
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
                // MinimalToml always emits the root table (""), empty unless the pack has root-level
                // keys, and the load pass already judged those keys — it is not an unknown section.
                path.isEmpty() -> Unit

                path.startsWith("crafting.") && path.count { it == '.' } == 1 ->
                    declareCrafting(path.removePrefix("crafting."), fields, source, registry)

                path.startsWith("smelting.") && path.count { it == '.' } == 1 ->
                    declareSmeltingSection(path.removePrefix("smelting."), fields, source, registry)

                path.startsWith("loot.") && path.count { it == '.' } == 1 ->
                    declareLootSection(path.removePrefix("loot."), fields, source, registry)

                path.startsWith("damage_type.") && path.count { it == '.' } == 1 ->
                    declareDamageTypeSection(path.removePrefix("damage_type."), fields, source, registry)

                path.startsWith("clock.") && path.count { it == '.' } == 1 ->
                    declareClockSection(path.removePrefix("clock."), fields, source, registry)

                path.startsWith("timeline.") && path.count { it == '.' } == 1 ->
                    declareTimelineSection(path.removePrefix("timeline."), fields, source, registry)

                path.startsWith("dimension_type.") && path.count { it == '.' } == 1 ->
                    declareDimensionTypeSection(
                        path.removePrefix("dimension_type."), fields, source, registry,
                    )

                path.startsWith("dimension.") && path.count { it == '.' } == 1 ->
                    declareDimensionSection(path.removePrefix("dimension."), fields, source, registry)

                path.startsWith("tag.") && path.count { it == '.' } == 1 ->
                    declareTagSection(path.removePrefix("tag."), fields, source, registry)

                // Block and item sections stay silent: the same pack file reloads as written, and its
                // [block.*] / [item.*] tables are skipped on every pass because those registries are frozen.
                path.startsWith("block.") && path.count { it == '.' } == 1 -> Unit
                path.startsWith("item.") && path.count { it == '.' } == 1 -> Unit

                else -> OmlLog.warn(
                    TAG,
                    "$source: ignoring section [$path] — the reload pass covers only the datapack sections " +
                        "[crafting.<id>] / [smelting.<id>] / [loot.<block>] / [damage_type.<id>] / [clock.<id>] / " +
                        "[timeline.<id>] / [dimension_type.<id>] / [dimension.<id>] / [tag.<label>]",
                )
            }
        }
    }

    private val DIMENSION_TYPE_KEYS = setOf(
        "sky_light", "ceiling", "dragon_fight", "coordinate_scale", "min_y", "height", "logical_height",
        "infiniburn", "ambient_light", "monster_spawn_light_level", "monster_spawn_block_light_limit",
        "fixed_time", "skybox", "clock", "timelines",
    )

    /** A `[dimension_type.<id>]` section: every field is optional and defaults to vanilla's overworld. */
    private fun declareDimensionTypeSection(
        id: String,
        fields: Map<String, Any>,
        source: String,
        registry: ContentRegistry,
    ) {
        requireId(id, source, "dimension_type")
        val timelines = listValue(fields, "timelines", source, "dimension_type '$id'").orEmpty()
        reportUnknown(fields, DIMENSION_TYPE_KEYS, source, "dimension_type '$id'")
        registry.declareDimensionType(id) {
            skyLight = boolValue(fields, "sky_light", source, "dimension_type '$id'") ?: true
            ceiling = boolValue(fields, "ceiling", source, "dimension_type '$id'") ?: false
            dragonFight = boolValue(fields, "dragon_fight", source, "dimension_type '$id'") ?: false
            coordinateScale = doubleValue(fields, "coordinate_scale", source, "dimension_type '$id'") ?: 1.0
            minY = intValue(fields, "min_y", source, "dimension_type '$id'") ?: -64
            height = intValue(fields, "height", source, "dimension_type '$id'") ?: 384
            logicalHeight = intValue(fields, "logical_height", source, "dimension_type '$id'") ?: height
            infiniburn = fields["infiniburn"] as? String ?: "#minecraft:infiniburn_overworld"
            ambientLight = floatValue(fields, "ambient_light", source, "dimension_type '$id'") ?: 0f
            monsterSpawnLightLevel =
                intValue(fields, "monster_spawn_light_level", source, "dimension_type '$id'") ?: 7
            monsterSpawnBlockLightLimit =
                intValue(fields, "monster_spawn_block_light_limit", source, "dimension_type '$id'") ?: 0
            fixedTime = boolValue(fields, "fixed_time", source, "dimension_type '$id'")
            skybox = fields["skybox"] as? String
            clock = fields["clock"] as? String
            for (timeline in timelines) {
                this.timelines += timeline as? String
                    ?: fail(source, "dimension_type '$id'.timelines entries must be strings")
            }
        }
    }

    private val DIMENSION_KEYS = setOf("type", "generator", "layers", "noise_settings", "biome", "features", "lakes")

    /**
     * A `[dimension.<id>]` section: the level stem the game builds a level for. `type` is required, `layers`
     * an array of inline tables for the flat generator (`{ block = "minecraft:bedrock", height = 1 }`).
     */
    private fun declareDimensionSection(
        id: String,
        fields: Map<String, Any>,
        source: String,
        registry: ContentRegistry,
    ) {
        requireId(id, source, "dimension")
        val type = fields["type"] as? String ?: fail(source, "dimension '$id' is missing its 'type' string")
        val layers = listValue(fields, "layers", source, "dimension '$id'")
        if (fields.containsKey("layers") && layers == null) {
            fail(source, "dimension '$id'.layers must be an array of { block = \"…\", height = 1 } tables")
        }
        reportUnknown(fields, DIMENSION_KEYS, source, "dimension '$id'")
        registry.declareDimension(id) {
            this.type = type
            generator = fields["generator"] as? String ?: "flat"
            noiseSettings = fields["noise_settings"] as? String
            biome = fields["biome"] as? String
            features = boolValue(fields, "features", source, "dimension '$id'") ?: false
            lakes = boolValue(fields, "lakes", source, "dimension '$id'") ?: false
            for (entry in layers.orEmpty()) {
                val table = entry as? Map<*, *>
                    ?: fail(source, "dimension '$id'.layers entries must be inline tables")
                val block = table["block"] as? String
                    ?: fail(source, "dimension '$id' layer is missing its 'block' string")
                val height = table["height"] as? Number
                    ?: fail(source, "dimension '$id' layer for '$block' is missing its numeric 'height'")
                layer(block, height.toInt())
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
