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
import java.util.function.IntUnaryOperator
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.system.exitProcess

/**
 * Measures the real annotation pipeline (jar → scanner → transform → defineClass → call) on one
 * fixture body, one injector per scenario. Timing is the minimum of several warmed rounds, which is
 * a machine-relative number; **bytes per call** is the decision-relevant fact (a struct allocation
 * per call cannot be optimized away). Every scenario also verifies its result and that the handler
 * ran, so a silently missed injection cannot masquerade as a fast path.
 *
 * Run with `./gradlew :oml-core:injectionBenchmark`. Not part of `build`/`check`.
 */
object InjectionBenchmark {

    private const val WARMUP_CALLS = 3_000_000
    private const val ROUNDS = 5
    private const val CALLS_PER_ROUND = 5_000_000

    private class Scenario(
        val label: String,
        val target: String,
        val expected: Int,
        val injected: Boolean,
    )

    private class Sampled(val label: String, val nsPerCall: Double, val bytesPerCall: Double)

    private val scenarios = listOf(
        Scenario("baseline", "omltest.bench.BenchFixtures\$BaselineTarget", 11, injected = false),
        Scenario("inject-notify", "omltest.bench.BenchFixtures\$NotifyTarget", 11, injected = true),
        Scenario("modify-return", "omltest.bench.BenchFixtures\$ModifyReturnTarget", 12, injected = true),
        Scenario("modify-arg", "omltest.bench.BenchFixtures\$ModifyArgTarget", 13, injected = true),
        Scenario("redirect", "omltest.bench.BenchFixtures\$RedirectTarget", 16, injected = true),
        Scenario("wrap-operation", "omltest.bench.BenchFixtures\$WrapOperationTarget", 13, injected = true),
        Scenario("modify-args", "omltest.bench.BenchFixtures\$ModifyArgsTarget", 13, injected = true),
        Scenario("wrap-method", "omltest.bench.BenchFixtures\$WrapMethodTarget", 12, injected = true),
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val loader = InjectionBenchmark::class.java.classLoader
        val ops = loader.loadClass("omltest.bench.BenchFixtures\$Ops")
        val hits = ops.getField("hits")

        HookRegistry.resetForTests()
        HookRegistry.installClassLoader(loader)
        val jar = jarOf(loader)
        val mod = ModContainer(
            id = "injection_benchmark", name = "Injection Benchmark", version = "0.1.0",
            entryClass = "omltest.bench.BenchFixtures", file = jar,
        )
        val transformer = HookScanner.createTransformer(listOf(mod))
            ?: error("the scanner produced no transformer for the benchmark fixtures")

        val failures = mutableListOf<String>()
        val samples = mutableListOf<Sampled>()
        var checksum = 0L
        for (scenario in scenarios) {
            val original = loader.loadClass(scenario.target)
            val node = classNodeOf(original)
            val internalName = scenario.target.replace('.', '/')
            if (scenario.injected) {
                if (!transformer.appliesTo(internalName)) {
                    failures += "${scenario.label}: the transformer does not target ${scenario.target}"
                    continue
                }
                if (!transformer.transform(TransformContext(internalName, node))) {
                    failures += "${scenario.label}: the injection did not happen"
                    continue
                }
            }
            val op = define(internalName.replace('/', '.'), write(node))
                .getDeclaredConstructor().newInstance() as IntUnaryOperator

            // A missed injection would look like a fast zero-allocation path; pin the result and the hit.
            val hitsBefore = hits.getLong(null)
            val result = op.applyAsInt(5)
            if (result != scenario.expected) {
                failures += "${scenario.label}: applyAsInt(5) = $result, expected ${scenario.expected}"
            }
            if (scenario.injected && hits.getLong(null) <= hitsBefore) {
                failures += "${scenario.label}: the handler did not run"
            }

            val sample = measure(scenario.label, op)
            samples += sample
            checksum += sample.nsPerCall.toLong() // keep the measurement visibly consumed
        }

        println("Injection hot-path benchmark")
        println("  ${System.getProperty("os.name")} ${System.getProperty("os.version")} / " +
            "JVM ${System.getProperty("java.version")}")
        println("  JVM args: ${ManagementFactory.getRuntimeMXBean().inputArguments}")
        println("  warmup $WARMUP_CALLS calls, best of $ROUNDS rounds of $CALLS_PER_ROUND calls")
        println("  B/call counts what the JVM actually allocated: escape analysis may scalar-replace")
        println("  a bridge's temporary array/box when the handler inlines into this tight loop.")
        println()
        println(String.format("%-16s %10s %10s", "scenario", "ns/call", "B/call"))
        for (sample in samples) {
            val bytes = if (sample.bytesPerCall < 0) "n/a" else String.format("%.1f", sample.bytesPerCall)
            println(String.format("%-16s %10.2f %10s", sample.label, sample.nsPerCall, bytes))
        }
        println()
        println("handler hits: ${hits.getLong(null)}, checksum: ${checksum xor 0L}")

        if (failures.isNotEmpty()) {
            failures.forEach { System.err.println("FAILED $it") }
            exitProcess(1)
        }
    }

    private fun measure(label: String, op: IntUnaryOperator): Sampled {
        var sink = 0L
        var i = 0
        while (i < WARMUP_CALLS) {
            sink += op.applyAsInt(i and 0x3F)
            i++
        }

        val bean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        val allocate = bean != null && runCatching { bean.isThreadAllocatedMemorySupported }.getOrDefault(false) &&
            runCatching {
                if (!bean!!.isThreadAllocatedMemoryEnabled) bean.isThreadAllocatedMemoryEnabled = true
                bean.isThreadAllocatedMemoryEnabled
            }.getOrDefault(false)
        val threadId = Thread.currentThread().id

        var bestNanos = Long.MAX_VALUE
        var bestBytes = -1L
        for (round in 0 until ROUNDS) {
            val bytesBefore = if (allocate) bean!!.getThreadAllocatedBytes(threadId) else -1L
            val started = System.nanoTime()
            var roundSink = 0L
            var call = 0
            while (call < CALLS_PER_ROUND) {
                roundSink += op.applyAsInt(call and 0x3F)
                call++
            }
            val elapsed = System.nanoTime() - started
            val bytesAfter = if (allocate) bean!!.getThreadAllocatedBytes(threadId) else -1L
            sink += roundSink
            if (elapsed < bestNanos) bestNanos = elapsed
            if (bytesBefore >= 0 && bytesAfter >= 0) {
                val allocated = bytesAfter - bytesBefore
                if (bestBytes < 0 || allocated < bestBytes) bestBytes = allocated
            }
        }
        if (sink == Long.MIN_VALUE && label.isEmpty()) {
            println() // unreachable; keeps the accumulator observably live
        }
        return Sampled(
            label,
            bestNanos.toDouble() / CALLS_PER_ROUND,
            if (bestBytes < 0) -1.0 else bestBytes.toDouble() / CALLS_PER_ROUND,
        )
    }

    private fun jarOf(loader: ClassLoader): File {
        val names = listOf(
            "BenchFixtures", "BenchFixtures\$Ops",
            "BenchFixtures\$BaselineTarget",
            "BenchFixtures\$NotifyTarget", "BenchFixtures\$NotifyMixin",
            "BenchFixtures\$ModifyReturnTarget", "BenchFixtures\$ModifyReturnMixin",
            "BenchFixtures\$ModifyArgTarget", "BenchFixtures\$ModifyArgMixin",
            "BenchFixtures\$RedirectTarget", "BenchFixtures\$RedirectMixin",
            "BenchFixtures\$WrapOperationTarget", "BenchFixtures\$WrapOperationMixin",
            "BenchFixtures\$ModifyArgsTarget", "BenchFixtures\$ModifyArgsMixin",
            "BenchFixtures\$WrapMethodTarget", "BenchFixtures\$WrapMethodMixin",
        )
        val jar = File.createTempFile("injection-benchmark", ".jar").apply { deleteOnExit() }
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

    private fun classNodeOf(clazz: Class<*>): ClassNode =
        ClassNode(Opcodes.ASM9).also { node ->
            ClassReader(
                clazz.classLoader.getResourceAsStream(clazz.name.replace('.', '/') + ".class")!!.readBytes(),
            ).accept(node, ClassReader.EXPAND_FRAMES)
        }

    private fun write(node: ClassNode): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        node.accept(writer)
        return writer.toByteArray()
    }

    /** The bridge is generated into a test-local child loader; the target must resolve it by name. */
    private fun define(name: String, bytes: ByteArray): Class<*> =
        object : ClassLoader(HookRegistry.bridgeClass!!.classLoader) {
            fun define(): Class<*> = defineClass(name, bytes, 0, bytes.size)
        }.define()
}