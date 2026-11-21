package org.ohmyloader.core.mod

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.ohmyloader.core.compression.NativeTestLibraries
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.zip.CRC32
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Differential gate: the native walker and the test-only Java oracle ([JavaScanOracle]) must
 * produce the exact same candidate sets and masks for every shape the loader accepts — including
 * stored and deflated entries, MR-JAR winners, a jar that is not multi-release, reserved
 * namespaces, empty jars and malformed class bytes. Skipped when the in-repo native cannot be
 * built.
 */
class NativeDiffTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun loadNativeLibrary() {
            NativeTestLibraries.ensureOmlNative()
        }
    }

    private val mod = "Lorg/ohmyloader/api/core/Mod;"
    private val rule = "Lorg/ohmyloader/api/core/inject/RuleSource;"
    private val hooked = "Lorg/ohmyloader/api/core/hook/Hooked;"
    private val merged = "Lorg/ohmyloader/api/core/hook/Merged;"

    @Test
    fun `deflated annotations agree`() = compare(
        jar(
            "mixed", multiRelease = false,
            "org/example/Mod.class" to listOf(mod),
            "org/example/Rules.class" to listOf(rule),
            "org/example/Hooks.class" to listOf(hooked),
            "org/example/Merge.class" to listOf(merged),
            "org/example/Both.class" to listOf(mod, hooked),
            "org/example/Plain.class" to emptyList(),
        ),
    )

    @Test
    fun `stored entries agree`() = compare(storedJar("stored", "org/example/Mod.class", listOf(mod)))

    @Test
    fun `MR-JAR winners agree`() = compare(
        jar(
            "mrjar", multiRelease = true,
            "org/example/Mod.class" to listOf(mod),
            "META-INF/versions/17/org/example/Mod.class" to listOf(rule),
            "META-INF/versions/21/org/example/Mod.class" to listOf(hooked),
            "META-INF/versions/27/org/example/Mod.class" to listOf(merged),
            "META-INF/versions/21/org/example/Only21.class" to listOf(mod),
        ),
    )

    @Test
    fun `a non-multi-release jar keeps the base copy`() = compare(
        jar(
            "not-mrjar", multiRelease = false,
            "org/example/Mod.class" to listOf(mod),
            "META-INF/versions/21/org/example/Mod.class" to listOf(hooked),
        ),
    )

    @Test
    fun `empty jar agrees`() = compare(jar("empty", multiRelease = false))

    @Test
    fun `malformed class bytes agree`() {
        val file = jar("malformed", multiRelease = false, "org/example/Broken.class" to null)
        // Replace the entry with bytes that are not a class file at all.
        file.delete()
        JarOutputStream(file.outputStream(), manifest(false)).use { out ->
            out.putNextEntry(JarEntry("org/example/Broken.class"))
            out.write(ByteArray(64) { (it * 31).toByte() })
            out.closeEntry()
        }
        compare(file)
    }

    @Test
    fun `the built test mod jar agrees`() {
        val jar = File("../../OhMyLoaderTestMod/build/libs/oml-testmod-0.1.0.jar")
        assumeTrue(jar.isFile, "the TestMod jar has not been built")
        compare(jar)
    }

    @Test
    fun `reserved namespace agrees`() = compare(
        jar(
            "reserved", multiRelease = true,
            "org/ohmyloader/api/core/Mod.class" to listOf(mod),
            "META-INF/versions/21/org/ohmyloader/api/core/Hooked.class" to listOf(hooked),
        ),
    )

    private fun compare(file: File) {
        assumeTrue(NativeJarScanner.available(), "oml-native is not built for this platform")
        val java = JavaScanOracle.candidates(file)
        val native = NativeJarScanner.scan(file, Runtime.version().feature())?.let(JarClassIndex::fromNative)
        assertNotNull(native, "the native walker refused ${file.name}")
        assertEquals(signature(java), signature(native), "native and Java disagree on ${file.name}")
    }

    /** Masks are compared as marker bits; the reserved flag is covered by the reserved set. */
    private fun signature(candidates: JarClassIndex.Candidates): String =
        "mods=${names(candidates.mods)} " +
            "rules=${names(candidates.rules)} " +
            "hooks=${names(candidates.hooks)} " +
            "reserved=${candidates.reserved.sorted()}"

    private fun names(list: List<JarClassIndex.ClassCandidate>): List<String> =
        list.map { "${it.internalName}@${it.version}#${it.mask and 7}" }.sorted()

    private fun manifest(multiRelease: Boolean): Manifest = Manifest().apply {
        mainAttributes.putValue("Manifest-Version", "1.0")
        if (multiRelease) mainAttributes.putValue("Multi-Release", "true")
    }

    private fun jar(
        name: String,
        multiRelease: Boolean,
        vararg entries: Pair<String, List<String>?>,
    ): File {
        val file = File(createTempDirectory("oml-diff").toFile(), "$name.jar")
        JarOutputStream(file.outputStream(), manifest(multiRelease)).use { out ->
            for ((entryName, annotations) in entries) {
                out.putNextEntry(JarEntry(entryName))
                out.write(classBytes(entryName.removeSuffix(".class"), annotations.orEmpty()))
                out.closeEntry()
            }
        }
        return file
    }

    private fun storedJar(name: String, entryName: String, annotations: List<String>): File {
        val file = File(createTempDirectory("oml-diff").toFile(), "$name.jar")
        val bytes = classBytes(entryName.removeSuffix(".class"), annotations)
        val crc = CRC32().apply { update(bytes) }
        JarOutputStream(file.outputStream(), manifest(false)).use { out ->
            val entry = JarEntry(entryName).apply {
                method = JarEntry.STORED
                size = bytes.size.toLong()
                this.crc = crc.value
            }
            out.putNextEntry(entry)
            out.write(bytes)
            out.closeEntry()
        }
        return file
    }

    private fun classBytes(internalName: String, annotations: List<String>): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null)
        for (annotation in annotations) {
            writer.visitAnnotation(annotation, true).visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }
}
