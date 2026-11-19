package org.ohmyloader.core.mod

import org.ohmyloader.core.cache.StartupCache
import java.io.File
import java.util.jar.JarFile

/**
 * The prefiltered class index of a mod jar, shared by the three cold scan passes.
 *
 * The walk is the native walker (`oml-native`) — the only one. oml-native is a first-party
 * component that deploys with the loader ([NativeJarScanner.requireAvailable] fails startup when
 * it is missing, exactly like a missing oml-core jar), so there is no Java fallback and no
 * optional-degradation path. A jar the native cannot read (`null` from [scan]) fails that jar's
 * own load with a problem report; it never silently downgrades the run.
 *
 * The pure-Java walk survives as a test-only oracle (`JavaScanOracle`, test fixtures), where it
 * differential-tests the native result against the same rules.
 *
 * MR-JAR: `META-INF/versions/N/<class>` replaces `<class>` for runtime N; the highest N not above
 * the running feature wins and the base copy is shadowed. Reserved-namespace classes are reported
 * separately so [ModScanner] can refuse the jar.
 *
 * The result is keyed by the jar digest and reused for the rest of the process. A jar in OML's
 * own namespace is not cached: the error path must report on every run.
 */
object JarClassIndex {

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

    /**
     * The marker bits, shared with the native record format (the record's mask byte) and with the
     * test-fixture oracle.
     */
    const val MASK_MOD = 1
    const val MASK_RULE = 2
    const val MASK_HOOK = 4
    const val MASK_RESERVED = 8

    private const val VERSION_PREFIX = "META-INF/versions/"

    /** OML's own namespace: defined parent-first by the launcher layer, so a mod may not pack it. */
    const val RESERVED_NAMESPACE = "org.ohmyloader."
    private const val RESERVED_PREFIX = "org/ohmyloader/"

    private val cache = HashMap<String, Candidates>()

    /** The index plus the jar digest it was recorded under. */
    class Scanned(val sha: String, val candidates: Candidates)

    /**
     * Scans [jar] through the native walk; the digest comes from the startup ledger (or one hash
     * on a miss). `null` only when the jar itself cannot be read or parsed — the caller reports
     * the failure against that jar.
     */
    fun scan(jar: File): Scanned? {
        val sha = StartupCache.statSha(jar) ?: StartupCache.hash(jar)
        cache[sha]?.let { return Scanned(sha, it) }
        val candidates =
            NativeJarScanner.scan(jar, Runtime.version().feature())?.let(::fromNative) ?: return null
        if (candidates.reserved.isEmpty()) cache[sha] = candidates
        return Scanned(sha, candidates)
    }

    /** The index for [jar], whose digest is [sha]; `null` when the jar cannot be read or parsed. */
    fun of(jar: File, sha: String): Candidates? {
        cache[sha]?.let { return it }
        return scan(jar)?.candidates
    }

    /** Test hook: drop the per-process index so a re-scan hits the same digest fresh. */
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

    // ------------------------------------------------------------- record mapping

    internal fun fromNative(records: List<NativeJarScanner.Record>): Candidates {
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

}
