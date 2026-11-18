package org.ohmyloader.core.mod

import org.ohmyloader.core.cache.StartupCache
import java.io.File
import java.util.jar.JarFile

/**
 * The prefiltered class index of a mod jar, shared by the three cold scan passes.
 *
 * The native walker (`oml-native`) is the fast path: it memory-maps the jar, inflates with
 * libdeflate, truncates the marker scan to the constant pool and returns candidate records. When
 * the library is absent, an old build lacks the symbol, or a jar is unsupported, [walkJava]
 * answers with the same result — a reusable buffer, a constant-pool-bounded prefix scan, and the
 * same MR-JAR winner rules. Both paths are differential-tested against each other.
 *
 * MR-JAR: `META-INF/versions/N/<class>` replaces `<class>` for runtime N; the highest N not above
 * the running feature wins and the base copy is shadowed. Reserved-namespace classes are reported
 * separately so [ModScanner] can refuse the jar with the same message on both paths.
 *
 * The result is keyed by the jar digest and reused for the rest of the process. A jar in OML's
 * own namespace is not cached: the error path must report on every run.
 */
internal object JarClassIndex {

    /**
     * One candidate class: its logical internal name, the winning MR-JAR version (0 = base), and
     * markers. `bytes` is present for `@Mod` candidates so the scanner need not reopen the jar.
     */
    class ClassCandidate(
        val internalName: String,
        val version: Int,
        val mask: Int,
        val bytes: ByteArray? = null,
    ) {
        val entryName: String
            get() = if (version == 0) "$internalName.class" else "META-INF/versions/$version/$internalName.class"
    }

    class Candidates(
        /** Classes carrying the `@Mod` descriptor. */
        val mods: List<ClassCandidate>,
        /** Classes carrying the `@RuleSource` descriptor. */
        val rules: List<ClassCandidate>,
        /** Classes carrying `@Hooked` or `@Merged` — the only classes the hook scanner parses. */
        val hooks: List<ClassCandidate>,
        /** Classes packed under [RESERVED_NAMESPACE]; non-empty means the jar must be refused. */
        val reserved: List<String>,
    )

    private const val MASK_MOD = 1
    private const val MASK_RULE = 2
    private const val MASK_HOOK = 4
    private const val MASK_RESERVED = 8

    private const val MAX_CLASS_BYTES = 16 * 1024 * 1024
    private const val CLASS_MAGIC: Int = 0xCAFEBABE.toInt()
    private const val VERSION_PREFIX = "META-INF/versions/"

    /** OML's own namespace: defined parent-first by the launcher layer, so a mod may not pack it. */
    const val RESERVED_NAMESPACE = "org.ohmyloader."
    private const val RESERVED_PREFIX = "org/ohmyloader/"

    private val PREFIX = "Lorg/ohmyloader/".toByteArray(Charsets.US_ASCII)
    private val MOD_TAIL = "api/core/Mod;".toByteArray(Charsets.US_ASCII)
    private val RULE_TAIL = "api/core/inject/RuleSource;".toByteArray(Charsets.US_ASCII)
    private val HOOKED_TAIL = "api/core/hook/Hooked;".toByteArray(Charsets.US_ASCII)
    private val MERGED_TAIL = "api/core/hook/Merged;".toByteArray(Charsets.US_ASCII)

    private class ScanBuffer {
        var bytes = ByteArray(16 * 1024)
    }

    /** Reused across entries and jars; the fallback path's zero-allocation contract. */
    private val scanBuffer = ThreadLocal.withInitial { ScanBuffer() }

    private val cache = HashMap<String, Candidates>()

    /** `-Doml.scan.native=off` forces the Java path (benchmarks, diagnosis, a known-bad native build). */
    private val nativeEnabled: Boolean
        get() = System.getProperty("oml.scan.native", "on").lowercase() !in setOf("off", "false", "0")

    /** The index plus the jar digest it was recorded under. */
    class Scanned(val sha: String, val candidates: Candidates)

    /**
     * Scans [jar] through the native walk or the Java fallback; the digest comes from the
     * startup ledger (or one hash on a miss). `null` only when neither path can read the jar.
     */
    fun scan(jar: File): Scanned? {
        val sha = StartupCache.statSha(jar) ?: StartupCache.hash(jar)
        cache[sha]?.let { return Scanned(sha, it) }
        val native = if (nativeEnabled) NativeJarScanner.scan(jar, Runtime.version().feature()) else null
        val candidates = (native?.let(::fromNative) ?: runCatching { walkJava(jar) }.getOrNull()) ?: return null
        if (candidates.reserved.isEmpty()) cache[sha] = candidates
        return Scanned(sha, candidates)
    }

    /** The index for [jar], whose digest is [sha]; `null` when neither path can read the jar. */
    fun of(jar: File, sha: String): Candidates? {
        cache[sha]?.let { return it }
        return scan(jar)?.candidates
    }

    /** Test hook: drop the per-process index so a mode switch re-scans the same digest. */
    fun clearForTests() {
        cache.clear()
    }

    /**
     * The MR-JAR entry a logical class must be read from. The cached rule/carrier records hold
     * logical names only, so a warm scan resolves the winning copy with a descending probe.
     */
    fun winningEntryName(
        zip: JarFile,
        internalName: String,
        runtimeFeature: Int = Runtime.version().feature(),
    ): String {
        // Standard MR-JAR only applies to a jar whose manifest says so.
        if (runtimeFeature <= 0 || !isMultiRelease(zip)) return "$internalName.class"
        for (version in runtimeFeature downTo 1) {
            val path = "META-INF/versions/$version/$internalName.class"
            if (zip.getEntry(path) != null) return path
        }
        return "$internalName.class"
    }

    /** The MR-JAR lookup feature for [zip]: the running feature, or 0 when the jar is not multi-release. */
    fun runtimeFeature(zip: JarFile): Int =
        if (isMultiRelease(zip)) Runtime.version().feature() else 0

    private fun isMultiRelease(zip: JarFile): Boolean =
        zip.manifest?.mainAttributes?.getValue("Multi-Release")?.equals("true", ignoreCase = true) == true

    /**
     * The logical internal name of a class entry: the path itself for a base copy, or the path
     * after `META-INF/versions/N/` for a version the running JVM recognises. Null for non-class
     * entries and for versioned copies the JVM ignores.
     */
    fun logicalClassName(entryName: String, runtimeFeature: Int = Runtime.version().feature()): String? {
        if (!entryName.endsWith(".class")) return null
        if (!entryName.startsWith("META-INF/")) return entryName.removeSuffix(".class")
        val versioned = versionedName(entryName, runtimeFeature) ?: return null
        return versioned.logical.removeSuffix(".class")
    }

    /**
     * Entry names that would define a class in OML's own namespace: both the base copy and a
     * versioned copy. MR-JAR versioned copies are otherwise not candidates, but the
     * reserved-namespace check deliberately sees them too.
     */
    fun reservedClassName(entryName: String): String? {
        if (!entryName.endsWith(".class")) return null
        val path = if (entryName.startsWith(VERSION_PREFIX)) {
            entryName.removePrefix(VERSION_PREFIX).substringAfter('/')
        } else {
            entryName
        }
        return if (path.startsWith(RESERVED_PREFIX)) path.removeSuffix(".class").replace('/', '.') else null
    }

    // ------------------------------------------------------------- native path

    private fun fromNative(records: List<NativeJarScanner.Record>): Candidates {
        val mods = ArrayList<ClassCandidate>()
        val rules = ArrayList<ClassCandidate>()
        val hooks = ArrayList<ClassCandidate>()
        val reserved = LinkedHashSet<String>()
        for (record in records) {
            // A reserved record still carries its marker bits: the jar is refused before these
            // candidates are used, while a direct rule/hook scan of a fixture jar can see them.
            if (record.mask and MASK_RESERVED != 0) reserved += record.internalName.replace('/', '.')
            val candidate = ClassCandidate(record.internalName, record.version, record.mask, record.bytes)
            if (record.mask and MASK_MOD != 0) mods += candidate
            if (record.mask and MASK_RULE != 0) rules += candidate
            if (record.mask and MASK_HOOK != 0) hooks += candidate
        }
        return Candidates(mods, rules, hooks, reserved.toList())
    }

    // ------------------------------------------------------------- Java path

    private fun walkJava(jar: File): Candidates {
        val winners = HashMap<String, Int>()
        val classes = ArrayList<ClassCandidate>()
        val reserved = LinkedHashSet<String>()

        JarFile(jar).use { zip ->
            val feature = runtimeFeature(zip)
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val name = entry.name
                if (!name.endsWith(".class")) continue
                // Versioned copies are parsed regardless of the feature so a reserved one is
                // still reported; only candidates are gated by Multi-Release and the runtime.
                val versioned = parseVersioned(name)
                if (versioned == null && name.startsWith("META-INF/")) continue
                val logical = versioned?.logical ?: name
                val version = versioned?.version ?: 0
                val internal = logical.removeSuffix(".class")
                if (internal.isEmpty()) continue
                val isReserved = internal.startsWith(RESERVED_PREFIX)
                if (isReserved) {
                    reserved += internal.replace('/', '.')
                    // A base copy contributes its markers (unit fixtures live in this package);
                    // a versioned copy only when the runtime would actually load it.
                    if (version == 0 || version <= feature) classes += ClassCandidate(internal, version, 0)
                    continue
                }
                if (version > feature) continue
                if (version > 0) winners.merge(internal, version, ::maxOf)
                classes += ClassCandidate(internal, version, 0)
            }

            val mods = ArrayList<ClassCandidate>()
            val rules = ArrayList<ClassCandidate>()
            val hooks = ArrayList<ClassCandidate>()
            val buffer = scanBuffer.get()
            for (candidate in classes) {
                // Reserved copies are reported regardless of MR-JAR shadowing; the jar is refused
                // before these candidates are used (see the native walker for the same rule).
                if (!candidate.internalName.startsWith(RESERVED_PREFIX)) {
                    if (candidate.version == 0) {
                        if (winners.containsKey(candidate.internalName)) continue
                    } else if (winners[candidate.internalName] != candidate.version) {
                        continue
                    }
                }
                val length = readEntry(zip, candidate.entryName, buffer)
                if (length < 0) continue
                val mask = scanClass(buffer.bytes, length)
                if (mask == 0) continue
                val parsed = ClassCandidate(
                    candidate.internalName,
                    candidate.version,
                    mask,
                    if (mask and MASK_MOD != 0) buffer.bytes.copyOf(length) else null,
                )
                if (mask and MASK_MOD != 0) mods += parsed
                if (mask and MASK_RULE != 0) rules += parsed
                if (mask and MASK_HOOK != 0) hooks += parsed
            }
            return Candidates(mods, rules, hooks, reserved.toList())
        }
    }

    private class Versioned(val logical: String, val version: Int)

    /** Parses the versioned path without the runtime/feature gate (reserved reporting needs it). */
    private fun parseVersioned(name: String): Versioned? {
        if (!name.startsWith(VERSION_PREFIX)) return null
        val rest = name.removePrefix(VERSION_PREFIX)
        val slash = rest.indexOf('/')
        if (slash <= 0) return null
        val digits = rest.substring(0, slash)
        if (digits.any { it !in '0'..'9' }) return null
        val version = digits.toIntOrNull() ?: return null
        if (version <= 0) return null
        val logical = rest.substring(slash + 1)
        if (logical.isEmpty() || logical.startsWith("META-INF/")) return null
        return Versioned(logical, version)
    }

    private fun versionedName(name: String, feature: Int): Versioned? {
        val versioned = parseVersioned(name) ?: return null
        return if (versioned.version <= feature) versioned else null
    }

    /** Reads an entry into the thread-local buffer; returns the length, or -1 to skip it. */
    private fun readEntry(zip: JarFile, entryName: String, buffer: ScanBuffer): Int {
        val entry = zip.getEntry(entryName) ?: return -1
        val declared = entry.size
        if (declared > MAX_CLASS_BYTES) return -1
        if (declared >= 0) {
            if (buffer.bytes.size < declared) buffer.bytes = ByteArray(maxOf(declared.toInt(), buffer.bytes.size * 2))
            zip.getInputStream(entry).use { input ->
                var offset = 0
                while (offset < declared) {
                    val read = input.read(buffer.bytes, offset, declared.toInt() - offset)
                    if (read < 0) return -1
                    offset += read
                }
                return offset
            }
        }
        // Unknown size (rare for a jar): grow while reading, under the same ceiling.
        var length = 0
        zip.getInputStream(entry).use { input ->
            while (true) {
                if (length == buffer.bytes.size) {
                    if (length >= MAX_CLASS_BYTES) return -1
                    buffer.bytes = buffer.bytes.copyOf(minOf(buffer.bytes.size * 2, MAX_CLASS_BYTES))
                }
                val read = input.read(buffer.bytes, length, buffer.bytes.size - length)
                if (read < 0) return length
                length += read
            }
        }
    }

    /**
     * The constant-pool end offset, or null when the header is malformed or the pool runs past
     * the buffer. Every read is bounds-checked: a hostile class must never take the scanner down.
     */
    private fun constantPoolEnd(bytes: ByteArray, length: Int): Int? {
        if (length < 10) return null
        if (u32(bytes, 0, length) != CLASS_MAGIC) return null
        val count = u16(bytes, 8, length)
        if (count <= 0) return null
        var offset = 10
        var slot = 1
        while (slot < count) {
            if (offset >= length) return null
            when (bytes[offset].toInt() and 0xFF) {
                1 -> {
                    val size = u16(bytes, offset + 1, length)
                    if (size < 0) return null
                    offset += 3 + size
                }

                3, 4, 9, 10, 11, 12, 17, 18 -> offset += 5
                5, 6 -> {
                    offset += 9
                    slot++
                }

                7, 8, 16, 19, 20 -> offset += 3
                15 -> offset += 4
                else -> return null
            }
            if (offset > length) return null
            slot++
        }
        return offset
    }

    private fun u16(bytes: ByteArray, offset: Int, limit: Int): Int {
        if (offset < 0 || offset + 2 > limit) return -1
        return (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun u32(bytes: ByteArray, offset: Int, limit: Int): Int {
        if (offset < 0 || offset + 4 > limit) return -1
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }

    /** One common-prefix search over the constant pool; the four tails are only compared on a hit. */
    private fun scanClass(bytes: ByteArray, length: Int): Int {
        val cpEnd = constantPoolEnd(bytes, length) ?: length
        val end = minOf(cpEnd, length)
        var mask = 0
        var offset = 10
        while (offset + PREFIX.size <= end) {
            if (bytes[offset] == 'L'.code.toByte() && regionMatches(bytes, offset, PREFIX, end)) {
                val tail = offset + PREFIX.size
                if (regionMatches(bytes, tail, MOD_TAIL, end)) mask = mask or MASK_MOD
                if (regionMatches(bytes, tail, RULE_TAIL, end)) mask = mask or MASK_RULE
                if (regionMatches(bytes, tail, HOOKED_TAIL, end) || regionMatches(bytes, tail, MERGED_TAIL, end)) {
                    mask = mask or MASK_HOOK
                }
            }
            offset++
        }
        return mask
    }

    private fun regionMatches(bytes: ByteArray, offset: Int, needle: ByteArray, end: Int): Boolean {
        if (offset < 0 || offset + needle.size > end) return false
        for (i in needle.indices) if (bytes[offset + i] != needle[i]) return false
        return true
    }

    /** Test hook: the pure-Java path without the native attempt. */
    fun javaCandidatesForTest(jar: File): Candidates = walkJava(jar)

    /** Test hook: the native path without the Java fallback. */
    fun nativeCandidatesForTest(
        jar: File,
        runtimeFeature: Int = Runtime.version().feature(),
    ): Candidates? = NativeJarScanner.scan(jar, runtimeFeature)?.let(::fromNative)
}
