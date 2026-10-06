package org.ohmyloader.adapter.common

import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.MessageToByteEncoder
import net.minecraft.network.VarInt
import org.ohmyloader.api.OmlLog
import org.ohmyloader.core.compression.OmlNativeZstd
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.util.zip.Deflater

/**
 * The `"compress"` side of the packet pipeline: frames one packet as `[VarInt uncompressedSize][payload]`, exactly like vanilla's `CompressionEncoder`, and picks the payload codec per packet: payload < threshold (or threshold disabled) → `VarInt(0)` + raw bytes, vanilla-identical; negotiated OML peer + native available → **Zstd level 1**, compressed **in place** when the outbound buffer is direct (source and destination both enter libzstd as `MemorySegment`s over their native addresses, no heap copy); otherwise (vanilla peer, or native failed to load) → Deflate, byte-compatible with vanilla — a peer that never negotiated cannot tell this handler from vanilla's own.
 * The negotiated flag lives on the [io.netty.channel.Channel] (see [OmlZstdNetwork.NEGOTIATED]) and may flip at any moment: the peer's decoder sniffs the zstd frame magic, so the format can change mid-stream without any synchronization. Level 1 is the network choice — the latency profile (chunk data, entity sync at tick rate) prefers throughput over the last few percent of ratio, which is where levels 3+ start earning their CPU.
 */
class OmlZstdCompressionEncoder(private var threshold: Int) : MessageToByteEncoder<ByteBuf>() {

    /** Compression level for the network path: zstd's fastest tier, tuned for latency. */
    private val level = 1

    /**
     * Reused across packets: construction + `end()` pay a native init/teardown per packet, while
     * `reset()` restores a clean state. Encode runs on the channel's event-loop thread, so a plain
     * field has no concurrency; if a packet fails mid-stream, the next `reset()` clears it.
     */
    private val deflater = Deflater()

    /** Scratch buffer for [encodeZlib]; reused for the same reason as [deflater]. */
    private val deflateChunk = ByteArray(8192)

    /**
     * Source staging buffer for [encodeZlib], grown as packet sizes demand — one reusable
     * allocation instead of one per packet. Event-loop confined like [deflater], so a plain field
     * has no concurrency; growth keeps the largest packet seen, so steady-state traffic stops
     * allocating after the first big packet.
     */
    private var zlibSource = ByteArray(0)

    fun reconfigure(threshold: Int) {
        this.threshold = threshold
    }

    override fun encode(ctx: ChannelHandlerContext, msg: ByteBuf, out: ByteBuf) {
        try {
            encodeTracked(ctx, msg, out)
        } catch (t: Throwable) {
            // Vanilla only surfaces exception.toString() on disconnect — print the stack so lazy
            // classloading/IO failures inside the first zstd encode stay visible.
            OmlLog.error("OMLZstd", "encode failed on ${ctx.channel()}", t)
            throw t
        }
    }

    private fun encodeTracked(ctx: ChannelHandlerContext, msg: ByteBuf, out: ByteBuf) {
        val plain = msg.readableBytes()
        // threshold in 0..plain reads as "enabled and reached" — a negative threshold (compression
        // disabled) can never sit inside the range, so the raw path covers it for free.
        val compressible = threshold in 0..plain
        VarInt.write(out, if (compressible) plain else 0)
        if (!compressible) {
            out.writeBytes(msg)
            return
        }
        if (OmlZstdNetwork.isNegotiated(ctx) && OmlNativeZstd.available()) {
            encodeZstd(ctx, msg, out, plain)
        } else {
            encodeZlib(msg, out, plain)
        }
    }

    private fun encodeZstd(ctx: ChannelHandlerContext, msg: ByteBuf, out: ByteBuf, plain: Int) {
        val cap = OmlNativeZstd.bound(plain.toLong())
        val dst = ctx.alloc().directBuffer(cap.toInt())
        try {
            // Zero-length segment = raw address holder for the downcall (the linker reads only the
            // address; sizes travel as their own arguments).
            val dstSegment = MemorySegment.ofAddress(dst.memoryAddress())
            val n = if (msg.hasMemoryAddress()) {
                // Direct buffer: the zero-copy path — libzstd reads straight out of Netty's pooled
                // native memory at [readerIndex, readerIndex + plain) and writes straight into the
                // pooled destination. No byte[] materializes. The reusable-context variant drops
                // the per-call CCtx create/destroy, the packet path's fixed overhead.
                val srcSegment = MemorySegment.ofAddress(msg.memoryAddress() + msg.readerIndex())
                OmlNativeZstd.compressReusable(dstSegment, cap, srcSegment, plain.toLong(), level)
            } else {
                // Composite / heap buffer: heap segments cannot cross a downcall, so copy once into
                // a confined arena — still no heap round-trip through the library.
                val src = ByteArray(plain)
                msg.readBytes(src)
                Arena.ofConfined().use { arena ->
                    val native = arena.allocate(plain.toLong())
                    MemorySegment.copy(src, 0, native, ValueLayout.JAVA_BYTE, 0L, plain)
                    OmlNativeZstd.compressReusable(dstSegment, cap, native, plain.toLong(), level)
                }
            }
            if (OmlNativeZstd.isErrorCode(n)) {
                throw IllegalStateException("oml_zstd_compress failed with zstd error code $n")
            }
            out.writeBytes(dst, 0, n.toInt())
        } finally {
            dst.release()
        }
    }

    private fun encodeZlib(msg: ByteBuf, out: ByteBuf, plain: Int) {
        if (zlibSource.size < plain) zlibSource = ByteArray(maxOf(plain, 8192))
        msg.readBytes(zlibSource, 0, plain)
        deflater.reset()
        deflater.setInput(zlibSource, 0, plain)
        deflater.finish()
        while (!deflater.finished()) {
            val n = deflater.deflate(deflateChunk)
            out.writeBytes(deflateChunk, 0, n)
        }
    }
}
