package org.ohmyloader.adapter.v26_3

import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.util.AttributeKey
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl
import net.minecraft.network.Connection
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket
import net.minecraft.network.protocol.login.ServerboundCustomQueryAnswerPacket
import net.minecraft.network.protocol.login.custom.CustomQueryAnswerPayload
import net.minecraft.network.protocol.login.custom.CustomQueryPayload
import net.minecraft.resources.Identifier
import net.minecraft.server.network.ServerLoginPacketListenerImpl
import org.ohmyloader.api.OmlLog
import org.ohmyloader.core.adapter.Refl

/**
 * The `oml:zstd` packet-compression negotiation and the hook handlers that implement it, over a login custom query. A vanilla client answers unknown login queries with an **empty** answer (`DiscardedQueryAnswerPayload`), guaranteed protocol behavior — so the query costs a vanilla client one round trip and nothing else; an OML client intercepts the query (head of `ClientHandshakePacketListenerImpl.handleCustomQuery`) and answers with the 4-byte `OMLZ` payload instead.
 * The server recognizes its own query by transaction id **and** payload bytes; the payload bytes need a read-path return-value rewrite because vanilla's `ServerboundCustomQueryAnswerPacket.readPayload` *discards* answer bytes (`skipBytes`) — and 26.3 passes the *transaction id* into `readPayload`'s `size` parameter, which must never be used as a length.
 * No ordering hazard: both sides install OML's handlers at `Connection.setupCompression` unconditionally, and both handlers are **format-agnostic** — the decoder sniffs the zstd frame magic per frame, the encoder emits Deflate until the negotiated flag flips. Whatever order "query sent / answered / compression enabled" lands in, the first zstd frame is decoded by a peer that recognizes it, and a peer that never negotiated keeps seeing pure Deflate, byte-identical to vanilla.
 */
object OmlZstdNetwork {

    /**
     * "OML" as the login-query transaction id. Vanilla servers send no custom login queries of
     * their own, so this id cannot collide with anything on the wire.
     */
    const val QUERY_ID = 0x4F4D4C

    /**
     * The negotiation channel: `oml:zstd`. Lazy on purpose: constructing an `Identifier` drags in
     * brigadier (a game *library* jar, not on the codec test's classpath), and this object must be
     * initializable wherever only the codec classes are present.
     */
    val CHANNEL: Identifier by lazy { Identifier.fromNamespaceAndPath("oml", "zstd") }

    /** Set on the channel the moment this connection's peer proved to be OML. Internal: tests flip it to exercise the negotiated path. */
    internal val NEGOTIATED: AttributeKey<Boolean> = AttributeKey.valueOf("oml-zstd-negotiated")
    private val QUERIED: AttributeKey<Boolean> = AttributeKey.valueOf("oml-zstd-queried")
    private val COMPRESSION_READY: AttributeKey<Boolean> = AttributeKey.valueOf("oml-zstd-compression-ready")

    /**
     * The transaction id of a query the client has accepted but not yet answered. The answer MUST
     * wait for the client's own compression setup: vanilla enables packet compression on the server
     * right after the hello, so an immediately-answered query arrives inside a window where the
     * server already decodes compressed frames while the client was still writing raw ones —
     * guaranteed `Failed to decode packet`. Held here and flushed by
     * [onCompressionReady] once both ends' framing is symmetric.
     */
    private val PENDING_ANSWER: AttributeKey<Int> = AttributeKey.valueOf("oml-zstd-pending-answer")

    /** `OMLZ` — the answer payload an OML client writes where vanilla would write nothing. */
    private val ANSWER_BYTES = byteArrayOf(0x4F, 0x4D, 0x4C, 0x5A)
    private val ANSWER_MAGIC_LE = (ANSWER_BYTES[3].toInt() and 0xFF shl 24) or
        (ANSWER_BYTES[2].toInt() and 0xFF shl 16) or
        (ANSWER_BYTES[1].toInt() and 0xFF shl 8) or
        (ANSWER_BYTES[0].toInt() and 0xFF)

    private val QUERY_PAYLOAD: CustomQueryPayload by lazy {
        object : CustomQueryPayload {
            override fun id(): Identifier = CHANNEL
            override fun write(buf: FriendlyByteBuf) {}
        }
    }
    private val CLIENT_ANSWER: CustomQueryAnswerPayload by lazy {
        CustomQueryAnswerPayload { buf -> buf.writeBytes(ANSWER_BYTES) }
    }

    /**
     * Returned from the read-path rewrite when the answer bytes carry the magic; the server's
     * query-answer hook checks this instance by identity. Its `write` is never called (the server
     * never re-sends an answer).
     */
    private val SERVER_MARKER: CustomQueryAnswerPayload by lazy {
        CustomQueryAnswerPayload { }
    }

    fun isNegotiated(ctx: ChannelHandlerContext): Boolean =
        java.lang.Boolean.TRUE == ctx.channel().attr(NEGOTIATED).get()

    // -----------------------------------------------------------------------------------------
    // Hook handlers (wired by NetworkCompressionTransformer)
    // -----------------------------------------------------------------------------------------

    /** Tail of `ServerLoginPacketListenerImpl.handleHello`: probe the client. */
    @JvmStatic
    fun onServerHello(listener: ServerLoginPacketListenerImpl) {
        val conn = connection(listener) ?: return
        val channel = channel(conn) ?: return
        if (java.lang.Boolean.TRUE == channel.attr(QUERIED).get()) return
        channel.attr(QUERIED).set(true)
        conn.send(ClientboundCustomQueryPacket(QUERY_ID, QUERY_PAYLOAD))
    }

    /**
     * Head of `ServerLoginPacketListenerImpl.handleCustomQueryPacket` (**cancellable**): recognize
     * the OML answer. 26.3's vanilla body is *unconditionally* `disconnect(UNEXPECTED_QUERY)` —
     * verified with `javap`, any answer to any login query gets kicked — so this hook MUST cancel
     * the body for every answer to our own query id: a `SERVER_MARKER` payload means OML peer
     * (negotiate), anything else (a vanilla client's empty answer) means zlib fallback. Answers to
     * ids we never sent stay uncanceled and keep vanilla's kick semantics.
     */
    @JvmStatic
    fun onServerQueryAnswer(
        listener: ServerLoginPacketListenerImpl,
        packet: ServerboundCustomQueryAnswerPacket,
    ): Boolean {
        if (packet.transactionId() != QUERY_ID) return false
        if (packet.payload() === SERVER_MARKER) {
            val conn = connection(listener)
            val channel = conn?.let { channel(it) }
            if (channel != null && java.lang.Boolean.TRUE != channel.attr(NEGOTIATED).get()) {
                channel.attr(NEGOTIATED).set(true)
                OmlLog.info("OMLNetwork", "Client negotiated Zstd packet compression (oml:zstd)")
            }
        }
        return true
    }

    /**
     * Return-value rewrite of `ServerboundCustomQueryAnswerPacket.readPayload`. **`size` is a
     * trap**: `javap` on 26.3 shows `read()` passes the *transaction id* into `readPayload`'s
     * `int` parameter, and `readPayload` delegates to `readUnknownPayload`, which skips
     * `readableBytes()` — so at return the reader index sits at the buffer's end and the wire
     * payload (`[present flag][OMLZ]`, written via `writeNullable`) ends exactly at
     * `writerIndex`. Slice the magic off the buffer's tail, never off `readerIndex - size`
     * (which is a negative index and explodes inside the packet decoder).
     */
    @JvmStatic
    fun onServerReadQueryAnswer(
        original: CustomQueryAnswerPayload,
        size: Int,
        buf: FriendlyByteBuf,
    ): CustomQueryAnswerPayload {
        if (buf.writerIndex() >= ANSWER_BYTES.size &&
            buf.getIntLE(buf.writerIndex() - ANSWER_BYTES.size) == ANSWER_MAGIC_LE
        ) {
            return SERVER_MARKER
        }
        return original
    }

    /**
     * Head of `ClientHandshakePacketListenerImpl.handleCustomQuery` (cancellable): answer our
     * query with the `OMLZ` marker and short-circuit vanilla, which would otherwise also emit an
     * empty answer for the unknown channel — two answers to one query would corrupt the stream.
     */
    @JvmStatic
    fun onClientQuery(listener: ClientHandshakePacketListenerImpl, packet: ClientboundCustomQueryPacket): Boolean {
        if (packet.payload().id() != CHANNEL) return false
        val conn = connection(listener)
        if (conn != null) {
            val channel = channel(conn)
            // If compression is already configured (query arrived late), answer at once — the
            // negotiated encoder emits zstd and the server sniffs it. Otherwise, hold the answer:
            // vanilla's own empty answer must still be suppressed, so cancel regardless.
            if (channel != null && java.lang.Boolean.TRUE == channel.attr(COMPRESSION_READY).get()) {
                conn.send(ServerboundCustomQueryAnswerPacket(packet.transactionId(), CLIENT_ANSWER))
            } else channel?.attr(PENDING_ANSWER)?.set(packet.transactionId())
            channel?.attr(NEGOTIATED)?.set(true)
        }
        return true
    }

    /**
     * Head of `Connection.setupCompression` (cancellable): when OML's handlers are already in the
     * pipeline, vanilla's "instanceof CompressionEncoder" check would fall through to its add
     * branch and hit a duplicate pipeline name — reconfigure ours and skip the body instead.
     */
    @JvmStatic
    fun onCompressionConfigured(conn: Connection, threshold: Int, validate: Boolean): Boolean {
        if (threshold < 0) return false
        val pipeline = channel(conn)?.pipeline() ?: return false
        val encoder = pipeline.get("compress")
        if (encoder !is OmlZstdCompressionEncoder) return false
        encoder.reconfigure(threshold)
        (pipeline.get("decompress") as? OmlZstdCompressionDecoder)?.reconfigure(threshold, validate)
        return true
    }

    /**
     * Tail of `Connection.setupCompression`: vanilla just installed its zlib handlers — replace
     * them in place. OML's handlers are installed for every compression-enabled connection: with a
     * peer that never negotiated they are byte-identical to vanilla, so this is invisible to
     * vanilla peers and instantly ready for OML ones. Threshold -1 (compression disabled, e.g. the
     * integrated server's default) never reaches a vanilla handler install and is guarded anyway.
     */
    @JvmStatic
    fun onCompressionReady(conn: Connection, threshold: Int, validate: Boolean) {
        if (threshold < 0) return
        val channel = channel(conn) ?: return
        val pipeline = channel.pipeline()
        if (pipeline.get("compress") is OmlZstdCompressionEncoder) return
        pipeline.replace("compress", "compress", OmlZstdCompressionEncoder(threshold))
        pipeline.replace("decompress", "decompress", OmlZstdCompressionDecoder(threshold, validate))
        channel.attr(COMPRESSION_READY).set(true)
        val negotiated = java.lang.Boolean.TRUE == channel.attr(NEGOTIATED).get()
        // Flush the answer that was waiting for this exact moment (see PENDING_ANSWER): the
        // negotiated encoder now frames it as zstd and the server's decoder sniffs it cleanly.
        channel.attr(PENDING_ANSWER).getAndSet(null)?.let { transactionId ->
            conn.send(ServerboundCustomQueryAnswerPacket(transactionId, CLIENT_ANSWER))
        }
        if (negotiated) {
            if (System.getProperty("oml.side") == "server") {
                OmlLog.info("OMLNetwork", "Zstd packet compression active for ${channel.remoteAddress()}")
            } else {
                OmlLog.info("OMLNetwork", "Negotiated Zstd packet compression with server!")
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Reflection: Connection's channel, and a listener's Connection
    //
    // Tolerant by design: a missing field degrades to "no negotiation on this connection", never
    // to a disconnect. The cached tolerant lookup is core Refl's (the single shared reflection
    // implementation per its contract) — miss results are cached, so a shape change costs one
    // failed lookup per (type, name) instead of one per packet.
    // -----------------------------------------------------------------------------------------

    private fun channel(conn: Connection): Channel? = try {
        Refl.fieldOrNull(conn.javaClass, "channel", Connection::class.java)?.get(conn) as Channel?
    } catch (_: Exception) {
        null
    }

    private fun connection(listener: Any): Connection? = try {
        Refl.fieldOrNull(listener.javaClass, "connection", Any::class.java)?.get(listener) as Connection?
    } catch (_: Exception) {
        null
    }
}
