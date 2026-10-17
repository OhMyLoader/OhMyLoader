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
import kotlin.test.assertTrue

/**
 * Reproduces the MR-JAR shape: an @Mod class whose base copy is shadowed by a
 * `META-INF/versions/` copy. Scanning both yields two containers for one modId —
 * preInit/init run twice and every event listener is registered twice.
 *
 * Also reproduces the reserved-namespace shape: a jar that carries `org/ohmyloader/` classes of its own.
 * The launcher layer defines that prefix parent-first, so without a scan-time refusal the mod either
 * replaces OML's own classes or dies later with `NoClassDefFoundError` on a game library the launcher
 * layer never had — and by then the jar name is gone from the message.
 */
class ModScannerTest {

    /** Strips the MR-JAR wrapper so the packed bytes declare the name their entry path implies. */
    private val versionedPrefix = Regex("^META-INF/versions/[^/]+/")

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

    @Test
    fun `non-class entries are never read as bytecode`() {
        // What a real mod jar actually carries besides classes: directory entries, `.json` data, a
        // `kotlin/…` module descriptor. Any of them reaching the class reader aborts the whole scan —
        // "Unsupported class file version" from a text file — so a jar with resources must still yield
        // its mods. This is the shape the reserved-namespace check nearly broke.
        val modsDir = createTempDirectory("oml-mods").toFile()
        JarOutputStream(File(modsDir, "with-resources.jar").outputStream()).use { jar ->
            jar.putNextEntry(JarEntry("data/")); jar.closeEntry()
            jar.putNextEntry(JarEntry("data/table.json"))
            jar.write("""{"ten_thousand": true}""".toByteArray()); jar.closeEntry()
            jar.putNextEntry(JarEntry("kotlin/foo.kotlin_module"))
            jar.write(ByteArray(24)); jar.closeEntry()
            jar.putNextEntry(JarEntry("org/example/Real.class"))
            jar.write(modClassBytes("org/example/Real", "real")); jar.closeEntry()
        }

        val found = ModScanner.scan(modsDir)

        assertEquals(listOf("real"), found.map { it.id })
    }

    /** One real mod entry point plus [reservedEntries], which decide whether the jar is refused. */
    private fun modsDirWith(vararg reservedEntries: String): File {
        val modsDir = createTempDirectory("oml-mods").toFile()
        JarOutputStream(File(modsDir, "packed.jar").outputStream()).use { jar ->
            for (name in listOf("org/example/Real.class") + reservedEntries) {
                jar.putNextEntry(JarEntry(name))
                jar.write(modClassBytes(versionedPrefix.replace(name, "").removeSuffix(".class"), "real"))
                jar.closeEntry()
            }
        }
        return modsDir
    }

    @Test
    fun `a jar that packs oml's own namespace is skipped whole`() {
        val modsDir = modsDirWith("org/ohmyloader/api/Mod.class")

        val found = ModScanner.scan(modsDir)

        assertEquals(emptyList(), found.map { it.id }, "a packed loader class disqualifies every mod in that jar")
    }

    @Test
    fun `a versioned copy under oml's namespace is refused too`() {
        // The base copy is absent here, so a JVM reading this jar defines the versioned one — same namespace,
        // same damage, and the reason the check does not simply skip META-INF on its way to the refusal.
        val modsDir = modsDirWith("META-INF/versions/21/org/ohmyloader/api/Mod.class")

        assertEquals(emptyList(), ModScanner.scan(modsDir).map { it.id })
    }

    @Test
    fun `a jar that stays out of oml's namespace is scanned normally`() {
        // `ohmyloader_not` pins the boundary: the reserved namespace is the prefix with its dot, not the word.
        val modsDir = modsDirWith(
            "org/example/Extra.class",
            "META-INF/versions/21/org/example/Extra.class",
            "org/ohmyloader_not/Thing.class",
        )

        val found = ModScanner.scan(modsDir)

        assertEquals(3, found.size, "only the base copies are entry points; the jar is not refused")
        assertTrue(found.all { it.id == "real" })
    }
}
