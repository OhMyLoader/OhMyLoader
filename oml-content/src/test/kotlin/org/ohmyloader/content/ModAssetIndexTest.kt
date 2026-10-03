package org.ohmyloader.content

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The version-independent half of asset injection, exercised directly: jar indexing (both `assets/`
 * and `data/` roots, directory entries skipped), namespace computation (jars + OML asset domains
 * that ship no files), and prefix-based directory listing. A game instance is not involved — the
 * index is built from plain zip files in a temp directory.
 */
class ModAssetIndexTest {

    private fun jarWith(entries: List<String>): File {
        val jar = File.createTempFile("mod", ".jar")
        jar.deleteOnExit()
        ZipOutputStream(jar.outputStream()).use { zip ->
            for (entry in entries) {
                zip.putNextEntry(ZipEntry(entry))
                zip.write(ByteArray(0))
                zip.closeEntry()
            }
        }
        return jar
    }

    @Test
    fun `assets and data entries are indexed with their root stripped`() {
        val jar = jarWith(
            listOf(
                "assets/oml/blockstates/ruby.json",
                "assets/oml/textures/block/ruby.png",
                "data/oml/recipe/ruby_smelting.json",
                "oml/Main.class",
            )
        )
        val index = ModAssetIndex(listOf(jar), emptyList())

        assertEquals(
            setOf("oml/blockstates/ruby.json", "oml/textures/block/ruby.png", "oml/recipe/ruby_smelting.json"),
            index.keys(),
        )
    }

    @Test
    fun `directory entries are not indexed`() {
        val jar = jarWith(listOf("assets/oml/", "assets/oml/blockstates/", "assets/oml/blockstates/ruby.json"))
        val index = ModAssetIndex(listOf(jar), emptyList())
        assertEquals(setOf("oml/blockstates/ruby.json"), index.keys())
    }

    @Test
    fun `namespaces are the indexed first segments plus the declared asset domains`() {
        val jar = jarWith(listOf("assets/oml/x.json", "assets/minecraft/textures/gui/overlay.png"))
        // a TOML content pack declares `ruby` but ships no assets entry at all
        val index = ModAssetIndex(listOf(jar), listOf("ruby"))

        assertEquals(setOf("oml", "minecraft", "ruby"), index.namespaces())
    }

    @Test
    fun `listUnder answers whatever the jars ship under the requested directory`() {
        val jar = jarWith(
            listOf(
                "assets/oml/sounds/step1.ogg",
                "assets/oml/sounds/step2.ogg",
                "assets/oml/blockstates/ruby.json",
                "assets/oml/textures/block/sounds.png",
            )
        )
        val index = ModAssetIndex(listOf(jar), emptyList())

        // a directory the index never special-cases — sounds are discovered by listing, exactly
        // like blockstates, so a mod's audio does not silently vanish
        assertEquals(
            listOf("sounds/step1.ogg", "sounds/step2.ogg"),
            index.listUnder("oml", "sounds").sorted(),
        )
        assertTrue(index.listUnder("oml", "particles").isEmpty())
    }

    @Test
    fun `an unreadable jar is skipped instead of poisoning the index`() {
        val good = jarWith(listOf("assets/oml/x.json"))
        val corrupt = File.createTempFile("corrupt", ".jar").apply {
            deleteOnExit()
            writeBytes(byteArrayOf(0, 1, 2, 3))
        }
        val index = ModAssetIndex(listOf(good, corrupt), emptyList())

        assertEquals(setOf("oml/x.json"), index.keys())
    }
}
