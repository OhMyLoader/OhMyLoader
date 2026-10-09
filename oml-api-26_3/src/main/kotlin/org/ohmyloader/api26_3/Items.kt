package org.ohmyloader.api26_3

import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.Container
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack

/**
 * Registry id of this stack's item, e.g. `minecraft:iron_ingot`; empty for an empty stack. The game's own
 * [ItemStack.item] answers air for an empty stack, which is a different fact from "there is nothing here".
 */
val ItemStack.itemId: String get() = if (isEmpty) "" else BuiltInRegistries.ITEM.getKey(item).toString()

/**
 * Replaces [slot]'s contents with `count` of [itemId]; false for an unregistered id, a slot outside the
 * container, or a slot the block itself refuses — a furnace's output slot answers false here without anything
 * being written, because the game's own `canPlaceItem` is asked before the write. An empty stack (`count` of 0)
 * clears the slot, which no block refuses.
 *
 * Reports whether the container now holds what was asked, so a stack the game clamped into its own limit reads
 * back clamped rather than as the number that was written.
 */
fun Container.setSlot(slot: Int, itemId: String, count: Int): Boolean {
    val stack = stackOf(itemId, count) ?: return false
    if (slot !in 0 until containerSize) return false
    if (!canPlaceItem(slot, stack)) return false
    setItem(slot, stack)
    setChanged()
    val written = getItem(slot)
    return count <= 0 || (!written.isEmpty && written.item === stack.item && written.count == stack.count)
}

/** `count` taken out of [slot], as the game's own removal reports it; null for a slot that does not exist or a non-positive count. */
fun Container.takeSlot(slot: Int, count: Int): ItemStack? =
    if (slot in 0 until containerSize && count > 0) removeItem(slot, count) else null

/** Total count of [itemId] across the container; 0 for an unregistered id. */
fun Container.countOf(itemId: String): Int = OMLRegistries.item(itemId)?.let(::countItem) ?: 0

/** Whether the block's own rule accepts `count` of [itemId] in [slot]; false for an unregistered id or a slot that does not exist. */
fun Container.canPlace(slot: Int, itemId: String, count: Int): Boolean {
    val stack = stackOf(itemId, count.coerceAtLeast(1)) ?: return false
    return slot in 0 until containerSize && canPlaceItem(slot, stack)
}

/** The players using this block right now. Only players are named: the game's own list also carries anything else that holds the block open. */
val Container.openedByPlayerNames: List<String>
    get() = entitiesWithContainerOpen.mapNotNull { (it as? Player)?.scoreboardName }

/**
 * Adds `count` of [itemId] to the player's container, spreading across slots like the game's own pickup; false
 * when not everything fit — whatever did fit stays in the inventory, as it does for vanilla pickup.
 */
fun Inventory.add(itemId: String, count: Int): Boolean = stackOf(itemId, count)?.let(::add) ?: false

/**
 * Takes up to [count] of [itemId] out of the main container and returns how many came out — fewer than asked
 * when it did not hold that many. Stacks are matched by item id, so components (damage, custom names) do not
 * have to match, and the game's own per-slot removal is what reports the amount.
 */
fun Inventory.remove(itemId: String, count: Int): Int {
    val item = OMLRegistries.item(itemId) ?: return 0
    if (count <= 0) return 0
    var missing = count
    for (slot in 0 until containerSize) {
        if (missing == 0) break
        val stack = getItem(slot)
        if (stack.isEmpty || stack.item !== item) continue
        missing -= removeItem(slot, missing).count
    }
    return count - missing
}

/** Points the hand at hotbar index [slot]; out-of-range indices do nothing. The game's own hotbar test decides which slots are addressable. */
fun Inventory.selectHotbarSlot(slot: Int) {
    if (Inventory.isHotbarSlot(slot)) selectedSlot = slot
}

private fun stackOf(itemId: String, count: Int): ItemStack? =
    OMLRegistries.item(itemId)?.let { if (count > 0) ItemStack(it, count) else ItemStack.EMPTY }
