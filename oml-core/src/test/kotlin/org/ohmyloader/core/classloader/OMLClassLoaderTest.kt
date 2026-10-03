package org.ohmyloader.core.classloader

import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File
import java.net.URLClassLoader
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * The loader's delegation contract, pinned because two of its consequences are invisible until they
 * bite at runtime:
 *
 * 1. **Adapter classes are defined by the main loader even when only the parent carries them.** That
 *    is why [OMLCore][org.ohmyloader.core.OMLCore] has to discover the adapter *through* the main
 *    loader: the injected hooks resolve adapter classes by the defining loader of the game class they
 *    were injected into, so an adapter instance from the parent would be a second copy carrying its
 *    own (empty) adapter-side singletons — content declared in one copy and materialized from the
 *    other, silently.
 * 2. **Everything else under `org.ohmyloader.` keeps a single identity** (parent-first), and game /
 *    mod classes are child-first. A change to either rule breaks class identity or the transform
 *    pipeline, and neither shows up as a compile error.
 */
class OMLClassLoaderTest {

    private val adapterClass = "org.ohmyloader.adapter.probe.AdapterProbe"
    private val coreClass = "org.ohmyloader.core.probe.CoreProbe"
    private val gameClass = "com.example.probe.GameProbe"

    /** A launcher/parent loader holding the class files, with the main loader having no URLs of its own. */
    private fun loaders(dir: File): Pair<URLClassLoader, OMLClassLoader> {
        val parent = URLClassLoader(arrayOf(dir.toURI().toURL()), javaClass.classLoader)
        return parent to OMLClassLoader(emptyArray(), parent)
    }

    @Test
    fun `an adapter class is redefined by the main loader even when only the parent ships it`() {
        // The installed layout puts the adapter in the bootstrap lib/ directory, so it is visible to
        // the parent only; the main loader still has to own the class (see the class KDoc).
        val dir = classDir()
        writeClass(dir, adapterClass)

        val [parent, main] = loaders(dir)
        val fromParent = parent.loadClass(adapterClass)

        assertSame(main, main.loadClass(adapterClass).classLoader, "the main loader must define the adapter class")
        assertNotSame(
            fromParent,
            main.loadClass(adapterClass),
            "the two loaders defining the same adapter class is exactly the split OMLCore.discoverAdapter guards against",
        )
    }

    @Test
    fun `a non-adapter loader class keeps a single identity through the parent`() {
        val dir = classDir()
        writeClass(dir, coreClass)

        val [parent, main] = loaders(dir)

        assertSame(
            parent.loadClass(coreClass),
            main.loadClass(coreClass),
            "org.ohmyloader. (outside the adapter prefix) is parent-first on purpose",
        )
    }

    @Test
    fun `a game or mod class is child-first`() {
        val dir = classDir()
        writeClass(dir, gameClass)

        val [parent, main] = loaders(dir)

        assertSame(main, main.loadClass(gameClass).classLoader, "game/mod classes must be defined by the main loader")
        assertNotSame(parent.loadClass(gameClass), main.loadClass(gameClass))
    }

    private var counter = 0

    private fun classDir(): File =
        File(System.getProperty("java.io.tmpdir"), "oml-classloader-test-${counter++}")
            .apply { deleteRecursively(); mkdirs() }

    /** Writes a minimal empty class for the dotted [className], so loader identity can be asserted without any dependency. */
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

        val file = File(dir, "$internalName.class")
        file.parentFile.mkdirs()
        file.writeBytes(writer.toByteArray())
    }
}
