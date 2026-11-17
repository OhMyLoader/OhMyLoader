package org.ohmyloader.api

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.entity.BlockEntity
import org.ohmyloader.api.core.content.OMLSlotStore

/**
 * The slot store of a declared machine at [pos], when the block stands there and was declared with
 * a container — the same object the interface renders and the ticker reads. Null otherwise (a
 * plain block, an unloaded position, a machine without slots).
 */
fun ServerLevel.machineSlotsAt(pos: BlockPos): OMLSlotStore? {
    val entity: BlockEntity = getBlockEntity(pos) ?: return null
    return entity as? OMLSlotStore
}
