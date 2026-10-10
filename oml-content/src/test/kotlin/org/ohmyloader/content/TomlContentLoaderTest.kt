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

        val smelting = mutableListOf<SmeltingDecl>()
        val loot = mutableListOf<LootDecl>()

        data class SmeltingDecl(
            val input: String,
            val result: String,
            val furnace: Furnace,
            val experience: Double,
            val cookingTime: Int,
        )

        data class LootDecl(val block: String, val drop: String, val min: Int, val max: Int)

        override fun declareSmelting(
            input: String,
            result: String,
            furnace: Furnace,
            experience: Double,
            cookingTime: Int,
        ) {
            smelting += SmeltingDecl(input, result, furnace, experience, cookingTime)
        }

        override fun declareBlockDrop(block: String, drop: String, dropCountMin: Int, dropCountMax: Int) {
            loot += LootDecl(block, drop, dropCountMin, dropCountMax)
        }

        data class DamageTypeDecl(
            val id: String,
            val messageId: String,
            val scaling: String,
            val effects: String?,
            val exhaustion: Float,
            val deathMessageType: String?,
        )

        val damageTypes = mutableListOf<DamageTypeDecl>()

        override fun declareDamageType(
            id: String,
            messageId: String,
            scaling: String,
            effects: String?,
            exhaustion: Float,
            deathMessageType: String?,
        ) {
            damageTypes += DamageTypeDecl(id, messageId, scaling, effects, exhaustion, deathMessageType)
        }

        val clocks = mutableListOf<String>()
        val timelines = mutableListOf<Pair<String, OMLTimelineDeclaration>>()

        override fun declareClock(id: String) {
            clocks += id
        }

        override fun declareTimeline(id: String, configure: OMLTimelineDeclaration.() -> Unit) {
            timelines += id to OMLTimelineDeclaration().apply(configure)
        }

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
                    8.0f,
                ),
                OMLItemDeclaration.ToolRuleSpec(
                    OMLItemDeclaration.ToolRuleKind.DENIES_DROPS,
                    "minecraft:grass_block",
                    0f,
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


    // ---------------------------------------------------------------------------------------------
    // T-2.4: the data track aligns with the code track — smelting, loot and ore generation
    // ---------------------------------------------------------------------------------------------

    private val sampleDamageType = """
        [damage_type.melt]
        message_id = "pack.melts"
        effects = "burning"
        exhaustion = 0.3
        death_message_type = "intentional_game_design"

        [damage_type.flop]
        message_id = "pack.flops"
    """.trimIndent()

    @Test
    fun `damage_type sections map onto declareDamageType with the game's defaults filled in`() {
        val (registry, summary) = loadPack("melter", sampleDamageType)

        assertEquals(2, registry.damageTypes.size)
        assertEquals(2, summary.damageTypes)
        val melt = registry.damageTypes.first { it.id == "melt" }
        assertEquals("pack.melts", melt.messageId)
        assertEquals("when_caused_by_living_non_player", melt.scaling)
        assertEquals("burning", melt.effects)
        assertEquals(0.3f, melt.exhaustion)
        assertEquals("intentional_game_design", melt.deathMessageType)
        val flop = registry.damageTypes.first { it.id == "flop" }
        assertEquals(null, flop.effects)
        assertEquals(0f, flop.exhaustion)
    }

    @Test
    fun `a damage_type section without its message_id fails the pack, not the loader`() {
        val failure = assertFailsWith<IllegalStateException> {
            loadPack("broken", "[damage_type.silent]\nscaling = \"always\"")
        }
        assertTrue("message_id" in failure.message!!, failure.message)
        assertTrue("broken" in failure.message!!, "the pack name has to be in the message")
    }

    @Test
    fun `smelting sections map onto declareSmelting with defaults and furnace variants`() {
        val (registry, summary) = loadPack(
            "smelter",
            """
            [smelting.tin_ingot]
            input = "raw_tin"

            [smelting.copper_ingot]
            input = "raw_copper"
            furnace = "blasting"
            experience = 0.7
            cooking_time = 100
            """.trimIndent(),
        )

        assertEquals(2, registry.smelting.size)
        assertEquals(2, summary.smelting)
        val plain = registry.smelting.first { it.result == "tin_ingot" }
        assertEquals("raw_tin", plain.input)
        assertEquals(Furnace.SMELTING, plain.furnace)
        assertEquals(0.0, plain.experience)
        assertEquals(200, plain.cookingTime)
        val blast = registry.smelting.first { it.result == "copper_ingot" }
        assertEquals(Furnace.BLASTING, blast.furnace)
        assertEquals(0.7, blast.experience)
        assertEquals(100, blast.cookingTime)
    }

    @Test
    fun `smelting sections require the input and reject unknown furnace types`() {
        assertFailsWith<IllegalStateException> {
            loadPack("smelter", "[smelting.tin_ingot]\nfurnace = \"smelting\"\n")
        }.let { assertTrue("missing its 'input'" in it.message!!) }

        assertFailsWith<IllegalStateException> {
            loadPack("smelter", "[smelting.tin_ingot]\ninput = \"raw_tin\"\nfurnace = \"microwave\"\n")
        }.let { assertTrue("unknown furnace 'microwave'" in it.message!!) }
    }

    @Test
    fun `loot sections map onto declareBlockDrop`() {
        val (registry, summary) = loadPack(
            "looty",
            """
            [loot.tin_ore]
            drop = "raw_tin"

            [loot.gravel_plus]
            drop = "minecraft:iron_nugget"
            drop_count_min = 2
            drop_count_max = 5
            """.trimIndent(),
        )

        assertEquals(2, registry.loot.size)
        assertEquals(2, summary.loot)
        val plain = registry.loot.first { it.block == "tin_ore" }
        assertEquals("raw_tin", plain.drop)
        assertEquals(1, plain.min)
        assertEquals(1, plain.max)
        val ranged = registry.loot.first { it.block == "gravel_plus" }
        assertEquals(2, ranged.min)
        assertEquals(5, ranged.max)
    }

    @Test
    fun `the ore table maps onto generateAsOre`() {
        val (registry, _) = loadPack(
            "miners",
            """
            [block.tin_ore]
            destroy_time = 3.0
            ore = { vein_size = 8, per_chunk = 6, min_y = 16, max_y = 64, biomes = ["minecraft:forest", "minecraft:taiga"] }
            """.trimIndent(),
        )

        val ore = registry.blocks.single().second.oreDeclaration
            ?: error("the ore declaration must ride the block spec")
        assertEquals(8, ore.veinSize)
        assertEquals(6, ore.perChunk)
        assertEquals(16, ore.minY)
        assertEquals(64, ore.maxY)
        assertEquals(listOf("minecraft:forest", "minecraft:taiga"), ore.biomes)
    }

    @Test
    fun `a non-table ore field fails the pack loudly`() {
        assertFailsWith<IllegalStateException> {
            loadPack("miners", "[block.tin_ore]\nore = true\n")
        }.let { assertTrue("must be an inline table" in it.message!!) }
    }

    private val sampleClockTimeline = """
        [clock.tide]

        [timeline.surge]
        clock = "tide"
        period_ticks = 24000
        markers = { low_tide = 0, high_tide = { ticks = 12000, in_commands = true } }
    """.trimIndent()

    @Test
    fun `clock and timeline sections map onto the declaration DSL`() {
        val (registry, summary) = loadPack("tides", sampleClockTimeline)

        assertEquals(listOf("tide"), registry.clocks)
        assertEquals(1, summary.clocks)
        val (id, spec) = registry.timelines.single()
        assertEquals("surge", id)
        assertEquals(1, summary.timelines)
        assertEquals("tide", spec.clock)
        assertEquals(24000, spec.periodTicks)
        assertEquals(listOf("low_tide" to 0, "high_tide" to 12000), spec.markers.map { it.id to it.ticks })
        assertEquals(listOf(false, true), spec.markers.map { it.inCommands })
    }

    @Test
    fun `a clock section with fields and a malformed marker fail the pack loudly`() {
        assertFailsWith<IllegalStateException> {
            loadPack("tides", "[clock.tide]\nperiod_ticks = 100\n")
        }.let { assertTrue("takes no fields" in it.message!!, it.message!!) }
        assertFailsWith<IllegalStateException> {
            loadPack("tides", "[timeline.surge]\nclock = \"tide\"\nmarkers = { dusk = \"morning\" }\n")
        }.let { assertTrue("tick number or a table" in it.message!!, it.message!!) }
        assertFailsWith<IllegalStateException> {
            loadPack("tides", "[timeline.surge]\nmarkers = { dusk = 1 }\n")
        }.let { assertTrue("missing its 'clock'" in it.message!!, it.message!!) }
    }

    @Test
    fun `a reload re-collects every datapack declaration the load pass collected`() {
        val registry = RecordingRegistry()
        TomlContentLoader.load("tides", "tides.toml", sampleDamageType + "\n" + sampleClockTimeline, registry)
        val reloaded = RecordingRegistry()
        TomlContentLoader.loadDataDeclarations(
            "tides", "tides.toml", sampleDamageType + "\n" + sampleClockTimeline, reloaded,
        )

        assertEquals(registry.damageTypes.map { it.id }, reloaded.damageTypes.map { it.id })
        assertEquals(registry.clocks, reloaded.clocks)
        assertEquals(registry.timelines.map { it.first }, reloaded.timelines.map { it.first })
        assertEquals(
            registry.timelines.single().second.markers.map { it.id to it.ticks },
            reloaded.timelines.single().second.markers.map { it.id to it.ticks },
        )
    }
}
