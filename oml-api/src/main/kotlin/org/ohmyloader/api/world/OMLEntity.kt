package org.ohmyloader.api.world

import org.ohmyloader.api.wrapper.OMLWorld

/**
 * A live entity, projected from the game object behind it. Every member reads the game object
 * directly, so a projection never goes stale: [platform] is the escape hatch for anything OML does
 * not type.
 */
interface OMLEntity {
    /** The underlying game entity. */
    val platform: Any

    /** Registry id of the entity type, e.g. `minecraft:cow`. */
    val typeId: String

    /** Display name as the game renders it. */
    val name: String

    /** Persistent identity of this entity instance. */
    val uuid: String

    val position: OMLVec3

    /** The block the entity stands in. */
    val blockPosition: OMLBlockPos

    /** Registry id of the dimension this entity is in, e.g. `minecraft:overworld`. */
    val dimensionId: String

    val world: OMLWorld

    /** True once the entity has been removed from its level; a removed entity still reads its last position. */
    val removed: Boolean

    val onFire: Boolean
}

/** A living entity: something with health. */
interface OMLLivingEntity : OMLEntity {
    val health: Float

    val maxHealth: Float

    /** Alive means "still present and above zero health"; a dying entity stays alive for its death animation. */
    val alive: Boolean

    /** The effects on this entity right now, with the duration and amplifier the game holds. */
    val effects: List<OMLEffectInstance>

    /** True when [effectId] (`minecraft:poison`) is on this entity; false for an unregistered id. */
    fun hasEffect(effectId: String): Boolean

    /**
     * Applies [effectId] for [durationTicks] at [amplifier] (0 is level I); false when the id is not a
     * registered effect. Re-applying the same effect follows the game's own merge rule — it does not
     * simply replace what is there.
     */
    fun applyEffect(effectId: String, durationTicks: Int, amplifier: Int = 0): Boolean

    /** Removes [effectId]; false when the id is unknown or the entity does not have it. */
    fun removeEffect(effectId: String): Boolean

    /** Restores up to [amount] health, capped at the maximum, exactly as the game's own healing is. */
    fun heal(amount: Float)
}

/**
 * One effect on a living entity. [durationTicks] is the **remaining** time, and [amplifier] is
 * zero-based, so `0` is level I.
 */
data class OMLEffectInstance(
    /** Registry id of the effect, e.g. `minecraft:regeneration`. */
    val typeId: String,
    val durationTicks: Int,
    val amplifier: Int,
)

/** A player, on whichever side the event was dispatched. */
interface OMLPlayer : OMLLivingEntity {
    /** The player's own inventory (main container, 36 slots for a vanilla player). */
    val inventory: OMLInventory

    /**
     * `survival` / `creative` / `adventure` / `spectator`, or null when the game has not given this
     * player a game type — the game's own accessor is nullable, and on a client-side player it can be
     * read before the server has sent one.
     */
    val gameMode: String?

    /** The item in the hand the game treats as main hand. */
    val mainHandItem: OMLItemStack

    val offhandItem: OMLItemStack

    /**
     * Vanilla's permission level: 0 player, 1 moderator, 2 gamemaster, 3 admin, 4 owner. A player the
     * server has no permission set for reports 0.
     */
    val permissionLevel: Int

    /**
     * Shows [text] to this player through its server-side connection. A player with no server
     * counterpart (a purely client-side projection) is a no-op — the message cannot be delivered
     * without one.
     */
    fun sendSystemMessage(text: String)

    /** Puts `count` of [itemId] into the inventory; false when not everything fit. */
    fun giveItem(itemId: String, count: Int): Boolean

    /**
     * Moves the player to [x], [y], [z] — and to [dimensionId] as well when one is given. Movement is
     * server-authoritative, so a client-side player is served through the integrated server's
     * counterpart of the same uuid; false means there was no counterpart to move, an unknown dimension,
     * or the game refused the destination.
     */
    fun teleport(x: Double, y: Double, z: Double, dimensionId: String? = null): Boolean

    /**
     * Plays [soundId] (`minecraft:entity.pig.ambient`) so this player hears it: a packet to that
     * client on a server-side player, a local sound on a client-side one. False for an unregistered id.
     */
    fun playSound(soundId: String, volume: Float = 1f, pitch: Float = 1f): Boolean
}
