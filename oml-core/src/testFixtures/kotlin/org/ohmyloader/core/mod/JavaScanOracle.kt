package org.ohmyloader.core.mod

import java.io.File
import java.util.jar.JarFile

/**
 * The pure-Java scan **oracle** — deliberately not shipped: production scans go through
 * `oml-native` only (a first-party component that deploys with the loader), so there is no Java
 * fallback to maintain at runtime. This object keeps the same walk alive in the test fixtures as
 * the differential oracle for [NativeDiffTest]: the native walker must produce exactly what this
 * hand-written walk produces, for every jar shape the loader accepts. It is also the readable
 * specification of the scan semantics the Rust side implements — MR-JAR winner rules, the
 * reserved-namespace report, constant-pool-bounded marker search.
 *
 * NOT for production use: a scan failure here is a test problem, never a runtime degrade.
 */
object JavaScanOracle {

    fun candidates(jar: File): JarClassIndex.Candidates {
        val winners = HashMap<String, Int>()
        val classes = ArrayList<JarClassIndex.ClassCandidate>()
        val reserved = LinkedHashSet<String>()

        JarFile(jar).use { zip ->
            val feature = JarClassIndex.runtimeFeature(zip)
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
                    if (version == 0 || version <= feature) classes += JarClassIndex.ClassCandidate(internal, version, 0)
                    continue
                }
                if (version > feature) continue
                if (version > 0) winners.merge(internal, version, ::maxOf)
                classes += JarClassIndex.ClassCandidate(internal, version, 0)
            }

            val mods = ArrayList<JarClassIndex.ClassCandidate>()
            val rules = ArrayList<JarClassIndex.ClassCandidate>()
            val hooks = ArrayList<JarClassIndex.ClassCandidate>()
            val buffer = ScanBuffer()
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
                val parsed = JarClassIndex.ClassCandidate(
                    candidate.internalName,
                    candidate.version,
                    mask,
                    if (mask and JarClassIndex.MASK_MOD != 0) buffer.bytes.copyOf(length) else null,
                )
                if (mask and JarClassIndex.MASK_MOD != 0) mods += parsed
                if (mask and JarClassIndex.MASK_RULE != 0) rules += parsed
                if (mask and JarClassIndex.MASK_HOOK != 0) hooks += parsed
            }
            return JarClassIndex.Candidates(mods, rules, hooks, reserved.toList())
        }
    }

    private const val MASK_MOD = JarClassIndex.MASK_MOD
    private const val MASK_RULE = JarClassIndex.MASK_RULE
    private const val MASK_HOOK = JarClassIndex.MASK_HOOK

    private const val MAX_CLASS_BYTES = 16 * 1024 * 1024
    private const val CLASS_MAGIC: Int = 0xCAFEBABE.toInt()

    private const val RESERVED_PREFIX = "org/ohmyloader/"

    private val PREFIX = "Lorg/ohmyloader/".toByteArray(Charsets.US_ASCII)
    private val MOD_TAIL = "api/core/Mod;".toByteArray(Charsets.US_ASCII)
    private val RULE_TAIL = "api/core/inject/RuleSource;".toByteArray(Charsets.US_ASCII)
    private val HOOKED_TAIL = "api/core/hook/Hooked;".toByteArray(Charsets.US_ASCII)
    private val MERGED_TAIL = "api/core/hook/Merged;".toByteArray(Charsets.US_ASCII)

    private class Versioned(val logical: String, val version: Int)

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

    private const val VERSION_PREFIX = "META-INF/versions/"

    private class ScanBuffer {
        var bytes = ByteArray(16 * 1024)
    }

    /** Reads an entry into the buffer; returns the length, or -1 to skip it. */
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
}
