package org.ohmyloader.api.content

/**
 * A persistent key-value store owned by one declared block entity, surviving save/reload. Values
 * are primitives, strings and [data item stacks][OMLDataItemStack] — anything richer belongs in the
 * block entity's own code, not in chunk data. Writes mark the block entity dirty for the next
 * autosave.
 */
interface OMLBlockData {
    fun getInt(key: String, default: Int = 0): Int
    fun putInt(key: String, value: Int)

    fun getLong(key: String, default: Long = 0L): Long
    fun putLong(key: String, value: Long)

    fun getFloat(key: String, default: Float = 0f): Float
    fun putFloat(key: String, value: Float)

    fun getDouble(key: String, default: Double = 0.0): Double
    fun putDouble(key: String, value: Double)

    fun getBoolean(key: String, default: Boolean = false): Boolean
    fun putBoolean(key: String, value: Boolean)

    /** `null` when absent (a string's sensible default is rarely the empty string). */
    fun getString(key: String): String?
    fun putString(key: String, value: String)

    /** `null` when absent. */
    fun getItemStack(key: String): OMLDataItemStack?
    fun putItemStack(key: String, value: OMLDataItemStack)

    /** Whether [key] holds a value; a stored default and an absent key are not the same thing. */
    fun hasKey(key: String): Boolean

    /** Drops [key], answering false when nothing was stored under it. */
    fun remove(key: String): Boolean

    /** Every key this store holds, in the order the values were first written. */
    fun keys(): Set<String>
}

/**
 * An item stack as block-entity data: the item's registry id (`minecraft:iron_ingot`) and a count.
 * Deliberately component-free — block data is for simple, declared items (a machine's raw materials
 * and products), not for enchanted or otherwise decorated gear; a machine that must hold those needs
 * its own container, which the data store deliberately is not.
 */
data class OMLDataItemStack(val itemId: String, val count: Int) {
    init {
        require(count >= 1) { "a data item stack holds at least one item, got $count" }
    }
}

/**
 * What a declared block entity exposes: its persistent [blockData]. The game owns the block entity, so
 * this is how code outside the block's own ticker reaches the very store the ticker writes — the two views
 * are one object, not a copy of it.
 */
interface OMLBlockDataCarrier {
    val blockData: OMLBlockData
}

/**
 * One game tick of a declared block entity, server side only — the ticker is registered for the
 * server level, so client copies never see this event. [data] is persistent: whatever the handler
 * writes survives save/reload, which is what turns a ticking block into a machine.
 */
class OMLBlockTickEvent(
    val x: Int,
    val y: Int,
    val z: Int,
    val data: OMLBlockData,
    levelSupplier: () -> Any,
) {
    val level: Any by lazy(levelSupplier)
}

/** Declaration body of [OMLBlockDeclaration.blockEntity]. */
class OMLBlockEntityDeclaration {

    // Public but not mod-facing API: the version adapter reads this to wire the server ticker.
    val tickHandlers = mutableListOf<(OMLBlockTickEvent) -> Unit>()

    /** Runs once per game tick while the block's chunk is loaded, server side only. */
    fun tick(handler: (OMLBlockTickEvent) -> Unit) {
        tickHandlers += handler
    }
}
