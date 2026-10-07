package org.ohmyloader.adapter.common

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.commands.arguments.blocks.BlockStateParser
import net.minecraft.core.BlockPos
import net.minecraft.core.Holder
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.core.particles.ParticleType
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.permissions.LevelBasedPermissionSet
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.world.effect.MobEffect
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.damagesource.DamageType
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.attributes.Attribute
import net.minecraft.world.entity.ai.attributes.AttributeInstance
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.entity.EntityTypeTest
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.gamerules.GameRule
import net.minecraft.world.level.gamerules.GameRules
import org.ohmyloader.api.world.OMLBlockPos
import org.ohmyloader.api.world.OMLBlockState
import org.ohmyloader.api.world.OMLEntity
import org.ohmyloader.api.world.OMLEffectInstance
import org.ohmyloader.api.world.OMLInventory
import org.ohmyloader.api.world.OMLItemStack
import org.ohmyloader.api.world.OMLLivingEntity
import org.ohmyloader.api.world.OMLPlayer
import org.ohmyloader.api.world.OMLVec3
import org.ohmyloader.api.wrapper.OMLGuiGraphics
import org.ohmyloader.api.wrapper.OMLScreen
import org.ohmyloader.api.wrapper.OMLWorld
import org.ohmyloader.core.OMLCore

/**
 * Typed projections of game objects for the mod API: every wrapper reads its game object live, so
 * nothing is cached and nothing can go stale. This is the only layer that translates mod-visible game
 * objects, which is why it needs the version jar (`compileOnly`) while `oml-api` does not.
 *
 * Registry ids are the API's currency for blocks and items, and a lookup misses instead of returning a
 * defaulted value: a typo in a mod's id must not silently place air.
 */
internal object GameProjections {
    private const val HOTBAR_SLOTS = 9

    fun entity(entity: Entity): OMLEntity =
        when (entity) {
            is Player -> PlayerWrap(entity)
            is LivingEntity -> LivingWrap(entity)
            else -> EntityWrap(entity)
        }

    fun living(entity: LivingEntity): OMLLivingEntity =
        if (entity is Player) PlayerWrap(entity) else LivingWrap(entity)

    fun world(level: Level): OMLWorld = LevelWrap(level)

    fun stack(stack: ItemStack): OMLItemStack = StackWrap(stack)

    fun screen(screen: Any): OMLScreen = ScreenWrap(screen)

    fun guiGraphics(extractor: GuiGraphicsExtractor): OMLGuiGraphics = GuiGraphicsWrap(extractor)

    val hotbarSlots: Int = HOTBAR_SLOTS

    fun blockId(id: String): Block? = identifier(id)
        ?.takeIf { BuiltInRegistries.BLOCK.containsKey(it) }
        ?.let { BuiltInRegistries.BLOCK.getValue(it) }

    /** Registry key of a block reference, or `minecraft:air`-style empty id when it is unregistered. */
    fun blockKey(block: Block): String = BuiltInRegistries.BLOCK.getKey(block).toString()

    fun itemKey(item: Item): String = BuiltInRegistries.ITEM.getKey(item).toString()

    fun itemId(id: String): Item? = identifier(id)
        ?.takeIf { BuiltInRegistries.ITEM.containsKey(it) }
        ?.let { BuiltInRegistries.ITEM.getValue(it) }

    /** A registered sound event; null for an unknown id, which must not play silence. */
    fun soundEvent(id: String): SoundEvent? = identifier(id)
        ?.takeIf { BuiltInRegistries.SOUND_EVENT.containsKey(it) }
        ?.let { BuiltInRegistries.SOUND_EVENT.getValue(it) }

    /** One of the game's sound categories, addressed by its lower-case name (`master`, `blocks`, …). */
    fun soundSource(category: String): SoundSource? =
        SoundSource.values().firstOrNull { it.name.lowercase() == category.lowercase() }

    /** A registered effect as its holder — the shape the game's own effect methods take. */
    fun effectHolder(id: String): Holder<MobEffect>? = identifier(id)
        ?.let { BuiltInRegistries.MOB_EFFECT.getOptional(it).orElse(null) }
        ?.let { BuiltInRegistries.MOB_EFFECT.wrapAsHolder(it) }

    fun dimensionKey(id: String): ResourceKey<Level>? = identifier(id)
        ?.let { ResourceKey.create(Registries.DIMENSION, it) }

    /** A registered entity type; null for an unknown id, which must not spawn a substitute. */
    fun entityType(id: String): EntityType<*>? = identifier(id)
        ?.takeIf { BuiltInRegistries.ENTITY_TYPE.containsKey(it) }
        ?.let { BuiltInRegistries.ENTITY_TYPE.getValue(it) }

    /** A registered damage type as its holder, the shape the game's own `DamageSource` takes. */
    fun damageTypeHolder(level: Level, id: String): Holder<DamageType>? {
        // Damage types are a datapack registry, so the table comes from the level the damage happens in
        // rather than from the built-in statics.
        val registry = level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE)
        return identifier(id)?.let { registry.getOptional(it).orElse(null) }?.let(registry::wrapAsHolder)
    }

    /** A registered attribute as its holder, which is what `LivingEntity.getAttribute` wants. */
    fun attributeHolder(id: String): Holder<Attribute>? = identifier(id)
        ?.let { BuiltInRegistries.ATTRIBUTE.getOptional(it).orElse(null) }
        ?.let { BuiltInRegistries.ATTRIBUTE.wrapAsHolder(it) }

    /** One of the game's attribute operations, addressed by its own name in lower case. */
    fun attributeOperation(name: String): AttributeModifier.Operation? =
        AttributeModifier.Operation.values().firstOrNull { it.name.lowercase() == name.lowercase() }

    /** A registered particle type — the game accepts it as its own particle options, so one lookup fits. */
    fun particleType(id: String): ParticleType<*>? = identifier(id)
        ?.takeIf { BuiltInRegistries.PARTICLE_TYPE.containsKey(it) }
        ?.let { BuiltInRegistries.PARTICLE_TYPE.getValue(it) }

    /**
     * Assembles `id[facing=east,powered=false]` and lets the game's own block-state argument parse it:
     * an unregistered block, an unknown property or a value the property does not allow all fail here,
     * which is the same rejection `/setblock` gives a player.
     */
    fun parseState(blockId: String, properties: Map<String, String>): BlockState? {
        val spec = buildString {
            append(blockId)
            if (properties.isNotEmpty()) {
                append('[')
                properties.entries.joinTo(this, separator = ",") { "${it.key}=${it.value}" }
                append(']')
            }
        }
        return runCatching {
            // `Registry` is itself a `HolderLookup.RegistryLookup`, so the built-in table is what the
            // parser wants — no registry-access round trip.
            BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, spec, false).blockState()
        }.getOrNull()
    }

    /** A game rule by registry id — the game keeps them in a registry precisely so ids can address them. */
    fun gameRuleById(id: String): GameRule<*>? = identifier(id)
        ?.takeIf { BuiltInRegistries.GAME_RULE.containsKey(it) }
        ?.let { BuiltInRegistries.GAME_RULE.getValue(it) }

    /** The rule's value spelled the way the game spells it, or null for an unregistered id. */
    fun gameRuleValue(rules: GameRules, id: String): String? =
        gameRuleById(id)?.let { rules.getAsString(it) }

    /**
     * Parses [raw] with the rule's own deserializer — so a `Boolean` rule asked for `"3"` is refused rather
     * than guessed at — and writes it through the game, which owns propagating the change to every loaded
     * dimension. The registry hands back a star-projected `GameRule<*>` while the setter is generic in the
     * value type; the erased setter parameter is `Object`, so the cast checks nothing the JVM would.
     */
    @Suppress("UNCHECKED_CAST")
    fun setGameRuleValue(rules: GameRules, server: MinecraftServer, id: String, raw: String): Boolean {
        val rule = gameRuleById(id) ?: return false
        val value = rule.deserialize(raw).result().orElse(null) ?: return false
        rules.set(rule as GameRule<Any>, value, server)
        return true
    }

    fun identifier(id: String): Identifier? = runCatching { Identifier.parse(id) }.getOrNull()

    /**
     * The server this process runs — the dedicated one, or the integrated server of a singleplayer game.
     * Datapack registries, damage types among them, only exist behind a running server.
     */
    fun serverOrNull(): MinecraftServer? =
        (OMLCore.serverInstance as? MinecraftServer)
            ?: runCatching { Minecraft.getInstance().singleplayerServer }.getOrNull()
}

/** The unfiltered entity-type test the game's own level queries take; the filter is the predicate. */
private val ALL_ENTITIES: EntityTypeTest<Entity, Entity> = EntityTypeTest.forClass(Entity::class.java)

internal fun OMLBlockPos.toGame(): BlockPos = BlockPos(x, y, z)
internal fun BlockPos.toApi(): OMLBlockPos = OMLBlockPos(x, y, z)

internal open class EntityWrap(open val entity: Entity) : OMLEntity {
    override val platform: Any get() = entity
    override val typeId: String get() = BuiltInRegistries.ENTITY_TYPE.getKey(entity.type).toString()
    override val name: String get() = entity.name.string
    override val uuid: String get() = entity.stringUUID
    override val position: OMLVec3 get() = entity.position().let { OMLVec3(it.x, it.y, it.z) }
    override val blockPosition: OMLBlockPos get() = entity.blockPosition().let { OMLBlockPos(it.x, it.y, it.z) }
    override val dimensionId: String get() = entity.level().dimension().identifier().toString()
    override val world: OMLWorld get() = GameProjections.world(entity.level())
    override val removed: Boolean get() = entity.isRemoved
    override val onFire: Boolean get() = entity.isOnFire

    override var velocity: OMLVec3
        get() = entity.deltaMovement.let { OMLVec3(it.x, it.y, it.z) }
        set(value) = entity.setDeltaMovement(value.x, value.y, value.z)

    override fun remove(): Boolean {
        entity.remove(Entity.RemovalReason.DISCARDED)
        return entity.isRemoved
    }
}

internal open class LivingWrap(override val entity: LivingEntity) : EntityWrap(entity), OMLLivingEntity {
    override val health: Float get() = entity.health
    override val maxHealth: Float get() = entity.maxHealth
    override val alive: Boolean get() = !entity.isRemoved && entity.health > 0f

    override val effects: List<OMLEffectInstance>
        get() = entity.activeEffects.map {
            OMLEffectInstance(
                BuiltInRegistries.MOB_EFFECT.getKey(it.effect.value()).toString(),
                it.duration,
                it.amplifier,
            )
        }

    override fun hasEffect(effectId: String): Boolean =
        GameProjections.effectHolder(effectId)?.let(entity::hasEffect) ?: false

    override fun applyEffect(effectId: String, durationTicks: Int, amplifier: Int): Boolean =
        GameProjections.effectHolder(effectId)
            ?.let { entity.addEffect(MobEffectInstance(it, durationTicks, amplifier)) }
            ?: false

    override fun removeEffect(effectId: String): Boolean =
        GameProjections.effectHolder(effectId)?.let(entity::removeEffect) ?: false

    override fun heal(amount: Float) {
        entity.heal(amount)
    }

    private fun attributeInstance(attributeId: String): AttributeInstance? =
        GameProjections.attributeHolder(attributeId)?.let(entity::getAttribute)

    override fun attributeValue(attributeId: String): Double? =
        GameProjections.attributeHolder(attributeId)?.let(entity::getAttributeValue)

    override fun attributeBase(attributeId: String): Double? =
        attributeInstance(attributeId)?.baseValue

    override fun setAttributeBase(attributeId: String, value: Double): Boolean {
        val instance = attributeInstance(attributeId) ?: return false
        instance.setBaseValue(value)
        return true
    }

    override fun addAttributeModifier(
        attributeId: String,
        modifierId: String,
        amount: Double,
        operation: String,
    ): Boolean {
        val instance = attributeInstance(attributeId) ?: return false
        val op = GameProjections.attributeOperation(operation) ?: return false
        val location = GameProjections.identifier(modifierId) ?: return false
        instance.addOrReplacePermanentModifier(AttributeModifier(location, amount, op))
        return true
    }

    override fun removeAttributeModifier(attributeId: String, modifierId: String): Boolean {
        val instance = attributeInstance(attributeId) ?: return false
        val location = GameProjections.identifier(modifierId) ?: return false
        return instance.removeModifier(location)
    }

    override fun hurt(damageTypeId: String, amount: Float, attacker: OMLEntity?): Boolean {
        val level = entity.level()
        val serverLevel = level as? ServerLevel ?: return false
        val holder = GameProjections.damageTypeHolder(level, damageTypeId) ?: return false
        val cause = attacker?.platform as? Entity
        val source = if (cause == null) DamageSource(holder) else DamageSource(holder, cause, cause)
        return entity.hurtServer(serverLevel, source, amount)
    }

    override val fireTicks: Int get() = entity.remainingFireTicks

    override fun setOnFire(ticks: Int): Boolean {
        entity.igniteForTicks(ticks)
        return entity.isOnFire
    }

    override fun clearFire(): Boolean {
        val burning = entity.isOnFire
        entity.clearFire()
        return burning && !entity.isOnFire
    }
}

internal class PlayerWrap(override val entity: Player) : LivingWrap(entity), OMLPlayer {
    override val inventory: OMLInventory get() = InventoryWrap(entity.inventory)
    /** The game's own accessor is `@Nullable` on the base class, so a player can be asked before its game type is known. */
    override val gameMode: String? get() = entity.gameMode()?.getName()
    override val mainHandItem: OMLItemStack get() = GameProjections.stack(entity.mainHandItem)
    override val offhandItem: OMLItemStack get() = GameProjections.stack(entity.offhandItem)

    /**
     * Delivered over a server connection, so a client-side player is served through the integrated
     * server's counterpart of the same uuid. With no counterpart (a multiplayer client's local player)
     * there is no channel to write on and the message is dropped.
     */
    override fun sendSystemMessage(text: String) {
        val target: ServerPlayer? =
            (entity as? ServerPlayer) ?: server()?.getPlayerList()?.getPlayer(entity.uuid)
        target?.connection?.send(ClientboundSystemChatPacket(Component.literal(text), false))
    }

    override fun giveItem(itemId: String, count: Int): Boolean {
        val item = GameProjections.itemId(itemId) ?: return false
        return entity.inventory.add(ItemStack(item, count))
    }

    /** Permission sets are only ever server-side; the game's own fallback for an unset player is level 0. */
    override val permissionLevel: Int
        get() = ((entity as? ServerPlayer)?.permissions() as? LevelBasedPermissionSet)?.level()?.id() ?: 0

    /**
     * Server-authoritative, so a client-side player is moved through the integrated server's
     * counterpart of the same uuid; with no counterpart there is nothing to move.
     */
    override fun teleport(x: Double, y: Double, z: Double, dimensionId: String?): Boolean {
        val server = server() ?: return false
        val target = (entity as? ServerPlayer) ?: server.getPlayerList().getPlayer(entity.uuid) ?: return false
        val level = if (dimensionId != null) {
            GameProjections.dimensionKey(dimensionId)?.let(server::getLevel) ?: return false
        } else {
            target.level()
        }
        return target.teleportTo(level, x, y, z, emptySet(), target.yRot, target.xRot, false)
    }

    /**
     * `null` as the excluded listener so the player hears its own sound, and the local playback path
     * for a client-side player, which has no connection to write a packet on.
     */
    override fun playSound(soundId: String, volume: Float, pitch: Float): Boolean {
        val event = GameProjections.soundEvent(soundId) ?: return false
        val level = entity.level()
        when (level) {
            is ServerLevel -> level.playSound(null, entity, event, SoundSource.PLAYERS, volume, pitch)
            is ClientLevel -> level.playLocalSound(entity, event, SoundSource.PLAYERS, volume, pitch)
            else -> return false
        }
        return true
    }

    private fun server(): MinecraftServer? = GameProjections.serverOrNull()
}

internal class InventoryWrap(val inventory: Inventory) : OMLInventory {
    override val size: Int get() = inventory.containerSize
    override val selectedSlot: Int get() = inventory.selectedSlot
    override val selected: OMLItemStack get() = GameProjections.stack(inventory.selectedItem)

    override fun itemAt(slot: Int): OMLItemStack? =
        if (slot in 0 until size) GameProjections.stack(inventory.getItem(slot)) else null

    override fun set(slot: Int, itemId: String, count: Int): Boolean {
        if (slot !in 0 until size) return false
        val stack = stackOf(itemId, count) ?: return false
        inventory.setItem(slot, stack)
        return true
    }

    override fun add(itemId: String, count: Int): Boolean =
        stackOf(itemId, count)?.let { inventory.add(it) } ?: false

    /**
     * Takes from every slot that holds the item, the same way the game's own crafting pickup does; the
     * game's per-slot removal reports how much it actually took, so the returned total is exact even
     * when a stack boundary fell in the middle of the request.
     */
    override fun remove(itemId: String, count: Int): Int {
        val item = GameProjections.itemId(itemId) ?: return 0
        if (count <= 0) return 0
        var missing = count
        for (slot in 0 until size) {
            if (missing == 0) break
            val stack = inventory.getItem(slot)
            if (stack.isEmpty || stack.item !== item) continue
            missing -= inventory.removeItem(slot, missing).count
        }
        return count - missing
    }

    override fun countOf(itemId: String): Int = GameProjections.itemId(itemId)?.let { inventory.countItem(it) } ?: 0

    override fun selectHotbarSlot(slot: Int) {
        if (slot in 0 until GameProjections.hotbarSlots) inventory.selectedSlot = slot
    }

    private fun stackOf(itemId: String, count: Int): ItemStack? =
        GameProjections.itemId(itemId)?.let { if (count > 0) ItemStack(it, count) else ItemStack.EMPTY }
}

internal class StackWrap(val stack: ItemStack) : OMLItemStack {
    override val platform: Any get() = stack

    override val id: String
        get() = if (stack.isEmpty) "" else BuiltInRegistries.ITEM.getKey(stack.item).toString()

    override val count: Int get() = stack.count
    override val isEmpty: Boolean get() = stack.isEmpty
    override val displayName: String get() = stack.itemName.string
    override val damageValue: Int get() = stack.damageValue
}

/**
 * A state read through property *names*. Values come from the game's own `Property.Value` naming, and a
 * rewritten state goes back through the `/setblock` argument parser — both keep the generic
 * `Property<T>` / `setValue` pair out of OML's code, and the parser is what rejects an unknown property
 * or an illegal combination.
 */
internal class BlockStateWrap(private val state: BlockState) : OMLBlockState {
    override val platform: Any get() = state
    override val blockId: String get() = GameProjections.blockKey(state.block)

    override val properties: Map<String, String>
        get() = state.values.toList().associate { it.property().name to it.valueName() }

    override fun value(name: String): String? = properties[name]

    override fun with(name: String, value: String): OMLBlockState? {
        val entries = properties.toMutableMap()
        if (name !in entries) return null
        entries[name] = value
        return GameProjections.parseState(blockId, entries)?.let(::BlockStateWrap)
    }
}

internal class LevelWrap(val level: Level) : OMLWorld {
    override val platform: Any get() = level
    override val dimensionId: String get() = level.dimension().identifier().toString()
    override val isClient: Boolean get() = level.isClientSide

    override fun blockAt(pos: OMLBlockPos): String =
        BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos.toGame()).block).toString()

    override fun blockStateAt(pos: OMLBlockPos): OMLBlockState? {
        val gamePos = pos.toGame()
        if (!level.isLoaded(gamePos)) return null
        return BlockStateWrap(level.getBlockState(gamePos))
    }

    override fun setBlockState(pos: OMLBlockPos, state: OMLBlockState): Boolean {
        val game = state.platform as? BlockState ?: return false
        val gamePos = pos.toGame()
        if (!level.isLoaded(gamePos)) return false
        level.setBlockAndUpdate(gamePos, game)
        return level.getBlockState(gamePos) == game
    }

    override fun setBlock(pos: OMLBlockPos, blockId: String): Boolean {
        val block = GameProjections.blockId(blockId) ?: return false
        return level.setBlockAndUpdate(pos.toGame(), block.defaultBlockState())
    }

    override fun isLoaded(pos: OMLBlockPos): Boolean = level.isLoaded(pos.toGame())

    override fun playSound(
        soundId: String,
        x: Double,
        y: Double,
        z: Double,
        volume: Float,
        pitch: Float,
        category: String,
    ): Boolean {
        val event = GameProjections.soundEvent(soundId) ?: return false
        val source = GameProjections.soundSource(category) ?: return false
        // null: play for everyone in range, the caller asking for no listener to exclude.
        level.playSound(null, x, y, z, event, source, volume, pitch)
        return true
    }

    override val entities: List<OMLEntity>        get() = when (level) {
            is ServerLevel -> level.getEntities(ALL_ENTITIES) { true }.map(GameProjections::entity)
            is ClientLevel -> level.entitiesForRendering().map(GameProjections::entity)
            // A bare `Level` exposes its entity view only through a protected accessor.
            else -> emptyList()
        }

    override fun entitiesOfType(typeId: String): List<OMLEntity> {
        val type = GameProjections.entityType(typeId) ?: return emptyList()
        return when (level) {
            is ServerLevel -> level.getEntities(ALL_ENTITIES) { it.type == type }.map(GameProjections::entity)
            else -> entities.filter { it.typeId == typeId }
        }
    }

    override fun spawnEntity(typeId: String, x: Double, y: Double, z: Double): OMLEntity? {
        val serverLevel = level as? ServerLevel ?: return null
        val type = GameProjections.entityType(typeId) ?: return null
        // COMMAND is the reason `/summon` passes: a spawn a mod asked for, not one the world generated
        // or a mob bred, which is what the game's own despawn and event rules key on.
        val spawned = type.create(serverLevel, EntitySpawnReason.COMMAND) ?: return null
        spawned.setPos(x, y, z)
        return if (serverLevel.addFreshEntity(spawned)) GameProjections.entity(spawned) else null
    }

    override fun spawnLivingEntity(typeId: String, x: Double, y: Double, z: Double): OMLLivingEntity? =
        spawnEntity(typeId, x, y, z) as? OMLLivingEntity

    override fun spawnParticles(
        particleId: String,
        x: Double,
        y: Double,
        z: Double,
        count: Int,
        offsetX: Double,
        offsetY: Double,
        offsetZ: Double,
        speed: Double,
    ): Boolean {
        val serverLevel = level as? ServerLevel ?: return false
        // Only a type that is its own options can be spawned by id: dust, item and block particles carry
        // arguments built per spawn, which a bare registry id cannot express.
        val options = GameProjections.particleType(particleId) as? ParticleOptions ?: return false
        // The game's return value counts the clients that received it, which says nothing about whether
        // the particle was shown — the answer this method gives is about the id and the side.
        serverLevel.sendParticles(options, x, y, z, count, offsetX, offsetY, offsetZ, speed)
        return true
    }
}

internal class ScreenWrap(private val screen: Any) : OMLScreen {
    override val platform: Any get() = screen
    override val screenName: String get() = screen.javaClass.simpleName
}

internal class GuiGraphicsWrap(private val extractor: GuiGraphicsExtractor) : OMLGuiGraphics {
    override val platform: Any get() = extractor
    override val width: Int get() = extractor.guiWidth()
    override val height: Int get() = extractor.guiHeight()

    override fun drawText(text: String, x: Int, y: Int) {
        extractor.text(Minecraft.getInstance().font, text, x, y, 0xFFFFFF)
    }
}
