package org.ohmyloader.core.cache

import org.ohmyloader.api.core.OmlLog
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/**
 * The cross-run startup scan cache: `cache/loader/` under the game directory, holding
 *
 * - the **jar ledger** — one stat stamp `(path, size, mtime)` per scanned jar mapped to its SHA-256
 *   and the scan products derived from its bytes (the `@Mod` records, the `@RuleSource` classes) —
 *   so the three full mod-jar decompress-and-parse passes that used to run every launch become one
 *   pass per changed jar and pure stat hits otherwise;
 * - the **asset index** (`assets/`/`data/` keys of the mod jars and pack archives), keyed on the
 *   exact stat signature of the file set — it is built lazily inside the game's resource-reload
 *   window, and this takes a full zip walk out of that window.
 *
 * Trust model: a stat stamp that matches answers everything without hashing; any mismatch (or a
 * missing file) re-hashes that jar once and re-scans. A deleted jar simply stops being asked
 * about. Every load and write is defensive — a broken or truncated cache is discarded and
 * regenerated, never fatal. `uninstall` preserves the installer's `cache/` directory, and this
 * directory is safe to delete by hand at any time.
 *
 * Line format (tab-separated, backslash-escaped fields): `JAR` records the stat stamp and digest,
 * `MOD`/`RULE` hang scan products off the digest, `SHA` is an entry recorded without products (a
 * jar whose scan is still pending in this run).
 */
object StartupCache {

    /** One `@Mod` record as scanned out of a jar's `@Mod` annotation. */
    class CachedMod(
        val id: String,
        val name: String,
        val version: String,
        val entryClass: String,
        /** The dependencies in their original constraint spelling (`"other@>=1.2.0"`). */
        val dependencies: List<String>,
    )

    /** One `@RuleSource` class as discovered in a jar. */
    class CachedRule(val internalName: String, val id: String?)

    private var dir: File? = null
    private val jars = LinkedHashMap<String, JarStamp>()
    private val mods = HashMap<String, List<CachedMod>>()
    private val rules = HashMap<String, List<CachedRule>>()
    private val hookCarriers = HashMap<String, List<String>>()
    /** Per-run answer for [statSha]: the three scanners ask about the same jars in sequence. */
    private val statMemo = HashMap<String, String?>()
    /** `absolutePath -> canonicalPath`: canonicalization hits the filesystem on Windows. */
    private val canonicalPaths = java.util.concurrent.ConcurrentHashMap<String, String>()
    private var dirty = false

    /// The carrier-discovery contract version — bump when the scanner's carrier criteria change.
    private const val HOOKS_SCHEMA = "HOOKS1"

    private class JarStamp(val size: Long, val mtime: Long, val sha: String)

    /**
     * Points the cache at [cacheDir] and loads the ledger. Called once per run, before the first
     * scan; before that (and in tests) every lookup misses and every remember is a no-op.
     */
    fun init(cacheDir: File?) {
        dir = cacheDir
        jars.clear()
        mods.clear()
        rules.clear()
        hookCarriers.clear()
        statMemo.clear()
        canonicalPaths.clear()
        dirty = false
        if (cacheDir == null) return
        val file = ledgerFile(cacheDir)
        if (!file.isFile) return
        runCatching { load(file) }.onFailure {
            OmlLog.warn("StartupCache", "the startup scan cache is unreadable and was discarded (${it.message})")
            jars.clear()
            mods.clear()
            rules.clear()
            hookCarriers.clear()
        }
    }

    private fun load(file: File) {
        for (raw in file.readLines()) {
            // Only the line terminator is stripped — a trailing tab is a real field (an empty id,
            // empty deps), and `trim()` would silently drop every record that ends with one.
            val line = raw.trimEnd('\r')
            if (line.isEmpty() || line.startsWith("#")) continue
            val fields = line.split('\t').map(::unescape)
            when (fields.getOrNull(0)) {
                "JAR" -> if (fields.size == 5) {
                    jars[fields[1]] = JarStamp(fields[2].toLong(), fields[3].toLong(), fields[4])
                }

                "MOD" -> if (fields.size == 7) {
                    val sha = fields[1]
                    val record = CachedMod(fields[2], fields[3], fields[4], fields[5], fields[6].split("\u001F").filter { it.isNotEmpty() })
                    mods[sha] = mods.getOrDefault(sha, emptyList()) + record
                }

                "RULE" -> if (fields.size == 4) {
                    val sha = fields[1]
                    val id = fields[3].takeIf { it.isNotEmpty() }
                    rules[sha] = rules.getOrDefault(sha, emptyList()) + CachedRule(fields[2], id)
                }

                "HOOKS" -> if (fields.size == 3) {
                    hookCarriers[fields[1]] = fields[2].split(";").filter { it.isNotEmpty() }
                }
            }
        }
    }

    /**
     * The ledger's SHA-256 for [file] when the stat stamp `(size, mtime)` still matches — no
     * hashing happens on the happy path. `null` = unknown jar (call [hash]).
     */
    fun statSha(file: File): String? {
        val path = canonicalPathOf(file)
        if (statMemo.containsKey(path)) return statMemo[path]
        val stamp = jars[path]
        if (stamp == null) {
            statMemo[path] = null
            return null
        }
        // One attributes call instead of length() + lastModified(): on Windows the two File calls
        // are two syscalls, and a modpack makes this check hundreds of times per phase. The memo
        // makes the second and third scanner reuse the first scanner's answer for this run.
        val attributes = runCatching {
            Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
        }.getOrNull()
        val sha = if (attributes != null &&
            stamp.size == attributes.size() &&
            stamp.mtime == attributes.lastModifiedTime().toMillis()
        ) {
            stamp.sha
        } else {
            null
        }
        statMemo[path] = sha
        return sha
    }

    /**
     * Hashes [file] (SHA-256) and records the stat stamp. Only called when the ledger has no
     * matching stamp — once per jar per content change, never on the happy path.
     */
    fun hash(file: File): String {
        val sha = digest(file)
        rememberHash(canonicalPathOf(file), sha, file.length(), file.lastModified())
        return sha
    }

    /** The ledger key for [file]; canonicalization is filesystem work on Windows, so memoize it. */
    fun canonicalPathOf(file: File): String =
        canonicalPaths.computeIfAbsent(file.absolutePath) { file.canonicalPath }

    /**
     * The pure SHA-256 of [file], safe to call from several threads. The cold scan hashes a
     * modpack on a small pool; registration happens on one thread in [rememberHash].
     */
    fun digest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Registers a digest and stat stamp; the rest of this run answers [statSha] from it without
     * touching the file again. [canonicalPath] is the key the scanners look up.
     */
    fun rememberHash(canonicalPath: String, sha: String, size: Long, mtime: Long) {
        jars[canonicalPath] = JarStamp(size, mtime, sha)
        statMemo[canonicalPath] = sha
        dirty = true
    }

    fun modsFor(sha: String): List<CachedMod>? = mods[sha]

    fun rememberMods(sha: String, records: List<CachedMod>) {
        mods[sha] = records
        dirty = true
    }

    fun rulesFor(sha: String): List<CachedRule>? = rules[sha]

    fun rememberRules(sha: String, records: List<CachedRule>) {
        rules[sha] = records
        dirty = true
    }

    /**
     * The jar's mixin-carrier classes (`@Hooked`/`@Merged`), from the ledger — the hook scanner
     * parses only these instead of every class in the jar. The record carries a discovery-schema
     * tag: bump `HOOKS_SCHEMA` when the carrier-annotation set or the scan-shape contract changes,
     * or a loader update would silently miss carriers the old tag never recorded.
     */
    fun hookCarriersFor(sha: String): List<String>? = hookCarriers["$HOOKS_SCHEMA:$sha"]

    fun rememberHookCarriers(sha: String, carriers: List<String>) {
        hookCarriers["$HOOKS_SCHEMA:$sha"] = carriers
        dirty = true
    }

    /** Persists the ledger (atomic rename); a no-op when nothing changed this run. */
    fun save() {
        val target = dir?.let(::ledgerFile) ?: return
        if (!dirty) return
        val body = buildString {
            appendLine("# oml startup scan cache v2 — safe to delete; regenerated from the jars")
            for ((path, stamp) in jars) {
                append("JAR\t${escape(path)}\t${stamp.size}\t${stamp.mtime}\t${stamp.sha}\n")
            }
            for ((sha, records) in mods) {
                for (record in records) {
                    append("MOD\t$sha\t${escape(record.id)}\t${escape(record.name)}\t${escape(record.version)}\t" +
                        "${escape(record.entryClass)}\t${escape(record.dependencies.joinToString("\u001F"))}\n")
                }
            }
            for ((sha, records) in rules) {
                for (record in records) {
                    append("RULE\t$sha\t${escape(record.internalName)}\t${escape(record.id.orEmpty())}\n")
                }
            }
            for ((key, carriers) in hookCarriers) {
                append("HOOKS\t$key\t${escape(carriers.joinToString(";"))}\n")
            }
        }
        runCatching {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(body)
            java.nio.file.Files.move(
                tmp.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
            dirty = false
        }.onFailure {
            OmlLog.warn("StartupCache", "could not write the startup scan cache (${it.message})")
        }
    }

    // ------------------------------------------------------------- asset index

    /**
     * The stat signature of the exact file set an asset index covers — mod jars plus pack
     * archives, sorted, with size and mtime. Any change to the set or to one of the files changes
     * the signature; a matching signature answers the cached index without opening anything.
     */
    fun assetSignature(files: List<File>): String =
        files.sortedBy { it.canonicalPath }.joinToString("\n") { file ->
            "${file.canonicalPath}|${file.length()}|${file.lastModified()}"
        }

    /** The cached `assets/`+`data/` keys for [signature], or `null`. */
    fun assetIndexCached(signature: String): List<String>? {
        val file = dir?.let(::assetFile) ?: return null
        if (!file.isFile) return null
        return runCatching { readAssetIndex(file, signature) }.getOrNull()
    }

    private fun readAssetIndex(file: File, signature: String): List<String>? {
        var signatureMatch = false
        val keys = mutableListOf<String>()
        for (raw in file.readLines()) {
            val line = raw.trimEnd('\r')
            if (line.isEmpty() || line.startsWith("#")) continue
            val fields = line.split('\t').map(::unescape)
            when (fields.getOrNull(0)) {
                "KEY" -> signatureMatch = fields.size == 2 && fields[1] == signature
                "A" -> if (signatureMatch && fields.size == 2) keys += fields[1]
            }
        }
        return if (signatureMatch) keys else null
    }

    /** Stores the asset index under [signature], written immediately (it lands after startup, outside the ledger's lifetime). */
    fun rememberAssetIndex(signature: String, keys: List<String>) {
        val target = dir?.let(::assetFile) ?: return
        val body = buildString {
            appendLine("# oml asset index cache v1 — safe to delete; regenerated from the jars and packs")
            append("KEY\t${escape(signature)}\n")
            for (key in keys) append("A\t${escape(key)}\n")
        }
        runCatching {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(body)
            java.nio.file.Files.move(
                tmp.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        }.onFailure {
            OmlLog.warn("StartupCache", "could not write the asset index cache (${it.message})")
        }
    }

    // ------------------------------------------------------------- plumbing

    // v2: MR-JAR winner semantics and the @Mod byte payload change the scan products; an old
    // v1 ledger must not be trusted for a multi-release jar.
    private fun ledgerFile(dir: File) = File(dir, "startup-scan.v2.txt")
    private fun assetFile(dir: File) = File(dir, "asset-index.v1.txt")

    private fun escape(field: String): String =
        field.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")

    private fun unescape(field: String): String =
        if ('\\' !in field) field else StringBuilder(field.length).let { out ->
            var i = 0
            while (i < field.length) {
                val c = field[i]
                if (c == '\\' && i + 1 < field.length) {
                    when (field[i + 1]) {
                        't' -> out.append('\t')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        else -> out.append(field[i + 1])
                    }
                    i += 2
                } else {
                    out.append(c)
                    i++
                }
            }
            out.toString()
        }
}
