package org.ohmyloader.core.benchmark

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.ohmyloader.core.cache.StartupCache
import org.ohmyloader.core.cache.TransformCacheKey
import org.ohmyloader.core.hook.HookScanner
import org.ohmyloader.core.mod.ModScanner
import org.ohmyloader.core.ruleset.ModRuleSets
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import kotlin.system.exitProcess

/**
 * Measures the startup scan of a synthetic 300-jar pack — the modpack shape `native-plan.md` §4.1
 * uses as the trigger for a native scanner. A cold run scans every class once through the shared
 * prefilter; a warm run answers from the startup ledger. The per-phase numbers are machine-relative;
 * compare runs, not machines.
 *
 * Run with `./gradlew :oml-core:scanBenchmark`. Not part of `build`/`check`.
 */
object ScanBenchmark {

    private const val JARS = 300
    private const val CLASSES_PER_JAR = 400
    private const val MOD_DESC = "Lorg/ohmyloader/api/core/Mod;"
    private const val RULE_DESC = "Lorg/ohmyloader/api/core/inject/RuleSource;"
    private const val HOOKED_DESC = "Lorg/ohmyloader/api/core/hook/Hooked;"

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File("build/scan-benchmark").apply { deleteRecursively(); mkdirs() }
        val modsDir = File(root, "mods").apply { mkdirs() }
        val cacheDir = File(root, "cache")
        val loader = ScanBenchmark::class.java.classLoader

        val generated = System.nanoTime()
        generate(modsDir)
        val generateMs = elapsedMs(generated)
        val bytes = modsDir.listFiles().orEmpty().sumOf { it.length() }
        println(
            "Scan benchmark: $JARS jars, ${JARS * CLASSES_PER_JAR} classes, " +
                "${bytes / (1024 * 1024)} MiB (generated in $generateMs ms)",
        )

        // The old cold path, reproduced with the same ASM flags each scanner used: three full
        // decompress-and-parse passes over the same pack, on the same data and machine.
        val legacyStarted = System.nanoTime()
        legacyScan(modsDir, "legacy mod scan", ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG) { node ->
            node.visibleAnnotations.orEmpty().any { it.desc == MOD_DESC }
        }
        legacyScan(modsDir, "legacy rule sets", ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES) { node ->
            annotationsOf(node).any { it.desc == RULE_DESC }
        }
        legacyScan(modsDir, "legacy hook scan", ClassReader.SKIP_DEBUG or ClassReader.EXPAND_FRAMES) { node ->
            annotationsOf(node).any { it.desc == HOOKED_DESC }
        }
        println("  legacy total: ${elapsedMs(legacyStarted)} ms")

        val coldStarted = System.nanoTime()
        StartupCache.init(cacheDir)
        val coldMods = timed("cold mod scan") { ModScanner.scan(modsDir) }
        val coldRules = timed("cold rule sets") { ModRuleSets.load(coldMods, loader) }
        timed("cold hook scan") { HookScanner.createTransformer(coldMods) }
        println("  cold total: ${elapsedMs(coldStarted)} ms")
        val fingerprint = timed("cold transform fingerprint") {
            TransformCacheKey.compute(
                gameJar = modsDir.listFiles().orEmpty().first(),
                loaderVersion = "benchmark",
                adapterId = "benchmark",
                adapterLocation = modsDir.listFiles().orEmpty().first().toURI().toURL(),
                layerLocations = coldMods.take(4).map { it.file.toURI().toURL() },
                modJars = coldMods.map { it.file },
                side = "client",
                jdkFeature = Runtime.version().feature(),
            )
        }

        val warmStarted = System.nanoTime()
        StartupCache.init(cacheDir)
        val warmMods = timed("warm mod scan") { ModScanner.scan(modsDir) }
        val warmRules = timed("warm rule sets") { ModRuleSets.load(warmMods, loader) }
        timed("warm hook scan") { HookScanner.createTransformer(warmMods) }
        println("  warm total: ${elapsedMs(warmStarted)} ms")
        val warmFingerprint = timed("warm transform fingerprint") {
            TransformCacheKey.compute(
                gameJar = modsDir.listFiles().orEmpty().first(),
                loaderVersion = "benchmark",
                adapterId = "benchmark",
                adapterLocation = modsDir.listFiles().orEmpty().first().toURI().toURL(),
                layerLocations = warmMods.take(4).map { it.file.toURI().toURL() },
                modJars = warmMods.map { it.file },
                side = "client",
                jdkFeature = Runtime.version().feature(),
            )
        }

        val ledger = File(cacheDir, "startup-scan.v1.txt")
        println("ledger: ${ledger.length() / 1024} KiB, ${ledger.readLines().size} lines")
        println(
            "results: cold=${coldMods.size} mods/${coldRules.problems.size} rule problems, " +
                "warm=${warmMods.size} mods/${warmRules.problems.size} rule problems",
        )

        val consistent = coldMods.size == JARS &&
            warmMods.size == JARS &&
            coldRules.problems.size == warmRules.problems.size &&
            fingerprint != null &&
            fingerprint == warmFingerprint
        if (!consistent) {
            println("Scan benchmark: FAIL — the cold and warm passes disagree")
            exitProcess(1)
        }
    }

    private fun generate(modsDir: File) {
        for (jarIndex in 0 until JARS) {
            val withRules = jarIndex % 5 == 0
            JarOutputStream(File(modsDir, "pack-$jarIndex.jar").outputStream()).use { out ->
                for (classIndex in 0 until CLASSES_PER_JAR) {
                    val internalName = "omlbench/p$jarIndex/C$classIndex"
                    val annotation = when {
                        classIndex == 0 -> MOD_DESC
                        classIndex == 1 && withRules -> RULE_DESC
                        classIndex == 2 && withRules -> HOOKED_DESC
                        else -> null
                    }
                    out.putNextEntry(JarEntry("$internalName.class"))
                    out.write(classBytes(internalName, annotation, "bench$jarIndex"))
                    out.closeEntry()
                }
            }
        }
    }

    /** A minimal valid class; the annotation is the only marker the scanners care about. */
    private fun classBytes(internalName: String, annotation: String?, modId: String): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null)
        writer.visitField(Opcodes.ACC_PRIVATE, "value", "Ljava/lang/String;", null, null).visitEnd()
        annotation?.let { descriptor ->
            writer.visitAnnotation(descriptor, true).apply {
                if (descriptor == MOD_DESC) visit("id", modId)
                visitEnd()
            }
        }
        val constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
        constructor.visitCode()
        constructor.visitVarInsn(Opcodes.ALOAD, 0)
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        constructor.visitInsn(Opcodes.RETURN)
        constructor.visitMaxs(1, 1)
        constructor.visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }

    /** One full pass over the pack with the old scanners' read mode and marker check. */
    private fun legacyScan(
        modsDir: File,
        label: String,
        flags: Int,
        matches: (ClassNode) -> Boolean,
    ) {
        var hits = 0
        val started = System.nanoTime()
        for (jar in modsDir.listFiles().orEmpty().sortedBy { it.name }) {
            JarFile(jar).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (!entry.name.endsWith(".class") || entry.name.startsWith("META-INF/")) continue
                    zip.getInputStream(entry).use { stream ->
                        val node = ClassNode(Opcodes.ASM9)
                        ClassReader(stream).accept(node, flags)
                        if (matches(node)) hits++
                    }
                }
            }
        }
        println("  $label: ${elapsedMs(started)} ms ($hits matches)")
    }

    private fun annotationsOf(node: ClassNode) =
        (node.visibleAnnotations ?: emptyList()) + (node.invisibleAnnotations ?: emptyList())

    private fun <T> timed(label: String, block: () -> T): T {
        val started = System.nanoTime()
        val value = block()
        println("  $label: ${elapsedMs(started)} ms")
        return value
    }

    private fun elapsedMs(started: Long): Long = (System.nanoTime() - started) / 1_000_000
}
