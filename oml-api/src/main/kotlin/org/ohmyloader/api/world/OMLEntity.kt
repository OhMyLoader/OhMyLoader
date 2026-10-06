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
}

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
     * Shows [text] to this player through its server-side connection. A player with no server
     * counterpart (a purely client-side projection) is a no-op — the message cannot be delivered
     * without one.
     */
    fun sendSystemMessage(text: String)

    /** Puts `count` of [itemId] into the inventory; false when not everything fit. */
    fun giveItem(itemId: String, count: Int): Boolean
}
