@file:Suppress("DestructingShortFormNameMismatch")

package org.ohmyloader.content

import org.ohmyloader.api.content.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The TOML-pack -> ContentRegistry mapping: field names, type coercion, defaults, and the loud
 * failure contract (malformed packs throw with the pack name in the message; callers catch per
 * pack so one bad file never takes the rest down).
 */
class TomlContentLoaderTest {

    /** Captures the declarations a pack produced. */
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

        // Recipes / loot are only declared through the code DSL so far, not the TOML pack format;
        // the fixture rejects them so a future TOML extension cannot silently drop declarations.
        override fun declareSmelting(
            input: String,
            result: String,
            furnace: Furnace,
            experience: Double,
            cookingTime: Int
        ) =
            error("TOML packs do not declare recipes yet")

        override fun declareBlockDrop(block: String, drop: String, dropCountMin: Int, dropCountMax: Int) =
            error("TOML packs do not declare loot yet")

        val shaped = mutableListOf<Triple<String, List<String>, Map<Char, String>>>()
        val shapeless = mutableListOf<Pair<String, List<String>>>()

        override fun declareShapedCrafting(result: String, pattern: List<String>, key: Map<Char, String>, count: Int) {
            shaped += Triple(result, pattern, key)
        }

        override fun declareShapelessCrafting(result: String, ingredients: List<String>, count: Int) {
            shapeless += result to ingredients
        }
    }

    /** Loads [text] as a pack named [name]. */
    private fun loadPack(name: String, text: String): Pair<RecordingRegistry, TomlContentLoader.PackSummary> {
        val registry = RecordingRegistry()
        val summary = TomlContentLoader.load(
            TomlContentLoader.namespaceOf(java.io.File(name)),
            name, text, registry,
        )
        return registry to summary
    }

    @Test
    fun `acceptance pack maps onto the declaration DSL`() {
        val (registry, summary) = loadPack(
            "ruby_pack.toml",
            """
            [block.ruby_ore]
            destroy_time = 3.0
            explosion_resistance = 6.0
            requires_correct_tool = true

            [item.ruby_sword]
            max_damage = 800
            attack_damage = 8.5
            attack_speed = -2.4
            mining_speed = 1.5
            """.trimIndent(),
        )
        assertEquals(TomlContentLoader.PackSummary("ruby_pack", 1, 1), summary)
        val (blockId, block) = registry.blocks.single()
        assertEquals("ruby_ore", blockId)
        assertEquals(3.0f, block.destroyTime)
        assertEquals(6.0f, block.explosionResistance)
        assertEquals(true, block.requiresCorrectToolForDrops)

        val (itemId, item) = registry.items.single()
        assertEquals("ruby_sword", itemId)
        assertEquals(800, item.maxDamage)
        assertEquals(8.5, item.attackDamage)
        assertEquals(-2.4, item.attackSpeed)
        assertEquals(1.5f, item.miningSpeed)
    }

    @Test
    fun `tool rules map onto the rule DSL`() {
        val (registry, _) = loadPack(
            "tool_pack.toml",
            """
            [item.pick]
            mining_speed = 1.0
            tool_damage_per_block = 2
            mines_and_drops = [ { block = "minecraft:stone", speed = 8.0 } ]
            denies_drops = [ { block = "minecraft:grass_block" } ]
            override_speed = [ { block = "minecraft:dirt", speed = 4.0 } ]
            """.trimIndent(),
        )
        val (_, item) = registry.items.single()
        assertEquals(2, item.toolDamagePerBlock)
        assertEquals(
            listOf(
                OMLItemDeclaration.ToolRuleSpec(
                    OMLItemDeclaration.ToolRuleKind.MINES_AND_DROPS,
                    "minecraft:stone",
                    8.0f
                ),
                OMLItemDeclaration.ToolRuleSpec(
                    OMLItemDeclaration.ToolRuleKind.DENIES_DROPS,
                    "minecraft:grass_block",
                    0f
                ),
                OMLItemDeclaration.ToolRuleSpec(OMLItemDeclaration.ToolRuleKind.OVERRIDE_SPEED, "minecraft:dirt", 4.0f),
            ),
            item.toolRules,
        )
    }

    @Test
    fun `empty sections fall back to the code-API defaults`() {
        val (registry, summary) = loadPack("defaults.toml", "[block.plain]\n[item.thing]\n")
        assertEquals(TomlContentLoader.PackSummary("defaults", 1, 1), summary)
        val (_, block) = registry.blocks.single()
        assertEquals(null, block.destroyTime)
        assertEquals(null, block.explosionResistance)
        assertEquals(false, block.requiresCorrectToolForDrops)
        val (_, item) = registry.items.single()
        assertEquals(null, item.maxDamage)
        assertEquals(1, item.toolDamagePerBlock)
        assertEquals(true, item.canDestroyBlocksInCreative)
    }

    @Test
    fun `int fields accept whole floats and reject fractions`() {
        val (ok, _) = loadPack("whole.toml", "[item.a]\nmax_damage = 800.0\n")
        assertEquals(800, ok.items.single().second.maxDamage)
        val e = assertFailsWith<IllegalStateException> { loadPack("frac.toml", "[item.a]\nmax_damage = 1.5\n") }
        assertTrue(e.message!!.contains("whole number"))
    }

    @Test
    fun `wrong value types fail loudly with field context`() {
        val e = assertFailsWith<IllegalStateException> {
            loadPack("bad_type.toml", "[item.a]\nmax_damage = \"eight hundred\"\n")
        }
        assertTrue(e.message!!.contains("bad_type.toml"))
        assertTrue(e.message!!.contains("item 'a'.max_damage"))
    }

    @Test
    fun `unknown fields and sections warn but do not reject the pack`() {
        val (registry, summary) = loadPack(
            "sloppy.toml",
            """
            [block.ruby_ore]
            destory_time = 3.0
            [unknown_section]
            x = 1
            [item.good]
            max_damage = 10
            """.trimIndent(),
        )
        assertEquals(TomlContentLoader.PackSummary("sloppy", 1, 1), summary)
        assertEquals(null, registry.blocks.single().second.destroyTime) // typo'd field never landed
        assertEquals(10, registry.items.single().second.maxDamage)
    }

    @Test
    fun `malformed toml fails with the pack name and line info`() {
        val e = assertFailsWith<IllegalStateException> {
            loadPack("broken.toml", "[block.ruby_ore]\ndestroy_time = \"oops")
        }
        assertTrue(e.message!!.contains("broken.toml"))
        assertTrue(e.message!!.contains("line 2"))
    }

    @Test
    fun `invalid ids and namespaces are rejected`() {
        assertFailsWith<IllegalArgumentException> { loadPack("Bad Id.toml", "[block.x]\n") }
        assertFailsWith<IllegalArgumentException> { loadPack("bad!name.toml", "[block.x]\n") }
        val e = assertFailsWith<IllegalArgumentException> { loadPack("ok.toml", "[block.Ruby_Ore]\n") }
        assertTrue(e.message!!.contains("not a valid block id"))
    }

    @Test
    fun `namespace is the file name sans extension`() {
        assertEquals("ruby_pack", TomlContentLoader.namespaceOf(java.io.File("mods/ruby_pack.toml")))
    }

    @Test
    fun `shaped crafting sections map onto the registry declaration`() {
        val (registry, summary) = loadPack(
            "craft_pack.toml",
            """
            [block.ruby_ore]
            destroy_time = 3.0

            [crafting.ruby_block]
            type = "shaped"
            count = 1
            pattern = ["RR", "RR"]
            key = { R = "ruby" }
            """.trimIndent(),
        )

        assertEquals(1, summary.recipes)
        assertEquals(listOf("ruby_block"), registry.shaped.map { it.first })
        assertEquals(listOf("RR", "RR"), registry.shaped.single().second)
        assertEquals(mapOf('R' to "ruby"), registry.shaped.single().third)
        assertTrue(registry.shapeless.isEmpty())
    }

    @Test
    fun `shapeless crafting sections map onto the registry declaration`() {
        val (registry, summary) = loadPack(
            "craft_pack.toml",
            """
            [crafting.ruby]
            type = "shapeless"
            count = 2
            ingredients = ["ruby_ore", "minecraft:stick"]
            """.trimIndent(),
        )

        assertEquals(1, summary.recipes)
        assertEquals(listOf("ruby"), registry.shapeless.map { it.first })
        assertEquals(listOf("ruby_ore", "minecraft:stick"), registry.shapeless.single().second)
    }

    @Test
    fun `a malformed crafting section fails the pack`() {
        // unknown type
        assertFailsWith<IllegalStateException> {
            loadPack("bad.toml", "[crafting.x]\ntype = \"weird\"\n")
        }.let { assertTrue("unknown type" in it.message!!) }

        // shaped without a pattern
        assertFailsWith<IllegalStateException> {
            loadPack("bad.toml", "[crafting.x]\ntype = \"shaped\"\nkey = { R = \"ruby\" }\n")
        }.let { assertTrue("'pattern' list" in it.message!!) }

        // shapeless with no ingredients at all is a registry-level failure
        assertFailsWith<IllegalStateException> {
            loadPack("bad.toml", "[crafting.x]\ntype = \"shapeless\"\n")
        }
    }

}
