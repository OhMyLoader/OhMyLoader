package org.ohmyloader.api.world

/**
 * An item stack, read through the game object behind it. Stacks are value-shaped but the game
 * mutates them in place, so a projection always reflects the current contents.
 */
interface OMLItemStack {
    /** The underlying game stack. */
    val platform: Any

    /** Registry id of the item, e.g. `minecraft:iron_ingot`; empty when the stack is empty. */
    val id: String

    val count: Int

    val isEmpty: Boolean

    /** Display name as the game shows it (renamed stacks report their custom name). */
    val displayName: String

    /** Data values are component-based in the modern game, so this reports the damage value only. */
    val damageValue: Int
}

/**
 * A player's main container. Slot indices follow the game's own container layout — for a vanilla
 * player 0..35 are the main inventory (36..39 armor, 40 offhand are separate slots of the same
 * object and are not addressed here).
 *
 * All mutations run on the thread that owns the player; OML hands out no locking of its own.
 */
interface OMLInventory {
    /** Number of addressable main-inventory slots. */
    val size: Int

    /** Hotbar index the player is holding (0-based). */
    val selectedSlot: Int

    /** The stack the player is currently holding. */
    val selected: OMLItemStack

    /** The stack in [slot], or null when the slot is out of range. */
    fun itemAt(slot: Int): OMLItemStack?

    /**
     * Replaces [slot]'s contents with `count` of [itemId]; an unknown id or out-of-range slot does
     * nothing and returns false.
     */
    fun set(slot: Int, itemId: String, count: Int): Boolean

    /**
     * Adds `count` of [itemId], spreading across slots like the game's own pickup path; false when
     * not everything fit — whatever did fit stays in the inventory, as it does for vanilla pickup.
     */
    fun add(itemId: String, count: Int): Boolean

    /** Total count of [itemId] across the addressable slots. */
    fun countOf(itemId: String): Int

    /** Points the hand at hotbar index [slot] (0..8); out-of-range indices do nothing. */
    fun selectHotbarSlot(slot: Int)
}
