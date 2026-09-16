package org.ohmyloader.adapter.v26_3

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.DecoderException
import org.ohmyloader.core.compression.NativeTestLibraries
import org.ohmyloader.core.compression.OmlNativeZstd
import kotlin.random.Random
import kotlin.test.*

/**
 * EmbeddedChannel round-trips for the packet codec: framing parity with vanilla, the negotiated
 * zstd path (including the direct-memory zero-copy route), the decoder's format sniffing across a
 * mid-stream flip, and the malformed-packet guard.
 */
class ZstdPacketCodecTest {

    init {
        // The codec drives the real oml-native library; skipped (not failed) when the zig build output is absent.
        org.junit.jupiter.api.Assumptions.assumeTrue(NativeTestLibraries.ensureOmlNative(), "oml-native not built")
    }

    private fun channel(negotiated: Boolean, threshold: Int = 256): EmbeddedChannel =
        EmbeddedChannel(
            OmlZstdCompressionDecoder(threshold, validate = true),
            OmlZstdCompressionEncoder(threshold),
        ).apply {
            if (negotiated) attr(OmlZstdNetwork.NEGOTIATED).set(true)
        }

    /** Deterministic pseudo-random bytes — incompressible, so zlib round-trips must stay exact. */
    private fun noisy(size: Int, seed: Int = 42): ByteBuf {
        val buf = Unpooled.buffer(size)
        val rng = Random(seed)
        repeat(size) { buf.writeByte(rng.nextInt() and 0xFF) }
        return buf.slice(0, size)
    }

    /** Chunk-like data: long runs and structure — highly compressible, so zstd must actually shrink it. */
    private fun chunkLike(size: Int, seed: Int = 0): ByteBuf {
        val buf = Unpooled.buffer(size)
        while (buf.writerIndex() < size) {
            buf.writeByte((buf.writerIndex() + seed) % 7)
            buf.writeZero(minOf(64, size - buf.writerIndex()))
            buf.writeShort((buf.writerIndex() + seed) and 0xFFFF)
        }
        return buf.slice(0, size)
    }

    private fun readVarInt(buf: ByteBuf): Int {
        var value = 0
        var shift = 0
        while (true) {
            val b = buf.readByte().toInt()
            value = value or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) return value
            shift += 7
        }
    }

    private fun roundTrip(ch: EmbeddedChannel, message: ByteBuf): ByteBuf {
        assertTrue(ch.writeOutbound(message.retainedSlice()))
        val wire = ch.readOutbound<ByteBuf>()
        assertTrue(ch.writeInbound(wire.retain()), "decoder must emit exactly one message")
        val decoded = ch.readInbound<ByteBuf>()
        ch.releaseOutbound()
        ch.releaseInbound()
        return decoded
    }

    // ---------------------------------------------------------------------------

    @Test
    fun `a small packet passes through raw and vanilla-framed`() {
        val ch = channel(negotiated = true) // even negotiated, below threshold is raw
        val message = noisy(100)
        assertTrue(ch.writeOutbound(message.retainedSlice()))
        val wire = ch.readOutbound<ByteBuf>()
        assertEquals(0, readVarInt(wire), "sub-threshold packets must be declared uncompressed (VarInt 0)")
        assertEquals(message, wire, "sub-threshold payload must be byte-identical on the wire")
        val decoded = roundTrip(ch, message)
        assertEquals(message, decoded)
    }

    @Test
    fun `an un-negotiated medium packet deflates like vanilla`() {
        val ch = channel(negotiated = false)
        val message = noisy(2048)
        assertTrue(ch.writeOutbound(message.retainedSlice()))
        val wire = ch.readOutbound<ByteBuf>()
        val declared = readVarInt(wire)
        assertEquals(2048, declared)
        assertEquals(0x78, wire.getByte(wire.readerIndex()).toInt() and 0xFF, "zlib CMF byte must be 0x78")
        assertNotEquals(
            wire.getIntLE(wire.readerIndex()),
            OmlNativeZstd.FRAME_MAGIC_LE,
            "an un-negotiated connection must never emit zstd"
        )
        assertEquals(message, roundTrip(ch, message))
    }

    @Test
    fun `repeated zlib packets of growing sizes stay byte-exact through one channel`() {
        // The zlib paths reuse per-handler staging buffers grown to the largest packet seen —
        // the round trip must stay byte-exact across growth and never leak stale bytes from a
        // previous, larger packet (same handler instances, sizes alternating up and down).
        val ch = channel(negotiated = false)
        val sizes = listOf(64, 8192, 2048, 65536, 4096, 8192)
        sizes.forEachIndexed { i, size ->
            val message = noisy(size, seed = i)
            assertEquals(message, roundTrip(ch, message), "packet $i of $size bytes must round-trip exactly")
        }
    }

    @Test
    fun `a negotiated medium packet is zstd-framed and round-trips`() {
        val ch = channel(negotiated = true)
        val message = chunkLike(4096)
        assertTrue(ch.writeOutbound(message.retainedSlice()))
        val wire = ch.readOutbound<ByteBuf>()
        assertEquals(4096, readVarInt(wire))
        assertEquals(0x28, wire.getByte(wire.readerIndex()).toInt() and 0xFF, "zstd magic first byte")
        assertEquals(
            OmlNativeZstd.FRAME_MAGIC_LE,
            wire.getIntLE(wire.readerIndex()),
            "a negotiated connection must emit zstd frames",
        )
        assertTrue(wire.readableBytes() < 4096, "chunk-like data must actually compress")
        assertEquals(message, roundTrip(ch, message))
    }

    @Test
    fun `a negotiated multi-megabyte packet round-trips through the direct-memory path`() {
        val ch = channel(negotiated = true)
        val message = chunkLike(4 * 1024 * 1024)
        val directMessage = Unpooled.directBuffer(message.readableBytes()).writeBytes(message.duplicate())

        // Direct source -> the encoder's zero-copy path; the decoder's input is direct too, so the
        // decompression reads straight out of pooled native memory on both sides.
        assertTrue(ch.writeOutbound(directMessage))
        val wire = ch.readOutbound<ByteBuf>()
        assertEquals(4 * 1024 * 1024, readVarInt(wire.duplicate()))
        assertTrue(ch.writeInbound(wire.retain()))
        val decoded = ch.readInbound<ByteBuf>()
        assertEquals(message, decoded)
        ch.releaseOutbound()
        ch.releaseInbound()
    }

    @Test
    fun `the decoder sniffs a mid-stream format flip`() {
        val ch = channel(negotiated = false)
        val first = chunkLike(2048)
        assertTrue(ch.writeOutbound(first.retainedSlice()))
        val zlibWire = ch.readOutbound<ByteBuf>()

        // The flag flips (the peer answered the query mid-connection); the encoder starts emitting
        // zstd without any switch packet, and the same decoder must keep up by sniffing.
        ch.attr(OmlZstdNetwork.NEGOTIATED).set(true)
        val second = chunkLike(2048, seed = 7)
        assertTrue(ch.writeOutbound(second.retainedSlice()))
        val zstdWire = ch.readOutbound<ByteBuf>()

        val zstdHeader = zstdWire.duplicate()
        assertEquals(2048, readVarInt(zstdHeader))
        assertEquals(OmlNativeZstd.FRAME_MAGIC_LE, zstdHeader.getIntLE(zstdHeader.readerIndex()))

        assertTrue(ch.writeInbound(zlibWire.retain()))
        assertEquals(first, ch.readInbound())
        assertTrue(ch.writeInbound(zstdWire.retain()))
        assertEquals(second, ch.readInbound())
        ch.releaseOutbound()
        ch.releaseInbound()
    }

    @Test
    fun `a declared size above the protocol maximum is rejected before any allocation`() {
        val ch = channel(negotiated = true)
        val malicious = Unpooled.buffer().apply {
            // VarInt for 9 MiB: uncompressed size beyond the 8 MiB guard, followed by junk.
            var v = 9 * 1024 * 1024
            while (v and 0x7F.inv() != 0) {
                writeByte((v and 0x7F) or 0x80)
                v = v ushr 7
            }
            writeByte(v)
            writeIntLE(OmlNativeZstd.FRAME_MAGIC_LE)
        }
        assertFailsWith<DecoderException> { ch.writeInbound(malicious) }
    }
}
