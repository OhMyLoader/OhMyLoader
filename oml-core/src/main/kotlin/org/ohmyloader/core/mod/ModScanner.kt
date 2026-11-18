package org.ohmyloader.core.mod

import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.ohmyloader.api.core.OmlLog
import org.ohmyloader.core.cache.StartupCache
import org.ohmyloader.core.diagnostics.Diagnostics
import java.io.File
import java.io.InputStream
import java.util.jar.JarFile

object ModScanner {

    /**
     * Scans every jar under the mods directory, subdirectories included, for **all** entry classes
     * annotated with @Mod. Dot-directories are skipped by [ModsDirectory].
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
        val started = System.nanoTime()
        val found = ArrayList<ModContainer>()
        for (jarFile in ModsDirectory.files(modsDir, ".jar")) {
            // Happy path: the ledger's stat stamp matches and the @Mod records come straight from
            // the cache — no hashing, no jar opened. A miss hashes the jar once and re-scans.
            val sha = StartupCache.statSha(jarFile) ?: StartupCache.hash(jarFile)
            val cached = StartupCache.modsFor(sha)
            if (cached != null) {
                for (record in cached) {
                    found += ModContainer(
                        record.id, record.name, record.version, record.entryClass, jarFile,
                        record.dependencies.map(DependencySpec::parse),
                    )
                }
                continue
            }
            // Cold path: one shared prefiltered walk finds the `@Mod` candidates, so parsing is
            // restricted to those classes instead of every class in the jar; the same index is
            // reused by the rule-set and hook scanners later in this run.
            val scanned = scanJar(jarFile, JarClassIndex.of(jarFile, sha))
            // A reserved-namespace jar is an error path (logged on every scan, never cached) —
            // a broken setup must keep shouting until it is fixed.
            if (!scanned.reservedHit) {
                StartupCache.rememberMods(
                    sha,
                    scanned.mods.map {
                        StartupCache.CachedMod(it.id, it.name, it.version, it.entryClass, it.dependencies.map(DependencySpec::display))
                    },
                )
            }
            found += scanned.mods
        }
        Diagnostics.recordPhase("mod scan", System.nanoTime() - started)
        StartupCache.save()
        return found
    }

    private class ScanResult(val mods: List<ModContainer>, val reservedHit: Boolean)

    /**
     * [index] is the shared prefiltered walk when it could be built; `null` falls back to the
     * full walk (an unreadable jar then fails exactly as it did before the index existed).
     */
    private fun scanJar(jarFile: File, index: JarClassIndex.Candidates?): ScanResult {
        val found = mutableListOf<ModContainer>()
        if (index != null && index.reserved.isNotEmpty()) return refused(jarFile, index.reserved)
        val reserved = mutableListOf<String>()
        JarFile(jarFile).use { zip ->
            if (index != null) {
                // Candidates only: the index already saw every entry and matched the descriptor.
                for (internalName in index.mods) {
                    val entry = zip.getEntry("$internalName.class") ?: continue
                    zip.getInputStream(entry).use { stream ->
                        parseModClass(stream, jarFile)?.let(found::add)
                    }
                }
            } else {
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    // Directory entries, `kotlin/…`, `.json` payloads: only class files are read as bytecode.
                    if (!entry.name.endsWith(".class")) continue
                    val packed = JarClassIndex.reservedClassName(entry.name)
                    if (packed != null) {
                        reserved += packed
                        continue
                    }
                    // MR-JAR versioned copies are not entry points: scanning them would produce a
                    // second ModContainer for the same modId and initialize the mod twice.
                    if (entry.name.startsWith("META-INF/")) continue

                    zip.getInputStream(entry).use { stream ->
                        parseModClass(stream, jarFile)?.let(found::add)
                    }
                }
            }
        }
        if (reserved.isNotEmpty()) return refused(jarFile, reserved)
        return ScanResult(found, reservedHit = false)
    }

    private fun parseModClass(stream: InputStream, jarFile: File): ModContainer? {
        val reader = ClassReader(stream)
        val classNode = ClassNode()
        reader.accept(classNode, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG)

        val modAnnotation = classNode.visibleAnnotations?.find {
            it.desc == "Lorg/ohmyloader/api/core/Mod;"
        } ?: return null

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

        return ModContainer(
            modId, name, version, classNode.name.replace('/', '.'), jarFile, dependencies,
        )
    }

    private fun refused(jarFile: File, reserved: List<String>): ScanResult {
        val listed = reserved.take(3).joinToString() +
            if (reserved.size > 3) ", … (${reserved.size} in total)" else ""
        OmlLog.error(
            "ModScanner",
            "skipping ${jarFile.name}: it packs $listed under ${JarClassIndex.RESERVED_NAMESPACE}, which the launcher " +
                "layer defines parent-first — a mod-supplied copy replaces OML's own classes there, and " +
                "the mod's game references fail to resolve in that layer. Ship the mod under its own " +
                "namespace and keep oml-api-core on the compile classpath instead of bundling it (the official " +
                "Gradle plugin provides it).",
        )
        return ScanResult(emptyList(), reservedHit = true)
    }
}
