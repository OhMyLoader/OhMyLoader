package org.ohmyloader.api.content

/**
 * Content declaration registry: a mod registers simple content declaratively inside
 * [OMLContentProvider.declareContent], and OML translates it to the version's registration
 * mechanism at the registry-freeze point (the earliest point the version registry is still
 * writable), so a 26.3 build can attach real `DataComponentMap` values.
 * Blocks and items declared here are **data-only**: their vanilla properties are configured
 * through the declaration DSL, and behavior beyond vanilla's comes from the declarative hooks
 * ([OMLBlockDeclaration.onStepOn] / [OMLBlockDeclaration.onHit]) whose implementation is the
 * version adapter's. Fully custom content classes (extending the versioned `Block` with
 * arbitrary logic) remain out of scope — use the `platform` escape hatch and write
 * version-specific code instead.
 */
interface ContentRegistry {
    /**
     * Declares a simple block (also registering its corresponding block item, retrievable via
     * /setblock). [configure] optionally sets vanilla block properties (strength, tool-required
     * drops) and behavior hooks ([OMLBlockDeclaration.onStepOn] / [OMLBlockDeclaration.onHit]);
     * every field is optional and defaults to vanilla's. With any behavior hook present the
     * adapter materializes its behavior-carrying Block subclass instead of a plain one.
     *
     * [id] contains no namespace; OML binds it using the declaring mod's id.
     */
    fun declareBlock(id: String, configure: OMLBlockDeclaration.() -> Unit = {}): OMLBlock

    /**
     * Declares a simple item configured through native data components: [OMLItemDeclaration]
     * covers max durability, attribute modifiers (attack damage / attack speed on the main hand)
     * and tool mining rules, which the version adapter materializes into `DataComponents.MAX_DAMAGE`,
     * `DataComponents.ATTRIBUTE_MODIFIERS` and `DataComponents.TOOL` at the freeze point.
     */
    fun declareItem(id: String, configure: OMLItemDeclaration.() -> Unit = {}): OMLItem

    /**
     * Declares a furnace-type recipe (smelting / blasting / smoking, selected by [furnace]).
     * Both ids may be namespaced (`minecraft:iron_ore`) or bare (resolved in the declaring
     * mod's namespace).
     *
     * Translated at materialization into a datapack recipe JSON served through OML's injected
     * resource pack, so it rides the vanilla datapack reload path (server-authoritative, synced
     * to the client) instead of patching the recipe manager.
     */
    fun declareSmelting(
        input: String,
        result: String,
        furnace: Furnace = Furnace.SMELTING,
        experience: Double = 0.0,
        cookingTime: Int = 200,
    )

    /**
     * Declares a shaped crafting recipe. [pattern] rows (1-3 rows of 1-3 cells, all the same
     * width) reference [key] characters; `' '` is an empty cell. Ingredient and [result] ids may
     * be namespaced (`minecraft:iron_ingot`) or bare (resolved in the declaring mod's namespace).
     *
     * Translated at materialization into a datapack recipe JSON served through OML's injected
     * resource pack. Throws [IllegalStateException] on a malformed pattern (ragged rows, a
     * character without a key entry) or [count] < 1.
     */
    fun declareShapedCrafting(
        result: String,
        pattern: List<String>,
        key: Map<Char, String>,
        count: Int = 1,
    )

    /**
     * Declares a shapeless crafting recipe: [ingredients] (1-9, namespaced or bare) combined in
     * any arrangement produce [result] (× [count]). Translated at materialization into a datapack
     * recipe JSON served through OML's injected resource pack; throws [IllegalStateException]
     * when [ingredients] is empty or longer than the 3×3 crafting grid allows.
     */
    fun declareShapelessCrafting(
        result: String,
        ingredients: List<String>,
        count: Int = 1,
    )

    /**
     * Declares that breaking [block] drops [drop] (instead of the block itself). Translated into
     * a datapack loot table override at materialization; [drop] may be namespaced or bare.
     */
    fun declareBlockDrop(block: String, drop: String, dropCountMin: Int = 1, dropCountMax: Int = 1)

    /**
     * Declares a damage type, which the game loads from OML's injected content pack: the version
     * adapter's `LivingEntity.hurt` extension (in `oml-api-<game version>`) takes its id, and a
     * `LIVING_HURT` handler reports [messageId] as the damage type of the hit.
     * [scaling] is how the game multiplies the amount by difficulty (`never`,
     * `when_caused_by_living_non_player`, `always`), [effects] the vanilla sounds and particles that come
     * with it (`hurt`, `thorns`, `drowning`, `burning`, `poking`, `freezing`; null keeps the game's own
     * default), [deathMessageType] how the death message reads (`default`, `fall_variants`,
     * `intentional_game_design`). An unknown name is refused with a warning at declaration rather than
     * written out for the datapack loader to reject later.
     */
    fun declareDamageType(
        id: String,
        messageId: String,
        scaling: String = "when_caused_by_living_non_player",
        effects: String? = null,
        exhaustion: Float = 0.0f,
        deathMessageType: String? = null,
    )

    /**
     * Declares a world clock, which the game loads from OML's injected content pack as
     * `data/<namespace>/world_clock/<id>.json`. A 26.3 clock carries no fields beyond its identity: the
     * position, rate and pause belong to the server's clock manager, which runs one instance for **every**
     * registered clock — including one no dimension binds, so a mod's own clock counts on its own.
     */
    fun declareClock(id: String)

    /**
     * Declares a timeline: the unit that attaches named moments to a clock, loaded as
     * `data/<namespace>/timeline/<id>.json`. [OMLTimelineDeclaration.clock] is required, [periodTicks]
     * optional, and each marker's tick has to sit inside the period — the game's own codec rule, enforced
     * here because a file the codec rejects leaves the whole timeline unregistered with no owner to say so.
     */
    fun declareTimeline(id: String, configure: OMLTimelineDeclaration.() -> Unit)

    /**
     * Declares a dimension type, loaded as `data/<namespace>/dimension_type/<id>.json`. [OMLDimensionTypeDeclaration]
     * covers the game's own fields and every one has a default taken from vanilla's overworld — the codec marks
     * eleven of them required, so the file has to carry them whether or not the mod cares. Out-of-range values
     * are refused at declaration: a rejected file leaves the type absent from the registry and the dimension that
     * names it fails to load with it.
     */
    fun declareDimensionType(id: String, configure: OMLDimensionTypeDeclaration.() -> Unit = {})

    /**
     * Declares a dimension, loaded as `data/<namespace>/dimension/<id>.json`. The game creates a level for
     * **every** entry in that table when the server starts ([org.ohmyloader.api26_3.levelById] then answers for
     * it), so this is the whole of what 26.3 gives a mod without writing a level implementer.
     * [OMLDimensionDeclaration.type] is required.
     */
    fun declareDimension(id: String, configure: OMLDimensionDeclaration.() -> Unit)

    /**
     * Adds this pack's members to a tag in the registry [registryId] (`minecraft:damage_type`,
     * `minecraft:item`, `minecraft:worldgen/biome`), written where the game reads tags:
     * `data/<tag namespace>/tags/<registry path>/<tag path>.json`. This is how content joins a group the game
     * already gates on — `#minecraft:is_fire` is what makes an entity's fire immunity bite — and how a mod
     * opens a group of its own.
     *
     * [tagId] may name a tag in **another** namespace: adding to a vanilla tag is the normal case, and the file
     * then lands in that namespace's folder. Because the game reads one file per tag, every pack that declares
     * the same tag contributes its members to it, in declaration order, and a second declaration that asks for
     * the opposite [OMLTagDeclaration.replace] is refused rather than guessed at.
     */
    fun declareTag(registryId: String, tagId: String, configure: OMLTagDeclaration.() -> Unit)
}

/**
 * Members of a tag for [ContentRegistry.declareTag]. [element] is an id in the registry and [tag] another tag
 * whose contents join this one (`minecraft:is_explosion` means `#minecraft:is_explosion`) — the distinction the
 * game's own file format makes. `required = false` is its own answer too: an absent optional member leaves the
 * tag loading, an absent required one drops it.
 */
class OMLTagDeclaration {

    /** Make these members the tag's whole content instead of adding to what the game holds. */
    var replace: Boolean = false

    // Public but not mod-facing API: the content layer reads this to write the datapack file.
    val entries = mutableListOf<OMLTagEntry>()

    /** Registry member [id]: namespaced, or bare to resolve in the declaring namespace. */
    fun element(id: String, required: Boolean = true) {
        entries += OMLTagEntry(id, required, tag = false)
    }

    /** Another tag, named as its registry id — the `#` is the file's spelling, added here. */
    fun tag(id: String, required: Boolean = true) {
        entries += OMLTagEntry(id, required, tag = true)
    }
}

/** One tag member: [id] is an element or, with [tag], a reference to another tag. */
data class OMLTagEntry(val id: String, val required: Boolean, val tag: Boolean)

/**
 * Dimension-type fields for [ContentRegistry.declareDimensionType]. Names mirror the datapack JSON so a
 * modder can read the vanilla file beside the declaration; the numeric defaults are vanilla's overworld.
 */
class OMLDimensionTypeDeclaration {

    /** `has_skylight`: whether daylight reaches the surface, and part of the game's open-sky weather test. */
    var skyLight: Boolean = true

    /** `has_ceiling`: a roofed dimension, which the game also reads as "no weather here". */
    var ceiling: Boolean = false

    /** `has_ender_dragon_fight`: whether the dragon fight logic claims this dimension. */
    var dragonFight: Boolean = false

    /** `coordinate_scale`: how many blocks this dimension covers per block of its parent. */
    var coordinateScale: Double = 1.0

    /** `min_y`: the lowest build height. The game's own window is -2032..2031. */
    var minY: Int = -64

    /** `height`: the total dimension height in blocks. The game's own window is 16..4064. */
    var height: Int = 384

    /**
     * `logical_height`: the height the day-light cycle uses. Null (the default) writes [height], which the
     * game requires this value not to exceed; its own window is 0..4064.
     */
    var logicalHeight: Int? = null

    /** `infiniburn`: the block tag fire burns forever in, spelled as a tag reference (`#minecraft:infiniburn_overworld`). */
    var infiniburn: String = "#minecraft:infiniburn_overworld"

    /** `ambient_light`: 0.0 (dark) to 1.0 (full), the light the game pretends the sky gives. */
    var ambientLight: Float = 0.0f

    /** `monster_spawn_light_level`: how bright it may be for monsters to spawn (0..15). */
    var monsterSpawnLightLevel: Int = 7

    /** `monster_spawn_block_light_limit`: the block-light ceiling for monster spawns (0 = never on block light). */
    var monsterSpawnBlockLightLimit: Int = 0

    /** `has_fixed_time`: freezes the sky's cycle. Null omits the field, which is the game's own default. */
    var fixedTime: Boolean? = null

    /** `skybox`: `none`, `overworld` or `end`. Null omits it, and the game picks its own default. */
    var skybox: String? = null

    /** `default_clock`: the clock dimensions of this type run on — a declared clock, or null for none. */
    var clock: String? = null

    /** `timelines`: the timeline declarations whose tracks apply to this type. Empty omits the field. */
    val timelines = mutableListOf<String>()
}

/**
 * The level stem for [ContentRegistry.declareDimension]: which dimension type the level runs on, and how its
 * terrain is produced. 26.3 offers three generators a mod can name without writing code: `flat`, `noise` and
 * `debug`.
 */
class OMLDimensionDeclaration {

    /** The dimension type this level runs on: namespaced, or bare to resolve in the declaring namespace. Required. */
    var type: String? = null

    /** The generator: `flat`, `noise` or `debug`. */
    var generator: String = "flat"

    // Public but not mod-facing API: the content layer reads these to write the generator JSON.
    val layers = mutableListOf<OMLFlatLayer>()
    var noiseSettings: String? = null
    var biome: String? = null

    /** Whether the flat generator decorates features. */
    var features: Boolean = false

    /** Whether the flat generator fills water lakes. */
    var lakes: Boolean = false

    /** One layer of the flat generator, [height] blocks of [block] tall, stacked bottom-up. */
    fun layer(block: String, height: Int) {
        layers += OMLFlatLayer(block, height)
    }
}

/** A flat-generator layer: [block] repeated [height] times, the shape the game's own superpreset files use. */
data class OMLFlatLayer(val block: String, val height: Int)

/**
 * Clock-time declaration for [ContentRegistry.declareTimeline]. Tracks (the environment-attribute
 * keyframe half of a timeline) have no declaration form here: their value type is per-attribute and the
 * data is version-facing, so write the datapack file yourself if you need one.
 */
class OMLTimelineDeclaration {

    /** The clock this timeline drives: namespaced (`minecraft:overworld`) or bare, resolved in the declaring namespace. */
    var clock: String? = null

    /** Ticks in one cycle; null means the markers never repeat. */
    var periodTicks: Int? = null

    // Public but not mod-facing API: the content layer reads this to emit the datapack file.
    val markers = mutableListOf<OMLTimeMarker>()

    /**
     * Names [ticks] of the cycle as `markerId`. [inCommands] decides whether `/time set` offers it — the
     * game's own flag, and the reason [org.ohmyloader.api26_3.markersOf] can list fewer markers than a
     * timeline declares.
     */
    fun marker(id: String, ticks: Int, inCommands: Boolean = false) {
        markers += OMLTimeMarker(id, ticks, inCommands)
    }
}

/** One named moment of a timeline: [id] is bound to the declaring namespace, [ticks] counts inside its period. */
data class OMLTimeMarker(val id: String, val ticks: Int, val inCommands: Boolean)

/** Furnace-type recipe variants supported by [ContentRegistry.declareSmelting]. */
enum class Furnace(val recipePath: String) {
    SMELTING("smelting"),
    BLASTING("blasting"),
    SMOKING("smoking"),
}

/** Creates a mod-facing [ContentRegistry] for a namespace. Implemented by version adapters. */
interface ContentRegistryFactory {
    fun forNamespace(namespace: String): ContentRegistry

    /**
     * [reloadable] marks declarations from TOML packs: they are re-collected from disk on every
     * reload. Code-track declarations (a mod's `declareContent`) are frozen like the blocks they
     * belong to and default to not reloadable.
     */
    fun forNamespace(namespace: String, reloadable: Boolean): ContentRegistry = forNamespace(namespace)
}

/** Handle for a registered block; [platform] is accessible after content registration completes. */
class OMLBlock(val id: String, platformSupplier: () -> Any) {
    val platform: Any by lazy(platformSupplier)
    override fun toString(): String = "OMLBlock($id)"
}

/** Handle for a registered item; [platform] is accessible after content registration completes. */
class OMLItem(val id: String, platformSupplier: () -> Any) {
    val platform: Any by lazy(platformSupplier)
    override fun toString(): String = "OMLItem($id)"
}

/**
 * Vanilla block properties for [ContentRegistry.declareBlock], all optional. Field names mirror
 * vanilla's `BlockBehaviour.Properties` semantics so a modder can copy values straight from
 * vanilla sources.
 */
class OMLBlockDeclaration {
    /** Hardness (`destroyTime`): how long breaking takes, in vanilla units. `null` = vanilla default (0). */
    var destroyTime: Float? = null

    /** Blast resistance (`explosionResistance`). `null` = follows [destroyTime] when set, vanilla default otherwise. */
    var explosionResistance: Float? = null

    /**
     * Whether the block only drops when broken with a tool that is "correct" for it (vanilla
     * `requiresCorrectToolForDrops`). Default `false`.
     */
    var requiresCorrectToolForDrops: Boolean = false

    // Public but not mod-facing API: the version adapter reads these to decide between a plain
    // Block and its behavior subclass. Mods register through onStepOn / onHit.
    val stepOnHandlers = mutableListOf<(OMLStepOnEvent) -> Unit>()
    val hitHandlers = mutableListOf<(OMLBlockHitEvent) -> Unit>()

    /**
     * Runs every tick an entity stands on the block (vanilla `stepOn`), on both sides — gate
     * gameplay effects on [OMLStepOnEvent.isClient].
     */
    fun onStepOn(handler: (OMLStepOnEvent) -> Unit) {
        stepOnHandlers += handler
    }

    /** Runs when a player starts breaking the block (vanilla `attack`). */
    fun onHit(handler: (OMLBlockHitEvent) -> Unit) {
        hitHandlers += handler
    }

    // Public but not mod-facing API: the version adapter reads this to materialize the block
    // entity. Mods declare through blockEntity.
    var blockEntityDeclaration: OMLBlockEntityDeclaration? = null
        private set

    /**
     * Gives the block a block entity with an optional server-side tick ([OMLBlockEntityDeclaration.tick])
     * and a persistent data store ([OMLBlockData]) — the pieces a machine is made of. Declaring it
     * twice replaces nothing: it throws.
     */
    fun blockEntity(configure: OMLBlockEntityDeclaration.() -> Unit) {
        check(blockEntityDeclaration == null) { "blockEntity { } can only be declared once per block" }
        blockEntityDeclaration = OMLBlockEntityDeclaration().apply(configure)
    }

    // Public but not mod-facing API: the version adapter reads this to generate the worldgen
    // datapack files. Mods declare through generateAsOre.
    var oreDeclaration: OMLBlockOreDeclaration? = null
        private set

    /**
     * Declares that this block generates naturally as an ore. Materialized into datapack
     * worldgen files (feature + placed feature) and merged into the target biomes' feature lists
     * by the version adapter; only newly generated chunks are affected, like any datapack ore.
     */
    fun generateAsOre(configure: OMLBlockOreDeclaration.() -> Unit = {}) {
        check(oreDeclaration == null) { "generateAsOre can only be declared once per block" }
        oreDeclaration = OMLBlockOreDeclaration().apply(configure)
    }
}

/**
 * Ore generation parameters for [OMLBlockDeclaration.generateAsOre]. The vein replaces
 * `minecraft:stone_ore_replaceables` (the stone family), so it appears in stone — a deepslate
 * variant is a later extension, not part of this declaration.
 */
class OMLBlockOreDeclaration {

    /** Blocks per vein (vanilla `size`). */
    var veinSize: Int = 9

    /** Placement attempts per chunk (vanilla `count`). */
    var perChunk: Int = 8

    /** Lower bound of the trapezoid height distribution, absolute Y. */
    var minY: Int = 16

    /** Upper bound of the trapezoid height distribution, absolute Y. */
    var maxY: Int = 64

    /**
     * Biomes (namespaced) the ore generates in. Empty means every biome the vanilla datapack
     * ships — harmless outside the overworld, because netherrack and end stone do not match the
     * stone replaceable tag the vein targets.
     */
    val biomes = mutableListOf<String>()
}

/**
 * Data-component configuration for [ContentRegistry.declareItem]; everything maps onto native 26.3 item components at materialization
 * time: [maxDamage] → `DataComponents.MAX_DAMAGE` (enables the durability bar and durability loss); [attackDamage] / [attackSpeed] →
 * `DataComponents.ATTRIBUTE_MODIFIERS`, `ADD_VALUE` modifiers on `Attributes.ATTACK_DAMAGE` / `Attributes.ATTACK_SPEED` in the MAINHAND
 * group — the values are the **modifier amounts** a hand adds while holding the item (vanilla fists are 1.0 attack damage / 4.0 attack
 * speed), matching how vanilla swords are written; the tool fields → `DataComponents.TOOL`: [miningSpeed] is the default speed for blocks
 * without a matching [ToolRule], [toolDamagePerBlock] the durability cost per broken block, and the rules decide speed/drops per block.
 */
class OMLItemDeclaration {

    /** Maximum durability; `null` = unbreakable plain item (no durability bar). */
    var maxDamage: Int? = null

    /** Attack damage **modifier** (main hand, `ADD_VALUE`). `null` = no modifier. */
    var attackDamage: Double? = null

    /** Attack speed **modifier** (main hand, `ADD_VALUE`; vanilla base is 4.0). `null` = no modifier. */
    var attackSpeed: Double? = null

    /** Default mining speed for blocks no [ToolRule] matches. Only meaningful with tool rules present. */
    var miningSpeed: Float? = null

    /** Durability removed per block broken (tool component). */
    var toolDamagePerBlock: Int = 1

    /** Whether the item breaks blocks in creative mode (tool component). */
    var canDestroyBlocksInCreative: Boolean = true

    // Public but not mod-facing API: the version adapter reads these to materialize the tool
    // component. Mods build them through minesAndDrops / deniesDrops / overrideSpeed.
    val toolRules = mutableListOf<ToolRuleSpec>()

    /** Blocks matched by this rule mine at [speed] **and drop their loot**. */
    fun minesAndDrops(block: String, speed: Float) {
        toolRules += ToolRuleSpec(ToolRuleKind.MINES_AND_DROPS, block, speed)
    }

    /** Blocks matched by this rule mine at vanilla speed and **never drop loot**. */
    fun deniesDrops(block: String) {
        toolRules += ToolRuleSpec(ToolRuleKind.DENIES_DROPS, block, 0f)
    }

    /** Blocks matched by this rule mine at [speed]; drops behave as vanilla. */
    fun overrideSpeed(block: String, speed: Float) {
        toolRules += ToolRuleSpec(ToolRuleKind.OVERRIDE_SPEED, block, speed)
    }

    enum class ToolRuleKind { MINES_AND_DROPS, DENIES_DROPS, OVERRIDE_SPEED }

    data class ToolRuleSpec(val kind: ToolRuleKind, val block: String, val speed: Float)
}

/**
 * Optional content declaration entry point: when a [Mod]-annotated class also
 * implements this interface, OML invokes it once before the game's main logic
 * starts (the earliest point at which the version registry is still writable).
 */
interface OMLContentProvider {
    fun declareContent(registry: ContentRegistry)
}
