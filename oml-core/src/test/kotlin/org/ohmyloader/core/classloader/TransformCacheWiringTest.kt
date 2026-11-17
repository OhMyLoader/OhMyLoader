package org.ohmyloader.core.classloader

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.FieldNode
import org.ohmyloader.core.cache.TransformCache
import org.ohmyloader.core.transformer.IClassTransformer
import org.ohmyloader.core.transformer.TransformContext
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** A cache hit must bypass the transform pipeline and still define the previously transformed bytes. */
class TransformCacheWiringTest {

    @TempDir
    lateinit var dir: Path

    @Test
    fun `a second loader consumes the cached bytes without running the transformer`() {
        val target = "com.example.cache.Target"
        writeClass(dir.toFile(), target)
        val parent = URLClassLoader(arrayOf(dir.toUri().toURL()), javaClass.classLoader)
        try {
            val cache = FakeCache()

            val first = OMLClassLoader(emptyArray(), parent)
            try {
                first.registerTransformer(MarkerTransformer(failOnCall = false))
                first.installTransformCache(cache, "ns")
                val loaded = first.loadClass(target)

                assertNotNull(loaded.getDeclaredField("marker"))
                assertEquals(setOf("ns/${target.replace('.', '/')}"), cache.entries.keys)
            } finally {
                first.close()
            }

            val second = OMLClassLoader(emptyArray(), parent)
            try {
                second.registerTransformer(MarkerTransformer(failOnCall = true))
                second.installTransformCache(cache, "ns")
                val cached = second.loadClass(target)

                assertNotNull(cached.getDeclaredField("marker"), "the cached bytes must be the transformed class")
            } finally {
                second.close()
            }
        } finally {
            parent.close()
        }
    }

    private class FakeCache : TransformCache {
        val entries = HashMap<String, ByteArray>()

        override fun get(namespace: String, internalName: String): ByteArray? =
            entries["$namespace/$internalName"]

        override fun put(namespace: String, internalName: String, bytes: ByteArray) {
            entries["$namespace/$internalName"] = bytes
        }
    }

    private class MarkerTransformer(private val failOnCall: Boolean) : IClassTransformer {
        override val id: String = "marker"

        override fun appliesTo(internalName: String): Boolean = internalName == TARGET

        override fun transform(context: TransformContext): Boolean {
            if (failOnCall) error("the transform cache was not consulted")
            context.node.fields.add(FieldNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "marker", "I", null, 7))
            return true
        }
    }

    private fun writeClass(dir: File, className: String) {
        val internalName = className.replace('.', '/')
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null)
        writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        writer.visitEnd()
        File(dir, "$internalName.class").apply {
            parentFile.mkdirs()
            writeBytes(writer.toByteArray())
        }
    }

    private companion object {
        const val TARGET = "com/example/cache/Target"
    }
}
