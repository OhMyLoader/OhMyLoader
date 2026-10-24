@file:Suppress("DestructingShortFormNameMismatch")

package org.ohmyloader.content

import org.ohmyloader.api.content.*
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The per-entity TOML file -> ContentRegistry mapping: field names, type coercion, defaults, and the
 * loud failure contract (a malformed file throws with the file name in the message; the caller
 * catches per pack so one bad file never takes the rest down).
 */
class TomlContentLoaderTest {

    /** Captures the declarations a file produced. */
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

        val dimensionTypes = mutableListOf<Pair<String, OMLDimensionTypeDeclaration>>()
        val dimensions = mutableListOf<Pair<String, OMLDimensionDeclaration>>()

        override fun declareDimensionType(id: String, configure: OMLDimensionTypeDeclaration.() -> Unit) {
            dimensionTypes += id to OMLDimensionTypeDeclaration().apply(configure)
        }

        override fun declareDimension(id: String, configure: OMLDimensionDeclaration.() -> Unit) {
            dimensions += id to OMLDimensionDeclaration().apply(configure)
        }

        data class TagDecl(val registryId: String, val tagId: String, val spec: OMLTagDeclaration)

        val tags = mutableListOf<TagDecl>()

        override fun declareTag(registryId: String, tagId: String, configure: OMLTagDeclaration.() -> Unit) {
            tags += TagDecl(registryId, tagId, OMLTagDeclaration().apply(configure))
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

    /** One content/ file: the type directory, the path id, the file's text. */
    private data class Src(val type: String, val id: String, val text: String)

    private fun loadFiles(vararg files: Src): RecordingRegistry {
        val registry = RecordingRegistry()
        for (file in files) {
            TomlContentLoader.loadFile("ruby_pack", "ruby_pack.oml/${file.type}/${file.id}.toml", file.type, file.id, file.text, registry)
        }
        return registry
    }

    private fun loadFile(type: String, id: String, text: String): RecordingRegistry = loadFiles(Src(type, id, text))

    /** Runs [block] with stderr captured — the loader's warnings are `[<tag>] WARN: …` lines on stderr. */
    private fun stderrLinesOf(block: () -> Unit): List<String> {
        val captured = ByteArrayOutputStream()
        val original = System.err
        System.setErr(PrintStream(captured, true, Charsets.UTF_8))
        try {
            block()
        } finally {
            System.setErr(original)
        }
        return captured.toString(Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }.toList()
    }

    @Test
    fun `a per-entity file maps onto the declaration DSL`() {
        val registry = loadFiles(
            Src(
                "block", "ruby_ore",
                """
                hardness = 3.0
                blast_resistance = 6.0
                drops_require_correct_tool = true
                """.trimIndent(),
            ),
            Src(
                "item", "ruby_sword",
                """
                max_durability = 800
                attack_damage = 8.5
                attack_speed = -2.4
                mining_speed = 1.5
                """.trimIndent(),
            ),
        )
        val (blockId, block) = registry.blocks.single()
        assertEquals("ruby_ore", blockId)
        assertEquals(3.0f, block.hardness)
        assertEquals(6.0f, block.blastResistance)
        assertEquals(true, block.dropsRequireCorrectTool)

        val (itemId, item) = registry.items.single()
        assertEquals("ruby_sword", itemId)
        assertEquals(800, item.maxDurability)
        assertEquals(8.5, item.attackDamage)
        assertEquals(-2.4, item.attackSpeed)
        assertEquals(1.5f, item.miningSpeed)
    }

    @Test
    fun `a path id may carry subdirectories`() {
        val registry = loadFile("block", "ores/deep/ruby_ore", "hardness = 3.0")
        assertEquals("ores/deep/ruby_ore", registry.blocks.single().first)
    }

    @Test
    fun `tool rules map onto the rule DSL`() {
        val registry = loadFile(
            "item", "pick",
            """
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
    fun `an empty file falls back to the code-API defaults`() {
        val registry = loadFiles(Src("block", "plain", ""), Src("item", "thing", ""))
        val (_, block) = registry.blocks.single()
        assertEquals(null, block.hardness)
        assertEquals(null, block.blastResistance)
        assertEquals(false, block.dropsRequireCorrectTool)
        val (_, item) = registry.items.single()
        assertEquals(null, item.maxDurability)
        assertEquals(1, item.toolDamagePerBlock)
        assertEquals(true, item.breaksBlocksInCreative)
    }

    @Test
    fun `int fields accept whole floats and reject fractions`() {
        val ok = loadFile("item", "a", "max_durability = 800.0")
        assertEquals(800, ok.items.single().second.maxDurability)
        val e = assertFailsWith<IllegalStateException> { loadFile("item", "a", "max_durability = 1.5") }
        assertTrue(e.message!!.contains("whole number"))
    }

    @Test
    fun `wrong value types fail loudly with field context`() {
        val e = assertFailsWith<IllegalStateException> {
            loadFile("item", "a", "max_durability = \"eight hundred\"")
        }
        assertTrue(e.message!!.contains("item/a.toml"))
        assertTrue(e.message!!.contains("item 'a'.max_durability"))
    }

    @Test
    fun `unknown fields warn but do not reject the file`() {
        val lines = stderrLinesOf {
            val registry = loadFile("block", "ruby_ore", "destory_time = 3.0")
            assertEquals(null, registry.blocks.single().second.hardness) // typo'd field never landed
        }
        assertTrue(
            lines.any { "WARN" in it && "destory_time" in it },
            "the typo'd field must be reported loudly, got: $lines",
        )
    }

    @Test
    fun `a file declaring a section is a structural error`() {
        // One file, one entity: a real TOML section means the author is writing the old
        // single-file layout, and silently loading only the root keys would drop half the data.
        val e = assertFailsWith<IllegalStateException> {
            loadFile("block", "ruby_ore", "hardness = 3.0\n\n[ore]\nvein_size = 8\n")
        }
        assertTrue("one file declares one entity" in e.message!!, e.message)
    }

    @Test
    fun `malformed toml fails with the file name and line info`() {
        val e = assertFailsWith<IllegalStateException> {
            loadFile("block", "ruby_ore", "hardness = 3.0\nbroken = \"oops")
        }
        assertTrue(e.message!!.contains("block/ruby_ore.toml"))
        assertTrue(e.message!!.contains("line 2"))
    }

    @Test
    fun `invalid ids, types and namespaces are rejected`() {
        val e = assertFailsWith<IllegalArgumentException> { loadFile("block", "Ruby_Ore", "") }
        assertTrue(e.message!!.contains("not a valid block id"))
        assertFailsWith<IllegalArgumentException> { loadFile("block", "ores/Ruby", "") }
        assertFailsWith<IllegalArgumentException> { loadFile("mystery", "x", "") }
        assertEquals("ruby_pack", TomlContentLoader.namespaceOf(java.io.File("mods/ruby_pack.oml")))
    }

    @Test
    fun `shaped crafting files map onto the registry declaration`() {
        val registry = loadFile(
            "crafting", "ruby_block",
            """
            type = "shaped"
            count = 1
            pattern = ["RR", "RR"]
            key = { R = "ruby" }
            """.trimIndent(),
        )
        assertEquals(listOf("ruby_block"), registry.shaped.map { it.first })
        assertEquals(listOf("RR", "RR"), registry.shaped.single().second)
        assertEquals(mapOf('R' to "ruby"), registry.shaped.single().third)
        assertTrue(registry.shapeless.isEmpty())
    }

    @Test
    fun `shapeless crafting files map onto the registry declaration`() {
        val registry = loadFile(
            "crafting", "ruby",
            """
            type = "shapeless"
            count = 2
            ingredients = ["ruby_ore", "minecraft:stick"]
            """.trimIndent(),
        )
        assertEquals(listOf("ruby"), registry.shapeless.map { it.first })
        assertEquals(listOf("ruby_ore", "minecraft:stick"), registry.shapeless.single().second)
    }

    @Test
    fun `a malformed crafting file fails the pack`() {
        // unknown type
        assertFailsWith<IllegalStateException> {
            loadFile("crafting", "x", "type = \"weird\"")
        }.let { assertTrue("unknown type" in it.message!!) }

        // shaped without a pattern
        assertFailsWith<IllegalStateException> {
            loadFile("crafting", "x", "type = \"shaped\"\nkey = { R = \"ruby\" }")
        }.let { assertTrue("'pattern' list" in it.message!!) }

        // shapeless with no ingredients at all is a registry-level failure
        assertFailsWith<IllegalStateException> {
            loadFile("crafting", "x", "type = \"shapeless\"")
        }
    }


    // ---------------------------------------------------------------------------------------------
    // T-2.4: the data track aligns with the code track — smelting, loot and ore generation
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `damage_type files map onto declareDamageType with the game's defaults filled in`() {
        val registry = loadFiles(
            Src(
                "damage_type", "melt",
                """
                message_id = "pack.melts"
                effects = "burning"
                exhaustion = 0.3
                death_message_type = "intentional_game_design"
                """.trimIndent(),
            ),
            Src("damage_type", "flop", "message_id = \"pack.flops\""),
        )

        assertEquals(2, registry.damageTypes.size)
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
    fun `a damage_type file without its message_id fails the pack, not the loader`() {
        val failure = assertFailsWith<IllegalStateException> {
            loadFile("damage_type", "silent", "scaling = \"always\"")
        }
        assertTrue("message_id" in failure.message!!, failure.message)
        assertTrue("damage_type/silent.toml" in failure.message!!, "the file has to be in the message")
    }

    @Test
    fun `smelting files map onto declareSmelting with defaults and furnace variants`() {
        val registry = loadFiles(
            Src("smelting", "tin_ingot", "input = \"raw_tin\""),
            Src(
                "smelting", "copper_ingot",
                """
                input = "raw_copper"
                furnace = "blasting"
                experience = 0.7
                cooking_time = 100
                """.trimIndent(),
            ),
        )

        assertEquals(2, registry.smelting.size)
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
    fun `smelting files require the input and reject unknown furnace types`() {
        assertFailsWith<IllegalStateException> {
            loadFile("smelting", "tin_ingot", "furnace = \"smelting\"")
        }.let { assertTrue("missing its 'input'" in it.message!!) }

        assertFailsWith<IllegalStateException> {
            loadFile("smelting", "tin_ingot", "input = \"raw_tin\"\nfurnace = \"microwave\"")
        }.let { assertTrue("unknown furnace 'microwave'" in it.message!!) }
    }

    @Test
    fun `loot files map onto declareBlockDrop`() {
        val registry = loadFiles(
            Src("loot", "tin_ore", "drop = \"raw_tin\""),
            Src(
                "loot", "gravel_plus",
                """
                drop = "minecraft:iron_nugget"
                drop_count_min = 2
                drop_count_max = 5
                """.trimIndent(),
            ),
        )

        assertEquals(2, registry.loot.size)
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
        val registry = loadFile(
            "block", "tin_ore",
            "hardness = 3.0\n" +
                "ore = { vein_size = 8, per_chunk = 6, min_y = 16, max_y = 64, biomes = [\"minecraft:forest\", \"minecraft:taiga\"] }",
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
            loadFile("block", "tin_ore", "ore = true")
        }.let { assertTrue("must be an inline table" in it.message!!) }
    }

    @Test
    fun `clock and timeline files map onto the declaration DSL`() {
        val registry = loadFiles(
            Src("clock", "tide", ""),
            Src(
                "timeline", "surge",
                "clock = \"tide\"\nperiod_ticks = 24000\nmarkers = { low_tide = 0, high_tide = { ticks = 12000, in_commands = true } }",
            ),
        )

        assertEquals(listOf("tide"), registry.clocks)
        val (id, spec) = registry.timelines.single()
        assertEquals("surge", id)
        assertEquals("tide", spec.clock)
        assertEquals(24000, spec.periodTicks)
        assertEquals(listOf("low_tide" to 0, "high_tide" to 12000), spec.markers.map { it.id to it.ticks })
        assertEquals(listOf(false, true), spec.markers.map { it.inCommands })
    }

    @Test
    fun `a clock file with fields and a malformed marker fail the pack loudly`() {
        assertFailsWith<IllegalStateException> {
            loadFile("clock", "tide", "period_ticks = 100")
        }.let { assertTrue("takes no fields" in it.message!!, it.message!!) }
        assertFailsWith<IllegalStateException> {
            loadFile("timeline", "surge", "clock = \"tide\"\nmarkers = { dusk = \"morning\" }")
        }.let { assertTrue("tick number or a table" in it.message!!, it.message!!) }
        assertFailsWith<IllegalStateException> {
            loadFile("timeline", "surge", "markers = { dusk = 1 }")
        }.let { assertTrue("missing its 'clock'" in it.message!!, it.message!!) }
    }

    @Test
    fun `dimension_type and dimension files map onto the declaration DSL`() {
        val registry = loadFiles(
            Src("clock", "tide", ""),
            Src(
                "dimension_type", "deep",
                """
                sky_light = false
                ceiling = true
                min_y = 0
                height = 128
                logical_height = 128
                coordinate_scale = 2.0
                ambient_light = 0.3
                skybox = "none"
                fixed_time = true
                clock = "tide"
                timelines = ["surge"]
                """.trimIndent(),
            ),
            Src(
                "dimension", "deep_home",
                """
                type = "deep"
                layers = [ { block = "minecraft:bedrock", height = 1 }, { block = "ruby_block", height = 3 } ]
                biome = "minecraft:plains"
                """.trimIndent(),
            ),
        )

        assertEquals(listOf("tide"), registry.clocks)
        val (typeId, type) = registry.dimensionTypes.single()
        assertEquals("deep", typeId)
        assertEquals(false, type.skyLight)
        assertEquals(true, type.ceiling)
        assertEquals(0, type.minY)
        assertEquals(128, type.height)
        assertEquals(128, type.logicalHeight)
        assertEquals(2.0, type.coordinateScale)
        assertEquals(0.3f, type.ambientLight)
        assertEquals("none", type.skybox)
        assertEquals(true, type.fixedTime)
        assertEquals("tide", type.clock)
        assertEquals(listOf("surge"), type.timelines)
        assertEquals("#minecraft:infiniburn_overworld", type.infiniburn)
        assertEquals(7, type.monsterSpawnLightLevel)

        val (dimId, dim) = registry.dimensions.single()
        assertEquals("deep_home", dimId)
        assertEquals("deep", dim.type)
        assertEquals("flat", dim.generator)
        assertEquals(listOf("minecraft:bedrock" to 1, "ruby_block" to 3), dim.layers.map { it.block to it.height })
        assertEquals("minecraft:plains", dim.biome)
        assertEquals(false, dim.features)
    }

    @Test
    fun `a dimension without a type or with a malformed layer fails the pack loudly`() {
        assertFailsWith<IllegalStateException> {
            loadFile("dimension", "home", "generator = \"flat\"")
        }.let { assertTrue("missing its 'type' string" in it.message!!, it.message!!) }
        assertFailsWith<IllegalStateException> {
            loadFile("dimension", "home", "type = \"deep\"\nlayers = \"bedrock\"")
        }.let { assertTrue("must be an array" in it.message!!, it.message!!) }
        assertFailsWith<IllegalStateException> {
            loadFile("dimension", "home", "type = \"deep\"\nlayers = [ { height = 1 } ]")
        }.let { assertTrue("missing its 'block' string" in it.message!!, it.message!!) }
        assertFailsWith<IllegalStateException> {
            loadFile("dimension_type", "deep", "sky_light = \"bright\"")
        }.let { assertTrue("must be true or false" in it.message!!, it.message!!) }
    }

    /**
     * The reload pass re-runs every file the load pass ran, minus the frozen kinds: OMLCore's
     * reload filters by [TomlContentLoader.RELOADABLE_TYPES], so block and item files are re-read
     * but not re-declared, and everything else must land identically.
     */
    @Test
    fun `a reload re-collects every datapack declaration the load pass collected`() {
        val files = listOf(
            Src("damage_type", "melt", "message_id = \"pack.melts\"\neffects = \"burning\"\nexhaustion = 0.3"),
            Src("damage_type", "flop", "message_id = \"pack.flops\""),
            Src("clock", "tide", ""),
            Src("timeline", "surge", "clock = \"tide\"\nperiod_ticks = 24000\nmarkers = { low_tide = 0, high_tide = { ticks = 12000, in_commands = true } }"),
            Src(
                "dimension_type", "deep",
                "sky_light = false\nceiling = true\nmin_y = 0\nheight = 128\ncoordinate_scale = 2.0\n" +
                    "ambient_light = 0.3\nskybox = \"none\"\nfixed_time = true\nclock = \"tide\"\ntimelines = [\"surge\"]",
            ),
            Src(
                "dimension", "deep_home",
                "type = \"deep\"\nlayers = [ { block = \"minecraft:bedrock\", height = 1 }, { block = \"ruby_block\", height = 3 } ]\nbiome = \"minecraft:plains\"",
            ),
            Src(
                "tag", "fire_join",
                "registry = \"minecraft:damage_type\"\ntag = \"minecraft:is_fire\"\nvalues = [\"melt\", \"#minecraft:is_projectile\"]",
            ),
            Src("block", "frozen", "hardness = 3.0"),
            Src("item", "also_frozen", "max_durability = 64"),
        )

        val loaded = RecordingRegistry()
        for (file in files) {
            TomlContentLoader.loadFile("tides", "tides.oml/${file.type}/${file.id}.toml", file.type, file.id, file.text, loaded)
        }
        val reloaded = RecordingRegistry()
        for (file in files.filter { it.type in TomlContentLoader.RELOADABLE_TYPES }) {
            TomlContentLoader.loadFile("tides", "tides.oml/${file.type}/${file.id}.toml", file.type, file.id, file.text, reloaded)
        }

        assertTrue(reloaded.blocks.isEmpty() && reloaded.items.isEmpty(), "the frozen kinds must not re-declare")
        assertEquals(loaded.damageTypes.map { it.id }, reloaded.damageTypes.map { it.id })
        assertEquals(loaded.clocks, reloaded.clocks)
        assertEquals(loaded.timelines.map { it.first }, reloaded.timelines.map { it.first })
        assertEquals(
            loaded.timelines.single().second.markers.map { it.id to it.ticks },
            reloaded.timelines.single().second.markers.map { it.id to it.ticks },
        )
        assertEquals(loaded.dimensionTypes.map { it.first }, reloaded.dimensionTypes.map { it.first })
        assertEquals(
            loaded.dimensionTypes.single().second.height,
            reloaded.dimensionTypes.single().second.height,
        )
        assertEquals(
            loaded.dimensions.single().second.layers.map { it.block to it.height },
            reloaded.dimensions.single().second.layers.map { it.block to it.height },
        )
        assertEquals(
            loaded.tags.map { it.registryId to it.tagId },
            reloaded.tags.map { it.registryId to it.tagId },
        )
        assertEquals(
            loaded.tags.first().spec.entries,
            reloaded.tags.first().spec.entries,
        )
    }

    private val sampleTag = Src(
        "tag", "fire_join",
        "registry = \"minecraft:damage_type\"\ntag = \"minecraft:is_fire\"\nvalues = [\"melt\", { id = \"#minecraft:is_projectile\", required = false }]",
    )

    /**
     * The tag's ids have to be fields rather than file names: a TOML bare key cannot spell the `:` of
     * `minecraft:is_fire` or the `/` of `worldgen/biome`, and both are ordinary tag names.
     */
    @Test
    fun `a tag file maps onto the declaration DSL`() {
        val registry = loadFiles(
            sampleTag,
            Src("tag", "probe_group", "registry = \"item\"\ntag = \"probe_group\"\nvalues = [\"minecraft:diamond\"]\nreplace = true"),
        )

        val join = registry.tags.first()
        assertEquals("minecraft:damage_type", join.registryId)
        assertEquals("minecraft:is_fire", join.tagId)
        assertEquals(false, join.spec.replace)
        assertEquals(
            listOf(
                OMLTagEntry("melt", required = true, tag = false),
                OMLTagEntry("minecraft:is_projectile", required = false, tag = true),
            ),
            join.spec.entries,
            "a leading `#` in a value is the game's spelling for a reference to another tag",
        )

        val group = registry.tags[1]
        assertEquals("item", group.registryId)
        assertEquals("probe_group", group.tagId)
        assertEquals(true, group.spec.replace)
        assertEquals(listOf(OMLTagEntry("minecraft:diamond", required = true, tag = false)), group.spec.entries)
    }

    @Test
    fun `a malformed tag file fails the pack loudly`() {
        assertFailsWith<IllegalStateException> {
            loadFile("tag", "join", "registry = \"minecraft:damage_type\"")
        }.let { assertTrue("missing its 'tag' string" in it.message!!, it.message!!) }
        assertFailsWith<IllegalStateException> {
            loadFile("tag", "join", "tag = \"is_fire\"")
        }.let { assertTrue("missing its 'registry' string" in it.message!!, it.message!!) }
        assertFailsWith<IllegalStateException> {
            loadFile("tag", "join", "registry = 3\ntag = \"is_fire\"")
        }.let { assertTrue("missing its 'registry' string" in it.message!!, it.message!!) }
        assertFailsWith<IllegalStateException> {
            loadFile("tag", "join", "registry = \"damage_type\"\ntag = \"is_fire\"\nvalues = 7")
        }.let { assertTrue("must be an array" in it.message!!, it.message!!) }
        assertFailsWith<IllegalStateException> {
            loadFile("tag", "join", "registry = \"damage_type\"\ntag = \"is_fire\"\nvalues = [ 3 ]")
        }.let { assertTrue("id strings or tables" in it.message!!, it.message!!) }
    }
}
