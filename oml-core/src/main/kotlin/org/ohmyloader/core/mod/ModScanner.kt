package org.ohmyloader.core.mod

import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.ohmyloader.api.OmlLog
import java.io.File
import java.util.jar.JarFile

object ModScanner {

    /**
     * Scans every jar under the mods directory for **all** entry classes annotated with @Mod.
     *
     * A single jar may carry several mods (or one mod plus a library with an entry point), so
     * scanning continues after the first hit — returning on the first match silently dropped every
     * subsequent mod in the same file, along with all of their event subscriptions.
     *
     * A jar that packs classes under [RESERVED_NAMESPACE] is skipped whole: that namespace is
     * parent-first, so the launcher layer — which carries the game jar but none of the game's
     * libraries — defines those copies, and every `net.minecraft.*` reference inside them resolves
     * there. Refusing at scan time is the only point that still has the jar name to report with.
     */
    fun scan(modsDir: File): List<ModContainer> {
        if (!modsDir.isDirectory) return emptyList()
        val jars = modsDir.listFiles { f -> f.isFile && f.name.endsWith(".jar") } ?: return emptyList()
        return jars.flatMap { scanJar(it) }
    }

    private fun scanJar(jarFile: File): List<ModContainer> {
        val found = mutableListOf<ModContainer>()
        val reserved = mutableListOf<String>()
        JarFile(jarFile).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                reservedClassName(entry.name)?.let { packed ->
                    reserved += packed
                    continue
                }
                // MR-JAR versioned copies are not entry points: scanning them would produce a
                // second ModContainer for the same modId and initialize the mod twice.
                if (entry.name.startsWith("META-INF/")) continue

                zip.getInputStream(entry).use { stream ->
                    val reader = ClassReader(stream)
                    val classNode = ClassNode()
                    reader.accept(classNode, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG)

                    val modAnnotation = classNode.visibleAnnotations?.find {
                        it.desc == "Lorg/ohmyloader/api/Mod;"
                    } ?: return@use

                    // Annotation values contain only explicitly set key-value pairs (in pairs); omitted entries fall back to defaults
                    var modId = "unknown"
                    var name = ""
                    var version = "1.0.0"
                    var dependencies = emptyList<DependencySpec>()
                    val values = modAnnotation.values.orEmpty()
                    var i = 0
                    while (i + 1 < values.size) {
                        when (values[i]) {
                            "id" -> modId = values[i + 1] as? String ?: modId
                            "name" -> name = values[i + 1] as? String ?: ""
                            "version" -> version = values[i + 1] as? String ?: "1.0.0"
                            // an array annotation member arrives as ArrayList<String>
                            "dependencies" -> dependencies = (values[i + 1] as? ArrayList<*>)
                                .orEmpty()
                                .filterIsInstance<String>()
                                .map(DependencySpec::parse)
                        }
                        i += 2
                    }
                    if (name.isEmpty()) name = modId

                    found += ModContainer(
                        modId, name, version, classNode.name.replace('/', '.'), jarFile, dependencies,
                    )
                }
            }
        }
        if (reserved.isNotEmpty()) {
            val listed = reserved.take(3).joinToString() +
                if (reserved.size > 3) ", … (${reserved.size} in total)" else ""
            OmlLog.error(
                "ModScanner",
                "skipping ${jarFile.name}: it packs $listed under $RESERVED_NAMESPACE, which the launcher " +
                    "layer defines parent-first — a mod-supplied copy replaces OML's own classes there, and " +
                    "the mod's game references fail to resolve in that layer. Ship the mod under its own " +
                    "namespace and keep oml-api on the compile classpath instead of bundling it (the official " +
                    "Gradle plugin provides it).",
            )
            return emptyList()
        }
        return found
    }

    /** Entries that would define a class in OML's own namespace: both the base copy and a versioned copy. */
    private fun reservedClassName(entryName: String): String? {
        if (!entryName.endsWith(".class")) return null
        val path = if (entryName.startsWith("META-INF/versions/")) {
            entryName.removePrefix("META-INF/versions/").substringAfter('/')
        } else {
            entryName
        }
        return if (path.startsWith("org/ohmyloader/")) path.removeSuffix(".class").replace('/', '.') else null
    }

    private const val RESERVED_NAMESPACE = "org.ohmyloader."
}
