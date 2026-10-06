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
