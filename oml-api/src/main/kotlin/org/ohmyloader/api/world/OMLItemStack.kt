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

    /**
     * Takes up to [count] of [itemId] out of the addressable slots and returns how many came out —
     * fewer than asked when the container did not hold that many. Stacks are matched by item id, so
     * components (damage, custom names) do not have to match.
     */
    fun remove(itemId: String, count: Int): Int

    /** Points the hand at hotbar index [slot] (0..8); out-of-range indices do nothing. */
    fun selectHotbarSlot(slot: Int)
}

/**
 * Storage a block holds — the game's own slot container behind a chest, furnace, hopper or barrel. Slot
 * indices are the container's own layout, which each block defines: a furnace has five slots and its
 * output slot refuses anything the game would not put there.
 *
 * Every read goes to the block entity as the game holds it, so a player opening the block, a hopper
 * moving items, or a write from this API are all the same state; nothing is cached between calls.
 */
interface OMLContainer {
    /** The underlying game container (a `Container`, usually the block entity itself). */
    val platform: Any

    /** Number of slots this storage addresses. */
    val size: Int

    val isEmpty: Boolean

    /** The stack in [slot], or null when the slot is out of range. An occupied-but-empty slot is not possible: the game keeps air out. */
    fun itemAt(slot: Int): OMLItemStack?

    /**
     * Replaces [slot]'s contents with `count` of [itemId] and marks the storage dirty the way the game
     * does after a change; false for an unknown id, an out-of-range slot, or a slot the storage's own
     * rules refuse. A stack larger than the item's own limit is clamped by the game, not by OML.
     */
    fun setSlot(slot: Int, itemId: String, count: Int): Boolean

    /**
     * Takes up to [count] out of [slot] through the game's own removal and reports what came out — null
     * for an out-of-range slot, an empty projection when the slot held nothing.
     */
    fun takeSlot(slot: Int, count: Int): OMLItemStack?

    /** Total count of [itemId] across the storage, counted the way the game counts it. */
    fun countOf(itemId: String): Int

    /**
     * Whether this storage would accept `count` of [itemId] in [slot] — the game's own check, which is how
     * a furnace's output slot and a hopper's filter answer no without anyone writing to them.
     */
    fun canPlace(slot: Int, itemId: String, count: Int): Boolean

    /** Scoreboard names of the players the game counts as using this storage right now. */
    val openedBy: List<String>
}
