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

/**
 * Reproduces the MR-JAR shape: an @Mod class whose base copy is shadowed by a
 * `META-INF/versions/` copy. Scanning both yields two containers for one modId —
 * preInit/init run twice and every event listener is registered twice.
 */
class ModScannerTest {

    private fun modClassBytes(internalName: String, modId: String): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null)
        writer.visitAnnotation("Lorg/ohmyloader/api/Mod;", true).apply {
            visit("id", modId)
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun jarWith(vararg entryNames: String): File {
        val manifest = Manifest().apply { mainAttributes.putValue("Multi-Release", "true") }
        val file = File(createTempDirectory("oml-modscan").toFile(), "mod.jar")
        JarOutputStream(file.outputStream(), manifest).use { jar ->
            for (name in entryNames) {
                jar.putNextEntry(JarEntry(name))
                jar.write(modClassBytes("org/example/TestMod", "testmod"))
                jar.closeEntry()
            }
        }
        return file
    }

    @Test
    fun `versioned copies inside META-INF do not produce a second container`() {
        val modsDir = createTempDirectory("oml-mods").toFile()
        jarWith(
            "org/example/TestMod.class",
            "META-INF/versions/21/org/example/TestMod.class",
        ).copyTo(File(modsDir, "mod.jar"))

        val found = ModScanner.scan(modsDir)
        assertEquals(1, found.size, "the base copy is the entry point; the versioned copy is not")
        assertEquals("testmod", found.single().id)
        assertEquals("org.example.TestMod", found.single().entryClass)
    }

    @Test
    fun `several mods in one jar are all discovered`() {
        val modsDir = createTempDirectory("oml-mods").toFile()
        val file = File(modsDir, "two-mods.jar")
        JarOutputStream(file.outputStream()).use { jar ->
            for ([name, id] in listOf("org/example/One.class" to "one", "org/example/Two.class" to "two")) {
                jar.putNextEntry(JarEntry(name))
                jar.write(modClassBytes(name.removeSuffix(".class"), id))
                jar.closeEntry()
            }
        }

        val found = ModScanner.scan(modsDir)
        assertEquals(listOf("one", "two"), found.map { it.id }.sorted())
    }
}
