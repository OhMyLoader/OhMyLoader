package org.ohmyloader.api.event

import org.ohmyloader.api.world.OMLBlockPos
import org.ohmyloader.api.world.OMLEntity
import org.ohmyloader.api.world.OMLLivingEntity
import org.ohmyloader.api.world.OMLPlayer
import org.ohmyloader.api.wrapper.OMLWorld

/**
 * One tick of one level: [level] is the dimension that is starting its tick. A server fires this once per
 * loaded dimension per server tick, so a mod's work can be scoped to a world instead of to the whole
 * server; a client fires it for the level it renders. Singleplayer therefore sees both — two projections
 * of the same dimension, told apart by [OMLWorld.isClient].
 */
class LevelTickEvent(val level: OMLWorld) : Event()

/**
 * A player is about to destroy a block (cancellable). Covers both creative instant-break and the
 * survival break that finished; cancelling makes the game resync the client's prediction, so the
 * block visibly snaps back — no extra packet work is needed by the mod.
 */
class BlockBreakEvent(
    val player: OMLPlayer,
    val position: OMLBlockPos,
    /** Registry id of the block being destroyed, e.g. `minecraft:stone`. */
    val blockId: String,
) : Event() {
    override val cancellable = true
}

/** A player's attack on an entity landed (cancellable; cancelling skips the whole swing). */
class EntityAttackEvent(val player: OMLPlayer, val target: OMLEntity) : Event() {
    override val cancellable = true
}

/**
 * A player right-clicked an entity with an item or an empty hand (cancellable). [hand] is
 * `main_hand` or `off_hand`; cancelling lets vanilla continue as though the entity were not there,
 * so the click falls through to the block behind it.
 */
class EntityInteractEvent(
    val player: OMLPlayer,
    val target: OMLEntity,
    val hand: String,
) : Event() {
    override val cancellable = true
}

/**
 * A player right-clicked a block with an item or an empty hand (cancellable). [itemId] is the item
 * that was held, `minecraft:air` for an empty hand; [face] is the side of [position] that was hit.
 * Cancelling fails the click outright, so neither the block's use nor the held item's use runs.
 */
class BlockUsedEvent(
    val player: OMLPlayer,
    val position: OMLBlockPos,
    /** Registry id of the block that was clicked, e.g. `minecraft:chest`. */
    val blockId: String,
    val itemId: String,
    /** `main_hand` or `off_hand`. */
    val hand: String,
    /** `up` / `down` / `north` / `south` / `west` / `east`. */
    val face: String,
) : Event() {
    override val cancellable = true
}

/**
 * A player used the held item without hitting a block or an entity (cancellable) — the click that
 * starts eating, draws a bow or places into open air. [itemId] is `minecraft:air` for an empty hand;
 * cancelling fails the click outright.
 */
class ItemUsedEvent(
    val player: OMLPlayer,
    val itemId: String,
    /** `main_hand` or `off_hand`. */
    val hand: String,
) : Event() {
    override val cancellable = true
}

/**
 * Damage is about to be applied to a living entity (cancellable). [amount] starts as what vanilla
 * computed and a handler may write a new value: the game applies the written number and the arithmetic
 * that follows it (shield reduction, damage cooldown) runs on that number too. Cancelling wins over a
 * rewrite — no damage is applied at all.
 */
class LivingHurtEvent(
    val entity: OMLLivingEntity,
    var amount: Float,
    /** Vanilla's damage-type message id, e.g. `inFire` / `generic`. */
    val damageType: String,
    /** The player or mob that dealt the damage, when the source carries one. */
    val attacker: OMLEntity?,
) : Event() {
    override val cancellable = true
}

/**
 * A living entity's death routine is starting. Cancelling stops it, which leaves the entity at zero
 * health until the next damage re-runs the check — the same shape as the game's own totem handling,
 * and a mod that keeps an entity alive normally pairs this event with [LivingHurtEvent].
 */
class LivingDiedEvent(
    val entity: OMLLivingEntity,
    val damageType: String,
) : Event() {
    override val cancellable = true
}
