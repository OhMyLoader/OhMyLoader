package org.ohmyloader.core.compression

import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import kotlin.random.Random
import kotlin.test.*

/**
 * Direct validation of the five `oml_zstd_*` exports through [OmlNativeZstd] — the acceptance
 * surface of the self-owned native library. Both performance-critical payload shapes are driven
 * through real compress/decompress calls: an NBT-like byte sequence (the region-file path) and a
 * Netty-packet-shaped buffer (the network path, exercised through the very MemorySegment-over-
 * direct-memory shape the encoder uses).
 */
class OmlNativeZstdTest {

    init {
        org.junit.jupiter.api.Assumptions.assumeTrue(NativeTestLibraries.ensureOmlNative(), "oml-native not built")
    }

    @Test
    fun `the five C exports round trip a byte sequence`() {
        val random = Random(2026)
        val payload = ByteArray(256 * 1024) { i ->
            // mixed entropy: long zero runs + random patches, the shape chunk data compresses
            if (i % 64 < 48) 0 else random.nextInt().toByte()
        }
        val bound = OmlNativeZstd.bound(payload.size.toLong())
        assertTrue(bound > payload.size, "compressBound must exceed the source size")

        val compressed = ByteArray(bound.toInt())
        val n = OmlNativeZstd.compress(compressed, payload, level = 3)
        assertTrue(n > 0 && n < payload.size, "256 KiB of mixed entropy must compress below its raw size: $n")

        // JDK 27 refuses heap segments in a downcall — stage through a confined Arena like the
        // production byte[] surface does.
        val declared = java.lang.foreign.Arena.ofConfined().use { arena ->
            val native = arena.allocate(compressed.size.toLong())
            MemorySegment.copy(compressed, 0, native, ValueLayout.JAVA_BYTE, 0L, n)
            OmlNativeZstd.getFrameContentSize(native, n.toLong())
        }
        assertEquals(payload.size.toLong(), declared, "our own frames declare their content size")

        val restored = ByteArray(declared.toInt())
        val produced = OmlNativeZstd.decompress(restored, compressed.copyOf(n))
        assertEquals(payload.size, produced)
        assertContentEquals(payload, restored)
    }

    @Test
    fun `a netty-shaped direct-memory buffer compresses in place`() {
        // The exact shape of the encoder's zero-copy path: source AND destination as segments over
        // (simulated) native memory, sizes traveling as arguments.
        val payload = ByteArray(64 * 1024) { if (it % 128 < 96) 0 else (it % 7).toByte() }
        val compressed = ByteArray(OmlNativeZstd.bound(payload.size.toLong()).toInt())
        val n = OmlNativeZstd.compress(compressed, payload, level = 1)

        val restored = ByteArray(payload.size)
        val produced = OmlNativeZstd.decompress(restored, compressed.copyOf(n))
        assertContentEquals(payload, restored.copyOf(produced))
    }

    @Test
    fun `frames without a declared content size still decompress via the growth loop`() {
        val payload = ByteArray(300 * 1024) { if (it % 96 < 80) 0 else 0x5A }
        val compressed = ByteArray(OmlNativeZstd.bound(payload.size.toLong()).toInt())
        val n = OmlNativeZstd.compress(compressed, payload, level = 3)

        // decompressGrowing is what old chunks (streaming-written frames) hit when their declared
        // size is unknown; here it runs on a frame that HAS a size, proving the path is byte-exact.
        val restored = OmlNativeZstd.decompressGrowing(compressed.copyOf(n))
        assertContentEquals(payload, restored)
    }

    @Test
    fun `a frame declaring more than the growth ceiling is refused, not allocated`() {
        // decompressGrowing bounds its growth loop by MAX_GROWN_SIZE (64 MiB); a frame that *declares*
        // more has to be held to the same ceiling. Trusting the declared size straight into an
        // allocation is a one-line OOM from any corrupt or hostile frame header.
        val declared = 96L * 1024 * 1024
        val frame = java.lang.foreign.Arena.ofConfined().use { arena ->
            // The payload is staged as native memory, so the Java heap never holds 96 MiB: the result
            // is a few-KiB frame that declares its full size — a real frame, not a forged header.
            val src = arena.allocate(declared)
            val dstCap = OmlNativeZstd.bound(declared)
            val dst = arena.allocate(dstCap)
            val n = OmlNativeZstd.compress(dst, dstCap, src, declared, level = 3)
            assertTrue(!OmlNativeZstd.isErrorCode(n) && n > 0, "staging compress failed with $n")
            assertEquals(declared, OmlNativeZstd.getFrameContentSize(dst, n), "the frame must declare 96 MiB")
            ByteArray(n.toInt()).also { MemorySegment.copy(dst, ValueLayout.JAVA_BYTE, 0L, it, 0, n.toInt()) }
        }

        val failure = assertFailsWith<IllegalStateException> { OmlNativeZstd.decompressGrowing(frame) }
        assertTrue(
            failure.message!!.contains("did not decompress within"),
            "a >64 MiB declared frame must fail inside the bounded loop, got: ${failure.message}",
        )
    }

    @Test
    fun `malformed input is reported through the error surface`() {
        val garbage = ByteArray(64) { it.toByte() }
        val dst = ByteArray(1024)
        val code = java.lang.foreign.Arena.ofConfined().use { arena ->
            val nativeSrc = arena.allocate(garbage.size.toLong())
            MemorySegment.copy(garbage, 0, nativeSrc, ValueLayout.JAVA_BYTE, 0L, garbage.size)
            val nativeDst = arena.allocate(dst.size.toLong())
            OmlNativeZstd.decompress(nativeDst, dst.size.toLong(), nativeSrc, garbage.size.toLong())
        }
        assertTrue(OmlNativeZstd.isErrorCode(code), "a 64-byte non-zstd payload must be an error")
        assertFailsWith<IllegalStateException> { OmlNativeZstd.decompress(dst, garbage) }
    }
}
