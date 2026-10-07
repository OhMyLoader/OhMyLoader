package org.ohmyloader.api.wrapper

import org.ohmyloader.api.world.OMLBlockPos
import org.ohmyloader.api.world.OMLBlockState
import org.ohmyloader.api.world.OMLClock
import org.ohmyloader.api.world.OMLEntity
import org.ohmyloader.api.world.OMLLivingEntity

/**
 * Wrapper for version-specific game objects. These wrapper
 * interfaces form the OML API "stable layer": consistent semantics,
 * small interfaces, growing with the event domains; [platform]
 * serves as the escape hatch — any blocked mod can reach the underlying native
 * object, so OML never imposes a capability ceiling.
 */
interface OMLScreen {
    /** The underlying game object (a `Screen`). */
    val platform: Any

    /** Registry-independent identity: the class name the game instantiated, e.g. `InventoryScreen`. */
    val screenName: String
}

/**
 * A world — the client's rendered level or a server-side level, depending on where the event was
 * dispatched (see [isClient]). Block queries always work; block writes only take effect on a
 * server-side world, because the client's level holds a prediction the server would overwrite.
 */
interface OMLWorld {
    /** The underlying game object (a `ClientLevel` or `ServerLevel`). */
    val platform: Any

    /** Registry id of this dimension, e.g. `minecraft:overworld`. */
    val dimensionId: String

    val isClient: Boolean

    /**
     * Registry id of the block at [pos], e.g. `minecraft:stone`. For a position that is not loaded this
     * is whatever the game's own getter answers, which is not a stable value — check [isLoaded] first
     * when the answer has to mean something.
     */
    fun blockAt(pos: OMLBlockPos): String

    /**
     * Places [blockId] at [pos] with vanilla's neighbour-update flags; false when the id is unknown
     * or the position is not loaded.
     */
    fun setBlock(pos: OMLBlockPos, blockId: String): Boolean

    /**
     * The state at [pos], block id and property values together, or null when the position is not loaded.
     * Reading through [blockAt] reports only the id; two chests facing different ways are the same id.
     */
    fun blockStateAt(pos: OMLBlockPos): OMLBlockState?

    /**
     * Places [state] at [pos] with vanilla's neighbour updates and client resync, and reports whether the
     * position now holds it. A state read from one position can be placed at another; the game refuses an
     * impossible state rather than storing it, which is why this reads the position back instead of
     * trusting the write.
     */
    fun setBlockState(pos: OMLBlockPos, state: OMLBlockState): Boolean

    /**
     * Whether the chunk holding [pos] is loaded — the only thing that tells an unloaded position apart
     * from one that genuinely holds air, since [blockAt] answers the game's own getter either way.
     */
    fun isLoaded(pos: OMLBlockPos): Boolean

    /**
     * Plays [soundId] (`minecraft:block.stone.place`) at a position for everyone who can hear it. False
     * for an unregistered id or an unknown [category], which is one of the game's own sound categories
     * in lower case: `master`, `music`, `records`, `weather`, `blocks`, `hostile`, `neutral`, `players`,
     * `ambient`, `voice`, `ui` — it decides which of the client's volume sliders the sound obeys.
     */
    fun playSound(
        soundId: String,
        x: Double,
        y: Double,
        z: Double,
        volume: Float = 1f,
        pitch: Float = 1f,
        category: String = "master",
    ): Boolean

    /**
     * The entities this level holds: a server level's live list, or the entities a client level renders.
     * The list is a snapshot taken at the call, not a view that follows later changes.
     */
    val entities: List<OMLEntity>

    /** The entities whose type registry id is [typeId] (`minecraft:cow`); empty for an unregistered id. */
    fun entitiesOfType(typeId: String): List<OMLEntity>

    /**
     * Spawns [typeId] at the position and returns the entity, or null when the id is not an entity type,
     * this is a client-side level — spawning is server-authoritative — or the game refused to add it.
     * The entity arrives with no motion; the game's own rules (despawning, fire, suffocation) apply to it
     * from the next tick like they do to a `/summon`ed one.
     */
    fun spawnEntity(typeId: String, x: Double, y: Double, z: Double): OMLEntity?

    /**
     * [spawnEntity] for a type the game makes living: the handle carries health, attributes and fire.
     * Null when the id is not a registered entity type, this is a client-side level, or the type is not a
     * living one — a projectile answers null here rather than being handed over as a healthless entity.
     */
    fun spawnLivingEntity(typeId: String, x: Double, y: Double, z: Double): OMLLivingEntity?

    /**
     * Shows [particleId] (`minecraft:poof`) at the position to every client that can see it; false for an
     * unregistered id, a client-side level (the game sends particles from the server), or a particle that
     * needs arguments of its own — dust colour, an item stack — which a bare id cannot carry. [count] of 0
     * means one particle at the exact position, the game's own convention, while a larger count spreads
     * that many through the offset box. [speed] is the game's per-particle parameter, whose meaning
     * belongs to the particle type.
     */
    fun spawnParticles(
        particleId: String,
        x: Double,
        y: Double,
        z: Double,
        count: Int = 0,
        offsetX: Double = 0.0,
        offsetY: Double = 0.0,
        offsetZ: Double = 0.0,
        speed: Double = 0.0,
    ): Boolean

    /**
     * The same for a particle that needs arguments of its own, which is what the bare-id overload refuses:
     * [spawnItemParticles] shows [itemId]'s texture breaking, [spawnBlockParticles] shows the default
     * state of [blockId], and [spawnDustParticles] takes a `0xRRGGBB` [color] with a size [scale]. Each
     * answers false for an unregistered id or a client-side level.
     */
    fun spawnItemParticles(itemId: String, x: Double, y: Double, z: Double, count: Int = 0): Boolean

    fun spawnBlockParticles(blockId: String, x: Double, y: Double, z: Double, count: Int = 0): Boolean

    fun spawnDustParticles(color: Int, scale: Float, x: Double, y: Double, z: Double, count: Int = 0): Boolean

    /**
     * Whether this dimension's weather reads as raining / thundering. The game ramps a level toward the
     * state [setWeather] asks for, 0.01 per tick, and calls it raining above 0.2 and thundering above 0.9
     * — so a fresh write shows up twenty-odd ticks later, thunder about a hundred. Thunder is scaled by
     * the rain level, and the game only lets a dimension with an open sky have weather at all (the End is
     * excluded by name), so neither reads true below ground or in a dimension without one.
     */
    val isRaining: Boolean

    val isThundering: Boolean

    /**
     * Asks for [raining] (and [thundering]) weather: one server-wide record, which every open-sky
     * dimension then ramps toward. [durationTicks] is how long the game holds the state before its own
     * cycle takes over; 0 lets the game pick, the way `/weather` without a duration does. False on a
     * client-side level, which does not own the weather, and for thunder without rain, which the game
     * cannot represent. The level's live read follows on the game's next weather tick, not on this call.
     */
    fun setWeather(raining: Boolean, thundering: Boolean = raining, durationTicks: Int = 0): Boolean

    /**
     * The clock this dimension runs on, or null when its dimension type names none — the Nether has no
     * clock, so nothing there reads or writes time.
     */
    val clock: OMLClock?
}

/** HUD draw-surface wrapper, handed out by [org.ohmyloader.api.event.HudRenderEvent]. */
interface OMLGuiGraphics {
    /** The underlying game object (a `GuiGraphicsExtractor`). */
    val platform: Any

    /** Width of the window this frame draws into, in GUI units. */
    val width: Int

    /** Height of the window this frame draws into, in GUI units. */
    val height: Int

    /** Draws [text] at a GUI-space position with vanilla's chat colour codes honoured. */
    fun drawText(text: String, x: Int, y: Int)
}
