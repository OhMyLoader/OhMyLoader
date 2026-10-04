package org.ohmyloader.api.content

/**
 * A persistent key-value store owned by one declared block entity, surviving save/reload. Values
 * are primitives and strings only — everything else belongs in mod code (or the `platform` escape
 * hatch), not in chunk data. Writes mark the block entity dirty for the next autosave.
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
