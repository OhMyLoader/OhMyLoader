package org.ohmyloader.core.benchmark

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.ohmyloader.core.hook.HookRegistry
import org.ohmyloader.core.hook.HookScanner
import org.ohmyloader.core.mod.ModContainer
import org.ohmyloader.core.transformer.TransformContext
import java.io.File
import java.lang.management.ManagementFactory
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.system.exitProcess

/**
 * Measures the loader's **per-class transform cost** — the side of the pipeline
 * [InjectionBenchmark] does not cover: parse (`EXPAND_FRAMES`, what the loader pays for every
 * class it claims), rule application, and write-back. `stacked` is the shape that tracks the
 * whole-method dataflow cost of stacking local-capturing injections on one method: four
 * `@ModifyVariable` handlers over one body means four dataflow passes under the current
 * invalidate-per-injection design. Machine-relative; compare across runs, not machines.
 *
 * Run with `./gradlew :oml-core:transformBenchmark`. Not part of `build`/`check`.
 */
object TransformBenchmark {

    private const val ROUNDS = 3_000
    private const val WARMUP_ROUNDS = 300

    private val scenarios = listOf(
        "omltest.bench.BenchFixtures\$BaselineTarget",
        "omltest.bench.BenchFixtures\$NotifyTarget",
        "omltest.bench.BenchFixtures\$RedirectTarget",
        "omltest.bench.BenchFixtures\$WrapOperationTarget",
        "omltest.bench.BenchFixtures\$StackedTarget",
    )

    private class Sampled(val label: String, val nsPerClass: Double, val bytesPerClass: Double)

    @JvmStatic
    fun main(args: Array<String>) {
        val loader = TransformBenchmark::class.java.classLoader
        HookRegistry.resetForTests()
        HookRegistry.installClassLoader(loader)
        val jar = jarOf(loader)
        val mod = ModContainer(
            id = "transform_benchmark", name = "Transform Benchmark", version = "0.1.0",
            entryClass = "omltest.bench.BenchFixtures", file = jar,
        )
        val transformer = HookScanner.createTransformer(listOf(mod))
            ?: error("the scanner produced no transformer for the benchmark fixtures")

        val failures = mutableListOf<String>()
        val samples = mutableListOf<Sampled>()
        for (target in scenarios) {
            val bytes = loader.getResourceAsStream(target.replace('.', '/') + ".class")!!.readBytes()
            val internalName = target.replace('.', '/')
            val label = target.substringAfterLast('$').lowercase()
            val targeted = transformer.appliesTo(internalName)
            if (!targeted && !label.contains("baseline")) {
                failures += "$label: the transformer does not target $internalName"
                continue
            }

            // One correctness probe: claimed classes must survive write-back.
            if (targeted) {
                val node = parse(bytes)
                if (!transformer.transform(TransformContext(internalName, node))) {
                    failures += "$label: the injection did not happen"
                } else if (write(node).isEmpty()) {
                    failures += "$label: write-back produced no bytes"
                }
            }

            samples += measure(label, transformer, internalName, bytes, targeted)
        }

        println("Transform-time benchmark (per claimed class)")
        println("  ${System.getProperty("os.name")} ${System.getProperty("os.version")} / " +
            "JVM ${System.getProperty("java.version")}")
        println("  parse(EXPAND_FRAMES) + rules + write(COMPUTE_MAXS), best of $ROUNDS rounds")
        println("  'stacked' = one method, four local-capturing injections (four dataflow passes)")
        println()
        println(String.format("%-18s %12s %12s", "scenario", "ns/class", "B/class"))
        for (sample in samples) {
            val bytesText = if (sample.bytesPerClass < 0) "n/a" else String.format("%.0f", sample.bytesPerClass)
            println(String.format("%-18s %12.0f %12s", sample.label, sample.nsPerClass, bytesText))
        }

        if (failures.isNotEmpty()) {
            failures.forEach { System.err.println("FAILED $it") }
            exitProcess(1)
        }
    }

    private fun measure(
        label: String,
        transformer: org.ohmyloader.core.transformer.IClassTransformer,
        internalName: String,
        bytes: ByteArray,
        applyRules: Boolean,
    ): Sampled {
        val bean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        val allocate = bean != null && runCatching { bean.isThreadAllocatedMemorySupported }.getOrDefault(false) &&
            runCatching {
                if (!bean!!.isThreadAllocatedMemoryEnabled) bean.isThreadAllocatedMemoryEnabled = true
                bean.isThreadAllocatedMemoryEnabled
            }.getOrDefault(false)
        val threadId = Thread.currentThread().id

        var bestNanos = Long.MAX_VALUE
        var bestBytes = -1L
        for (round in 0 until WARMUP_ROUNDS + ROUNDS) {
            val bytesBefore = if (allocate) bean!!.getThreadAllocatedBytes(threadId) else -1L
            val started = System.nanoTime()
            val node = parse(bytes)
            if (applyRules) transformer.transform(TransformContext(internalName, node))
            write(node)
            val elapsed = System.nanoTime() - started
            val bytesAfter = if (allocate) bean!!.getThreadAllocatedBytes(threadId) else -1L
            if (round >= WARMUP_ROUNDS) {
                if (elapsed < bestNanos) bestNanos = elapsed
                if (bytesBefore >= 0 && bytesAfter >= 0) {
                    val allocated = bytesAfter - bytesBefore
                    if (bestBytes < 0 || allocated < bestBytes) bestBytes = allocated
                }
            }
        }
        return Sampled(label, bestNanos.toDouble(), if (bestBytes < 0) -1.0 else bestBytes.toDouble())
    }

    private fun jarOf(loader: ClassLoader): File {
        val names = listOf(
            "BenchFixtures", "BenchFixtures\$Ops",
            "BenchFixtures\$BaselineTarget",
            "BenchFixtures\$NotifyTarget", "BenchFixtures\$NotifyMixin",
            "BenchFixtures\$RedirectTarget", "BenchFixtures\$RedirectMixin",
            "BenchFixtures\$WrapOperationTarget", "BenchFixtures\$WrapOperationMixin",
            "BenchFixtures\$StackedTarget", "BenchFixtures\$StackedMixin",
        )
        val jar = File.createTempFile("transform-benchmark", ".jar").apply { deleteOnExit() }
        JarOutputStream(jar.outputStream()).use { out ->
            for (name in names) {
                val entry = "omltest/bench/$name.class"
                out.putNextEntry(JarEntry(entry))
                out.write(loader.getResourceAsStream(entry)!!.readBytes())
                out.closeEntry()
            }
        }
        return jar
    }

    private fun parse(bytes: ByteArray): ClassNode =
        ClassNode(Opcodes.ASM9).also { node ->
            ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES)
        }

    private fun write(node: ClassNode): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        node.accept(writer)
        return writer.toByteArray()
    }
}
