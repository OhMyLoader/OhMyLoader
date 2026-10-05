package org.ohmyloader.adapter.snapshot

import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.ItemLike
import org.ohmyloader.api.OmlLog
import org.ohmyloader.api.content.OMLBlock
import org.ohmyloader.api.content.OMLItem
import org.ohmyloader.api.content.OMLItemDeclaration
import org.ohmyloader.content.AbstractContentRegistry
import org.ohmyloader.core.OMLCore

/**
 * 26.4-snapshot-2 content registration: two-stage translation. Collection (before `Main.main`) only records declarations — bootstrap has not run yet, and registry writes would be rejected by vanilla's own freeze check. Materialization happens at the registry freeze point (the `freeze()` call inside `BuiltInRegistries.bootStrap` redirected to [EventBridge.onRegistryFreeze]): content is registered before the registry truly closes, and 26.4-snapshot-2 runs `validate(REGISTRY)` straight after, so the game itself validates the injected content.
 * Data components go through `Item$Properties.component(DataComponentType, T)` — vanilla's own path, so the values live in the item's `DataComponentMap` like any vanilla item's. `maxDamage` must be accompanied by `MAX_STACK_SIZE=1` / `DAMAGE=0` (exactly what vanilla's `durability(int)` sets): the game's component validation rejects a damageable item that is still stackable.
 * 26.4-snapshot-2 traps: `net.minecraft.resources.ResourceLocation` no longer exists — the class is `net.minecraft.resources.Identifier` (same API). `BlockBehaviour.Properties` / `Item$Properties` **require the id to be set before construction** (`drops`/`descriptionId` are `DependantName`s derived from the key), and the id must be the very key the object is registered under, or loot tables and translation keys point at nothing.
 * Vanilla registers block states into `Block.BLOCK_STATE_REGISTRY` and runs each state's `initCache()` in one pass at the end of `Blocks.<clinit>` — before this freeze-point materialization — so materialization must replay both; skipping `initCache()` leaves every modded state's occlusion shapes null and the chunk renderer NPEs on first placement.
 * All reflection goes through the OML game class loader (game classes are not on the parent loader's classpath); queues, handle caches and the mod facade come from [AbstractContentRegistry].
 */
object MinecraftContentRegistry : AbstractContentRegistry() {

    /** The feature step every underground ore lives in (vanilla `Decoration.UNDERGROUND_ORES`). */
    private const val UNDERGROUND_ORES_STEP = 6

    /** Invoked by EventBridge at the registry freeze point: translate every declaration into Block + BlockItem + Item. */
    override fun doMaterialize() {
        val creativeItems = mutableListOf<ItemLike>()
        val loader = OMLCore.gameClassLoader()
        val propertiesClass =
            Class.forName($$"net.minecraft.world.level.block.state.BlockBehaviour$Properties", true, loader)
        val blockClass = Class.forName("net.minecraft.world.level.block.Block", true, loader)
        val itemClass = Class.forName("net.minecraft.world.item.Item", true, loader)
        val blockItemClass = Class.forName("net.minecraft.world.item.BlockItem", true, loader)
        val identifierClass = Class.forName("net.minecraft.resources.Identifier", true, loader)
        val resourceKeyClass = Class.forName("net.minecraft.resources.ResourceKey", true, loader)
        // The registry keys of the two registries an entry is keyed by — a block's id and its block
        // item's id are keys in *different* registries, so they are built from different registry keys
        // even though both end up carrying the same identifier.
        val registriesClass = Class.forName("net.minecraft.core.registries.Registries", true, loader)
        val blockRegistryKey = registriesClass.getField("BLOCK").get(null)
        val itemRegistryKey = registriesClass.getField("ITEM").get(null)
        val keyCreate = resourceKeyClass.getMethod("create", resourceKeyClass, identifierClass)
        // 26.4-snapshot-2: the id is required on both sides of the pair. `Properties.drops` and
        // `.descriptionId` are `DependantName`s — values *derived from* the entry's key rather than
        // stored — so `effectiveDrops()` does `Objects.requireNonNull(id, "Block id not set")` and
        // the `Block` constructor calls it; `Item$Properties` has the same requirement ("Item id
        // not set"). The field must therefore be filled in **before** the object is constructed,
        // and the id has to be the very key the object is registered under or the loot table and
        // translation key would point somewhere nothing is registered.
        val setBlockId = propertiesClass.getMethod("setId", resourceKeyClass)
        val itemPropertiesClass = itemClass.classes.first { it.simpleName == "Properties" }
        val setItemId = itemPropertiesClass.getMethod("setId", resourceKeyClass)

        val identifierOf =
            identifierClass.getMethod("fromNamespaceAndPath", String::class.java, String::class.java)

        for (decl in collected) {
            val [namespace, id] = decl.namespace to decl.id
            val identifier = identifierOf.invoke(null, namespace, id)
            val properties = propertiesClass.getMethod("of").invoke(null)
            setBlockId.invoke(properties, keyCreate.invoke(null, blockRegistryKey, identifier))
            applyBlockProperties(properties, propertiesClass, decl)

            val blockEntitySpec = decl.spec.blockEntityDeclaration
            val block: Any =
                when {
                    blockEntitySpec != null ->
                        // A machine block carries the block entity AND may carry the behavior hooks:
                        // OMLBlockEntityBlock extends OMLBehaviorBlock for exactly that.
                        OMLBlockEntityBlock(propertiesClass.cast(properties) as BlockBehaviour.Properties).apply {
                            stepOnHandlers = decl.spec.stepOnHandlers.toList()
                            hitHandlers = decl.spec.hitHandlers.toList()
                            tickHandlers = blockEntitySpec.tickHandlers.toList()
                        }

                    decl.spec.stepOnHandlers.isNotEmpty() || decl.spec.hitHandlers.isNotEmpty() ->
                        // Behavior hooks materialize into the adapter's own Block subclass; the handler
                        // list is copied because the declaration object is read-only by contract after
                        // collection (the freeze point may fire while the mod code is still reachable).
                        OMLBehaviorBlock(propertiesClass.cast(properties) as BlockBehaviour.Properties).apply {
                            stepOnHandlers = decl.spec.stepOnHandlers.toList()
                            hitHandlers = decl.spec.hitHandlers.toList()
                        }

                    else -> blockClass.getConstructor(propertiesClass).newInstance(properties)
                }
            registerIn("net.minecraft.core.registries.BuiltInRegistries", "BLOCK", identifier, block)

            // Block states must also be registered into Block.BLOCK_STATE_REGISTRY: network packets
            // such as block_update encode states to ids through it, and an unregistered state throws
            // an EncoderException that kicks the player when it is sent.
            //
            // Vanilla performs this add *and* the per-state initCache() in one pass at the end of
            // Blocks.<clinit> — which runs before this materialization (it fires during
            // createContents(), while we materialize at the redirected freeze()). initCache() fills
            // occlusionShapesByFace; without it every modded state is null there and the chunk
            // renderer NPEs in getFaceOcclusionShape when the block is placed.
            val blockStateRegistry = blockClass.getField("BLOCK_STATE_REGISTRY").get(null)
            val addState = blockStateRegistry.javaClass.methods
                .first { it.name == "add" && it.parameterCount == 1 }
            val stateDefinition = blockClass.getMethod("getStateDefinition").invoke(block)
            val possibleStates =
                stateDefinition.javaClass.getMethod("getPossibleStates").invoke(stateDefinition) as? Collection<*>
            possibleStates?.filterNotNull()?.forEach { state ->
                addState.invoke(blockStateRegistry, state)
                state.javaClass.getMethod("initCache").invoke(state)
            }

            // A declared block entity gets its own type, valid for exactly this block — the same
            // identifier the block registered under (different registries, so no clash). The
            // supplier dereferences the type only when the first entity is created (a chunk load),
            // long after this registration closes, which is what breaks the type/supplier cycle.
            (block as? OMLBlockEntityBlock)?.let { machineBlock ->
                var typeRef: BlockEntityType<OMLMachineBlockEntity>? = null
                val entityType = BlockEntityType(
                    { pos, state -> OMLMachineBlockEntity(typeRef!!, pos, state) },
                    setOf(machineBlock),
                )
                typeRef = entityType
                machineBlock.attachEntityType(entityType)
                registerIn(
                    "net.minecraft.core.registries.BuiltInRegistries",
                    "BLOCK_ENTITY_TYPE",
                    identifier,
                    entityType,
                )
            }

            // A fresh `Item$Properties` per block, not one shared instance: it now carries the id, and
            // a shared instance would leave every item keyed by the last block the loop wrote.
            val itemProperties = itemPropertiesClass.getDeclaredConstructor().newInstance()
            setItemId.invoke(itemProperties, keyCreate.invoke(null, itemRegistryKey, identifier))
            val item =
                blockItemClass.getConstructor(blockClass, itemPropertiesClass).newInstance(block, itemProperties)
            registerIn("net.minecraft.core.registries.BuiltInRegistries", "ITEM", identifier, item)
            creativeItems.add(item as ItemLike)

            val handle = OMLBlock("$namespace:$id") { block }
            materialized[handle.id] = handle
        }

        for (decl in collectedItems) {
            val [namespace, id] = decl.namespace to decl.id
            val identifier = identifierOf.invoke(null, namespace, id)
            val itemProperties = itemPropertiesClass.getDeclaredConstructor().newInstance()
            setItemId.invoke(itemProperties, keyCreate.invoke(null, itemRegistryKey, identifier))
            applyItemComponents(itemProperties, itemPropertiesClass, decl, identifierOf, loader)

            val item = itemClass.getConstructor(itemPropertiesClass).newInstance(itemProperties)
            registerIn("net.minecraft.core.registries.BuiltInRegistries", "ITEM", identifier, item)
            creativeItems.add(item as ItemLike)

            val handle = OMLItem("$namespace:$id") { item }
            materializedItems[handle.id] = handle
        }

        OMLCreativeTabs.register(creativeItems)
    }

    // ---- worldgen: biome merges for declared ores ----

    private val biomeMerges = java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<String>>()

    /** The merged biome JSON for `minecraft:worldgen/biome/<biome>.json`, null when no ore targets it. */
    fun mergedBiomeJsonFor(biome: String): String? =
        biomeMerges.computeIfAbsent(biome) { java.util.Optional.ofNullable(mergeBiome(it)) }.orElse(null)

    /** Every biome targeted by at least one ore declaration (the pack's override listing). */
    fun oreTargetedBiomes(): List<String> =
        oreGenDecls.flatMap { ore ->
            if (ore.biomes.isEmpty()) vanillaBiomeIds() else ore.biomes.map(::qualifyBiome)
        }.distinct().sorted()

    private fun qualifyBiome(biome: String): String =
        if (biome.contains(':')) biome else "minecraft:$biome"

    /**
     * Reads the baseline biome JSON from the game jar's embedded datapack (the injected pack is
     * not on the classpath, so this is always the pre-merge original) and appends the ore placed
     * ids to the underground-ores step. Biome files are whole-file overrides in the datapack
     * format — there is no additive mechanism — so the merged full file is what the pack serves.
     */
    private fun mergeBiome(biome: String): String? {
        val placedIds = oreGenDecls.filter { ore ->
            val targets = if (ore.biomes.isEmpty()) vanillaBiomeIds() else ore.biomes.map(::qualifyBiome)
            biome in targets
        }.map { "${it.namespace}:${it.key}" }
        if (placedIds.isEmpty()) return null
        val original = OMLCore.gameClassLoader().getResource("data/minecraft/worldgen/biome/$biome.json")
        if (original == null) {
            OmlLog.warn("Content", "ore targets biome [$biome] but no biome json exists for it; skipped")
            return null
        }
        val root = com.google.gson.JsonParser.parseString(
            original.openStream().use { it.readBytes().toString(Charsets.UTF_8) },
        ).asJsonObject
        val features = root.getAsJsonArray("features")
        if (features == null || features.size() <= UNDERGROUND_ORES_STEP) {
            OmlLog.warn("Content", "biome [$biome] has no underground-ores feature step; ore merge skipped")
            return null
        }
        val step = features.get(UNDERGROUND_ORES_STEP).asJsonArray
        placedIds.forEach { step.add(com.google.gson.JsonPrimitive(it)) }
        return root.toString()
    }

    /** Biome files the vanilla datapack ships, from the game jar. Empty when the jar is unknown. */
    private fun vanillaBiomeIds(): List<String> {
        cachedVanillaBiomes?.let { return it }
        val gameJar = System.getProperty("oml.game.jar")
        val ids = if (gameJar == null) {
            emptyList()
        } else {
            runCatching {
                java.util.jar.JarFile(gameJar).use { jar ->
                    jar.entries().asSequence()
                        .map { it.name }
                        .filter { it.startsWith("data/minecraft/worldgen/biome/") && it.endsWith(".json") }
                        .map { it.substringAfterLast('/').removeSuffix(".json") }
                        .toSortedSet()
                        .toList()
                }
            }.getOrElse {
                OmlLog.warn("Content", "cannot enumerate the vanilla biomes in $gameJar: ${it.message}")
                emptyList()
            }
        }
        cachedVanillaBiomes = ids
        return ids
    }

    @Volatile
    private var cachedVanillaBiomes: List<String>? = null

    /** Applies [org.ohmyloader.api.content.OMLBlockDeclaration] values onto `BlockBehaviour.Properties`. */
    private fun applyBlockProperties(properties: Any, propertiesClass: Class<*>, decl: BlockDecl) {
        val spec = decl.spec
        val destroyTime = spec.destroyTime
        val resistance = spec.explosionResistance
        if (destroyTime != null || resistance != null) {
            // Either half unset falls back to vanilla's "unset" value (0.0), mirroring
            // Properties.strength(float, float) semantics.
            propertiesClass.getMethod("strength", Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
                .invoke(properties, destroyTime ?: 0f, resistance ?: 0f)
        }
        if (spec.requiresCorrectToolForDrops) {
            propertiesClass.getMethod("requiresCorrectToolForDrops").invoke(properties)
        }
    }

    /**
     * Applies the native data components of [org.ohmyloader.api.content.OMLItemDeclaration] onto
     * `Item$Properties` through `component(DataComponentType, T)`.
     */
    private fun applyItemComponents(
        itemProperties: Any,
        itemPropertiesClass: Class<*>,
        decl: ItemDecl,
        identifierOf: java.lang.reflect.Method,
        loader: ClassLoader,
    ) {
        val component = itemPropertiesClass.methods
            .first { it.name == "component" && it.parameterCount == 2 }
        val spec = decl.spec

        // DataComponents.MAX_DAMAGE — Integer. Vanilla's `durability(int)` sets *three* components
        // (verified by disassembly): MAX_DAMAGE, MAX_STACK_SIZE=1 and DAMAGE=0. Skipping the stack
        // size trips the game's /give-time validation — "Malformed item: Item cannot be both
        // damageable and stackable" — because the item keeps the default max stack size of 64.
        spec.maxDamage?.let { maxDamage ->
            component.invoke(itemProperties, dataComponent(loader, "MAX_DAMAGE"), maxDamage)
            component.invoke(itemProperties, dataComponent(loader, "MAX_STACK_SIZE"), 1)
            component.invoke(itemProperties, dataComponent(loader, "DAMAGE"), 0)
        }

        // DataComponents.ATTRIBUTE_MODIFIERS — ItemAttributeModifiers (attack damage / attack speed, main hand)
        if (spec.attackDamage != null || spec.attackSpeed != null) {
            val attrModClass = Class.forName("net.minecraft.world.item.component.ItemAttributeModifiers", true, loader)
            val builder = attrModClass.getMethod("builder").invoke(null)
            val add = builder.javaClass.methods.first {
                it.name == "add" && it.parameterCount == 3 && it.parameterTypes[2].simpleName == "EquipmentSlotGroup"
            }
            val build = builder.javaClass.methods.first { it.name == "build" && it.parameterCount == 0 }
            val modifierCtor = Class.forName("net.minecraft.world.entity.ai.attributes.AttributeModifier", true, loader)
                .getConstructor(identifierOf.returnType, java.lang.Double.TYPE, operationClass(loader))
            val attributesClass = Class.forName("net.minecraft.world.entity.ai.attributes.Attributes", true, loader)

            spec.attackDamage?.let { amount ->
                add.invoke(
                    builder,
                    attributesClass.getField("ATTACK_DAMAGE").get(null),
                    modifierCtor.newInstance(
                        identifierOf.invoke(null, decl.namespace, "${decl.id}.attack_damage"),
                        amount,
                        operationAddValue(loader),
                    ),
                    mainHandGroup(loader),
                )
            }
            spec.attackSpeed?.let { amount ->
                add.invoke(
                    builder,
                    attributesClass.getField("ATTACK_SPEED").get(null),
                    modifierCtor.newInstance(
                        identifierOf.invoke(null, decl.namespace, "${decl.id}.attack_speed"),
                        amount,
                        operationAddValue(loader),
                    ),
                    mainHandGroup(loader),
                )
            }
            component.invoke(itemProperties, dataComponent(loader, "ATTRIBUTE_MODIFIERS"), build.invoke(builder))
        }

        // DataComponents.TOOL — Tool(rules, defaultMiningSpeed, damagePerBlock, canDestroyBlocksInCreative)
        if (spec.toolRules.isNotEmpty() || spec.miningSpeed != null) {
            val toolClass = Class.forName("net.minecraft.world.item.component.Tool", true, loader)
            val ruleClass = Class.forName($$"net.minecraft.world.item.component.Tool$Rule", true, loader)
            val holderSetClass = Class.forName("net.minecraft.core.HolderSet", true, loader)
            val direct = holderSetClass.methods.first {
                it.name == "direct" && it.parameterCount == 1 && it.parameterTypes[0] == List::class.java
            }
            val blockRegistry = Class.forName("net.minecraft.core.registries.BuiltInRegistries", true, loader)
                .getField("BLOCK").get(null)
            val blockByid = blockRegistry.javaClass.methods
                .first { it.name == "get" && it.parameterCount == 1 && it.parameterTypes[0] == identifierOf.returnType }

            fun holderSetOf(blockName: String): Any {
                val [ns, path] = splitBlockName(blockName)
                val identifier = identifierOf.invoke(null, ns, path)
                // Fail loudly rather than silently mining at vanilla speed forever: an unknown block
                // name in a tool rule is a mod bug, and the freeze point is the cheapest place to say so.
                val holder = (blockByid.invoke(blockRegistry, identifier) as java.util.Optional<*>)
                    .orElseThrow { IllegalStateException("tool rule references unknown block [$blockName]") }
                return direct.invoke(null, listOf(holder))
            }

            val minesAndDrops = ruleClass.methods.first { it.name == "minesAndDrops" }
            val deniesDrops = ruleClass.methods.first { it.name == "deniesDrops" }
            val overrideSpeed = ruleClass.methods.first { it.name == "overrideSpeed" }
            val rules = spec.toolRules.map { rule ->
                when (rule.kind) {
                    OMLItemDeclaration.ToolRuleKind.MINES_AND_DROPS ->
                        minesAndDrops.invoke(null, holderSetOf(rule.block), rule.speed)

                    OMLItemDeclaration.ToolRuleKind.DENIES_DROPS ->
                        deniesDrops.invoke(null, holderSetOf(rule.block))

                    OMLItemDeclaration.ToolRuleKind.OVERRIDE_SPEED ->
                        overrideSpeed.invoke(null, holderSetOf(rule.block), rule.speed)
                }
            }
            val tool = toolClass.getConstructor(
                List::class.java,
                java.lang.Float.TYPE,
                Integer.TYPE,
                java.lang.Boolean.TYPE,
            ).newInstance(
                rules,
                spec.miningSpeed ?: 1.0f,
                spec.toolDamagePerBlock,
                spec.canDestroyBlocksInCreative,
            )
            component.invoke(itemProperties, dataComponent(loader, "TOOL"), tool)
        }
    }

    private fun splitBlockName(blockName: String): Pair<String, String> {
        val colon = blockName.indexOf(':')
        return if (colon < 0) "minecraft" to blockName else blockName.substring(
            0,
            colon,
        ) to blockName.substring(colon + 1)
    }

    private fun dataComponent(loader: ClassLoader, fieldName: String): Any =
        Class.forName("net.minecraft.core.component.DataComponents", true, loader).getField(fieldName).get(null)

    private fun operationClass(loader: ClassLoader): Class<*> =
        Class.forName($$"net.minecraft.world.entity.ai.attributes.AttributeModifier$Operation", true, loader)

    private fun operationAddValue(loader: ClassLoader): Any =
        operationClass(loader).getField("ADD_VALUE").get(null)

    private fun mainHandGroup(loader: ClassLoader): Any =
        Class.forName("net.minecraft.world.entity.EquipmentSlotGroup", true, loader).getField("MAINHAND").get(null)

    private fun registerIn(registryClass: String, fieldName: String, identifier: Any, value: Any) {
        val loader = OMLCore.gameClassLoader()
        val registry = Class.forName(registryClass, true, loader).getField(fieldName).get(null)
        // 26.4-snapshot-2's MappedRegistry.register is private; the public entry point is the static
        // Registry.register(Registry, Identifier, T). Three 3-argument overloads exist
        // (String / Identifier / ResourceKey key types) — the Identifier one is selected by matching
        // the key's actual class rather than by position.
        val registryClassObj = Class.forName("net.minecraft.core.Registry", true, loader)
        registryClassObj.methods
            .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.name == "register" && it.parameterCount == 3 }
            .firstOrNull { it.parameterTypes[1] == identifier.javaClass }
            ?.invoke(null, registry, identifier, value)
            ?: throw IllegalStateException("registry $registryClass.$fieldName lacks a static register entry point")
    }
}
