package org.ohmyloader.adapter.common

import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import org.ohmyloader.api.content.OMLBlockTickEvent

/**
 * The block class a declaration with a block entity materializes into. Extends [OMLBehaviorBlock]
 * so a machine can also carry the step-on / hit hooks; the block entity adds the server ticker and
 * the persistent data store. [entityType] is assigned by materialization right after the type is
 * built — the type's supplier reads it back only when the first block entity is created (a chunk
 * load), long after registration closes, which is what breaks the type/supplier cycle.
 */
class OMLBlockEntityBlock(properties: Properties) : OMLBehaviorBlock(properties), EntityBlock {

    /** The declaration's tick handlers, copied at materialization like the behavior hooks are. */
    var tickHandlers: List<(OMLBlockTickEvent) -> Unit> = emptyList()

    lateinit var entityType: BlockEntityType<OMLMachineBlockEntity>
        private set

    internal fun attachEntityType(type: BlockEntityType<OMLMachineBlockEntity>) {
        check(!::entityType.isInitialized) { "the block entity type was attached twice" }
        entityType = type
    }

    override fun newBlockEntity(pos: BlockPos, state: BlockState): OMLMachineBlockEntity =
        OMLMachineBlockEntity(entityType, pos, state)

    /**
     * Server side only: the machine tick is a simulation concept, and a client-side ticker would
     * run it twice with divergent state. Returning null is vanilla's way of having no ticker.
     */
    override fun <T : BlockEntity> getTicker(
        level: Level,
        state: BlockState,
        type: BlockEntityType<T>,
    ): BlockEntityTicker<T>? {
        val handlers = tickHandlers
        if (handlers.isEmpty() || type !== entityType || level.isClientSide) return null
        return BlockEntityTicker { tickLevel, pos, _, blockEntity ->
            val machine = blockEntity as OMLMachineBlockEntity
            val event = OMLBlockTickEvent(pos.x, pos.y, pos.z, machine.data) { tickLevel }
            handlers.forEach { it(event) }
            // the data store may have been written this tick; a clean store makes this a no-op cost
            if (machine.data.consumeDirty()) machine.setChanged()
        }
    }
}

/**
 * The block entity behind [OMLBlockEntityBlock]: vanilla save/load plumbing around the mod's
 * persistent [data] store, no behavior of its own.
 */
class OMLMachineBlockEntity(
    type: BlockEntityType<*>,
    pos: BlockPos,
    state: BlockState,
) : BlockEntity(type, pos, state) {

    val data = OMLBlockDataStore()

    override fun saveAdditional(output: ValueOutput) {
        data.writeTo(output)
    }

    override fun loadAdditional(input: ValueInput) {
        data.readFrom(input)
    }
}
