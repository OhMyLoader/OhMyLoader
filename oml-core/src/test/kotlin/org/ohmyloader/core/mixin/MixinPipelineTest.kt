package org.ohmyloader.core.mixin

import omltest.pipeline.PipelineMixin
import omltest.pipeline.PipelineRecorder
import omltest.pipeline.PipelineTarget
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode

import org.ohmyloader.core.mod.ModContainer
import org.ohmyloader.core.transformer.TransformContext
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The one test that walks the whole real pipeline — annotation mixin compiled into a jar, scanned by
 * `MixinScanner`, merged and injected by `MixinTransformer`, defined through a class loader, called —
 * because every step is testable in isolation and none of them proves the steps are *wired together*:
 * a scanner that produces rules the transformer never sees, or a merge whose handler the injection
 * cannot resolve, passes every per-step test and does nothing at runtime.
 */
class MixinPipelineTest {

    // ---------- the real pipeline: jar → scanner → transformer → defineClass → call ----------

    @Test
    fun `an annotated mixin rides the full real pipeline`() {
        PipelineRecorder.hits.set(0)
        val jar = jarOf(PipelineTarget::class.java, PipelineMixin::class.java)
        val mod = ModContainer(
            id = "pipeline_mod", name = "Pipeline Mod", version = "0.1.0",
            entryClass = "omltest.pipeline.PipelineMixin", file = jar,
        )

        val transformer = MixinScanner.createTransformer(listOf(mod))
        assertNotNull(transformer, "the scanner must produce a transformer for an annotated mixin")
        assertTrue(transformer.appliesTo("omltest/pipeline/PipelineTarget"))

        val node = classNode<PipelineTarget>()
        assertTrue(transformer.transform(TransformContext("omltest/pipeline/PipelineTarget", node)))

        val clazz = define(node)
        val instance = clazz.getDeclaredConstructor().newInstance()
        assertEquals(
            "orig",
            clazz.methods.first { it.name == "tag" }.invoke(instance),
            "the merge must not change the method's own behavior",
        )
        assertEquals(1, PipelineRecorder.hits.get(), "the handler must have run exactly once per call")
    }

    // ---------- the concurrency shape: one transformer, many distinct targets, parallel ----------

    @Test
    fun `distinct targets merge correctly when transformed in parallel`() {
        val count = 16
        val merges = (0 until count).map { index ->
            ClassMerger.MixinClass(
                modId = "pipeline_mod",
                className = "omltest/pipeline/GenMixin$index",
                targetInternal = "omltest/pipeline/GenTarget$index",
                node = genMixin("omltest/pipeline/GenMixin$index", "omltest/pipeline/GenTarget$index"),
            )
        }
        val transformer = MixinScanner.createTransformer(
            mods = emptyList(),
            extraMerges = merges,
        ) ?: error("the scanner must produce a transformer for merge-only candidates")

        val pool = Executors.newFixedThreadPool(8)
        val done = CountDownLatch(count)
        val failures = java.util.Collections.synchronizedList(mutableListOf<String>())
        try {
            for (index in 0 until count) {
                pool.submit {
                    try {
                        val name = "omltest/pipeline/GenTarget$index"
                        val node = genTarget(name)
                        assertTrue(transformer.appliesTo(name))
                        assertTrue(transformer.transform(TransformContext(name, node)))
                        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
                        node.accept(writer)
                        val bytes = writer.toByteArray()
                        val clazz = define(name.replace('/', '.'), bytes)
                        val instance = clazz.getDeclaredConstructor().newInstance()
                        val out = clazz.methods.first { it.name == "tag" }.invoke(instance)
                        if (out != "$index") failures += "GenTarget$index returned $out"
                        if (node.methods.first { it.name == "onTag" }.instructions.size() == 0) {
                            failures += "GenTarget$index lost its merged handler"
                        }
                    } catch (t: Throwable) {
                        failures += "GenTarget$index: $t"
                    } finally {
                        done.countDown()
                    }
                }
            }
            assertTrue(done.await(30, TimeUnit.SECONDS), "the parallel transforms did not finish")
        } finally {
            pool.shutdownNow()
        }
        assertEquals(emptyList(), failures, failures.joinToString("\n"))
    }

    // ---------- helpers ----------

    /** Packs the fixture classes' runtime bytes into the jar shape `scanMod` reads. */
    private fun jarOf(vararg classes: Class<*>): File {
        val jar = File.createTempFile("pipeline-mod", ".jar").apply { deleteOnExit() }
        JarOutputStream(jar.outputStream()).use { out ->
            for (clazz in classes) {
                val entryName = clazz.name.replace('.', '/') + ".class"
                out.putNextEntry(JarEntry(entryName))
                out.write(javaClass.classLoader.getResourceAsStream(entryName)!!.readBytes())
                out.closeEntry()
            }
        }
        return jar
    }

    private inline fun <reified T> classNode(): ClassNode =
        ClassNode(Opcodes.ASM9).also { node ->
            ClassReader(T::class.java.classLoader.getResourceAsStream(T::class.java.name.replace('.', '/') + ".class")!!
                .readBytes()).accept(node, ClassReader.EXPAND_FRAMES)
        }

    private fun define(node: ClassNode): Class<*> {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        node.accept(writer)
        val bytes = writer.toByteArray()
        return define(node.name.replace('/', '.'), bytes)
    }

    /** The protected defineClass is callable from a subclass body — no reflection, no module opens. */
    private fun define(name: String, bytes: ByteArray): Class<*> =
        object : ClassLoader(javaClass.classLoader) {
            fun define(): Class<*> = defineClass(name, bytes, 0, bytes.size)
        }.define()

    /** `class GenTarget<n> { String tag() → "orig<n>" }` — one distinct transform target. */
    private fun genTarget(name: String): ClassNode = ClassNode(Opcodes.ASM9).apply {
        visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null)
        visitField(Opcodes.ACC_PRIVATE, "marker", "Ljava/lang/String;", null, null).visitEnd()
        visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        visitMethod(Opcodes.ACC_PUBLIC, "tag", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn(name.substringAfterLast("GenTarget"))
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        visitEnd()
    }

    /**
     * `@Mixin(GenTarget)` in merge model: a `@Unique` marker field plus a HEAD `@Inject` handler,
     * hand-built with the same annotation shapes the Kotlin fixtures compile to.
     */
    private fun genMixin(name: String, targetInternal: String): ClassNode = ClassNode(Opcodes.ASM9).apply {
        visit(
            Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object",
            arrayOf("Lorg/ohmyloader/api/mixin/Mixin;"),
        )
        visitAnnotation("Lorg/ohmyloader/api/mixin/Mixin;", true).apply {
            visit("target", targetInternal.replace('/', '.'))
            visitEnd()
        }
        visitField(
            Opcodes.ACC_PRIVATE, "merged", "Z", null, null,
        ).apply {
            visitAnnotation("Lorg/ohmyloader/api/mixin/Unique;", true).visitEnd()
            visitEnd()
        }
        visitMethod(
            Opcodes.ACC_PUBLIC, "onTag", "(Lorg/ohmyloader/api/mixin/CallbackInfo;)V", null, null,
        ).apply {
            visitAnnotation("Lorg/ohmyloader/api/mixin/Inject;", true).apply {
                visitEnum("at", "Lorg/ohmyloader/api/mixin/At;", "HEAD")
                visit("method", "tag")
                visitEnd()
            }
            visitCode()
            visitFieldInsn(
                Opcodes.GETSTATIC, "org/ohmyloader/core/mixin/GenRecorder", "hits", "Ljava/util/concurrent/atomic/AtomicInteger;",
            )
            visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, "java/util/concurrent/atomic/AtomicInteger", "incrementAndGet", "()I", false,
            )
            visitInsn(Opcodes.POP)
            visitInsn(Opcodes.RETURN)
            visitMaxs(1, 2)
            visitEnd()
        }
        visitEnd()
    }
}

/** The handler's write target for the generated mixins; lives here so the bytecode stays simple. */
/** The handler's write target for the generated mixins; lives here so the bytecode stays simple. */
object GenRecorder {
    val hits = java.util.concurrent.atomic.AtomicInteger(0)
}
