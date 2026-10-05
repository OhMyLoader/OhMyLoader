package org.ohmyloader.adapter.v26_3

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.ByteToMessageDecoder
import io.netty.handler.codec.DecoderException
import net.minecraft.network.VarInt
import org.ohmyloader.core.compression.OmlNativeZstd
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * The `"decompress"` side of the packet pipeline, mirroring vanilla's `CompressionDecoder` frame protocol (`[VarInt uncompressedSize][payload]`, `0` = raw passthrough) with one addition: **the payload codec is sniffed, not assumed** — a payload starting with the zstd frame magic (`28 B5 2F FD`) decompresses through libzstd, anything else through Deflate.
 * This is what makes the negotiation race-free: when either side's encoder starts emitting zstd, the peer picks the format up from the bytes on the wire with no ordering guarantee and no switch packet, and a vanilla peer (which never flips) keeps speaking pure Deflate that this handler decodes exactly like vanilla's own. zlib streams cannot collide with the magic: a zlib header's first byte is `0x78` (CMF for a 32 KiB window), zstd's is `0x28`.
 * The declared uncompressed size is checked against the protocol maximum (8 MiB) **before** any buffer is allocated, so a malformed packet costs a `DecoderException`, not an OOM. Direct inbound buffers take the zero-copy path: source enters libzstd as a `MemorySegment` over Netty's pooled native memory, and the inflated bytes land in a pooled direct buffer.
 */
class OmlZstdCompressionDecoder(private var threshold: Int, private var validate: Boolean) : ByteToMessageDecoder() {

    /** 2 MiB — the protocol's largest legitimate packet, and the malformed-packet guard. */
    private val maxUncompressedLength = 8 * 1024 * 1024

    private val inflater = Inflater()

    /**
     * Reusable staging for [decodeZlib]: source buffer (grown to the largest packet seen) and
     * inflate scratch chunk — two allocations for the decoder's lifetime instead of two per
     * packet. Event-loop confined like [inflater], so plain fields have no concurrency.
     */
    private var zlibSource = ByteArray(0)
    private val zlibChunk = ByteArray(8192)

    fun reconfigure(threshold: Int, validate: Boolean) {
        this.threshold = threshold
        this.validate = validate
    }

    override fun decode(ctx: ChannelHandlerContext, input: ByteBuf, out: MutableList<Any>) {
        if (!input.isReadable) return
        val size = VarInt.read(input)
        if (size == 0) {
            out.add(input.readRetainedSlice(input.readableBytes()))
            return
        }
        if (validate && size < threshold) {
            throw DecoderException("Badly compressed packet - size of $size is below threshold of $threshold")
        }
        if (size > maxUncompressedLength) {
            throw DecoderException("Badly compressed packet - size of $size is larger than protocol maximum of $maxUncompressedLength")
        }
        val payload = input.readableBytes()
        if (payload >= 4 && input.getIntLE(input.readerIndex()) == OmlNativeZstd.FRAME_MAGIC_LE) {
            decodeZstd(ctx, input, out, size, payload)
        } else {
            decodeZlib(input, out, size, payload)
        }
    }

    private fun decodeZstd(ctx: ChannelHandlerContext, input: ByteBuf, out: MutableList<Any>, size: Int, payload: Int) {
        if (!OmlNativeZstd.available()) {
            throw DecoderException("received a zstd-compressed packet but the native library failed to load")
        }
        val dst = ctx.alloc().directBuffer(size)
        try {
            val dstSegment = MemorySegment.ofAddress(dst.memoryAddress())
            var n = -1L
            if (input.hasMemoryAddress()) {
                val srcSegment = MemorySegment.ofAddress(input.memoryAddress() + input.readerIndex())
                n = OmlNativeZstd.decompress(dstSegment, size.toLong(), srcSegment, payload.toLong())
                input.skipBytes(payload)
            } else {
                val src = ByteArray(payload)
                input.readBytes(src)
                Arena.ofConfined().use { arena ->
                    val native = arena.allocate(payload.toLong())
                    MemorySegment.copy(src, 0, native, ValueLayout.JAVA_BYTE, 0L, payload)
                    OmlNativeZstd.decompress(dstSegment, size.toLong(), native, payload.toLong()).also { n = it }
                }
            }
            if (OmlNativeZstd.isErrorCode(n)) {
                throw DecoderException("zstd frame failed to inflate: zstd error code $n")
            }
            if (n != size.toLong()) {
                throw DecoderException("zstd frame inflated to $n bytes but the header declared $size")
            }
            out.add(dst.setIndex(0, n.toInt()))
        } catch (t: Throwable) {
            dst.release()
            throw t
        }
    }

    private fun decodeZlib(input: ByteBuf, out: MutableList<Any>, size: Int, payload: Int) {
        if (zlibSource.size < payload) zlibSource = ByteArray(maxOf(payload, 8192))
        input.readBytes(zlibSource, 0, payload)
        inflater.reset()
        inflater.setInput(zlibSource, 0, payload)
        val dst = Unpooled.buffer(size)
        try {
            var total = 0
            while (!inflater.finished()) {
                val n = try {
                    inflater.inflate(zlibChunk)
                } catch (e: DataFormatException) {
                    throw DecoderException("Badly compressed packet - corrupt zlib payload", e)
                }
                if (n == 0 && inflater.needsInput()) {
                    throw DecoderException("Badly compressed packet - truncated zlib payload")
                }
                dst.writeBytes(zlibChunk, 0, n)
                total += n
            }
            if (total != size) {
                throw DecoderException("Badly compressed packet - inflated $total bytes but the header declared $size")
            }
            out.add(dst)
        } catch (t: Throwable) {
            dst.release()
            throw t
        }
    }
}
