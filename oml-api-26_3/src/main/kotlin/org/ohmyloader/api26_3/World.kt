package org.ohmyloader.api26_3

import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.Holder
import net.minecraft.core.particles.BlockParticleOption
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.core.particles.ItemParticleOption
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.Container
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.clock.WorldClock
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.entity.EntityTypeTest
import net.minecraft.world.level.block.entity.BlockEntity
import org.ohmyloader.api.content.OMLBlockData
import org.ohmyloader.api.content.OMLBlockDataCarrier

/** Registry id of this dimension, e.g. `minecraft:overworld`. */
val Level.dimensionId: String get() = dimension().identifier().toString()

/**
 * Registry id of the block at [pos]. For an unloaded position the game's own getter answers, which is not a
 * stable value — check [isLoaded] first when the answer has to mean something.
 */
fun Level.blockIdAt(pos: BlockPos): String = BuiltInRegistries.BLOCK.getKey(getBlockState(pos).block).toString()

/** The state at [pos], or null when the chunk is not loaded — the only way to tell an unloaded position
 * apart from one holding air. */
fun Level.blockStateAt(pos: BlockPos): BlockState? = pos.takeIf(::isLoaded)?.let(::getBlockState)

/**
 * Places [blockId]'s default state at [pos] with vanilla's neighbour-update flags, and reports whether the
 * position now holds that block — the game's `setBlock` return value is not it, since the game can answer
 * false for a state it did store. False for an unknown id or an unloaded position. Writes take effect on a
 * server-side level: the client's level holds a prediction the server overwrites.
 */
fun Level.setBlockAt(pos: BlockPos, blockId: String): Boolean {
    val block = OMLRegistries.block(blockId) ?: return false
    if (!isLoaded(pos)) return false
    setBlockAndUpdate(pos, block.defaultBlockState())
    return getBlockState(pos).block == block
}

/**
 * Places [state] at [pos] with vanilla's neighbour updates and client resync, and reports whether the
 * position now holds it. A state read from one position can be placed at another; the game refuses an
 * impossible state rather than storing it, which is why this reads the position back instead of trusting
 * the write.
 */
fun Level.setBlockAt(pos: BlockPos, state: BlockState): Boolean {
    if (!isLoaded(pos)) return false
    setBlockAndUpdate(pos, state)
    return getBlockState(pos) == state
}

/** Registry id of this state's block, e.g. `minecraft:chest`. */
val BlockState.blockId: String get() = BuiltInRegistries.BLOCK.getKey(block).toString()

/** Every property the block defines, with the value this state carries, named the way the game writes them
 * into blockstate JSON. */
val BlockState.propertyValues: Map<String, String>
    get() = values.toList().associate { it.property().name to it.valueName() }

/** The value of property [name], or null when this block has no such property. */
fun BlockState.property(name: String): String? = propertyValues[name]

/**
 * This state with [name] set to [value]; null when the property is not one this block has or [value] is not
 * one of its legal values. It goes back through the game's own `/setblock` parser, so a name that is legal
 * but produces an impossible state answers null too.
 */
fun BlockState.withProperty(name: String, value: String): BlockState? {
    val entries = propertyValues.toMutableMap()
    if (name !in entries) return null
    entries[name] = value
    return OMLRegistries.blockState(blockId, entries)
}

/**
 * The storage at [pos] — chest, barrel, furnace, hopper — or null when nothing there holds slots. Reads and
 * writes reach the block entity the game itself holds, so a player using the same block sees every change.
 */
fun Level.containerAt(pos: BlockPos): Container? = blockEntityAt(pos) as? Container

/**
 * The block entity at [pos], or null when nothing lives there. The position has to be loaded:
 * `Level.getBlockEntity` reaches through `getChunkAt`, which **loads** a chunk the caller never assumed was
 * there, so this answers null for an unloaded position instead of quietly generating one for it.
 */
fun Level.blockEntityAt(pos: BlockPos): BlockEntity? = pos.takeIf(::isLoaded)?.let(::getBlockEntity)

/**
 * The persistent data the declared block entity at [pos] owns, or null when nothing loaded lives there or that
 * block has no declared block entity. This is the store its own ticker writes — one object, reached from
 * outside the tick handler — and it is why the address carries a level: the game holds a block entity per
 * position **per dimension**, so two dimensions with the same coordinates keep two answers.
 */
fun Level.blockDataAt(pos: BlockPos): OMLBlockData? =
    (blockEntityAt(pos) as? OMLBlockDataCarrier)?.blockData

/** The same store addressed by the dimension [dimensionId] names; null when this server has no such level. */
fun MinecraftServer.blockDataAt(dimensionId: String, pos: BlockPos): OMLBlockData? =
    levelById(dimensionId)?.blockDataAt(pos)

/**
 * Plays [soundId] (`minecraft:block.stone.place`) at the position for everyone who can hear it. False for
 * an unregistered id or an unknown [category], which is one of the game's own sound categories in lower
 * case: `master`, `music`, `records`, `weather`, `blocks`, `hostile`, `neutral`, `players`, `ambient`,
 * `voice`, `ui` — it decides which of the client's volume sliders the sound obeys.
 */
fun Level.playSoundAt(
    soundId: String,
    x: Double,
    y: Double,
    z: Double,
    volume: Float = 1f,
    pitch: Float = 1f,
    category: String = "master",
): Boolean {
    val event = OMLRegistries.soundEvent(soundId) ?: return false
    val source = OMLRegistries.soundSource(category) ?: return false
    // null: play for everyone in range, the caller asking for no listener to exclude.
    playSound(null, x, y, z, event, source, volume, pitch)
    return true
}

/**
 * Asks for [raining] (and [thundering]) weather: one server-wide record, which every open-sky dimension then
 * ramps toward. [durationTicks] is how long the game holds the state before its own cycle takes over; 0 lets
 * the game pick, the way `/weather` without a duration does. False for thunder without rain, which the game
 * cannot represent. The level's own `isRaining` follows on the game's next weather tick, not on this call:
 * the game ramps a level toward the state (0.01 per tick) and calls it raining above 0.2, thundering above
 * 0.9, so a fresh write shows up twenty-odd ticks later and thunder about a hundred. Only a dimension with an
 * open sky has weather at all, so neither reads true below ground or in the End.
 */
fun ServerLevel.setWeather(raining: Boolean, thundering: Boolean = raining, durationTicks: Int = 0): Boolean {
    if (thundering && !raining) return false
    // The entry /weather itself calls: it zeroes the clear timer, which would otherwise veto the state
    // on the next weather tick, and a weather time of 0 makes the game sample its own duration.
    server.setWeatherParameters(0, durationTicks.coerceAtLeast(0), raining, thundering)
    return weatherData.isRaining == raining && weatherData.isThundering == thundering
}

/** The clock this dimension runs on, or null when its dimension type names none — the Nether declares none,
 * so nothing there reads or writes time. */
val Level.clock: Holder<WorldClock>? get() = dimensionType().defaultClock().orElse(null)

/** The unfiltered entity-type test the game's own level queries take. */
private val ALL_ENTITIES: EntityTypeTest<Entity, Entity> = EntityTypeTest.forClass(Entity::class.java)

/** Every entity this level holds. The list is a snapshot taken at the call, not a view that follows later
 * changes. */
fun ServerLevel.entities(): List<Entity> = getEntities(ALL_ENTITIES) { true }

/** The entities a client level renders this frame — the client's own view, which lags the server's list. */
fun ClientLevel.renderedEntities(): List<Entity> = entitiesForRendering().toList()

/** The entities whose type registry id is [typeId] (`minecraft:cow`); empty for an unregistered id. */
fun ServerLevel.entitiesOfType(typeId: String): List<Entity> {
    val type = OMLRegistries.entityType(typeId) ?: return emptyList()
    return getEntities(ALL_ENTITIES) { it.type == type }
}

fun ClientLevel.entitiesOfType(typeId: String): List<Entity> =
    renderedEntities().filter { it.typeId == typeId }

/**
 * Spawns [typeId] at the position and returns the game's own entity, or null when the id is not an entity
 * type or the game refused to add it. The entity arrives with no motion; the game's own rules (despawning,
 * fire, suffocation) apply to it from the next tick like they do to a `/summon`ed one.
 */
fun ServerLevel.spawnEntity(typeId: String, x: Double, y: Double, z: Double): Entity? {
    val type = OMLRegistries.entityType(typeId) ?: return null
    // COMMAND is the reason `/summon` passes: a spawn a mod asked for, not one the world generated
    // or a mob bred, which is what the game's own despawn and event rules key on.
    val spawned = type.create(this, EntitySpawnReason.COMMAND) ?: return null
    spawned.setPos(x, y, z)
    return if (addFreshEntity(spawned)) spawned else null
}

/** [spawnEntity] for a type the game makes living; null when the type is not a living one, rather than a
 * healthless stand-in. */
fun ServerLevel.spawnLivingEntity(typeId: String, x: Double, y: Double, z: Double): LivingEntity? =
    spawnEntity(typeId, x, y, z) as? LivingEntity

/**
 * Shows [particleId] (`minecraft:poof`) at the position to every client that can see it; false for an
 * unregistered id or a particle that needs arguments of its own — dust colour, an item stack — which a bare
 * id cannot carry. [count] of 0 means one particle at the exact position, the game's own convention, while a
 * larger count spreads that many through the offset box. [speed] is the game's per-particle parameter, whose
 * meaning belongs to the particle type.
 */
fun ServerLevel.spawnParticles(
    particleId: String,
    x: Double,
    y: Double,
    z: Double,
    count: Int = 0,
    offsetX: Double = 0.0,
    offsetY: Double = 0.0,
    offsetZ: Double = 0.0,
    speed: Double = 0.0,
): Boolean {
    // Only a type that is its own options can be spawned by id.
    val options = OMLRegistries.particleType(particleId) as? ParticleOptions ?: return false
    // The game's return value counts the clients that received it, which says nothing about whether
    // the particle was shown — the answer this method gives is about the id.
    sendParticles(options, x, y, z, count, offsetX, offsetY, offsetZ, speed)
    return true
}

/**
 * The same for a particle that needs arguments of its own, which the bare-id form refuses:
 * [spawnItemParticles] shows [itemId]'s texture breaking, [spawnBlockParticles] shows the default state of
 * [blockId], and [spawnDustParticles] takes a `0xRRGGBB` [color] with a size [scale]. Each answers false for
 * an unregistered id.
 */
fun ServerLevel.spawnItemParticles(itemId: String, x: Double, y: Double, z: Double, count: Int = 0): Boolean {
    val item = OMLRegistries.item(itemId) ?: return false
    // The static ParticleTypes fields are typed by their own options class.
    return sendParticlesHere(ItemParticleOption(ParticleTypes.ITEM, item), x, y, z, count)
}

fun ServerLevel.spawnBlockParticles(blockId: String, x: Double, y: Double, z: Double, count: Int = 0): Boolean {
    val block = OMLRegistries.block(blockId) ?: return false
    return sendParticlesHere(
        BlockParticleOption(ParticleTypes.BLOCK, block.defaultBlockState()), x, y, z, count,
    )
}

fun ServerLevel.spawnDustParticles(
    color: Int,
    scale: Float,
    x: Double,
    y: Double,
    z: Double,
    count: Int = 0,
): Boolean = sendParticlesHere(DustParticleOptions(color, scale), x, y, z, count)

private fun ServerLevel.sendParticlesHere(
    options: ParticleOptions,
    x: Double,
    y: Double,
    z: Double,
    count: Int,
): Boolean {
    sendParticles(options, x, y, z, count, 0.0, 0.0, 0.0, 0.0)
    return true
}
