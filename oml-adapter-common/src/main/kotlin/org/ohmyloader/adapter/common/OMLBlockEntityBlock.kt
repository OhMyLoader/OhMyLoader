package org.ohmyloader.adapter.common

import net.minecraft.core.BlockPos
import net.minecraft.core.NonNullList
import net.minecraft.world.ContainerHelper
import net.minecraft.nbt.NbtUtils
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.Container
import net.minecraft.world.MenuProvider
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import org.ohmyloader.api.content.OMLBlockDataCarrier
import org.ohmyloader.api26_3.OMLRegistries
import org.ohmyloader.api26_3.itemId
import org.ohmyloader.api.content.OMLBlockTickEvent
import org.ohmyloader.api.content.OMLDataItemStack
import org.ohmyloader.api.content.OMLSlotLayout
import org.ohmyloader.api.content.OMLSlotStore

/**
 * The block class a declaration with a block entity materializes into. Extends [OMLBehaviorBlock]
 * so a machine can also carry the step-on / hit hooks; the block entity adds the server ticker,
 * the persistent data store and — when [org.ohmyloader.api.content.OMLBlockEntityDeclaration.slots]
 * was declared — the item container. [entityType] is assigned by materialization right after the
 * type is built — the type's supplier reads it back only when the first block entity is created
 * (a chunk load), long after registration closes, which is what breaks the type/supplier cycle.
 */
class OMLBlockEntityBlock(properties: Properties) : OMLBehaviorBlock(properties), EntityBlock {

    /** The declaration's tick handlers, copied at materialization like the behavior hooks are. */
    var tickHandlers: List<(OMLBlockTickEvent) -> Unit> = emptyList()

    /** The declared slot layout; null when the machine has no container. */
    var slotLayout: OMLSlotLayout? = null

    lateinit var entityType: BlockEntityType<OMLMachineBlockEntity>
        private set

    internal fun attachEntityType(type: BlockEntityType<OMLMachineBlockEntity>) {
        check(!::entityType.isInitialized) { "the block entity type was attached twice" }
        entityType = type
    }

    override fun newBlockEntity(pos: BlockPos, state: BlockState): OMLMachineBlockEntity =
        OMLMachineBlockEntity(entityType, pos, state, slotLayout)

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
            val event = OMLBlockTickEvent(
                pos.x, pos.y, pos.z, machine.blockData, { tickLevel },
            ) { if (machine.slotLayout != null) machine else null }
            handlers.forEach { it(event) }
            // the data store may have been written this tick; a clean store makes this a no-op cost
            if (machine.blockData.consumeDirty()) machine.setChanged()
        }
    }

    /**
     * Breaking the machine drops what its slots hold — cargo belongs to the player who breaks it.
     * The data store is deliberately NOT dropped: it is the machine's internal state (progress,
     * counters), not cargo, and vanilla block entities drop none of theirs either.
     */
    override fun playerWillDestroy(
        level: Level,
        pos: BlockPos,
        state: BlockState,
        player: Player,
    ): BlockState {
        (level.getBlockEntity(pos) as? OMLMachineBlockEntity)?.dropSlots(level, pos)
        return super.playerWillDestroy(level, pos, state, player)
    }
}

/**
 * The block entity behind [OMLBlockEntityBlock]: vanilla save/load plumbing around the mod's
 * persistent [blockData] store and — when a layout was declared — the [slots] container.
 * Implementing [Container] hands the machine to vanilla's whole container economy (the chest
 * interface, hoppers, quick-move); [MenuProvider] is what a right-click opens. [slots] is the
 * mod-facing view over the very same stacks.
 */
class OMLMachineBlockEntity(
    type: BlockEntityType<*>,
    pos: BlockPos,
    state: BlockState,
    val slotLayout: OMLSlotLayout?,
) : BlockEntity(type, pos, state), OMLBlockDataCarrier, Container, MenuProvider, OMLSlotStore {

    /** Typed as the store, not the interface: the ticker needs `consumeDirty`, which is not mod-facing. */
    override val blockData: OMLBlockDataStore = OMLBlockDataStore()

    /**
     * The slot store as the game's own shape: a [NonNullList] of stacks, EMPTY meaning an empty
     * slot — exactly what [ContainerHelper] persists and what every vanilla container expects.
     */
    private val slotStacks: NonNullList<ItemStack> =
        slotLayout?.let { NonNullList.withSize(it.size, ItemStack.EMPTY) } ?: NonNullList.create()

    // ---------- Container: the interface, hoppers and quick-move land here ----------

    override fun getContainerSize(): Int = slotStacks.size

    override fun isEmpty(): Boolean = slotStacks.all { it.isEmpty }

    override fun getItem(index: Int): ItemStack = slotStacks[index]

    override fun removeItem(index: Int, count: Int): ItemStack {
        val taken = ContainerHelper.removeItem(slotStacks, index, count)
        setChanged()
        return taken
    }

    override fun removeItemNoUpdate(index: Int): ItemStack {
        val taken = ContainerHelper.takeItem(slotStacks, index)
        setChanged()
        return taken
    }

    override fun setItem(index: Int, stack: ItemStack) {
        if (index !in slotStacks.indices) return
        slotStacks[index] = stack
        setChanged()
    }

    override fun setChanged() {
        super.setChanged()
        // sync so an open chest interface and the chunk both see the write; a null level (entity
        // not yet placed) is the load path, where the caller syncs instead
        (level as? ServerLevel)?.chunkSource?.blockChanged(blockPos)
    }

    override fun stillValid(player: Player): Boolean = player.level().getBlockEntity(blockPos) === this

    override fun clearContent() {
        for (index in slotStacks.indices) slotStacks[index] = ItemStack.EMPTY
        setChanged()
    }

    // ---------- MenuProvider: what a right-click opens ----------

    override fun getDisplayName(): Component = Component.literal(slotLayout?.title ?: "")

    override fun createMenu(
        containerId: Int,
        playerInventory: Inventory,
        player: Player,
    ): AbstractContainerMenu? {
        val layout = slotLayout ?: return null
        val menuType = when (layout.rows) {
            1 -> MenuType.GENERIC_9x1
            2 -> MenuType.GENERIC_9x2
            3 -> MenuType.GENERIC_9x3
            4 -> MenuType.GENERIC_9x4
            5 -> MenuType.GENERIC_9x5
            else -> MenuType.GENERIC_9x6
        }
        return ChestMenu(menuType, containerId, playerInventory, this, layout.rows)
    }

    // ---------- OMLSlotStore: the mod-facing read/write over the same slots ----------

    override val size: Int get() = slotStacks.size

    override fun stackAt(index: Int): OMLDataItemStack? {
        if (index !in slotStacks.indices) return null
        val stack = slotStacks[index]
        if (stack.isEmpty) return null
        return OMLDataItemStack(stack.itemId, stack.count)
    }

    override fun putStack(index: Int, stack: OMLDataItemStack?): OMLDataItemStack? {
        if (index !in slotStacks.indices) return null
        val previous = stackAt(index)
        slotStacks[index] = stack?.let { declared ->
            val item = OMLRegistries.item(declared.itemId) ?: return null
            ItemStack(item, declared.count)
        } ?: ItemStack.EMPTY
        setChanged()
        return previous
    }

    // ---------- persistence: both stores ride the entity's own save ----------

    override fun saveAdditional(output: ValueOutput) {
        blockData.writeTo(output)
        // the game's own chest persistence: id, count and components all ride along, so an
        // enchanted item in a machine slot comes back out enchanted
        if (slotLayout != null) ContainerHelper.saveAllItems(output, slotStacks)
    }

    override fun loadAdditional(input: ValueInput) {
        blockData.readFrom(input)
        if (slotLayout != null) ContainerHelper.loadAllItems(input, slotStacks)
    }

    /** Drops every held stack where the block stood; called from [playerWillDestroy]. */
    fun dropSlots(level: Level, pos: BlockPos) {
        for (index in slotStacks.indices) {
            val stack = slotStacks[index]
            if (stack.isEmpty) continue
            net.minecraft.world.Containers.dropItemStack(level, pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, stack)
            slotStacks[index] = ItemStack.EMPTY
        }
        setChanged()
    }
}
