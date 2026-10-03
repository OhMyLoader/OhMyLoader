package org.ohmyloader.core.compression

import org.ohmyloader.api.OmlLog
import org.ohmyloader.api.natives.NativeManager
import org.ohmyloader.api.natives.downcall
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout

/**
 * The official driver wrapper around our own **oml-native** library (`oml-native/src/root.zig`, built by zig with zstd statically linked). Every export carries the `oml_zstd_` prefix, so the lookup can never collide with any other libzstd mapped in the process (a launcher, LWJGL, or another mod's copy): the JVM resolves our addresses by name from our library, whatever else is loaded.
 * The library is found via [NativeManager.loadLibrary] — FFM's `SymbolLookup.libraryLookup` on an explicit path, never `System.loadLibrary`, so no JVM-startup search-path capture is involved.
 * API shape: address-in/address-out one-shots mirroring libzstd's own C ABI; streaming is implemented Java-side on top (see [OmlZstdStreams]) — chunk payloads are bounded by the region sector size, so whole-payload buffering costs one memcpy and buys a five-function C surface.
 * Lazy and fault-tolerant by contract: [available] is false when the library or any symbol cannot be resolved, and every consumer (region registration, packet codec) degrades to the vanilla zlib paths it also supports.
 */
object OmlNativeZstd {

    /** zstd frame magic, read little-endian off the wire: bytes `28 B5 2F FD`. */
    const val FRAME_MAGIC_LE: Int = 0xFD2FB528.toInt()

    /**
     * The six bound downcall handles, held behind one lazy so [available] can honestly mean what its
     * name says: binding happens as a unit, and any failure — the library missing *or* a single symbol
     * missing — collapses to `null`, which every consumer reads as "degrade to vanilla zlib". Binding
     * eagerly is what makes a missing symbol surface here, at load, instead of as a [LinkageError] on
     * the first packet or chunk it is asked to handle.
     */
    private class Bindings(lookup: SymbolLookup) {
        val compressBound =
            lookup.downcall("oml_zstd_compress_bound", ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG)

        val compress = lookup.downcall(
            "oml_zstd_compress",
            ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG,
            ValueLayout.JAVA_INT,
        )

        val compressReusable = lookup.downcall(
            "oml_zstd_compress_reusable",
            ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG,
            ValueLayout.JAVA_INT,
        )

        val decompress = lookup.downcall(
            "oml_zstd_decompress",
            ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
        )

        val frameContentSize = lookup.downcall(
            "oml_zstd_get_frame_content_size",
            ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG
        )

        // downcallVoid in the DSL covers the no-return case; here the C `unsigned int` widens to
        // JAVA_INT, which is what the FFM linker maps C ints to.
        val isError = lookup.downcall("oml_zstd_is_error", ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG)
    }

    private val bindings: Bindings? by lazy {
        try {
            Bindings(NativeManager.loadLibrary(OmlNativeZstd::class.java, "oml-native"))
        } catch (t: Throwable) {
            OmlLog.error("OMLNative", "oml-native unavailable — zstd consumers stay on vanilla zlib", t)
            null
        }
    }

    /** True once the library opened AND all six symbols bound. Consumers call this before use. */
    fun available(): Boolean = bindings != null

    /** Upper bound for the compressed form of [size] bytes at any level. */
    fun bound(size: Long): Long = bindings!!.compressBound.invoke(size) as Long

    /**
     * One-shot compress; returns the compressed byte count. The segments go straight to the
     * downcall as address holders — direct segments are the zero-copy path, heap segments are
     * staged by the FFM linker (one copy, still no JNI).
     */
    fun compress(dst: MemorySegment, dstCap: Long, src: MemorySegment, srcSize: Long, level: Int): Long =
        bindings!!.compress.invoke(dst, dstCap, src, srcSize, level) as Long

    /**
     * Compress through the library's per-thread persistent [ZSTD_CCtx]: same result as
     * [compress] without the context create/destroy per call — the packet path's saving.
     * The native side keys the context to the calling thread, so concurrent event-loop
     * threads each hold their own and no locking is involved.
     */
    fun compressReusable(dst: MemorySegment, dstCap: Long, src: MemorySegment, srcSize: Long, level: Int): Long =
        bindings!!.compressReusable.invoke(dst, dstCap, src, srcSize, level) as Long

    /** One-shot decompress; returns the produced byte count. */
    fun decompress(dst: MemorySegment, dstCap: Long, src: MemorySegment, srcSize: Long): Long =
        bindings!!.decompress.invoke(dst, dstCap, src, srcSize) as Long

    /** Declared content size of a frame: a byte count, `-1` (unknown), or `-2` (malformed). */
    fun getFrameContentSize(src: MemorySegment, srcSize: Long): Long =
        bindings!!.frameContentSize.invoke(src, srcSize) as Long

    /** Non-zero when [code] is a zstd error code (as returned by the functions above). */
    fun isErrorCode(code: Long): Boolean = (bindings!!.isError.invoke(code) as Int) != 0

    // -----------------------------------------------------------------------------------------
    // byte[] conveniences for the region-file streaming wrappers and tests
    //
    // JDK 27 refuses HEAP segments as downcall address arguments — so the array surface stages
    // through a confined Arena (copy in, call, copy out). One copy per side, still no JNI.
    // -----------------------------------------------------------------------------------------

    /** Compresses [src] at [level] into [dst]; returns the compressed size. Throws on zstd error. */
    fun compress(dst: ByteArray, src: ByteArray, level: Int): Int {
        Arena.ofConfined().use { arena ->
            val nativeSrc = arena.allocate(src.size.toLong())
            MemorySegment.copy(src, 0, nativeSrc, ValueLayout.JAVA_BYTE, 0L, src.size)
            val nativeDst = arena.allocate(dst.size.toLong())
            val n = compress(nativeDst, dst.size.toLong(), nativeSrc, src.size.toLong(), level)
            requireErrorCode(n, "compress")
            MemorySegment.copy(nativeDst, ValueLayout.JAVA_BYTE, 0L, dst, 0, n.toInt())
            return n.toInt()
        }
    }

    /**
     * Decompresses [src] into [dst]; returns the produced size. Throws on zstd error — callers
     * that cannot know the frame's declared size use [decompressGrowing] instead.
     */
    fun decompress(dst: ByteArray, src: ByteArray): Int {
        Arena.ofConfined().use { arena ->
            val nativeSrc = arena.allocate(src.size.toLong())
            MemorySegment.copy(src, 0, nativeSrc, ValueLayout.JAVA_BYTE, 0L, src.size)
            val nativeDst = arena.allocate(dst.size.toLong())
            val n = decompress(nativeDst, dst.size.toLong(), nativeSrc, src.size.toLong())
            requireErrorCode(n, "decompress")
            MemorySegment.copy(nativeDst, ValueLayout.JAVA_BYTE, 0L, dst, 0, n.toInt())
            return n.toInt()
        }
    }

    /**
     * Decompresses [src] without a caller-provided size: trusts the frame's declared content size
     * when it has one, otherwise grows the destination on `dstSize_tooSmall`-shaped failures.
     * The growth loop is what keeps old chunks whose frames were written *streaming* (no declared
     * size) readable by the one-shot surface — bounded, so a corrupt frame fails loudly instead
     * of looping forever.
     */
    fun decompressGrowing(src: ByteArray): ByteArray {
        Arena.ofConfined().use { arena ->
            val srcSegment = arena.allocate(src.size.toLong())
            MemorySegment.copy(src, 0, srcSegment, ValueLayout.JAVA_BYTE, 0L, src.size)
            val declared = getFrameContentSize(srcSegment, src.size.toLong())
            // The declared size comes off the wire, so it may not exceed the same ceiling the
            // growth loop enforces — a frame claiming 4 GiB must fail inside the bounded loop
            // instead of being trusted into a `declared`-sized allocation.
            if (declared in 0..MAX_GROWN_SIZE.toLong()) {
                val nativeDst = arena.allocate(declared)
                val n = decompress(nativeDst, declared, srcSegment, src.size.toLong())
                requireErrorCode(n, "decompress")
                check(n == declared) { "oml_zstd_decompress produced $n bytes, frame declared $declared" }
                val dst = ByteArray(n.toInt())
                MemorySegment.copy(nativeDst, ValueLayout.JAVA_BYTE, 0L, dst, 0, n.toInt())
                return dst
            }
            var cap = 64 * 1024
            while (cap <= MAX_GROWN_SIZE) {
                val nativeDst = arena.allocate(cap.toLong())
                val n = decompress(nativeDst, cap.toLong(), srcSegment, src.size.toLong())
                if (!isErrorCode(n)) {
                    val dst = ByteArray(n.toInt())
                    MemorySegment.copy(nativeDst, ValueLayout.JAVA_BYTE, 0L, dst, 0, n.toInt())
                    return dst
                }
                cap = cap shl 1
            }
            error("zstd frame of ${src.size} compressed bytes did not decompress within $MAX_GROWN_SIZE bytes")
        }
    }

    private fun requireErrorCode(code: Long, op: String) {
        check(!isErrorCode(code)) { "oml_zstd_$op failed with zstd error code $code" }
    }

    private const val MAX_GROWN_SIZE: Int = 64 * 1024 * 1024
}
