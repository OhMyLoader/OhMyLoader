package org.ohmyloader.adapter.snapshot

import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import org.ohmyloader.api.content.OMLBlockData
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.*

/**
 * The [OMLBlockData] implementation over a plain map. 26.4-snapshot-2's `ValueInput` cannot enumerate its
 * keys, so the whole store travels under ONE known key as a self-describing binary payload
 * (DataOutputStream frames + Base64): type tag per value, `writeUTF` for every string — a stored
 * key or value may contain any character.
 */
class OMLBlockDataStore : OMLBlockData {

    private val values = LinkedHashMap<String, Any>()
    private var dirty = false

    /** True once since the last call and any value was written — the ticker's save hint. */
    fun consumeDirty(): Boolean {
        val was = dirty
        dirty = false
        return was
    }

    private fun put(key: String, value: Any) {
        values[key] = value
        dirty = true
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> get(key: String, default: T): T = values[key] as? T ?: default

    override fun getInt(key: String, default: Int): Int = get(key, default)
    override fun putInt(key: String, value: Int) = put(key, value)
    override fun getLong(key: String, default: Long): Long = get(key, default)
    override fun putLong(key: String, value: Long) = put(key, value)
    override fun getFloat(key: String, default: Float): Float = get(key, default)
    override fun putFloat(key: String, value: Float) = put(key, value)
    override fun getDouble(key: String, default: Double): Double = get(key, default)
    override fun putDouble(key: String, value: Double) = put(key, value)
    override fun getBoolean(key: String, default: Boolean): Boolean = get(key, default)
    override fun putBoolean(key: String, value: Boolean) = put(key, value)
    override fun getString(key: String): String? = values[key] as? String
    override fun putString(key: String, value: String) = put(key, value)

    fun writeTo(output: ValueOutput) {
        if (values.isEmpty()) return
        output.putString(DATA_KEY, encode())
    }

    fun readFrom(input: ValueInput) {
        decode(input.getStringOr(DATA_KEY, ""))
    }

    /** The payload framing lives here, apart from the game IO, so the roundtrip is unit-testable. */
    fun encode(): String {
        val bytes = ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { out ->
                out.writeInt(values.size)
                values.forEach { [key, value] ->
                    out.writeUTF(key)
                    when (value) {
                        is Boolean -> {
                            out.writeByte(TAG_BOOLEAN); out.writeBoolean(value)
                        }

                        is Int -> {
                            out.writeByte(TAG_INT); out.writeInt(value)
                        }

                        is Long -> {
                            out.writeByte(TAG_LONG); out.writeLong(value)
                        }

                        is Float -> {
                            out.writeByte(TAG_FLOAT); out.writeFloat(value)
                        }

                        is Double -> {
                            out.writeByte(TAG_DOUBLE); out.writeDouble(value)
                        }

                        is String -> {
                            out.writeByte(TAG_STRING); out.writeUTF(value)
                        }
                    }
                }
            }
            buffer.toByteArray()
        }
        return Base64.getEncoder().encodeToString(bytes)
    }

    fun decode(encoded: String) {
        values.clear()
        if (encoded.isEmpty()) return
        DataInputStream(ByteArrayInputStream(Base64.getDecoder().decode(encoded))).use { input ->
            repeat(input.readInt()) {
                val key = input.readUTF()
                when (val tag = input.readByte().toInt()) {
                    TAG_BOOLEAN -> values[key] = input.readBoolean()
                    TAG_INT -> values[key] = input.readInt()
                    TAG_LONG -> values[key] = input.readLong()
                    TAG_FLOAT -> values[key] = input.readFloat()
                    TAG_DOUBLE -> values[key] = input.readDouble()
                    TAG_STRING -> values[key] = input.readUTF()
                    else -> throw IllegalStateException(
                        "corrupt block-entity data: unknown type tag $tag for key [$key] — the saved value is unreadable",
                    )
                }
            }
        }
    }

    private companion object {
        const val DATA_KEY = "oml_data"
        const val TAG_BOOLEAN = 0
        const val TAG_INT = 1
        const val TAG_LONG = 2
        const val TAG_FLOAT = 3
        const val TAG_DOUBLE = 4
        const val TAG_STRING = 5
    }
}
