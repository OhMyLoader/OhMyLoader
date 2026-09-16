package org.ohmyloader.core.compression

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Codec round-trip for the region-file Zstd format (ID 127): the exact classes the adapter
 * registers into `RegionFileVersion` — `ZstdOutputStream(wrapped, LEVEL)` on the write side,
 * `ZstdInputStream(wrapped)` on the read side — exercised against a realistic payload: a
 * hand-emitted NBT byte block with the hierarchical shape of a real chunk (palette strings,
 * section arrays, block entities with nested compounds). What a unit test can pin is that the
 * installed codec pair is lossless at the byte level; the registration itself and the `.mca`
 * byte layout are verified on the live server (run-verification step).
 */
class ZstdChunkCodecTest {

    init {
        // Drives the real oml-native library; skipped (not failed) when the zig build output is absent.
        org.junit.jupiter.api.Assumptions.assumeTrue(NativeTestLibraries.ensureOmlNative(), "oml-native not built")
    }

    /** Big-endian NBT writer — just enough of the format to build deep, realistic chunk data. */
    private class NbtWriter {
        val out = ByteArrayOutputStream()

        private fun utf(s: String) {
            val bytes = s.toByteArray(StandardCharsets.UTF_8)
            out.write(bytes.size ushr 8)
            out.write(bytes.size)
            out.write(bytes)
        }

        fun named(type: Int, name: String, body: NbtWriter.() -> Unit) {
            out.write(type)
            utf(name)
            body()
        }

        fun int(value: Int) {
            out.write(value ushr 24); out.write(value ushr 16); out.write(value ushr 8); out.write(value)
        }

        fun long(value: Long) {
            repeat(8) { shift -> out.write((value ushr ((7 - shift) * 8)).toInt()) }
        }

        fun string(value: String) {
            out.write(8)
            utf(value)
        }

        /** TAG_List of TAG_Compound, each element emitted through [element]. */
        fun compoundList(name: String, count: Int, element: NbtWriter.(Int) -> Unit) {
            out.write(9)
            utf(name)
            out.write(10) // element type: compound
            int(count)
            repeat(count) { i -> element(i) }
        }

        fun byteArray(name: String, data: ByteArray) {
            out.write(7)
            utf(name)
            int(data.size)
            out.write(data)
        }

        fun longArray(name: String, data: LongArray) {
            out.write(12)
            utf(name)
            int(data.size)
            for (v in data) long(v)
        }

        fun compound(name: String, body: NbtWriter.() -> Unit) {
            out.write(10)
            utf(name)
            body()
        }

        fun end() = out.write(0)
    }

    /**
     * A chunk-shaped NBT payload: 24 sections of palette/string + packed long arrays + block
     * entities, with both highly repetitive regions (palette names, zero-filled light) and
     * pseudo-random binary (a "biome noise" array) — the mix a real chunk compresses.
     */
    private fun chunkNbt(seed: Int): ByteArray {
        val random = Random(seed)
        val writer = NbtWriter()
        writer.compound("") {
            writer.named(3, "DataVersion") { writer.int(4188) }
            writer.named(8, "Status") { writer.string("minecraft:full") }
            writer.named(2, "xPos") { writer.int(7) }
            writer.named(2, "zPos") { writer.int(-13) }
            writer.compoundList("sections", 24) { index ->
                writer.named(7, "Y") { writer.int(index - 4) }
                // block states: palette + packed data, the dominant bulk of a chunk
                writer.compoundList("palette", 4) { i ->
                    writer.named(8, "Name") { writer.string("minecraft:${if (i == 0) "air" else "stone"}") }
                    if (i == 1) writer.named(8, "Properties") { writer.string("waterlogged=false") }
                }
                writer.byteArray("data", ByteArray(2048)) // packed indices, mostly zeros in practice
                writer.byteArray("SkyLight", ByteArray(2048)) // light arrays: long zero runs
                writer.longArray("Biomes", LongArray(64) { random.nextLong() })
            }
            writer.compoundList("block_entities", 3) { i ->
                writer.named(8, "id") { writer.string("minecraft:chest") }
                writer.named(3, "x") { writer.int(i * 16) }
                writer.named(3, "y") { writer.int(64 + i) }
                writer.named(3, "z") { writer.int(-i * 8) }
                writer.named(9, "Items") { writer.int(0) } // empty list of compounds
                writer.end()
            }
            writer.end()
        }
        return writer.out.toByteArray()
    }

    /**
     * The compression level the region-file codec runs at. Mirrors the adapter's
     * `ZstdRegionChunkFormat.LEVEL` (which owns the constant — region storage is a game format,
     * so the constant lives there): this test must exercise the exact level the registered codec
     * uses, so keep the two in sync.
     */
    private val REGION_LEVEL = 3

    /** The exact write path the adapter registers: DataOutputStream over OmlZstdOutputStream(level 3). */
    private fun compress(payload: ByteArray): ByteArray {
        val sink = ByteArrayOutputStream()
        OmlZstdOutputStream(sink, REGION_LEVEL).use { compressor ->
            DataOutputStream(compressor).use { it.write(payload) }
        }
        return sink.toByteArray()
    }

    @Test
    fun `nbt chunk payload survives a zstd round trip byte for byte`() {
        repeat(3) { seed ->
            val payload = chunkNbt(seed)
            val frame = compress(payload)
            val restored = java.io.DataInputStream(
                OmlZstdInputStream(ByteArrayInputStream(frame)),
            ).use { it.readAllBytes() }
            assertContentEquals(payload, restored, "round trip must be byte-exact (seed $seed)")
        }
    }

    @Test
    fun `chunk-sized nbt actually compresses`() {
        val payload = chunkNbt(42)
        val frame = compress(payload)
        assertTrue(
            frame.size < payload.size / 2,
            "repetitive NBT should compress well: ${payload.size} -> ${frame.size}"
        )
    }

    @Test
    fun `chunked reads reassemble the same bytes as readAllBytes`() {
        val payload = chunkNbt(7)
        val frame = compress(payload)
        val input = OmlZstdInputStream(ByteArrayInputStream(frame))
        val chunks = mutableListOf<Byte>()
        val buffer = ByteArray(512) // smaller than any internal zstd block — forces many read() calls
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            chunks.addAll(buffer.asList().subList(0, n))
        }
        input.close()
        assertContentEquals(payload, chunks.toByteArray())
    }

    @Test
    fun `a zero length read returns zero even at the end of the stream`() {
        val frame = compress(chunkNbt(11))
        val input = OmlZstdInputStream(ByteArrayInputStream(frame))
        // Drain to EOF: read() answers 0 only for a zero-length request, so this loop ends at -1.
        while (input.read(ByteArray(4096)) >= 0) {
            // keep reading until the stream reports its end
        }

        // InputStream.read(b, off, 0) is defined to return 0 unconditionally — the len == 0 test has
        // to precede the EOF test. Ordered the other way, a caller that probes with an empty buffer
        // before writing reads -1 as "end of data" and abandons the chunk one read early.
        assertEquals(0, input.read(ByteArray(0), 0, 0))
        assertEquals(0, input.read(ByteArray(32), 7, 0))
        input.close()
    }
}
