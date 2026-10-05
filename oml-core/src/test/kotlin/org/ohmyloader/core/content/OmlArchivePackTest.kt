package org.ohmyloader.core.content

import org.ohmyloader.api.content.*
import org.ohmyloader.content.AbstractContentRegistry
import org.ohmyloader.core.OMLCore
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The `.oml` content pack archive: `content.toml` at the root + the pack's own `assets/` and
 * `data/`. Exercises the full no-code path — archive parsing, namespace derivation, duplicate
 * detection — and the two halves that must agree: the declared content and the asset index that
 * serves the archive's textures. If either half breaks alone, the symptom is a block with no
 * texture, which no unit test elsewhere would catch.
 */
class OmlArchivePackTest {

    private class RecordingRegistry : ContentRegistry {
        val blocks = mutableListOf<Pair<String, OMLBlockDeclaration>>()
        val items = mutableListOf<Pair<String, OMLItemDeclaration>>()

        override fun declareBlock(id: String, configure: OMLBlockDeclaration.() -> Unit): OMLBlock {
            blocks += id to OMLBlockDeclaration().apply(configure)
            return OMLBlock(id) { "block-platform" }
        }

        override fun declareItem(id: String, configure: OMLItemDeclaration.() -> Unit): OMLItem {
            items += id to OMLItemDeclaration().apply(configure)
            return OMLItem(id) { "item-platform" }
        }

        override fun declareSmelting(
            input: String,
            result: String,
            furnace: Furnace,
            experience: Double,
            cookingTime: Int,
        ) {
        }

        override fun declareBlockDrop(block: String, drop: String, dropCountMin: Int, dropCountMax: Int) {}
        val shaped = mutableListOf<Pair<String, List<String>>>()

        override fun declareShapedCrafting(result: String, pattern: List<String>, key: Map<Char, String>, count: Int) {
            shaped += result to pattern
        }

        override fun declareShapelessCrafting(result: String, ingredients: List<String>, count: Int) {}
    }

    private val factory = object : ContentRegistryFactory {
        val registry = RecordingRegistry()
        override fun forNamespace(namespace: String): ContentRegistry = registry
    }

    /** Builds `<dir>/<name>.oml` and returns the archive file. */
    private fun writeArchive(
        dir: File,
        name: String,
        contentToml: String,
        vararg extra: Pair<String, ByteArray>,
    ): File {
        val archive = File(dir, "$name.oml")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("content.toml"))
            zip.write(contentToml.toByteArray())
            zip.closeEntry()
            for ([path, bytes] in extra) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return archive
    }

    @Test
    fun `an oml archive declares content and its assets reach the index`() {
        val dir = tempDir()
        val texture = ByteArray(32) { it.toByte() }
        writeArchive(
            dir, "oml_pack",
            "[block.ruby_ore]\ndestroy_time = 3.0\n\n[item.ruby]\nmax_damage = 64\n",
            "assets/oml_pack/textures/block/ruby_ore.png" to texture,
            "data/oml_pack/recipes/ruby.json" to "{}".toByteArray(),
        )

        OMLCore.loadContentPacks(dir, factory)

        assertEquals(listOf("ruby_ore"), factory.registry.blocks.map { it.first })
        assertEquals(listOf("ruby"), factory.registry.items.map { it.first })
        assertEquals(3.0f, factory.registry.blocks.single().second.destroyTime)

        // the two halves that must agree: the declared namespace is an asset domain, and the
        // archive's texture is indexed under it
        assertTrue(OMLCore.assetDomainIds().contains("oml_pack"))
        assertTrue(OMLCore.contentPackFiles().any { it.name == "oml_pack.oml" })
        val index = OMLCore.assetIndex()
        assertTrue(
            "oml_pack/textures/block/ruby_ore.png" in index.keys(),
            "the archive's texture must be indexed — a block whose texture is not served " +
                "renders with the missing-texture placeholder",
        )
        assertTrue("oml_pack/recipes/ruby.json" in index.keys())
    }

    @Test
    fun `an archive can declare crafting recipes`() {
        val dir = tempDir()
        writeArchive(
            dir, "crafted_pack",
            "[block.ruby_ore]\ndestroy_time = 3.0\n\n[crafting.ruby_block]\ntype = \"shaped\"\n" +
                "pattern = [\"RR\", \"RR\"]\nkey = { R = \"ruby\" }\n",
            "assets/crafted_pack/textures/block/ruby_ore.png" to ByteArray(8),
        )

        OMLCore.loadContentPacks(dir, factory)

        assertEquals(listOf("ruby_block"), factory.registry.shaped.map { it.first })
        assertEquals(listOf("RR", "RR"), factory.registry.shaped.single().second)
    }

    @Test
    fun `a loose toml next to an archive is refused, the archive loads`() {
        val dir = tempDir()
        File(dir, "clash.toml").writeText("[block.a]\n")
        writeArchive(dir, "clash", "[block.b]\n")

        OMLCore.loadContentPacks(dir, factory)

        assertEquals(listOf("b"), factory.registry.blocks.map { it.first })
        assertTrue(
            OMLCore.contentPackFiles().any { it.name == "clash.oml" },
            "the archive must join the asset index",
        )
    }

    @Test
    fun `an archive without a content declaration is skipped with a report`() {
        val dir = tempDir()
        val archive = File(dir, "empty.oml")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("readme.txt"))
            zip.write("not a pack".toByteArray())
            zip.closeEntry()
        }

        OMLCore.loadContentPacks(dir, factory)

        assertTrue(factory.registry.blocks.isEmpty() && factory.registry.items.isEmpty())
        assertTrue(OMLCore.contentPackFiles().none { it.name == "empty.oml" })
    }


    @Test
    fun `reload re-reads crafting recipes from disk`() {
        // the reload path replaces the data queues on an AbstractContentRegistry, so this test
        // uses a real one instead of the recording fake
        val registry = object : AbstractContentRegistry() {
            override fun doMaterialize() {}
        }
        val dir = tempDir()
        writeArchive(
            dir, "hot_pack",
            "[crafting.hot]\ntype = \"shapeless\"\ningredients = [\"minecraft:iron_ingot\"]\n",
        )

        OMLCore.loadContentPacks(dir, registry)
        val before = registry.recipeJsonFor("hot_pack", "hot_pack_hot")!!
        assertTrue("iron_ingot" in before)

        // the pack author edits the recipe on disk, then the game runs /reload (F3+T joins in)
        writeArchive(
            dir, "hot_pack",
            "[crafting.hot]\ntype = \"shapeless\"\ningredients = [\"minecraft:gold_ingot\"]\n",
        )
        OMLCore.reloadContentPacks()

        val after = registry.recipeJsonFor("hot_pack", "hot_pack_hot")!!
        assertTrue("gold_ingot" in after && "iron_ingot" !in after, "a vanilla reload must pick up the edited recipe")
    }

    private fun tempDir(): File =
        createTempDirectory(prefix = "oml-archive").toFile().also { it.deleteOnExit() }
}
