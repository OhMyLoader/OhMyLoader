package org.ohmyloader.content

import org.ohmyloader.api.OmlLog
import org.ohmyloader.api.content.*

/**
 * The **two-phase template** for content registration (the version-independent mechanics).
 * 1. **Collect** (before the game's main logic starts): declarations via [ContentRegistry.declareBlock] /
 *    [ContentRegistry.declareItem] are only recorded — the registries are not writable yet (bootstrap has
 *    not run / would be rejected by the freeze check).
 * 2. **Materialize** (the legal moment for that version): translate declarations into the version's blocks/items
 *    and write them into the registry; the trigger point varies by version, so this phase is the subclass's [doMaterialize].
 * Contains **no `net.minecraft.*` references** — the red line of not depending on game classes at compile time.
 * An adapter without content registration need not subclass it: return null from `IAdapter.createContentRegistry()`.
 */
abstract class AbstractContentRegistry : ContentRegistryFactory {

    /** A collected block declaration: namespace + local id (bound to the declaring mod's id) + vanilla properties. */
    data class BlockDecl(val namespace: String, val id: String, val spec: OMLBlockDeclaration)

    /** A collected item declaration: namespace + local id + native data-component configuration. */
    data class ItemDecl(val namespace: String, val id: String, val spec: OMLItemDeclaration)

    /** Declarations collected but not yet materialized; consumed in declaration order during materialization. */
    protected val collected: MutableList<BlockDecl> = mutableListOf()

    /** Item declarations collected but not yet materialized; consumed after [collected] in declaration order. */
    protected val collectedItems: MutableList<ItemDecl> = mutableListOf()

    /** Cache of materialized block handles, key = `"namespace:id"`; backs lazy resolution of the OMLBlock returned by [collect]. */
    protected val materialized: MutableMap<String, OMLBlock> = mutableMapOf()

    /** Cache of materialized item handles, keyed the same way; backs lazy resolution of the OMLItem returned by [collectItem]. */
    protected val materializedItems: MutableMap<String, OMLItem> = mutableMapOf()

    /** Collected furnace-type recipe declaration: [key] is the datapack file's local id (input item). */
    data class RecipeDecl(
        val namespace: String,
        val key: String,
        val kind: String,
        val json: String,
        /** Reloadable declarations come from TOML packs (re-collected from disk on every reload);
         *  code-track declarations are frozen like the blocks they belong to. */
        val reloadable: Boolean,
    )

    /** Collected block-drop loot table declaration: [key] is the datapack file's local id (the block). */
    data class LootDecl(
        val namespace: String,
        val key: String,
        val json: String,
        val reloadable: Boolean,
    )

    protected val collectedRecipes: MutableList<RecipeDecl> = mutableListOf()
    protected val collectedLoot: MutableList<LootDecl> = mutableListOf()
    protected val collectedDamageTypes: MutableList<DamageTypeDecl> = mutableListOf()
    protected val collectedClocks: MutableList<ClockDecl> = mutableListOf()
    protected val collectedTimelines: MutableList<TimelineDecl> = mutableListOf()
    protected val collectedDimensionTypes: MutableList<DimensionTypeDecl> = mutableListOf()
    protected val collectedDimensions: MutableList<DimensionDecl> = mutableListOf()

    /**
     * A collected damage-type declaration: [key] is the datapack file's local id, so the registry id the
     * game binds is `namespace:key` and the 26.3 `hurt` extension takes that same string.
     */
    data class DamageTypeDecl(
        val namespace: String,
        val key: String,
        val json: String,
        val reloadable: Boolean,
    )

    /**
     * A collected clock declaration. 26.3's `WorldClock` is an identity with no fields, so the datapack file
     * is an empty object and the declaration is the id.
     */
    data class ClockDecl(val namespace: String, val key: String, val reloadable: Boolean)

    /** A collected timeline declaration: [key] is the file's local id under `timeline/`. */
    data class TimelineDecl(
        val namespace: String,
        val key: String,
        /** The clock this file drives, fully qualified — the game gives one marker to one timeline per clock. */
        val clockId: String,
        /** Every marker id this file declares, fully qualified. */
        val markerIds: List<String>,
        val json: String,
        val reloadable: Boolean,
    )

    /** A collected dimension-type declaration: [key] is the file's local id under `dimension_type/`. */
    data class DimensionTypeDecl(
        val namespace: String,
        val key: String,
        val json: String,
        val reloadable: Boolean,
    )

    /** A collected dimension declaration: [key] is the file's local id under `dimension/`. */
    data class DimensionDecl(
        val namespace: String,
        val key: String,
        /** The dimension type this level runs on, fully qualified. */
        val typeId: String,
        val json: String,
        val reloadable: Boolean,
    )

    /**
     * The datapack JSON for a collected recipe (`data/<ns>/recipe/<key>.json`), or null if no
     * declaration matches. Served by the version's asset injector through the injected resource
     * pack, riding the vanilla datapack reload instead of patching the recipe manager.
     *
     * Synchronized against [clearDataDeclarations]: a vanilla reload re-reads these while the
     * loader may be re-collecting from freshly edited pack files.
     */
    @Synchronized
    fun recipeJsonFor(namespace: String, id: String): String? =
        collectedRecipes.firstOrNull { it.namespace == namespace && it.key == id }?.json

    /** The collected recipe keys of [namespace], in declaration order — the datapack directory listing. */
    @Synchronized
    fun recipeIdsFor(namespace: String): List<String> =
        collectedRecipes.filter { it.namespace == namespace }.map { it.key }

    /** The datapack JSON for a collected block-drop loot table (`data/<ns>/loot_table/blocks/<key>.json`). */
    @Synchronized
    fun lootJsonFor(namespace: String, id: String): String? =
        collectedLoot.firstOrNull { it.namespace == namespace && it.key == id }?.json

    /** The collected loot-table keys of [namespace], in declaration order — the datapack directory listing. */
    @Synchronized
    fun lootIdsFor(namespace: String): List<String> =
        collectedLoot.filter { it.namespace == namespace }.map { it.key }

    /** The datapack JSON for a collected damage type (`data/<ns>/damage_type/<key>.json`). */
    @Synchronized
    fun damageTypeJsonFor(namespace: String, id: String): String? =
        collectedDamageTypes.firstOrNull { it.namespace == namespace && it.key == id }?.json

    /** The collected damage-type keys of [namespace], in declaration order — the datapack directory listing. */
    @Synchronized
    fun damageTypeIdsFor(namespace: String): List<String> =
        collectedDamageTypes.filter { it.namespace == namespace }.map { it.key }

    /** A 26.3 clock declares nothing but its own id, so its datapack file is the empty object. */
    @Synchronized
    fun clockJsonFor(namespace: String, id: String): String? =
        if (collectedClocks.any { it.namespace == namespace && it.key == id }) "{}" else null

    /** The collected clock keys of [namespace], in declaration order — the datapack directory listing. */
    @Synchronized
    fun clockIdsFor(namespace: String): List<String> =
        collectedClocks.filter { it.namespace == namespace }.map { it.key }

    /** The datapack JSON for a collected timeline (`data/<ns>/timeline/<key>.json`). */
    @Synchronized
    fun timelineJsonFor(namespace: String, id: String): String? =
        collectedTimelines.firstOrNull { it.namespace == namespace && it.key == id }?.json

    /** The collected timeline keys of [namespace], in declaration order. */
    @Synchronized
    fun timelineIdsFor(namespace: String): List<String> =
        collectedTimelines.filter { it.namespace == namespace }.map { it.key }

    /** The datapack JSON for a collected dimension type (`data/<ns>/dimension_type/<key>.json`). */
    @Synchronized
    fun dimensionTypeJsonFor(namespace: String, id: String): String? =
        collectedDimensionTypes.firstOrNull { it.namespace == namespace && it.key == id }?.json

    /** The collected dimension-type keys of [namespace], in declaration order. */
    @Synchronized
    fun dimensionTypeIdsFor(namespace: String): List<String> =
        collectedDimensionTypes.filter { it.namespace == namespace }.map { it.key }

    /** The datapack JSON for a collected dimension (`data/<ns>/dimension/<key>.json`). */
    @Synchronized
    fun dimensionJsonFor(namespace: String, id: String): String? =
        collectedDimensions.firstOrNull { it.namespace == namespace && it.key == id }?.json

    /** The collected dimension keys of [namespace], in declaration order. */
    @Synchronized
    fun dimensionIdsFor(namespace: String): List<String> =
        collectedDimensions.filter { it.namespace == namespace }.map { it.key }

    /**
     * Drops every collected recipe / loot declaration so a reload pass can re-collect them from
     * the pack files as they exist on disk **now**. Blocks and items are NOT touched: their
     * registries are frozen, so they cannot be re-materialized and their declarations stay
     * consumed.
     */
    @Synchronized
    fun clearDataDeclarations() {
        // only the reloadable (TOML-pack) declarations are re-collected from disk; the code
        // track's recipes and loot belong to frozen blocks and would be lost forever here
        collectedRecipes.removeAll { it.reloadable }
        collectedLoot.removeAll { it.reloadable }
        collectedDamageTypes.removeAll { it.reloadable }
        collectedClocks.removeAll { it.reloadable }
        collectedTimelines.removeAll { it.reloadable }
        collectedDimensionTypes.removeAll { it.reloadable }
        collectedDimensions.removeAll { it.reloadable }
    }

    /** A collected ore-generation declaration, flattened to its two datapack files' JSON. */
    data class OreGenDecl(
        val namespace: String,
        val key: String,
        val featureJson: String,
        val placedJson: String,
        /** The declared biome list (possibly empty = every biome); the adapter resolves it. */
        val biomes: List<String>,
    )

    /**
     * Every ore declaration's datapack files, in declaration order. The adapter serves them under
     * its version's worldgen paths and merges the placed ids into the target biomes' feature
     * lists. The JSON is the version-independent datapack format (the same shape across 26.x), so
     * it is generated at collect time; the list survives the post-materialization drain of the
     * block queue, because blocks are frozen and their ore files must stay served for every later
     * datapack reload.
     */
    protected val collectedOreGen: MutableList<OreGenDecl> = mutableListOf()

    /** Read-only view for the adapter (serving + biome merge), in declaration order. */
    val oreGenDecls: List<OreGenDecl> get() = collectedOreGen

    fun oreGenKeysFor(namespace: String): List<String> =
        collectedOreGen.filter { it.namespace == namespace }.map { it.key }

    fun oreFeatureJsonFor(namespace: String, key: String): String? =
        collectedOreGen.firstOrNull { it.namespace == namespace && it.key == key }?.featureJson

    fun orePlacedJsonFor(namespace: String, key: String): String? =
        collectedOreGen.firstOrNull { it.namespace == namespace && it.key == key }?.placedJson

    private fun oreFeatureJson(namespace: String, blockId: String, ore: OMLBlockOreDeclaration): String =
        "{\"type\":\"minecraft:ore\",\"size\":${ore.veinSize},\"discard_chance_on_air_exposure\":0.0," +
            "\"targets\":[{\"state\":\"$namespace:$blockId\",\"target\":{" +
            "\"predicate_type\":\"minecraft:tag_match\",\"tag\":\"minecraft:stone_ore_replaceables\"}}]}"

    private fun orePlacedJson(namespace: String, key: String, ore: OMLBlockOreDeclaration): String =
        "{\"feature\":\"$namespace:$key\",\"placement\":[" +
            "{\"type\":\"minecraft:count\",\"count\":${ore.perChunk}}," +
            "{\"type\":\"minecraft:in_square\"}," +
            "{\"type\":\"minecraft:height_range\",\"height\":{\"type\":\"minecraft:trapezoid\"," +
            "\"min_inclusive\":{\"absolute\":${ore.minY}},\"max_inclusive\":{\"absolute\":${ore.maxY}}}}," +
            "{\"type\":\"minecraft:biome\"}]}"

    /** Mod-facing facade: fixes the namespace binding, then lets the mod declare content. */
    final override fun forNamespace(namespace: String): ContentRegistry =
        forNamespace(namespace, reloadable = false)

    /** [reloadable] marks TOML-pack declarations: re-collected from disk on every reload. */
    override fun forNamespace(namespace: String, reloadable: Boolean): ContentRegistry =
        object : ContentRegistry {
            override fun declareBlock(id: String, configure: OMLBlockDeclaration.() -> Unit): OMLBlock =
                collect(namespace, id, OMLBlockDeclaration().apply(configure))

            override fun declareItem(id: String, configure: OMLItemDeclaration.() -> Unit): OMLItem =
                collectItem(namespace, id, OMLItemDeclaration().apply(configure))

            override fun declareSmelting(
                input: String,
                result: String,
                furnace: Furnace,
                experience: Double,
                cookingTime: Int,
            ) {
                val full = recipeJson(input, result, namespace, furnace, experience, cookingTime)
                collectedRecipes += RecipeDecl(
                    namespace,
                    uniqueKey(
                        collectedRecipes.mapTo(HashSet()) { it.key },
                        qualify(namespace, input).replace(':', '_'),
                    ),
                    furnace.recipePath, full, reloadable,
                )
            }

            override fun declareShapedCrafting(
                result: String,
                pattern: List<String>,
                key: Map<Char, String>,
                count: Int,
            ) {
                validateShaped(pattern, key, count)
                val resultId = qualify(namespace, result)
                val keyJson = key.entries.joinToString(",") { [c, id] ->
                    "\"$c\":\"" + qualify(namespace, id) + "\""
                }
                val patternJson = pattern.joinToString(",", "[", "]") { "\"$it\"" }
                val resultCount = if (count == 1) "" else ",\"count\":$count"
                val json = "{\"type\":\"minecraft:crafting_shaped\",\"category\":\"misc\",\"group\":\"\"," +
                    "\"pattern\":" + patternJson + ",\"key\":{" + keyJson + "}," +
                    "\"result\":{\"id\":\"" + resultId + "\"" + resultCount + "}}"
                collectedRecipes += RecipeDecl(
                    namespace,
                    craftingKey(namespace, result),
                    "crafting_shaped",
                    json,
                    reloadable,
                )
            }

            override fun declareShapelessCrafting(result: String, ingredients: List<String>, count: Int) {
                if (ingredients.isEmpty() || ingredients.size > 9) {
                    throw IllegalStateException(
                        "shapeless crafting for '$result' needs 1-9 ingredients (got ${ingredients.size})",
                    )
                }
                if (count < 1) throw IllegalStateException("crafting result count must be >= 1 (got $count)")
                val resultId = qualify(namespace, result)
                val ingredientsJson = ingredients.joinToString(",") { "\"" + qualify(namespace, it) + "\"" }
                val resultCount = if (count == 1) "" else ",\"count\":$count"
                val json = "{\"type\":\"minecraft:crafting_shapeless\",\"category\":\"misc\",\"group\":\"\"," +
                    "\"ingredients\":[" + ingredientsJson + "]," +
                    "\"result\":{\"id\":\"" + resultId + "\"" + resultCount + "}}"
                collectedRecipes += RecipeDecl(
                    namespace,
                    craftingKey(namespace, result),
                    "crafting_shapeless",
                    json,
                    reloadable,
                )
            }

            override fun declareBlockDrop(block: String, drop: String, dropCountMin: Int, dropCountMax: Int) {
                val key = qualify(namespace, block).replace(':', '_')
                // A block has exactly one default loot table (its id derives from the file name), so a
                // second declaration cannot also take effect — suffixing would emit an orphan table
                // nothing references. Keep the first, say so loudly.
                if (collectedLoot.any { it.namespace == namespace && it.key == key }) {
                    OmlLog.warn(
                        "Content",
                        "block drop for '$block' declared more than once; keeping the first, ignoring this one",
                    )
                    return
                }
                val full = lootJson(block, drop, namespace, dropCountMin, dropCountMax)
                collectedLoot += LootDecl(namespace, key, full, reloadable)
            }

            override fun declareDamageType(
                id: String,
                messageId: String,
                scaling: String,
                effects: String?,
                exhaustion: Float,
                deathMessageType: String?,
            ) {
                val refusal = when {
                    !DATAPACK_NAME.matches(id) -> "'$id' is not a lowercase datapack name"
                    scaling !in DAMAGE_SCALINGS -> "unknown damage scaling '$scaling'"
                    effects != null && effects !in DAMAGE_EFFECTS -> "unknown damage effects '$effects'"
                    deathMessageType != null && deathMessageType !in DEATH_MESSAGE_TYPES ->
                        "unknown death message type '$deathMessageType'"
                    collectedDamageTypes.any { it.namespace == namespace && it.key == id } ->
                        "damage type '$id' is declared twice"
                    else -> null
                }
                if (refusal != null) {
                    // Refusing here is the loud answer; writing the file anyway would let the datapack
                    // codec reject it during reload and leave the damage type missing with no owner.
                    OmlLog.warn("Content", "$refusal; the damage type is not declared")
                    return
                }
                collectedDamageTypes += DamageTypeDecl(
                    namespace,
                    id,
                    damageTypeJson(messageId, scaling, effects, exhaustion, deathMessageType),
                    reloadable,
                )
            }

            override fun declareClock(id: String) {
                val refusal = when {
                    !DATAPACK_NAME.matches(id) -> "'$id' is not a lowercase datapack name"
                    collectedClocks.any { it.namespace == namespace && it.key == id } ->
                        "clock '$id' is declared twice"
                    else -> null
                }
                if (refusal != null) {
                    OmlLog.warn("Content", "$refusal; the clock is not declared")
                    return
                }
                collectedClocks += ClockDecl(namespace, id, reloadable)
            }

            override fun declareTimeline(id: String, configure: OMLTimelineDeclaration.() -> Unit) {
                val spec = OMLTimelineDeclaration().apply(configure)
                val clock = spec.clock?.let { qualify(namespace, it) }
                    ?: run {
                        OmlLog.warn("Content", "timeline '$id' names no clock; the timeline is not declared")
                        return
                    }
                val period = spec.periodTicks
                val unreachable = spec.markers.firstOrNull {
                    it.ticks < 0 || (period != null && it.ticks >= period)
                }
                val claimed = spec.markers.firstOrNull { marker ->
                    val key = qualify(namespace, marker.id)
                    collectedTimelines.any { it.clockId == clock && key in it.markerIds }
                }
                val refusal = when {
                    !DATAPACK_NAME.matches(id) -> "'$id' is not a lowercase datapack name"
                    !DATAPACK_NAME.matches(clock.substringAfterLast(':')) ->
                        "clock '${spec.clock}' is not a lowercase datapack name"
                    collectedTimelines.any { it.namespace == namespace && it.key == id } ->
                        "timeline '$id' is declared twice"
                    period != null && period <= 0 ->
                        "timeline '$id' period $period is not a positive number of ticks"
                    clock.substringBefore(':') == namespace &&
                        collectedClocks.none { it.namespace == namespace && it.key == clock.substringAfter(':') } ->
                        "timeline '$id' drives clock '$clock', which this pack has not declared " +
                            "(declare the clock before the timeline that drives it)"
                    unreachable != null ->
                        "timeline '$id' marker '${unreachable.id}' names tick ${unreachable.ticks}, which " +
                            "the cycle it declares never reaches"
                    spec.markers.map { it.id }.distinct().size != spec.markers.size ->
                        "timeline '$id' declares one marker twice"
                    spec.markers.any { !DATAPACK_NAME.matches(it.id) } ->
                        "timeline '$id' declares a marker that is not a lowercase datapack name"
                    claimed != null ->
                        "marker '${claimed.id}' is already declared on clock '$clock' by another timeline"
                    else -> null
                }
                if (refusal != null) {
                    // The tick-inside-the-cycle rule and the one-timeline-per-marker rule are the game's own:
                    // its codec and its registry validation enforce them, and a file they reject leaves the
                    // timeline absent from the registry with nothing in the log naming the pack that wrote it.
                    OmlLog.warn("Content", "$refusal; the timeline is not declared")
                    return
                }
                collectedTimelines += TimelineDecl(
                    namespace,
                    id,
                    clock,
                    spec.markers.map { qualify(namespace, it.id) },
                    timelineJson(namespace, clock, spec),
                    reloadable,
                )
            }

            override fun declareDimensionType(id: String, configure: OMLDimensionTypeDeclaration.() -> Unit) {
                val spec = OMLDimensionTypeDeclaration().apply(configure)
                val clock = spec.clock?.let { qualify(namespace, it) }
                val badTimeline = spec.timelines.firstOrNull { !DATAPACK_NAME.matches(it.substringAfterLast(':')) }
                val refusal = when {
                    !DATAPACK_NAME.matches(id) -> "'$id' is not a lowercase datapack name"
                    collectedDimensionTypes.any { it.namespace == namespace && it.key == id } ->
                        "dimension type '$id' is declared twice"
                    spec.coordinateScale !in COORDINATE_SCALE_MIN..COORDINATE_SCALE_MAX ->
                        "dimension type '$id' scale ${spec.coordinateScale} is outside the game's " +
                            "$COORDINATE_SCALE_MIN..$COORDINATE_SCALE_MAX"
                    spec.minY !in DIMENSION_MIN_Y..DIMENSION_MAX_Y ->
                        "dimension type '$id' min_y ${spec.minY} is outside the game's " +
                            "$DIMENSION_MIN_Y..$DIMENSION_MAX_Y"
                    spec.height !in DIMENSION_MIN_HEIGHT..DIMENSION_HEIGHT_SPAN ->
                        "dimension type '$id' height ${spec.height} is outside the game's " +
                            "$DIMENSION_MIN_HEIGHT..$DIMENSION_HEIGHT_SPAN"
                    spec.logicalHeight !in 0..DIMENSION_HEIGHT_SPAN ->
                        "dimension type '$id' logical height ${spec.logicalHeight} is outside the game's " +
                            "0..$DIMENSION_HEIGHT_SPAN"
                    spec.ambientLight !in 0f..1f ->
                        "dimension type '$id' ambient light ${spec.ambientLight} is not between 0.0 and 1.0"
                    spec.monsterSpawnLightLevel !in 0..15 || spec.monsterSpawnBlockLightLimit !in 0..15 ->
                        "dimension type '$id' monster spawn light is not a 0..15 light level"
                    !spec.infiniburn.startsWith("#") ||
                        !DATAPACK_NAME.matches(spec.infiniburn.removePrefix("#").substringAfterLast(':')) ->
                        "dimension type '$id' infiniburn '${spec.infiniburn}' is not a `#namespace:tag` reference"
                    spec.skybox != null && spec.skybox !in SKYBOXES ->
                        "dimension type '$id' has unknown skybox '${spec.skybox}' (none, overworld, end)"
                    badTimeline != null ->
                        "dimension type '$id' names timeline '$badTimeline', which is not a datapack id"
                    clock != null && clock.substringBefore(':') == namespace &&
                        collectedClocks.none { it.namespace == namespace && it.key == clock.substringAfter(':') } ->
                        "dimension type '$id' runs on clock '$clock', which this pack has not declared " +
                            "(declare the clock before the type that runs on it)"
                    else -> null
                }
                if (refusal != null) {
                    // Every window above is the game's own codec range: a file outside it is rejected during
                    // reload, and the dimension that names this type then fails to load alongside it.
                    OmlLog.warn("Content", "$refusal; the dimension type is not declared")
                    return
                }
                collectedDimensionTypes += DimensionTypeDecl(
                    namespace, id, dimensionTypeJson(namespace, spec), reloadable,
                )
            }

            override fun declareDimension(id: String, configure: OMLDimensionDeclaration.() -> Unit) {
                val spec = OMLDimensionDeclaration().apply(configure)
                val type = spec.type?.let { qualify(namespace, it) }
                    ?: run {
                        OmlLog.warn(
                            "Content",
                            "dimension '$id' names no dimension type; the dimension is not declared",
                        )
                        return
                    }
                val badLayer = spec.layers.firstOrNull { it.height < 1 || !isDataPackId(it.block) }
                val refusal = when {
                    !DATAPACK_NAME.matches(id) -> "'$id' is not a lowercase datapack name"
                    collectedDimensions.any { it.namespace == namespace && it.key == id } ->
                        "dimension '$id' is declared twice"
                    spec.generator !in GENERATORS ->
                        "dimension '$id' generator '${spec.generator}' is not one of ${GENERATORS.sorted()}"
                    type.substringBefore(':') == namespace &&
                        collectedDimensionTypes.none {
                            it.namespace == namespace && it.key == type.substringAfter(':')
                        } ->
                        "dimension '$id' runs on type '$type', which this pack has not declared " +
                            "(declare the type before the dimension that uses it)"
                    spec.generator == "noise" &&
                        (spec.noiseSettings == null || spec.biome == null || !isDataPackId(spec.biome!!)) ->
                        "dimension '$id' needs a noise 'settings' id and a fixed 'biome' id"
                    spec.generator == "flat" && spec.biome != null && !isDataPackId(spec.biome!!) ->
                        "dimension '$id' names biome '${spec.biome}', which is not a datapack id"
                    badLayer != null ->
                        "dimension '$id' layer '${badLayer.block}' x${badLayer.height} is not a " +
                            "block id with a positive height"
                    else -> null
                }
                if (refusal != null) {
                    OmlLog.warn("Content", "$refusal; the dimension is not declared")
                    return
                }
                collectedDimensions += DimensionDecl(
                    namespace, id, type, dimensionJson(namespace, type, spec), reloadable,
                )
            }
        }

    private fun qualify(namespace: String, id: String): String =
        if (id.contains(':')) id else "$namespace:$id"

    private fun recipeJson(
        input: String,
        result: String,
        namespace: String,
        furnace: Furnace,
        experience: Double,
        cookingTime: Int,
    ): String {
        // The recipe's input id doubles as the declaration key the adapter reads back for datapack
        // directory listing ("random_sequence" is a loot-table field; smelting recipes do not carry
        // one in vanilla, but an extra unknown field would fail strict parsers, so the key is
        // tracked here in the adapter instead — this field is *not* emitted in the final form).
        val resultId = qualify(namespace, result)
        val inputId = qualify(namespace, input)
        return "{\"type\":\"minecraft:" + furnace.recipePath + "\",\"category\":\"misc\",\"group\":\"\",\"ingredient\":\"" +
            inputId + "\",\"result\":{\"id\":\"" + resultId + "\"},\"experience\":" + experience +
            ",\"cookingtime\":" + cookingTime + "}"
    }

    /**
     * The datapack file id for a crafting recipe. Unlike furnace recipes (whose input doubles as
     * the key) a crafting recipe has no single ingredient, so the key derives from the result —
     * and two recipes producing the same result get a numeric suffix, because a datapack
     * directory cannot hold two files with the same name.
     */
    private fun craftingKey(namespace: String, result: String): String =
        uniqueKey(collectedRecipes.mapTo(HashSet()) { it.key }, qualify(namespace, result).replace(':', '_'))

    /** A datapack directory cannot hold two files with the same name — suffix the second and on. */
    private fun uniqueKey(taken: Set<String>, base: String): String {
        var candidate = base
        var n = 2
        while (candidate in taken) {
            candidate = base + "_" + n
            n++
        }
        return candidate
    }

    /** Shaped-pattern validation: rectangular 1-3 × 1-3, every non-blank character keyed. */
    private fun validateShaped(pattern: List<String>, key: Map<Char, String>, count: Int) {
        if (pattern.isEmpty() || pattern.size > 3) {
            throw IllegalStateException("shaped crafting pattern needs 1-3 rows (got ${pattern.size})")
        }
        val widths = pattern.map { it.length }
        if (widths.distinct().size > 1 || widths[0] !in 1..3) {
            throw IllegalStateException(
                "shaped crafting pattern rows must all be 1-3 cells wide (got $widths)",
            )
        }
        for (row in pattern) {
            for (c in row) {
                if (c == ' ') continue
                if (c !in key) {
                    throw IllegalStateException(
                        "shaped crafting pattern character '$c' has no key entry — add it to the key map",
                    )
                }
            }
        }
        if (count < 1) throw IllegalStateException("crafting result count must be >= 1 (got $count)")
    }

    /**
     * The damage-type JSON, in the game's own field shape: `scaling`, `effects` and `death_message_type`
     * are plain enum names (no namespace, unlike every other id in these files), and the two optional ones
     * are omitted rather than defaulted so the game's own codec path applies its default.
     */
    private fun damageTypeJson(
        messageId: String,
        scaling: String,
        effects: String?,
        exhaustion: Float,
        deathMessageType: String?,
    ): String = buildString {
        append('{')
        if (deathMessageType != null) append("\"death_message_type\":\"$deathMessageType\",")
        if (effects != null) append("\"effects\":\"$effects\",")
        append("\"exhaustion\":$exhaustion,")
        append("\"message_id\":\"$messageId\",")
        append("\"scaling\":\"$scaling\"")
        append('}')
    }

    /**
     * The dimension-type file in the game's own field shape. The eleven fields its codec marks required are
     * always written, with vanilla's overworld values as the declaration defaults; the four the codec defaults
     * on its own are omitted when the mod leaves them unset, so the game applies its default rather than OML
     * guessing one.
     */
    private fun dimensionTypeJson(namespace: String, spec: OMLDimensionTypeDeclaration): String = buildString {
        append("{\"ambient_light\":${spec.ambientLight},")
        append("\"coordinate_scale\":${spec.coordinateScale},")
        append("\"has_ceiling\":${spec.ceiling},")
        append("\"has_ender_dragon_fight\":${spec.dragonFight},")
        append("\"has_skylight\":${spec.skyLight},")
        append("\"height\":${spec.height},")
        append("\"infiniburn\":\"${spec.infiniburn}\",")
        append("\"logical_height\":${spec.logicalHeight},")
        append("\"min_y\":${spec.minY},")
        append("\"monster_spawn_block_light_limit\":${spec.monsterSpawnBlockLightLimit},")
        append("\"monster_spawn_light_level\":${spec.monsterSpawnLightLevel}")
        spec.fixedTime?.let { append(",\"has_fixed_time\":$it") }
        spec.skybox?.let { append(",\"skybox\":\"$it\"") }
        spec.clock?.let { append(",\"default_clock\":\"${qualify(namespace, it)}\"") }
        if (spec.timelines.isNotEmpty()) {
            append(",\"timelines\":[")
            spec.timelines.joinTo(this, separator = ",") { "\"${qualify(namespace, it)}\"" }
            append(']')
        }
        append('}')
    }

    /**
     * The level-stem file: `type` names the dimension type, and `generator` is one of the three the game can
     * build without code — a flat stack of layers, a noise settings id with one fixed biome, or the debug
     * generator, which carries no settings of its own.
     */
    private fun dimensionJson(namespace: String, type: String, spec: OMLDimensionDeclaration): String =
        buildString {
            append("{\"type\":\"$type\",\"generator\":{\"type\":\"minecraft:${spec.generator}\"")
            when (spec.generator) {
                "flat" -> {
                    append(",\"settings\":{\"layers\":[")
                    spec.layers.joinTo(this, separator = ",") {
                        "{\"block\":\"${qualify(namespace, it.block)}\",\"height\":${it.height}}"
                    }
                    append("],\"features\":${spec.features},\"lakes\":${spec.lakes}")
                    spec.biome?.let { append(",\"biome\":\"${qualify(namespace, it)}\"") }
                    append('}')
                }

                "noise" -> append(
                    ",\"settings\":\"${qualify(namespace, spec.noiseSettings!!)}\"," +
                        "\"biome_source\":{\"type\":\"minecraft:fixed\",\"biome\":" +
                        "\"${qualify(namespace, spec.biome!!)}\"}",
                )
            }
            append("}}")
        }

    /**
     * The timeline file in the game's own shape: `clock` is a full id (a bare one is bound to the declaring
     * namespace like every other id in these files), `period_ticks` is omitted when the markers do not repeat,
     * and a marker that stays out of `/time set` is written as the bare number the codec also accepts.
     */
    private fun timelineJson(namespace: String, clock: String, spec: OMLTimelineDeclaration): String =
        buildString {
            append("{\"clock\":\"").append(clock).append('"')
            spec.periodTicks?.let { append(",\"period_ticks\":").append(it) }
            if (spec.markers.isNotEmpty()) {
                append(",\"time_markers\":{")
                spec.markers.joinTo(this, separator = ",") { marker ->
                    val key = qualify(namespace, marker.id)
                    if (marker.inCommands) {
                        "\"$key\":{\"show_in_commands\":true,\"ticks\":${marker.ticks}}"
                    } else {
                        "\"$key\":${marker.ticks}"
                    }
                }
                append('}')
            }
            append('}')
        }

    private fun lootJson(block: String, drop: String, namespace: String, min: Int, max: Int): String {
        val blockId = qualify(namespace, block)
        val dropId = qualify(namespace, drop)
        // 26.3 loot shape: pool conditions are a single `condition` object and entry functions are
        // `modifier` entries — the pre-1.21 array shapes fail the loot codec, and a rejected loot
        // override silently drops nothing.
        val count = if (min == max) "$min" else "{\"type\":\"minecraft:uniform\",\"min\":$min,\"max\":$max}"
        return """
            {"type":"minecraft:block","pools":[{"condition":{"type":"minecraft:survives_explosion"},"entries":[{"type":"minecraft:item","name":"$dropId","modifier":[{"type":"minecraft:set_count","count":$count}]}],"rolls":1}],"random_sequence":"$blockId"}
        """.trim()
    }

    /**
     * Declares a block: immediately returns a handle (whose [OMLBlock.platform] is lazy-resolved
     * and errors if accessed before materialization), and queues the declaration for materialization.
     */
    protected fun collect(namespace: String, id: String, spec: OMLBlockDeclaration): OMLBlock {
        val key = "$namespace:$id"
        val ore = spec.oreDeclaration
        if (ore != null) {
            // snapshotted at collect time: the block queue is drained after materialization, but
            // the ore's datapack files must stay served for every later datapack reload (blocks
            // are frozen, so the declaration cannot be re-collected)
            val key2 = "ore_$id"
            collectedOreGen += OreGenDecl(
                namespace, key2, oreFeatureJson(namespace, id, ore),
                orePlacedJson(namespace, key2, ore),
                ore.biomes.toList(),
            )
        }
        return OMLBlock(key) { materialized[key]?.platform ?: error("block $key has not been materialized") }
            .also { collected += BlockDecl(namespace, id, spec) }
    }

    /** Declares an item: same two-phase contract as [collect]. */
    protected fun collectItem(namespace: String, id: String, spec: OMLItemDeclaration): OMLItem {
        val key = "$namespace:$id"
        return OMLItem(key) { materializedItems[key]?.platform ?: error("item $key has not been materialized") }
            .also { collectedItems += ItemDecl(namespace, id, spec) }
    }

    /**
     * Template method: performs the version-specific translation, then clears the consumed
     * declaration queues. Called from the version's trigger point (onGameReady / the registry
     * freeze point). Implementations must not clear the queues — that is handled centrally here.
     */
    fun materializeAll() {
        doMaterialize()
        collected.clear()
        collectedItems.clear()
        // Recipe / loot declarations are NOT consumed here: they are translated into datapack
        // JSON by the adapter (served through the injected resource pack) and stay queued for
        // the pack's per-reload answering. The adapter reads them back via [recipeJsonFor] /
        // [lootJsonFor] and lists them via [recipeIdsFor] / [lootIdsFor].
    }

    /**
     * Translates every declaration in [collected] / [collectedItems] into the version's blocks
     * (with their vanilla properties), items (with their native data components) and block items,
     * also writing handles into [materialized] / [materializedItems]. Blocks materialize before
     * items: a block item pairs with an already-registered block.
     */
    protected abstract fun doMaterialize()
}

/**
 * The damage-type enum names the game's codec accepts, spelled as the vanilla datapack spells them. This
 * is a deliberately closed set: oml-content compiles without the game, so it cannot read the enums, and a
 * name outside it is refused at declaration instead of reaching a datapack reload that would drop the file.
 */
private val DAMAGE_SCALINGS = setOf("never", "when_caused_by_living_non_player", "always")
private val DAMAGE_EFFECTS = setOf("hurt", "thorns", "drowning", "burning", "poking", "freezing")
private val DEATH_MESSAGE_TYPES = setOf("default", "fall_variants", "intentional_game_design")

/** A datapack file name: lower case, and no namespace — OML binds the declaring mod's id. */
private val DATAPACK_NAME = Regex("[a-z0-9_.-]+")

/**
 * The game's own `DimensionType` codec windows, read off 26.3: `intRange(MIN_Y, MAX_Y)` for `min_y`,
 * `intRange(16, Y_SIZE)` for `height` and `intRange(0, Y_SIZE)` for `logical_height`, where
 * `Y_SIZE = (1 << BlockPos.PACKED_Y_LENGTH) - 32 = 4064`, `MAX_Y = 2031` and `MIN_Y = -2032`.
 */
private const val COORDINATE_SCALE_MIN = 9.999999747378752E-6
private const val COORDINATE_SCALE_MAX = 3.0E7
private const val DIMENSION_MIN_Y = -2032
private const val DIMENSION_MAX_Y = 2031
private const val DIMENSION_MIN_HEIGHT = 16
private const val DIMENSION_HEIGHT_SPAN = 4064

private val SKYBOXES = setOf("none", "overworld", "end")
private val GENERATORS = setOf("flat", "noise", "debug")

/** A `namespace:path` (or a bare path, which the game binds to `minecraft`) the codec can parse. */
private fun isDataPackId(id: String): Boolean = DATAPACK_NAME.matches(id.substringAfterLast(':'))
