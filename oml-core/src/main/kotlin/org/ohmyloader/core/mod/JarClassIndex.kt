package org.ohmyloader.core.mod

import java.io.File
import java.util.jar.JarFile

/**
 * A single prefiltered walk of a mod jar, shared by the three cold scan passes.
 *
 * The three scanners (`@Mod`, `@RuleSource`, `@Hooked`/`@Merged`) each used to decompress and
 * parse every class file in every jar on a cold start — three passes over the same bytes. This
 * index reads each class entry **once** and only checks whether its raw bytes contain the
 * annotation descriptor, so the scanners parse just their candidates. Raw-byte containment is a
 * safe over-approximation: a present annotation always has its descriptor in the constant pool,
 * so a match may be a false positive (one unnecessary parse) but never a miss.
 *
 * The result is keyed by the jar digest and reused for the rest of the process: the same jar is
 * asked about by [ModScanner], then `ModRuleSets`, then `HookScanner`. A jar in OML's own
 * namespace is refused by [ModScanner]; such an index is not retained, because the error path
 * must report on every run.
 */
internal object JarClassIndex {

    class Candidates(
        /** Classes carrying the `@Mod` descriptor (base copies only). */
        val mods: List<String>,
        /** Classes carrying the `@RuleSource` descriptor. */
        val rules: List<String>,
        /** Classes carrying `@Hooked` or `@Merged` — the only classes the hook scanner parses. */
        val hooks: List<String>,
        /** Classes packed under [RESERVED_NAMESPACE]; non-empty means the jar must be refused. */
        val reserved: List<String>,
    )

    private const val MOD_DESC = "Lorg/ohmyloader/api/core/Mod;"
    private const val RULE_DESC = "Lorg/ohmyloader/api/core/inject/RuleSource;"
    private const val HOOKED_DESC = "Lorg/ohmyloader/api/core/hook/Hooked;"
    private const val MERGED_DESC = "Lorg/ohmyloader/api/core/hook/Merged;"

    /** OML's own namespace: defined parent-first by the launcher layer, so a mod may not pack it. */
    const val RESERVED_NAMESPACE = "org.ohmyloader."
    private const val RESERVED_PREFIX = "org/ohmyloader/"

    private val cache = HashMap<String, Candidates>()

    /** The index for [jar], whose digest is [sha]; `null` when the jar cannot be read. */
    fun of(jar: File, sha: String): Candidates? {
        cache[sha]?.let { return it }
        val scanned = runCatching { walk(jar) }.getOrNull() ?: return null
        if (scanned.reserved.isEmpty()) cache[sha] = scanned
        return scanned
    }

    /**
     * Entry names that would define a class in OML's own namespace: both the base copy and a
     * versioned copy. MR-JAR versioned copies are otherwise not candidates (they would register
     * the same entry point twice), but the reserved-namespace check deliberately sees them too.
     */
    fun reservedClassName(entryName: String): String? {
        if (!entryName.endsWith(".class")) return null
        val path = if (entryName.startsWith("META-INF/versions/")) {
            entryName.removePrefix("META-INF/versions/").substringAfter('/')
        } else {
            entryName
        }
        return if (path.startsWith(RESERVED_PREFIX)) path.removeSuffix(".class").replace('/', '.') else null
    }

    private fun walk(jar: File): Candidates {
        val mods = ArrayList<String>()
        val rules = ArrayList<String>()
        val hooks = ArrayList<String>()
        val reserved = ArrayList<String>()
        JarFile(jar).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                // Directory entries, `kotlin/…`, `.json` payloads: only class files are read as bytecode.
                if (!entry.name.endsWith(".class")) continue
                // Record the refusal for [ModScanner], but keep indexing the entry: the rule-set
                // and hook scanners run on jars passed to them directly (unit fixtures live in
                // the loader's own package), and [ModScanner] refuses the jar before using the
                // candidate lists anyway.
                reservedClassName(entry.name)?.let(reserved::add)
                // MR-JAR versioned copies are not entry points: scanning them would produce a
                // second container for the same modId and initialize the mod twice.
                if (entry.name.startsWith("META-INF/")) continue

                val internalName = entry.name.removeSuffix(".class")
                // ISO-8859-1 maps bytes 1:1, so the descriptors (plain ASCII in the constant
                // pool) can be searched without an ASM parse. One transient string per class.
                val text = zip.getInputStream(entry).use { stream ->
                    String(stream.readBytes(), Charsets.ISO_8859_1)
                }
                if (text.contains(MOD_DESC)) mods += internalName
                if (text.contains(RULE_DESC)) rules += internalName
                if (text.contains(HOOKED_DESC) || text.contains(MERGED_DESC)) hooks += internalName
            }
        }
        return Candidates(mods, rules, hooks, reserved)
    }
}
