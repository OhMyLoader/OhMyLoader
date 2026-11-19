package org.ohmyloader.core.benchmark

import org.ohmyloader.api.core.natives.NativeManager
import org.ohmyloader.api.core.natives.downcall
import java.io.File
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import kotlin.system.exitProcess

/**
 * The native half of the startup-scan experiment (`native-plan.md` §4.1): `oml_scan_jar_v1` maps
 * the jar, inflates with libdeflate and writes candidate records into the caller's buffer. Runs
 * against the pack [ScanBenchmark] generated, so both numbers describe the same 300 jars /
 * 120k classes.
 *
 * Run with `./gradlew :oml-core:scanBenchmark :oml-core:nativeScanBenchmark`. Not part of
 * `build`/`check`.
 */
object NativeScanBenchmark {

    private const val CLASSES = 120_000
    private const val MODS = 300
    private const val RULES = 60
    private const val HOOKS = 60

    @JvmStatic
    fun main(args: Array<String>) {
        val modsDir = File(args.getOrElse(0) { "build/scan-benchmark/mods" }).absoluteFile
        if (!modsDir.isDirectory) {
            println("native scan benchmark: run :oml-core:scanBenchmark first (missing $modsDir)")
            exitProcess(1)
        }
        if (!org.ohmyloader.core.compression.NativeTestLibraries.ensureOmlNative()) {
            println("native scan benchmark: oml-native is not on the test runtime classpath — skipped")
            return
        }
        val lookup = runCatching {
            NativeManager.loadLibrary(NativeScanBenchmark::class.java, "oml-native")
        }.getOrNull()
        if (lookup == null) {
            println("native scan benchmark: oml-native could not be loaded — skipped")
            return
        }
        val nativeScan = lookup.downcall(
            "oml_scan_jar_v1",
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, // path
            ValueLayout.JAVA_LONG, // path length
            ValueLayout.JAVA_INT, // runtime feature
            ValueLayout.ADDRESS, // output records
            ValueLayout.JAVA_LONG, // output capacity
            ValueLayout.ADDRESS, // required bytes
            ValueLayout.ADDRESS, // counts
        )
        val jars = modsDir.listFiles().orEmpty().filter { it.name.endsWith(".jar") }.sortedBy { it.name }

        var mods = 0L
        var rules = 0L
        var hooks = 0L
        var classes = 0L
        var reserved = 0L
        Arena.ofConfined().use { arena ->
            var output = arena.allocate(256 * 1024)
            val required = arena.allocate(8)
            val counts = arena.allocate(5 * 8)
            // One warm-up jar before the measured loop: ties the FFM linkage to this run, not JIT.
            check(scanJar(arena, nativeScan, jars.first(), output, required, counts) == 0) {
                "warm-up scan failed"
            }

            val started = System.nanoTime()
            for (jar in jars) {
                var rc = scanJar(arena, nativeScan, jar, output, required, counts)
                if (rc == 1) {
                    val needed = required.get(ValueLayout.JAVA_LONG, 0)
                    if (needed > output.byteSize()) {
                        output = arena.allocate(needed)
                        rc = scanJar(arena, nativeScan, jar, output, required, counts)
                    }
                }
                if (rc != 0) {
                    println("native scan benchmark: oml_scan_jar_v1(${jar.name}) returned $rc")
                    exitProcess(1)
                }
                mods += counts.get(ValueLayout.JAVA_LONG, 0)
                rules += counts.get(ValueLayout.JAVA_LONG, 8)
                hooks += counts.get(ValueLayout.JAVA_LONG, 16)
                classes += counts.get(ValueLayout.JAVA_LONG, 24)
                reserved += counts.get(ValueLayout.JAVA_LONG, 32)
            }
            println(
                "native jar scan: ${(System.nanoTime() - started) / 1_000_000} ms " +
                    "(mods=$mods rules=$rules hooks=$hooks classes=$classes reserved=$reserved)",
            )
        }

        // The scanner must see exactly what the Java walk sees; a mismatch invalidates the timing.
        if (mods != MODS.toLong() || rules != RULES.toLong() ||
            hooks != HOOKS.toLong() || classes != CLASSES.toLong() || reserved != 0L
        ) {
            println(
                "native scan benchmark: expected mods=$MODS rules=$RULES hooks=$HOOKS classes=$CLASSES reserved=0; " +
                    "got mods=$mods rules=$rules hooks=$hooks classes=$classes reserved=$reserved",
            )
            exitProcess(1)
        }
    }

    private fun scanJar(
        arena: Arena,
        handle: java.lang.invoke.MethodHandle,
        jar: File,
        output: MemorySegment,
        required: MemorySegment,
        counts: MemorySegment,
    ): Int {
        val bytes = jar.absolutePath.toByteArray(Charsets.UTF_8)
        val path = arena.allocate(bytes.size.toLong())
        MemorySegment.copy(bytes, 0, path, ValueLayout.JAVA_BYTE, 0, bytes.size)
        return handle.invoke(
            path,
            bytes.size.toLong(),
            Runtime.version().feature(),
            output,
            output.byteSize(),
            required,
            counts,
        ) as Int
    }
}
