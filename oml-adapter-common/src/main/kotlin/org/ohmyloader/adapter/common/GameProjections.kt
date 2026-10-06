package org.ohmyloader.adapter.common

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import org.ohmyloader.api.world.OMLBlockPos
import org.ohmyloader.api.world.OMLEntity
import org.ohmyloader.api.world.OMLInventory
import org.ohmyloader.api.world.OMLItemStack
import org.ohmyloader.api.world.OMLLivingEntity
import org.ohmyloader.api.world.OMLPlayer
import org.ohmyloader.api.world.OMLVec3
import org.ohmyloader.api.wrapper.OMLGuiGraphics
import org.ohmyloader.api.wrapper.OMLScreen
import org.ohmyloader.api.wrapper.OMLWorld
import org.ohmyloader.core.OMLCore

/**
 * Typed projections of game objects for the mod API: every wrapper reads its game object live, so
 * nothing is cached and nothing can go stale. This is the only layer that translates mod-visible game
 * objects, which is why it needs the version jar (`compileOnly`) while `oml-api` does not.
 *
 * Registry ids are the API's currency for blocks and items, and a lookup misses instead of returning a
 * defaulted value: a typo in a mod's id must not silently place air.
 */
internal object GameProjections {
    private const val HOTBAR_SLOTS = 9

    fun entity(entity: Entity): OMLEntity =
        when (entity) {
            is Player -> PlayerWrap(entity)
            is LivingEntity -> LivingWrap(entity)
            else -> EntityWrap(entity)
        }

    fun living(entity: LivingEntity): OMLLivingEntity =
        if (entity is Player) PlayerWrap(entity) else LivingWrap(entity)

    fun world(level: Level): OMLWorld = LevelWrap(level)

    fun stack(stack: ItemStack): OMLItemStack = StackWrap(stack)

    fun screen(screen: Any): OMLScreen = ScreenWrap(screen)

    fun guiGraphics(extractor: GuiGraphicsExtractor): OMLGuiGraphics = GuiGraphicsWrap(extractor)

    val hotbarSlots: Int = HOTBAR_SLOTS

    fun blockId(id: String): Block? = identifier(id)
        ?.takeIf { BuiltInRegistries.BLOCK.containsKey(it) }
        ?.let { BuiltInRegistries.BLOCK.getValue(it) }

    /** Registry key of a block reference, or `minecraft:air`-style empty id when it is unregistered. */
    fun blockKey(block: Block): String = BuiltInRegistries.BLOCK.getKey(block).toString()

    fun itemKey(item: Item): String = BuiltInRegistries.ITEM.getKey(item).toString()

    fun itemId(id: String): Item? = identifier(id)
        ?.takeIf { BuiltInRegistries.ITEM.containsKey(it) }
        ?.let { BuiltInRegistries.ITEM.getValue(it) }

    private fun identifier(id: String): Identifier? = runCatching { Identifier.parse(id) }.getOrNull()
}

internal fun OMLBlockPos.toGame(): BlockPos = BlockPos(x, y, z)

internal fun BlockPos.toApi(): OMLBlockPos = OMLBlockPos(x, y, z)

internal open class EntityWrap(open val entity: Entity) : OMLEntity {
    override val platform: Any get() = entity
    override val typeId: String get() = BuiltInRegistries.ENTITY_TYPE.getKey(entity.type).toString()
    override val name: String get() = entity.name.string
    override val uuid: String get() = entity.stringUUID
    override val position: OMLVec3 get() = entity.position().let { OMLVec3(it.x, it.y, it.z) }
    override val blockPosition: OMLBlockPos get() = entity.blockPosition().let { OMLBlockPos(it.x, it.y, it.z) }
    override val dimensionId: String get() = entity.level().dimension().identifier().toString()
    override val world: OMLWorld get() = GameProjections.world(entity.level())
    override val removed: Boolean get() = entity.isRemoved
    override val onFire: Boolean get() = entity.isOnFire
}

internal open class LivingWrap(override val entity: LivingEntity) : EntityWrap(entity), OMLLivingEntity {
    override val health: Float get() = entity.health
    override val maxHealth: Float get() = entity.maxHealth
    override val alive: Boolean get() = !entity.isRemoved && entity.health > 0f
}

internal class PlayerWrap(override val entity: Player) : LivingWrap(entity), OMLPlayer {
    override val inventory: OMLInventory get() = InventoryWrap(entity.inventory)
    /** The game's own accessor is `@Nullable` on the base class, so a player can be asked before its game type is known. */
    override val gameMode: String? get() = entity.gameMode()?.getName()
    override val mainHandItem: OMLItemStack get() = GameProjections.stack(entity.mainHandItem)
    override val offhandItem: OMLItemStack get() = GameProjections.stack(entity.offhandItem)

    /**
     * Delivered over a server connection, so a client-side player is served through the integrated
     * server's counterpart of the same uuid. With no counterpart (a multiplayer client's local player)
     * there is no channel to write on and the message is dropped.
     */
    override fun sendSystemMessage(text: String) {
        val target: ServerPlayer? =
            (entity as? ServerPlayer) ?: server()?.getPlayerList()?.getPlayer(entity.uuid)
        target?.connection?.send(ClientboundSystemChatPacket(Component.literal(text), false))
    }

    override fun giveItem(itemId: String, count: Int): Boolean {
        val item = GameProjections.itemId(itemId) ?: return false
        return entity.inventory.add(ItemStack(item, count))
    }

    private fun server(): MinecraftServer? =
        (OMLCore.serverInstance as? MinecraftServer)
            ?: runCatching { Minecraft.getInstance().singleplayerServer }.getOrNull()
}

internal class InventoryWrap(val inventory: Inventory) : OMLInventory {
    override val size: Int get() = inventory.containerSize
    override val selectedSlot: Int get() = inventory.selectedSlot
    override val selected: OMLItemStack get() = GameProjections.stack(inventory.selectedItem)

    override fun itemAt(slot: Int): OMLItemStack? =
        if (slot in 0 until size) GameProjections.stack(inventory.getItem(slot)) else null

    override fun set(slot: Int, itemId: String, count: Int): Boolean {
        if (slot !in 0 until size) return false
        val stack = stackOf(itemId, count) ?: return false
        inventory.setItem(slot, stack)
        return true
    }

    override fun add(itemId: String, count: Int): Boolean =
        stackOf(itemId, count)?.let { inventory.add(it) } ?: false

    override fun countOf(itemId: String): Int = GameProjections.itemId(itemId)?.let { inventory.countItem(it) } ?: 0

    override fun selectHotbarSlot(slot: Int) {
        if (slot in 0 until GameProjections.hotbarSlots) inventory.selectedSlot = slot
    }

    private fun stackOf(itemId: String, count: Int): ItemStack? =
        GameProjections.itemId(itemId)?.let { if (count > 0) ItemStack(it, count) else ItemStack.EMPTY }
}

internal class StackWrap(val stack: ItemStack) : OMLItemStack {
    override val platform: Any get() = stack

    override val id: String
        get() = if (stack.isEmpty) "" else BuiltInRegistries.ITEM.getKey(stack.item).toString()

    override val count: Int get() = stack.count
    override val isEmpty: Boolean get() = stack.isEmpty
    override val displayName: String get() = stack.itemName.string
    override val damageValue: Int get() = stack.damageValue
}

internal class LevelWrap(val level: Level) : OMLWorld {
    override val platform: Any get() = level
    override val dimensionId: String get() = level.dimension().identifier().toString()
    override val isClient: Boolean get() = level.isClientSide

    override fun blockAt(pos: OMLBlockPos): String =
        BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos.toGame()).block).toString()

    override fun setBlock(pos: OMLBlockPos, blockId: String): Boolean {
        val block = GameProjections.blockId(blockId) ?: return false
        return level.setBlockAndUpdate(pos.toGame(), block.defaultBlockState())
    }
}

internal class ScreenWrap(private val screen: Any) : OMLScreen {
    override val platform: Any get() = screen
    override val screenName: String get() = screen.javaClass.simpleName
}

internal class GuiGraphicsWrap(private val extractor: GuiGraphicsExtractor) : OMLGuiGraphics {
    override val platform: Any get() = extractor
    override val width: Int get() = extractor.guiWidth()
    override val height: Int get() = extractor.guiHeight()

    override fun drawText(text: String, x: Int, y: Int) {
        extractor.text(Minecraft.getInstance().font, text, x, y, 0xFFFFFF)
    }
}
