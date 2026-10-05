package org.ohmyloader.core.mod

import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import java.io.File
import java.util.jar.JarFile

object ModScanner {

    /**
     * Scans every jar under the mods directory for **all** entry classes annotated with @Mod.
     *
     * A single jar may carry several mods (or one mod plus a library with an entry point), so
     * scanning continues after the first hit — returning on the first match silently dropped every
     * subsequent mod in the same file, along with all of their event subscriptions.
     */
    fun scan(modsDir: File): List<ModContainer> {
        if (!modsDir.isDirectory) return emptyList()
        val jars = modsDir.listFiles { f -> f.isFile && f.name.endsWith(".jar") } ?: return emptyList()
        return jars.flatMap { scanJar(it) }
    }

    private fun scanJar(jarFile: File): List<ModContainer> {
        val found = mutableListOf<ModContainer>()
        JarFile(jarFile).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (!entry.name.endsWith(".class")) continue
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
                                .mapNotNull { it as? String }
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
        return found
    }
}
