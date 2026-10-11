package org.ohmyloader.api26_3.event

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import org.ohmyloader.api.event.Event

/**
 * One tick of one level is starting: [level] is the dimension doing it. A server fires this once per
 * loaded dimension per server tick, so a mod's work can be scoped to one world; a client fires it for
 * the level it renders. Singleplayer therefore sees the same dimension twice — once as the integrated
 * `ServerLevel`, once as the rendered `ClientLevel` — which is the pair to tell apart by
 * `level.isClientSide`.
 */
class LevelTickEvent(val level: Level) : Event()

/**
 * A chunk has become a ticking chunk of [level]: its tile entities are live and its block ticks run.
 * [chunk] is the game's own `LevelChunk`, position included (`chunk.pos`), and the dispatch is an
 * observation rather than a decision — the game has already promoted it.
 */
class ChunkStartedTickingEvent(val level: ServerLevel, val chunk: LevelChunk) : Event()

/**
 * A chunk is leaving [level]. The game calls this while the chunk is still readable, so a mod can sweep
 * per-chunk state out of its own tables; it cannot stop the unload.
 */
class ChunkUnloadedEvent(val level: ServerLevel, val chunk: LevelChunk) : Event()

/**
 * A player is about to destroy a block (cancellable). Covers the creative instant break and the
 * survival break that finished. [state] is the block together with its property values, as the level
 * holds it. Cancelling makes the game resync the client's prediction, so the block visibly snaps back
 * — no packet work is left to the mod.
 */
class BlockBreakEvent(
    val player: ServerPlayer,
    val position: BlockPos,
    val state: BlockState,
) : Event() {
    override val cancellable = true
}

/** A player's attack on an entity landed (cancellable; cancelling skips the whole swing). */
class EntityAttackEvent(val player: ServerPlayer, val target: Entity) : Event() {
    override val cancellable = true
}

/**
 * A player right-clicked an entity with an item or an empty hand (cancellable). Cancelling lets
 * vanilla continue as though the entity were not there, so the click falls through to the block
 * behind it.
 */
class EntityInteractEvent(
    val player: ServerPlayer,
    val target: Entity,
    val hand: InteractionHand,
) : Event() {
    override val cancellable = true
}

/**
 * A player right-clicked a block with an item or an empty hand (cancellable). [hit] carries the
 * position and the face that was struck, [state] the block as the server holds it, [stack] what was
 * in [hand] — an empty hand is `ItemStack.EMPTY`. Cancelling fails the click outright, so neither the
 * block's use nor the held item's use runs.
 */
class BlockUsedEvent(
    val player: ServerPlayer,
    val hit: BlockHitResult,
    val state: BlockState,
    val stack: ItemStack,
    val hand: InteractionHand,
) : Event() {
    override val cancellable = true
}

/**
 * A player used the held item without hitting a block or an entity (cancellable) — the click that
 * starts eating, draws a bow or places into open air. [stack] is `ItemStack.EMPTY` for an empty hand;
 * cancelling fails the click outright.
 */
class ItemUsedEvent(
    val player: ServerPlayer,
    val stack: ItemStack,
    val hand: InteractionHand,
) : Event() {
    override val cancellable = true
}

/**
 * Damage is about to be applied to a living entity (cancellable). [amount] starts as what the source
 * computed and a handler may write a new value: the game applies the written number, and the
 * arithmetic that follows in the same call (shield reduction, damage cooldown) runs on it too.
 * Cancelling wins over a rewrite — no damage is applied at all. [source] is the game's own, so the
 * damage type, the true and direct causer and the bypass flags are all readable rather than
 * pre-digested.
 */
class LivingHurtEvent(
    val entity: LivingEntity,
    var amount: Float,
    val source: DamageSource,
) : Event() {
    override val cancellable = true
}

/**
 * A living entity's death routine is starting. Cancelling stops it, which leaves the entity at zero
 * health until the next damage re-runs the check — the same shape as the game's own totem handling,
 * and a mod that keeps an entity alive normally pairs this event with [LivingHurtEvent].
 */
class LivingDiedEvent(
    val entity: LivingEntity,
    val source: DamageSource,
) : Event() {
    override val cancellable = true
}
