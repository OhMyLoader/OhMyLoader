package org.ohmyloader.api.event

import org.ohmyloader.api.world.OMLBlockPos
import org.ohmyloader.api.world.OMLEntity
import org.ohmyloader.api.world.OMLLivingEntity
import org.ohmyloader.api.world.OMLPlayer

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
 * Damage is about to be applied to a living entity (cancellable). [amount] is what vanilla computed
 * before the event — cancelling prevents the damage entirely, while changing the number is not
 * supported (the event cannot rewrite a parameter it only reads).
 */
class LivingHurtEvent(
    val entity: OMLLivingEntity,
    val amount: Float,
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
