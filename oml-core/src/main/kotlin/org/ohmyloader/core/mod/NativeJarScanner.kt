package org.ohmyloader.core.mod

import org.ohmyloader.api.core.OmlLog
import org.ohmyloader.api.core.natives.NativeManager
import org.ohmyloader.api.core.natives.downcall
import java.io.File
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.nio.ByteOrder

/**
 * The FFM bridge to `oml-native`'s jar walker (`oml_scan_jar_v1`).
 *
 * The native side maps the jar, inflates with libdeflate and emits
 * `[u8 mask][u16 version][u16 nameLen][utf8 internalName]` records into a thread-local buffer.
 * `@Mod` records append `[u32 len][bytes]`, so the Java side reads the annotation values
 * without reopening the jar. A record's version is 0 for the base copy and N for an
 * `META-INF/versions/N/` MR-JAR winner; mask bit 8 marks a class packed under OML's own
 * namespace, which the Java side refuses.
 *
 * Every failure — no library, an old library without the symbol, a corrupt jar, a buffer that
 * still does not fit after one growth — returns null, and the caller falls back to the Java walk.
 * The cache is best-effort and must never fail a scan.
 */
internal object NativeJarScanner {

    class Record(
        val internalName: String,
        val version: Int,
        val mask: Int,
        /** Present for `@Mod` records: the class bytes, so the Java side need not reopen the jar. */
        val bytes: ByteArray?,
    )

    private const val MASK_MOD = 1
    private const val BUFFER_TOO_SMALL = 1
    private const val MAX_OUTPUT = 64 * 1024 * 1024
    private const val RECORD_HEADER = 5L

    private val shortLe: ValueLayout.OfShort =
        ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN)
    private val intLe: ValueLayout.OfInt =
        ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN)

    private class Bindings(val scan: MethodHandle)

    private val bindings: Bindings? by lazy {
        try {
            val lookup = NativeManager.loadLibrary(NativeJarScanner::class.java, "oml-native")
            Bindings(
                lookup.downcall(
                    "oml_scan_jar_v1",
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, // path
                    ValueLayout.JAVA_LONG, // path length
                    ValueLayout.JAVA_INT, // runtime feature
                    ValueLayout.ADDRESS, // output
                    ValueLayout.JAVA_LONG, // output capacity
                    ValueLayout.ADDRESS, // required bytes
                    ValueLayout.ADDRESS, // counts
                ),
            )
        } catch (t: Throwable) {
            // The pure-Java walk is a complete replacement; a missing native library is a
            // performance decision, not an error the user needs to see.
            OmlLog.info("OMLNative", "native jar scanner unavailable, using the Java walk (${t.message})")
            null
        }
    }

    private class Buffer {
        val arena: Arena = Arena.ofAuto()
        // The @Mod records carry class bytes; 1 MiB covers a few hundred entries without a
        // growth round trip. A larger pack still grows through the required-size path.
        var output: MemorySegment = arena.allocate(1024 * 1024)
        val required: MemorySegment = arena.allocate(8)
        val counts: MemorySegment = arena.allocate(5 * 8)
    }

    /** One buffer per thread; growth replaces the segment, the arena reclaims it after GC. */
    private val buffers = ThreadLocal.withInitial { Buffer() }

    /** True when the library and the symbol are both available. */
    fun available(): Boolean = bindings != null

    /**
     * The startup gate: binds now or throws with the diagnosis the user needs. oml-native is a
     * first-party component that deploys with the loader — a missing library is as fatal as a
     * missing oml-core jar, and there is no Java fallback to silently degrade into.
     */
    fun requireAvailable() {
        bindings ?: error(
            "oml-native is unavailable — the mod scan cannot run without it. " +
                "This is an install problem, not a mod problem: reinstall OML so the native library " +
                "is deployed next to the loader (expected in ${NativeManager.nativesDir().absolutePath}).",
        )
    }

    /** Null whenever the native path cannot answer; the caller must fall back to Java. */
    fun scan(jar: File, runtimeFeature: Int): List<Record>? {
        val bindings = bindings ?: return null
        return try {
            val buffer = buffers.get()
            val pathBytes = jar.absolutePath.toByteArray(Charsets.UTF_8)
            val path = buffer.arena.allocate(pathBytes.size.toLong())
            MemorySegment.copy(pathBytes, 0, path, ValueLayout.JAVA_BYTE, 0, pathBytes.size)

            var rc = call(bindings, buffer, path, pathBytes.size, runtimeFeature)
            if (rc == BUFFER_TOO_SMALL) {
                val needed = buffer.required.get(ValueLayout.JAVA_LONG, 0)
                if (needed <= buffer.output.byteSize() || needed > MAX_OUTPUT) return null
                buffer.output = buffer.arena.allocate(needed)
                rc = call(bindings, buffer, path, pathBytes.size, runtimeFeature)
            }
            if (rc != 0) return null
            parse(buffer.output, buffer.required.get(ValueLayout.JAVA_LONG, 0))
        } catch (t: Throwable) {
            // A LinkageError from an older library, a bad record, or an FFM failure: Java answers.
            null
        }
    }

    private fun call(
        bindings: Bindings,
        buffer: Buffer,
        path: MemorySegment,
        pathLength: Int,
        runtimeFeature: Int,
    ): Int = bindings.scan.invoke(
        path,
        pathLength.toLong(),
        runtimeFeature,
        buffer.output,
        buffer.output.byteSize(),
        buffer.required,
        buffer.counts,
    ) as Int

    private fun parse(output: MemorySegment, used: Long): List<Record>? {
        val records = ArrayList<Record>()
        var offset = 0L
        while (offset < used) {
            if (offset + RECORD_HEADER > used) return null
            val mask = output.get(ValueLayout.JAVA_BYTE, offset).toInt() and 0xFF
            val version = output.get(shortLe, offset + 1).toInt() and 0xFFFF
            val nameLength = output.get(shortLe, offset + 3).toInt() and 0xFFFF
            val nameStart = offset + RECORD_HEADER
            if (nameStart + nameLength > used) return null
            val name = String(
                output.asSlice(nameStart, nameLength.toLong()).toArray(ValueLayout.JAVA_BYTE),
                Charsets.UTF_8,
            )
            var next = nameStart + nameLength
            var classBytes: ByteArray? = null
            if (mask and MASK_MOD != 0) {
                if (next + 4 > used) return null
                val length = output.get(intLe, next)
                if (length < 0 || next + 4 + length.toLong() > used) return null
                classBytes = output.asSlice(next + 4, length.toLong()).toArray(ValueLayout.JAVA_BYTE)
                next += 4 + length.toLong()
            }
            records += Record(name, version, mask, classBytes)
            offset = next
        }
        return records
    }
}
