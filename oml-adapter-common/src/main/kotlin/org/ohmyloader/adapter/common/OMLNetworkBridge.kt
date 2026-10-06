package org.ohmyloader.adapter.common

import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.multiplayer.ClientPacketListener
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.network.ServerGamePacketListenerImpl
import org.ohmyloader.api.OmlLog
import org.ohmyloader.api.network.OMLNetworkContext
import org.ohmyloader.api.network.OMLNetworkSender
import org.ohmyloader.api.network.OMLPacketBuffer
import org.ohmyloader.api.network.OMLPayloadType
import org.ohmyloader.core.OMLCore
import org.ohmyloader.core.adapter.Refl
import org.ohmyloader.core.network.PayloadDeclarations
import org.ohmyloader.core.network.PayloadDeclarations.Direction
import org.ohmyloader.core.network.PayloadDeclarations.Entry
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 26.3 custom-payload bridge: publishes the declared channels into the game's payload registry, routes
 * inbound payloads to the declaring mod's handler, and sends outbound ones.
 *
 * 26.3 allocates packet ids by registration order (`ProtocolInfoBuilder`), so a mod-added packet type
 * would shift every id after it and desync the vanilla client — mod traffic travels exclusively as
 * `CustomPacketPayload`, which the game routes by channel name over two fixed packet ids.
 */
object OMLNetworkBridge : OMLNetworkSender {

    /** The channels this process actually published — what the E2E gate reads to prove the hook fired. */
    @Volatile
    var publishedChannels: Set<String> = emptySet()
        private set

    private val payloadTypes = ConcurrentHashMap<String, CustomPacketPayload.Type<Envelope<*>>>()

    /**
     * One mod payload on the wire: the channel it was published under plus the value the mod's codec
     * produced (encode) or read back (decode).
     */
    class Envelope<T>(val wireId: String, val value: T) : CustomPacketPayload {
        override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = typeOf(wireId)
    }

    // ---------------------------------------------------------------------------------------------
    // Registration: injected at the head of CustomPacketPayload.codec(FallbackProvider, List)
    // ---------------------------------------------------------------------------------------------

    /**
     * Appends the declared channels to the list the game is about to snapshot into its dispatch map.
     *
     * Only the mutable `ArrayList` form is touched: the config-phase codec of the same method is built
     * from `List.of(…)`, and adding to it would throw inside the payload class's own `<clinit>` — a game
     * bootstrap crash naming neither mod nor channel. A channel name the game already registers (a
     * vanilla one, or another mod's earlier claim of the same id) is skipped with a warning for the same
     * reason: the snapshot is `Collectors.toUnmodifiableMap`, which throws on a duplicate key.
     */
    @JvmStatic
    fun onPayloadRegistration(list: Any?) {
        @Suppress("UNCHECKED_CAST")
        val target = (list as? java.util.ArrayList<*>) as? MutableList<Any> ?: return
        val taken = target.mapNotNull {
            (it as? CustomPacketPayload.TypeAndCodec<*, *>)?.type()?.id()?.toString()
        }.toSet()
        val published = LinkedHashSet(publishedChannels)
        for (entry in PayloadDeclarations.entries) {
            if (entry.wireId in taken) {
                OmlLog.warn(
                    "Network",
                    "channel '${entry.wireId}' from mod [${entry.modId}] collides with an already " +
                        "registered channel and is not published",
                )
                continue
            }
            runCatching {
                target.add(CustomPacketPayload.TypeAndCodec(typeOf(entry.wireId), ChannelCodec(entry)))
                published += entry.wireId
            }.onFailure {
                OmlLog.error("Network", "publishing the channel '${entry.wireId}' failed", it)
            }
        }
        publishedChannels = published
    }

    /** The game's payload-type key for a channel; records compare by identifier, but one instance per channel is cheaper. */
    private fun typeOf(wireId: String): CustomPacketPayload.Type<Envelope<*>> =
        payloadTypes.getOrPut(wireId) { CustomPacketPayload.Type<Envelope<*>>(identifierOf(wireId)) }

    private fun identifierOf(wireId: String): Identifier {
        val (namespace, path) = wireId.split(':', limit = 2)
        return Identifier.fromNamespaceAndPath(namespace, path)
    }

    /**
     * The game-side codec of one channel: it knows its entry, so encode and decode never dispatch by
     * name. It is written against `FriendlyByteBuf` rather than the registry-aware subclass the gameplay
     * codec uses — the payload record takes any supertype, and mod codecs touch no registry lookup.
     */
    private class ChannelCodec(private val entry: Entry) : StreamCodec<FriendlyByteBuf, Envelope<*>> {
        override fun encode(buf: FriendlyByteBuf, value: Envelope<*>) {
            @Suppress("UNCHECKED_CAST")
            (entry.type as OMLPayloadType<Any?>).codec.write(GameBuffer(buf), value.value)
        }

        override fun decode(buf: FriendlyByteBuf): Envelope<*> =
            Envelope(entry.wireId, entry.type.codec.read(GameBuffer(buf)))
    }

    // ---------------------------------------------------------------------------------------------
    // Inbound: injected at the head of each side's custom-payload handler
    // ---------------------------------------------------------------------------------------------

    /** Inbound on the client; true means a mod owns this channel, so vanilla's unknown-payload path is skipped. */
    @JvmStatic
    fun onClientPayload(listener: ClientPacketListener, payload: CustomPacketPayload): Boolean =
        deliverInbound(payload, Direction.SERVER_TO_CLIENT, null, listener)

    /**
     * Inbound on a server. The sender is taken from the listener's own player, never from the payload:
     * a client decides what it sends, not who it is.
     */
    @JvmStatic
    fun onServerPayload(listener: ServerGamePacketListenerImpl, packet: ServerboundCustomPayloadPacket): Boolean =
        deliverInbound(packet.payload(), Direction.CLIENT_TO_SERVER, listener.player?.scoreboardName, listener)

    /**
     * The routing mechanism: an OML envelope arriving in a direction nobody declared is left to vanilla
     * (false), a declared one reaches its handler and swallows a handler failure — a mod that throws on a
     * packet thread must not take the connection down with it. `internal` so the routing is testable
     * without a game listener instance.
     */
    internal fun deliverInbound(
        payload: CustomPacketPayload?,
        direction: Direction,
        senderName: String?,
        platform: Any,
    ): Boolean {
        val envelope = payload as? Envelope<*> ?: return false
        val entry = PayloadDeclarations.handlerFor(envelope.wireId, direction) ?: return false
        val context = OMLNetworkContext(entry.modId, senderName) { platform }
        runCatching { entry.handler(envelope.value, context) }.onFailure {
            OmlLog.error("Network", "payload '${entry.wireId}' handler from mod [${entry.modId}] failed", it)
        }
        return true
    }

    // ---------------------------------------------------------------------------------------------
    // Outbound
    // ---------------------------------------------------------------------------------------------

    override fun <T> toServer(type: OMLPayloadType<T>, value: T) {
        val entry = declared(type, Direction.CLIENT_TO_SERVER)
        val level = Minecraft.getInstance().level
            ?: error("OMLNetwork.sendToServer needs a client that is in a world")
        val listener = clientListener(level)
            ?: error("the client's packet listener is not reachable (26.3 field 'connection' on ClientLevel)")
        listener.send(ServerboundCustomPayloadPacket(Envelope(entry.wireId, value)))
    }

    override fun <T> toPlayer(type: OMLPayloadType<T>, value: T, playerName: String) {
        val entry = declared(type, Direction.SERVER_TO_CLIENT)
        val player = serverPlayers()?.firstOrNull { it.scoreboardName == playerName }
        if (player == null) {
            OmlLog.warn("Network", "no online player named '$playerName' to send '${entry.wireId}' to")
            return
        }
        player.connection.send(ClientboundCustomPayloadPacket(Envelope(entry.wireId, value)))
    }

    override fun <T> toAllPlayers(type: OMLPayloadType<T>, value: T) {
        val entry = declared(type, Direction.SERVER_TO_CLIENT)
        val envelope = Envelope(entry.wireId, value)
        serverPlayers()?.forEach { it.connection.send(ClientboundCustomPayloadPacket(envelope)) }
    }

    /** A channel is sendable only in the direction it was declared with, so a typo names itself. */
    private fun declared(type: OMLPayloadType<*>, direction: Direction): Entry =
        PayloadDeclarations.entryFor(type, direction)
            ?: error(
                "payload channel '${type.id}' has no ${direction.name.lowercase().replace('_', '-')} " +
                    "declaration to send on",
            )

    /**
     * The players of whichever server this process hosts. Only the dedicated side records
     * `OMLCore.serverInstance` — a client never injects the server constructor hook — so singleplayer
     * reaches the integrated server through `Minecraft`, and the cast short-circuits that lookup on a
     * dedicated server, where the `Minecraft` class does not exist.
     */
    private fun serverPlayers(): List<ServerPlayer>? {
        val server = (OMLCore.serverInstance as? MinecraftServer)
            ?: runCatching { Minecraft.getInstance().singleplayerServer }.getOrNull()
        return server?.playerList?.players
    }

    private fun clientListener(level: ClientLevel): ClientPacketListener? = try {
        Refl.fieldOrNull(ClientLevel::class.java, "connection", Any::class.java)?.get(level) as? ClientPacketListener
    } catch (_: Exception) {
        null
    }

    /** Maps OML's primitive surface onto the game's packet buffer; the integers travel as varints. */
    private class GameBuffer(private val buf: FriendlyByteBuf) : OMLPacketBuffer {
        override fun writeBoolean(value: Boolean) {
            buf.writeBoolean(value)
        }

        override fun writeByte(value: Int) {
            buf.writeByte(value)
        }

        override fun writeInt(value: Int) {
            buf.writeVarInt(value)
        }

        override fun writeLong(value: Long) {
            buf.writeVarLong(value)
        }

        override fun writeFloat(value: Float) {
            buf.writeFloat(value)
        }

        override fun writeDouble(value: Double) {
            buf.writeDouble(value)
        }

        override fun writeString(value: String) {
            buf.writeUtf(value)
        }

        override fun writeUuid(value: UUID) {
            buf.writeUUID(value)
        }

        override fun writeBytes(value: ByteArray) {
            buf.writeByteArray(value)
        }

        override fun readBoolean(): Boolean = buf.readBoolean()
        override fun readByte(): Int = buf.readByte().toInt()
        override fun readInt(): Int = buf.readVarInt()
        override fun readLong(): Long = buf.readVarLong()
        override fun readFloat(): Float = buf.readFloat()
        override fun readDouble(): Double = buf.readDouble()
        override fun readString(): String = buf.readUtf()
        override fun readUuid(): UUID = buf.readUUID()
        override fun readBytes(): ByteArray = buf.readByteArray()
    }
}
