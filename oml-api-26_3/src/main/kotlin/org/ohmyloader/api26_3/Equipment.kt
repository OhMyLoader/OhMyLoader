package org.ohmyloader.api26_3

import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.ItemStack

/*
 * What a mod can reach without naming an `EquipmentSlot` constant: the name the game gives each slot in its
 * own data (`chest`, `feet`, `offhand`, …), the item ids that go in them, and the inventory index a player's
 * worn stack sits at.
 */

/** The slot the game names [slotName]; null for a name the game does not use. */
fun equipmentSlotOf(slotName: String): EquipmentSlot? =
    EquipmentSlot.values().firstOrNull { it.serializedName.equals(slotName, ignoreCase = true) }

/** The name the game gives this slot — the one `EquipmentSlot.CODEC` encodes, and what the lookups here take. */
val EquipmentSlot.slotName: String get() = serializedName

/** What the game holds in [slotName], or null when the name is not one of the game's slots. */
fun LivingEntity.itemIn(slotName: String): ItemStack? =
    equipmentSlotOf(slotName)?.let(::getItemBySlot)

/**
 * Puts [stack] into [slotName] through the game's own slot write and returns what the slot held before, which
 * the game leaves for the caller to put somewhere; null means nothing was written — an unknown slot name, a
 * slot this entity cannot use, or an item the game says does not belong in it.
 *
 * Whether an item fits a slot is the game's decision (`isEquippableInSlot`, which reads the item's own
 * `Equippable` component), so asking it is what makes a refusal here the same refusal a player gets from the
 * inventory screen. An empty stack always clears a usable slot — that is how the game unequips.
 */
fun LivingEntity.equip(slotName: String, stack: ItemStack): ItemStack? {
    val slot = equipmentSlotOf(slotName) ?: return null
    if (!canUseSlot(slot)) return null
    if (!stack.isEmpty && !isEquippableInSlot(stack, slot)) return null
    val previous = getItemBySlot(slot)
    setItemSlot(slot, stack)
    return previous
}

/** [equip] addressed by registry id; null also when [itemId] is not a registered item. */
fun LivingEntity.equip(slotName: String, itemId: String, count: Int = 1): ItemStack? {
    val item = OMLRegistries.item(itemId) ?: return null
    return equip(slotName, if (count > 0) ItemStack(item, count) else ItemStack.EMPTY)
}

/** Takes whatever is worn in [slotName] off and returns it — empty when the slot was already clear, null for an unknown slot name. */
fun LivingEntity.unequip(slotName: String): ItemStack? = equip(slotName, ItemStack.EMPTY)

/**
 * The index this player container holds [slotName] at — 36..39 for the armour pieces and 40 for the offhand —
 * which is how a mod reads a worn stack out of the player's own `Container`. Null for a slot the container does
 * not address, the main hand among them.
 */
fun Inventory.indexOf(slotName: String): Int? =
    equipmentSlotOf(slotName)?.let { wanted ->
        Inventory.EQUIPMENT_SLOT_MAPPING.entries.firstOrNull { it.value == wanted }?.key
    }
