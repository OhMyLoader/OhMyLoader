package org.ohmyloader.core.mod

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The shared prefiltered walk: each marker must be found exactly where the corresponding scanner
 * looks, versioned copies stay out, and a second consumer reuses the result instead of walking
 * the jar again.
 */
class JarClassIndexTest {

    private fun classBytes(internalName: String, annotation: String?): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null)
        annotation?.let { writer.visitAnnotation(it, true).visitEnd() }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun jar(vararg entries: Pair<String, String?>, multiRelease: Boolean = false): File {
        val file = File(createTempDirectory("oml-index").toFile(), "mod.jar")
        val manifest = Manifest().apply {
            mainAttributes.putValue("Manifest-Version", "1.0")
            if (multiRelease) mainAttributes.putValue("Multi-Release", "true")
        }
        JarOutputStream(file.outputStream(), manifest).use { out ->
            for ((name, annotation) in entries) {
                out.putNextEntry(JarEntry(name))
                out.write(classBytes(name.removeSuffix(".class"), annotation))
                out.closeEntry()
            }
        }
        return file
    }

    @Test
    fun `each annotation marker is found in one prefiltered walk`() {
        val file = jar(
            "org/example/Mod.class" to "Lorg/ohmyloader/api/core/Mod;",
            "org/example/Rules.class" to "Lorg/ohmyloader/api/core/inject/RuleSource;",
            "org/example/Hooks.class" to "Lorg/ohmyloader/api/core/hook/Hooked;",
            "org/example/Merge.class" to "Lorg/ohmyloader/api/core/hook/Merged;",
            "org/example/Plain.class" to null,
        )

        val index = JarClassIndex.of(file, "index-each")!!

        assertEquals(listOf("org/example/Mod"), index.mods.map { it.internalName })
        assertEquals(listOf("org/example/Rules"), index.rules.map { it.internalName })
        assertEquals(listOf("org/example/Hooks", "org/example/Merge"), index.hooks.map { it.internalName })
        assertTrue(index.reserved.isEmpty())
    }

    @Test
    fun `an MR-JAR copy wins without producing a second candidate, and the index is reused`() {
        val file = jar(
            "org/example/Mod.class" to "Lorg/ohmyloader/api/core/Mod;",
            "META-INF/versions/21/org/example/Mod.class" to "Lorg/ohmyloader/api/core/Mod;",
            multiRelease = true,
        )

        val first = JarClassIndex.of(file, "index-reuse")!!
        assertEquals(
            listOf("org/example/Mod"),
            first.mods.map { it.internalName },
            "the versioned copy replaces the base copy; it is not a second entry point",
        )
        assertEquals(21, first.mods.single().version, "the highest applicable MR-JAR version wins")

        // The second consumer must answer from the index: deleting the file cannot change it.
        file.delete()
        assertSame(first, JarClassIndex.of(file, "index-reuse"))
    }

    @Test
    fun `a versioned copy is ignored when the jar does not declare Multi-Release`() {
        val file = jar(
            "org/example/Mod.class" to "Lorg/ohmyloader/api/core/Mod;",
            "META-INF/versions/21/org/example/Mod.class" to "Lorg/ohmyloader/api/core/Mod;",
        )

        val index = JarClassIndex.of(file, "index-no-mr")!!

        assertEquals(0, index.mods.single().version, "without Multi-Release the base copy is the class")
    }

    @Test
    fun `classes in oml's namespace are reported instead of indexed`() {
        val file = jar(
            "org/example/Mod.class" to "Lorg/ohmyloader/api/core/Mod;",
            "org/ohmyloader/api/core/Mod.class" to null,
            "META-INF/versions/21/org/ohmyloader/api/core/Hooked.class" to null,
            multiRelease = true,
        )

        val index = JarClassIndex.of(file, "index-reserved")!!

        assertEquals(
            listOf("org.ohmyloader.api.core.Mod", "org.ohmyloader.api.core.Hooked"),
            index.reserved,
        )
        assertEquals(listOf("org/example/Mod"), index.mods.map { it.internalName })
    }
}
