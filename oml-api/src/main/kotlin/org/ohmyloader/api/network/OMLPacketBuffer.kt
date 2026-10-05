package org.ohmyloader.api.network

/**
 * The wire form of a payload: [OMLPayloadCodec.write] and [OMLPayloadCodec.read] consume the same
 * sequence positionally, so the two sides must write and read fields in the same order. The integers
 * use compact (variable-length) framing; nothing here names a version-internal type.
 */
interface OMLPacketBuffer {
    fun writeBoolean(value: Boolean)
    fun writeByte(value: Int)
    fun writeInt(value: Int)
    fun writeLong(value: Long)
    fun writeFloat(value: Float)
    fun writeDouble(value: Double)
    fun writeString(value: String)
    fun writeUuid(value: java.util.UUID)

    /** Length-prefixed: the array length travels with it, so [readBytes] needs no argument. */
    fun writeBytes(value: ByteArray)

    fun readBoolean(): Boolean
    fun readByte(): Int
    fun readInt(): Int
    fun readLong(): Long
    fun readFloat(): Float
    fun readDouble(): Double
    fun readString(): String
    fun readUuid(): java.util.UUID
    fun readBytes(): ByteArray
}
