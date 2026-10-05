package org.ohmyloader.adapter.v26_3

import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import org.ohmyloader.api.content.OMLBlockHitEvent
import org.ohmyloader.api.content.OMLStepOnEvent

/**
 * The block class a declaration with behavior hooks materializes into: the overrides translate
 * the vanilla callback args into the OML event and dispatch to the mod's handlers, then always
 * run the vanilla super — a behavior block is a plain Block plus hooks, never a replacement of
 * vanilla behavior. Dispatch is unguarded like [org.ohmyloader.api.event.Events] firing: a
 * handler exception surfaces as the game crash it is. Open because the block-entity variant
 * ([OMLBlockEntityBlock]) extends it with the ticker and the persistent data store.
 */
open class OMLBehaviorBlock(properties: Properties) : Block(properties) {

    var stepOnHandlers: List<(OMLStepOnEvent) -> Unit> = emptyList()
    var hitHandlers: List<(OMLBlockHitEvent) -> Unit> = emptyList()

    override fun stepOn(level: Level, pos: BlockPos, state: BlockState, entity: Entity) {
        if (stepOnHandlers.isNotEmpty()) {
            val event = OMLStepOnEvent(pos.x, pos.y, pos.z, level.isClientSide, { level }, { entity })
            stepOnHandlers.forEach { it(event) }
        }
        super.stepOn(level, pos, state, entity)
    }

    override fun attack(state: BlockState, level: Level, pos: BlockPos, player: Player) {
        if (hitHandlers.isNotEmpty()) {
            val event = OMLBlockHitEvent(pos.x, pos.y, pos.z, level.isClientSide, { level }, { player })
            hitHandlers.forEach { it(event) }
        }
        super.attack(state, level, pos, player)
    }
}
