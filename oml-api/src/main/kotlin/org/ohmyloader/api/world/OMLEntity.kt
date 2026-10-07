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

    /**
     * Motion in blocks per tick — the vector the game adds to the position when it moves the entity.
     * Assigning replaces the game's own value, and the game's friction and gravity then work on it.
     *
     * A living entity recomputes this vector from its own movement input every tick, so a write to a mob
     * is integrated on the tick it lands and replaced on the next. A projectile or an item has no such
     * recomputation and keeps the written vector.
     */
    var velocity: OMLVec3

    /**
     * Takes the entity out of its level; false when the game left it in place. Not for players: removing
     * a player entity detaches it from the level while its connection keeps pointing at it.
     */
    fun remove(): Boolean
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

    /**
     * The value the game computes for [attributeId] (`minecraft:max_health`) — base plus modifiers — or
     * null for an unregistered id. Health, speed and attack damage all live in these attributes, and
     * [maxHealth] is this one read back as a float.
     */
    fun attributeValue(attributeId: String): Double?

    /** The value before any modifier, which is the number a mod writes; null for an unregistered id. */
    fun attributeBase(attributeId: String): Double?

    /**
     * Sets the base value; false for an unregistered id. The game clamps the number into the attribute's
     * own range and applies its own consequence (a raised maximum does not refill health, a lowered one
     * caps it), so read [attributeValue] back rather than assuming the write is the answer.
     */
    fun setAttributeBase(attributeId: String, value: Double): Boolean

    /**
     * Adds a permanent modifier under [modifierId] (`my_mod:sword_bonus`) — permanent in the game's own
     * sense: it stays until removed, unlike one the game drops when the attribute recomputes. A modifier
     * already held under that id is replaced. [operation] is one of the game's three, in lower case:
     * `add_value`, `add_multiplied_base`, `add_multiplied_total`. False for an unregistered attribute or
     * an unknown operation.
     */
    fun addAttributeModifier(attributeId: String, modifierId: String, amount: Double, operation: String): Boolean

    /** Removes the modifier held under [modifierId]; false when this attribute carries no such modifier. */
    fun removeAttributeModifier(attributeId: String, modifierId: String): Boolean

    /**
     * Deals [amount] damage of [damageTypeId] (`minecraft:cactus`, `minecraft:campfire`, a mod's own type)
     * through the game's own damage path, crediting [attacker] as the entity behind it when given; false
     * when the id is not a registered damage type, the entity is not in a server-side level, or the game
     * applied nothing.
     *
     * The damage arrives through the same hook an attack does, so a `LIVING_HURT` handler sees it, can
     * cancel it and can rewrite the number — this is a gameplay action, not a way around the rules.
     */
    fun hurt(damageTypeId: String, amount: Float, attacker: OMLEntity? = null): Boolean

    /** Ticks of fire the game has left to burn on this entity; it counts the number down each tick. */
    val fireTicks: Int

    /**
     * Sets the entity alight for [ticks] (60 is three seconds); false when the game refused, which is what
     * a fire-immune entity answers. The verdict is the game's own burning flag after the request, not the
     * call's return.
     */
    fun setOnFire(ticks: Int): Boolean

    /** Takes the fire off; false when the game already reported the entity as unlit. */
    fun clearFire(): Boolean
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
