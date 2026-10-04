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
}

/** Furnace-type recipe variants supported by [ContentRegistry.declareSmelting]. */
enum class Furnace(val recipePath: String) {
    SMELTING("smelting"),
    BLASTING("blasting"),
    SMOKING("smoking"),
}

/** Creates a mod-facing [ContentRegistry] for a namespace. Implemented by version adapters. */
interface ContentRegistryFactory {
    fun forNamespace(namespace: String): ContentRegistry
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
