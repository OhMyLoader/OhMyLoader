package org.ohmyloader.core.mod

import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.ohmyloader.api.core.OmlLog
import org.ohmyloader.core.cache.StartupCache
import org.ohmyloader.core.diagnostics.Diagnostics
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.Callable
import java.util.concurrent.Executors

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
        val jars = ModsDirectory.files(modsDir, ".jar")
        hashMissing(jars)
        for (jarFile in jars) {
            // Happy path: the ledger's stat stamp matches and the @Mod records come straight from
            // the cache — no hashing, no jar opened. A miss goes through the shared scan; the
            // missing digests were already computed in parallel before this loop.
            val known = StartupCache.statSha(jarFile)
            val cached = known?.let(StartupCache::modsFor)
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
            val scan = JarClassIndex.scan(jarFile)
            if (scan == null) {
                // The native walker could not read the jar. No Java walk exists to fall back on:
                // the jar fails its own load with a report, and nothing from it enters the run or
                // the ledger — a broken setup must keep shouting until it is fixed.
                OmlLog.error(
                    "ModScanner",
                    "skipping ${jarFile.name}: the native scanner cannot read it (corrupt or unsupported " +
                        "jar structure). Rebuild or re-export the jar; there is no Java fallback.",
                )
                continue
            }
            val sha = scan.sha
            val index = scan.candidates
            val scanned = if (index.mods.isEmpty() && index.reserved.isEmpty()) {
                // A library jar: nothing to parse, and the empty record is still cached below.
                ScanResult(emptyList(), reservedHit = false)
            } else {
                scanJar(jarFile, index)
            }
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

    private class HashResult(val path: String, val sha: String, val size: Long, val mtime: Long)

    /**
     * Hashes the jars whose stat stamp no longer matches on a small pool: a cold modpack is
     * dominated by reading every jar once, and the digests are independent. Registration is
     * sequential — the ledger maps are not thread-safe, and the digest is the expensive part.
     */
    private fun hashMissing(jars: List<File>) {
        val missing = jars.filter { StartupCache.statSha(it) == null }
        if (missing.isEmpty()) return
        if (missing.size == 1) {
            val file = missing.single()
            val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
            StartupCache.rememberHash(
                StartupCache.canonicalPathOf(file),
                StartupCache.digest(file),
                attributes.size(),
                attributes.lastModifiedTime().toMillis(),
            )
            return
        }
        val pool = Executors.newFixedThreadPool(minOf(8, missing.size))
        try {
            val results = missing.map { file ->
                pool.submit(Callable {
                    val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
                    HashResult(
                        StartupCache.canonicalPathOf(file),
                        StartupCache.digest(file),
                        attributes.size(),
                        attributes.lastModifiedTime().toMillis(),
                    )
                })
            }
            for (result in results) {
                val value = try {
                    result.get()
                } catch (e: java.util.concurrent.ExecutionException) {
                    throw e.cause ?: e
                }
                StartupCache.rememberHash(value.path, value.sha, value.size, value.mtime)
            }
        } finally {
            pool.shutdown()
        }
    }

    /**
     * [index] is the shared prefiltered walk; every `@Mod` candidate carries its class bytes, so
     * the annotation values are read from memory and not a single jar is opened here.
     */
    private fun scanJar(jarFile: File, index: JarClassIndex.Candidates): ScanResult {
        if (index.reserved.isNotEmpty()) return refused(jarFile, index.reserved)
        val found = mutableListOf<ModContainer>()
        for (candidate in index.mods) {
            // The ABI guarantees bytes for `@Mod` records; a record without them is a native-side
            // contract violation, not a jar shape to work around.
            parseModClass(requireNotNull(candidate.bytes), jarFile)?.let(found::add)
        }
        return ScanResult(found, reservedHit = false)
    }

    private fun parseModClass(bytes: ByteArray, jarFile: File): ModContainer? =
        parseModClass(ClassReader(bytes), jarFile)

    private fun parseModClass(reader: ClassReader, jarFile: File): ModContainer? {
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
