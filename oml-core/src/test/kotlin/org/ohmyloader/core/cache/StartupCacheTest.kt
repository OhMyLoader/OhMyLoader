package org.ohmyloader.core.cache

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The startup cache's contract: a stat-stamp match answers from the ledger (never re-hashing),
 * any change re-hashes, every persisted field survives a save/load round trip including
 * backslashes, tabs and newlines (Windows paths!), and a broken cache file is discarded rather
 * than fatal. */
class StartupCacheTest {

    private fun tempDir(): File = createTempDirectory("oml-startup-cache").toFile()

    private fun jarFile(dir: File, name: String): File {
        val file = File(dir, name)
        file.writeBytes(ByteArray(16) { it.toByte() })
        return file
    }

    @Test
    fun `a stat-stamp match answers without re-hashing`() {
        val dir = tempDir()
        val jar = jarFile(dir, "mod.jar")
        StartupCache.init(dir)
        val sha = StartupCache.hash(jar)

        StartupCache.rememberMods(sha, listOf(StartupCache.CachedMod("demo", "Demo", "1.0.0", "com.example.Entry", listOf("other@>=1.2.0"))))
        StartupCache.save()

        // A second instance is a second run: same cache directory, ledger loaded from disk
        StartupCache.init(dir)
        assertEquals(sha, StartupCache.statSha(jar))
        val records = StartupCache.modsFor(StartupCache.statSha(jar)!!)
        assertEquals(1, records!!.size)
        assertEquals("demo", records[0].id)
        assertEquals(listOf("other@>=1.2.0"), records[0].dependencies)
    }

    @Test
    fun `a changed file re-hashes`() {
        val dir = tempDir()
        val jar = jarFile(dir, "mod.jar")
        StartupCache.init(tempDir())
        val first = StartupCache.hash(jar)

        // Same size, new mtime: the stat stamp must miss even though the digest of the same
        // bytes would be identical — mtime is the change signal, not the content
        jar.setLastModified(first.hashCode().toLong().coerceAtLeast(jar.lastModified() + 1000))

        StartupCache.init(dir)
        val second = StartupCache.statSha(jar)
        assertNull(second)
        assertEquals(first, StartupCache.hash(jar))
    }

    @Test
    fun `rule records and hostile field values round-trip`() {
        val dir = tempDir()
        val jar = jarFile(dir, "weird path with spaces.jar")
        StartupCache.init(dir)
        val sha = StartupCache.hash(jar)
        StartupCache.rememberRules(
            sha,
            listOf(
                StartupCache.CachedRule("com/example/Rules", "custom id"),
                // Hostile *field* values — the ledger is line/tab-separated, so a value carrying
                // separators or escapes must survive the round trip; the jar name above exercises
                // the same escaping on the path field
                StartupCache.CachedRule("com/example/Back\\slash\tand\nnewline", null),
            ),
        )
        StartupCache.save()

        StartupCache.init(dir)
        val rules = StartupCache.rulesFor(StartupCache.statSha(jar)!!)!!
        assertEquals(2, rules.size)
        assertEquals("com/example/Rules", rules[0].internalName)
        assertEquals("custom id", rules[0].id)
        assertEquals("com/example/Back\\slash\tand\nnewline", rules[1].internalName)
        assertNull(rules[1].id)
    }

    @Test
    fun `a broken ledger is discarded, not fatal`() {
        val dir = tempDir()
        File(dir, "startup-scan.v1.txt").writeText("JAR\tthis is not a record\nMOD\txxx\u0000garbage\n")
        StartupCache.init(dir)
        val jar = jarFile(dir, "mod.jar")
        assertTrue(StartupCache.statSha(jar) == null)
        val sha = StartupCache.hash(jar)
        StartupCache.rememberMods(sha, emptyList())
        StartupCache.save()

        // The regenerated ledger is valid and the garbage is gone
        StartupCache.init(dir)
        assertNotNull(StartupCache.statSha(jar))
    }

    @Test
    fun `asset index round-trips under its signature`() {
        val dir = tempDir()
        StartupCache.init(dir)
        val signature = "a.jar|10|100\nb.oml|20|200"
        StartupCache.rememberAssetIndex(signature, listOf("oml/model.json", "oml/lang/en_us.json"))

        StartupCache.init(dir)
        assertEquals(listOf("oml/model.json", "oml/lang/en_us.json"), StartupCache.assetIndexCached(signature))
        assertNull(StartupCache.assetIndexCached("a.jar|10|100\nb.oml|20|201"))
    }
}
