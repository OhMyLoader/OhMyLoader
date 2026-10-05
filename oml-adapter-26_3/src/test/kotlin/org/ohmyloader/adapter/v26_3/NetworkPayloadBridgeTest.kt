package org.ohmyloader.adapter.v26_3

import io.netty.buffer.Unpooled
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import org.ohmyloader.api.network.OMLNetworkContext
import org.ohmyloader.api.network.OMLPacketBuffer
import org.ohmyloader.api.network.OMLPayloadCodec
import org.ohmyloader.api.network.OMLPayloadType
import org.ohmyloader.core.network.PayloadDeclarations
import org.ohmyloader.core.network.PayloadDeclarations.Direction
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The payload bridge against the game's own routing: a declared channel is appended to the real list
 * `CustomPacketPayload.codec` snapshots, then encoded and decoded through the resulting dispatch codec, so
 * the identifier framing the game writes and reads is part of the assertion. Channel names are unique per
 * test because the declaration collection is a process-wide singleton.
 */
class NetworkPayloadBridgeTest {

    private data class Point(
        val flag: Boolean,
        val tiny: Int,
        val count: Int,
        val big: Long,
        val ratio: Float,
        val amount: Double,
        val label: String,
        val who: UUID,
        val blob: ByteArray,
    )

    private object PointCodec : OMLPayloadCodec<Point> {
        override fun write(buffer: OMLPacketBuffer, value: Point) {
            buffer.writeBoolean(value.flag)
            buffer.writeByte(value.tiny)
            buffer.writeInt(value.count)
            buffer.writeLong(value.big)
            buffer.writeFloat(value.ratio)
            buffer.writeDouble(value.amount)
            buffer.writeString(value.label)
            buffer.writeUuid(value.who)
            buffer.writeBytes(value.blob)
        }

        override fun read(buffer: OMLPacketBuffer): Point = Point(
            buffer.readBoolean(),
            buffer.readByte(),
            buffer.readInt(),
            buffer.readLong(),
            buffer.readFloat(),
            buffer.readDouble(),
            buffer.readString(),
            buffer.readUuid(),
            buffer.readBytes(),
        )
    }

    private val sample = Point(
        flag = true, tiny = -3, count = 123456, big = 5_000_000_000L,
        ratio = 0.25f, amount = -1.5e9, label = "a payload with ünïcode",
        who = UUID.randomUUID(), blob = byteArrayOf(0, 1, 2, -128, 127),
    )

    private fun declare(
        modId: String,
        id: String,
        direction: Direction = Direction.CLIENT_TO_SERVER,
        handler: (Point, OMLNetworkContext) -> Unit = { _, _ -> },
    ): OMLPayloadType<Point> = OMLPayloadType(id, PointCodec).also {
        PayloadDeclarations.add(modId, it, direction, handler)
    }

    /** Vanilla's own snapshot step, called the way a payload packet class's static init calls it. */
    private fun dispatchOf(list: java.util.ArrayList<Any>): StreamCodec<FriendlyByteBuf, CustomPacketPayload> {
        val unknown = CustomPacketPayload.FallbackProvider<FriendlyByteBuf> { id ->
            error("the test sent a channel nobody registered: $id")
        }
        @Suppress("UNCHECKED_CAST")
        val channels = list as List<CustomPacketPayload.TypeAndCodec<in FriendlyByteBuf, *>>
        return CustomPacketPayload.codec(unknown, channels)
    }

    @Test
    fun `a declared channel travels the game's identifier routing with every field`() {
        declare("rt1", "ping")
        val list = java.util.ArrayList<Any>().apply { add(vanillaBrandEntry()) }
        OMLNetworkBridge.onPayloadRegistration(list)
        assertTrue(
            OMLNetworkBridge.publishedChannels.contains("rt1:ping"),
            "the channel was never published (list size ${list.size})",
        )

        val buf = FriendlyByteBuf(Unpooled.buffer())
        val dispatch = dispatchOf(list)
        dispatch.encode(buf, OMLNetworkBridge.Envelope("rt1:ping", sample))
        val decoded = assertIs<OMLNetworkBridge.Envelope<*>>(dispatch.decode(buf))
        assertEquals("rt1:ping", decoded.wireId)
        val value = assertIs<Point>(decoded.value)
        // The blob is a ByteArray, whose equality is identity — content equality asserted separately.
        assertContentEquals(sample.blob, value.blob)
        assertEquals(sample.copy(blob = value.blob), value)
    }

    @Test
    fun `the immutable config-phase list is left alone`() {
        declare("rt2", "ping")
        val before = OMLNetworkBridge.publishedChannels
        // `List.of(…)` is what the config-phase codec of the same method passes: adding to it would throw
        // inside vanilla's payload class initialization — a bootstrap crash naming neither mod nor channel.
        OMLNetworkBridge.onPayloadRegistration(java.util.List.of(vanillaBrandEntry()))
        assertEquals(before, OMLNetworkBridge.publishedChannels, "an immutable list must be skipped")
    }

    @Test
    fun `a channel the game already registers is skipped rather than duplicated`() {
        declare("minecraft", "brand")
        val list = java.util.ArrayList<Any>().apply { add(vanillaBrandEntry()) }
        // The game's snapshot is Collectors.toUnmodifiableMap, which throws on a duplicate key.
        OMLNetworkBridge.onPayloadRegistration(list)
        val brands = list.count {
            (it as CustomPacketPayload.TypeAndCodec<*, *>).type().id().toString() == "minecraft:brand"
        }
        assertEquals(1, brands, "a channel the game owns must not be published a second time")
        assertFalse(OMLNetworkBridge.publishedChannels.contains("minecraft:brand"))
    }

    @Test
    fun `an inbound payload reaches only the side that declared it`() {
        val received = mutableListOf<Pair<Point, String?>>()
        declare("rt4", "c2s") { value, context -> received += value to context.senderName }

        val envelope = OMLNetworkBridge.Envelope("rt4:c2s", sample)
        assertTrue(OMLNetworkBridge.deliverInbound(envelope, Direction.CLIENT_TO_SERVER, "Steve", "listener"))
        assertEquals(listOf<Pair<Point, String?>>(sample to "Steve"), received)

        received.clear()
        assertFalse(OMLNetworkBridge.deliverInbound(envelope, Direction.SERVER_TO_CLIENT, null, "listener"))
        assertTrue(received.isEmpty(), "a server-bound channel must not reach the client's dispatch")
    }

    @Test
    fun `a payload OML does not own is left to vanilla`() {
        val foreign = object : CustomPacketPayload {
            override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> =
                CustomPacketPayload.Type(Identifier.fromNamespaceAndPath("minecraft", "foreign"))
        }
        assertFalse(OMLNetworkBridge.deliverInbound(foreign, Direction.CLIENT_TO_SERVER, null, "listener"))
        assertFalse(OMLNetworkBridge.deliverInbound(null, Direction.CLIENT_TO_SERVER, null, "listener"))
    }

    @Test
    fun `a handler that throws keeps the connection alive`() {
        declare("rt6", "boom") { _, _ -> error("the mod handler failed") }
        val envelope = OMLNetworkBridge.Envelope("rt6:boom", sample)
        assertTrue(OMLNetworkBridge.deliverInbound(envelope, Direction.CLIENT_TO_SERVER, null, "listener"))
    }

    /** A list element of the shape the game's own list holds, claiming the vanilla brand channel. */
    private fun vanillaBrandEntry(): Any = CustomPacketPayload.TypeAndCodec(
        CustomPacketPayload.Type<CustomPacketPayload>(Identifier.fromNamespaceAndPath("minecraft", "brand")),
        object : StreamCodec<FriendlyByteBuf, CustomPacketPayload> {
            override fun encode(buf: FriendlyByteBuf, value: CustomPacketPayload) {
                buf.writeBoolean(true)
            }

            override fun decode(buf: FriendlyByteBuf): CustomPacketPayload = foreignPayload()
        },
    )

    private fun foreignPayload(): CustomPacketPayload = object : CustomPacketPayload {
        override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> =
            CustomPacketPayload.Type(Identifier.fromNamespaceAndPath("minecraft", "brand"))
    }
}
