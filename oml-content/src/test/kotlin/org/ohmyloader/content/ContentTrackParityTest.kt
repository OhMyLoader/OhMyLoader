package org.ohmyloader.content

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two declaration tracks must produce the same registration result for the same content: a
 * jar mod writing the code DSL and a `.oml` pack writing the equivalent TOML end up with
 * identical collected specs and identical datapack JSON, or one of the two tracks is drifting
 * from the other and only one of them is getting fixed.
 */
class ContentTrackParityTest {

    /** Collects without materializing — the comparison happens on the collected artifacts. */
    private class CollectingRegistry : AbstractContentRegistry() {
        override fun doMaterialize() = Unit

        fun blockSpec(id: String) = collected.single { it.id == id }.spec
        fun itemSpec(id: String) = collectedItems.single { it.id == id }.spec
    }

    @Test
    fun `code track and TOML track produce identical registration results`() {
        val code = CollectingRegistry()
        val data = CollectingRegistry()

        // --- the same content, declared twice -----------------------------------------------
        code.forNamespace("ruby").declareBlock("ruby_ore") {
            destroyTime = 3.0f
            explosionResistance = 3.0f
            requiresCorrectToolForDrops = true
            generateAsOre {
                veinSize = 8
                perChunk = 6
                minY = 16
                maxY = 64
                biomes += listOf("minecraft:forest", "minecraft:taiga")
            }
        }
        code.forNamespace("ruby").declareItem("ruby") {
            maxDamage = 250
            attackDamage = 5.0
        }
        code.forNamespace("ruby")
            .declareSmelting(input = "raw_ruby", result = "ruby", experience = 0.7, cookingTime = 100)
        code.forNamespace("ruby").declareBlockDrop(block = "ruby_ore", drop = "raw_ruby")
        code.forNamespace("ruby")
            .declareDamageType(id = "melt", messageId = "ruby.melts", effects = "burning", exhaustion = 0.3f)
        code.forNamespace("ruby")
            .declareShapelessCrafting(result = "ruby", ingredients = listOf("raw_ruby", "raw_ruby"))
        code.forNamespace("ruby").declareClock("tide")
        code.forNamespace("ruby").declareTimeline("surge") {
            clock = "tide"
            periodTicks = 24000
            marker("low_tide", 0)
            marker("high_tide", 12000, inCommands = true)
        }
        code.forNamespace("ruby").declareDimensionType("deep") {
            skyLight = false
            ceiling = true
            minY = 0
            height = 128
            coordinateScale = 2.0
            ambientLight = 0.3f
            skybox = "none"
            fixedTime = true
            clock = "tide"
            timelines += "surge"
        }
        code.forNamespace("ruby").declareDimension("deep_home") {
            type = "deep"
            layer("minecraft:bedrock", 1)
            layer("raw_ruby", 3)
            biome = "minecraft:plains"
        }
        code.forNamespace("ruby").declareTag("minecraft:damage_type", "minecraft:is_fire") {
            element("melt")
            tag("minecraft:is_projectile")
        }

        TomlContentLoader.load(
            "ruby",
            "parity-test content.toml",
            """
            [block.ruby_ore]
            destroy_time = 3.0
            explosion_resistance = 3.0
            requires_correct_tool = true
            ore = { vein_size = 8, per_chunk = 6, min_y = 16, max_y = 64, biomes = ["minecraft:forest", "minecraft:taiga"] }

            [item.ruby]
            max_damage = 250
            attack_damage = 5.0

            [smelting.ruby]
            input = "raw_ruby"
            experience = 0.7
            cooking_time = 100

            [loot.ruby_ore]
            drop = "raw_ruby"

            [damage_type.melt]
            message_id = "ruby.melts"
            effects = "burning"
            exhaustion = 0.3

            [crafting.ruby]
            type = "shapeless"
            ingredients = ["raw_ruby", "raw_ruby"]

            [clock.tide]

            [timeline.surge]
            clock = "tide"
            period_ticks = 24000
            markers = { low_tide = 0, high_tide = { ticks = 12000, in_commands = true } }

            [dimension_type.deep]
            sky_light = false
            ceiling = true
            min_y = 0
            height = 128
            coordinate_scale = 2.0
            ambient_light = 0.3
            skybox = "none"
            fixed_time = true
            clock = "tide"
            timelines = ["surge"]

            [dimension.deep_home]
            type = "deep"
            layers = [ { block = "minecraft:bedrock", height = 1 }, { block = "raw_ruby", height = 3 } ]
            biome = "minecraft:plains"

            [tag.fire_join]
            registry = "minecraft:damage_type"
            tag = "minecraft:is_fire"
            values = ["melt", "#minecraft:is_projectile"]
            """.trimIndent(),
            data.forNamespace("ruby"),
        )

        // --- and the results must not be distinguishable ------------------------------------
        val codeBlock = code.blockSpec("ruby_ore")
        val dataBlock = data.blockSpec("ruby_ore")
        assertEquals(codeBlock.destroyTime, dataBlock.destroyTime)
        assertEquals(codeBlock.explosionResistance, dataBlock.explosionResistance)
        assertEquals(codeBlock.requiresCorrectToolForDrops, dataBlock.requiresCorrectToolForDrops)
        val codeOre = codeBlock.oreDeclaration ?: error("code track lost the ore declaration")
        val dataOre = dataBlock.oreDeclaration ?: error("data track lost the ore declaration")
        assertEquals(codeOre.veinSize, dataOre.veinSize)
        assertEquals(codeOre.perChunk, dataOre.perChunk)
        assertEquals(codeOre.minY, dataOre.minY)
        assertEquals(codeOre.maxY, dataOre.maxY)
        assertEquals(codeOre.biomes, dataOre.biomes)

        val codeItem = code.itemSpec("ruby")
        val dataItem = data.itemSpec("ruby")
        assertEquals(codeItem.maxDamage, dataItem.maxDamage)
        assertEquals(codeItem.attackDamage, dataItem.attackDamage)

        // the datapack files the pack layer serves must be byte-identical (smelting keys by
        // input, crafting keys by result — the same keying both tracks go through)
        assertEquals(code.recipeJsonFor("ruby", "ruby_raw_ruby"), data.recipeJsonFor("ruby", "ruby_raw_ruby"))
        assertEquals(code.recipeJsonFor("ruby", "ruby_ruby"), data.recipeJsonFor("ruby", "ruby_ruby"))
        assertEquals(code.lootJsonFor("ruby", "ruby_ore"), data.lootJsonFor("ruby", "ruby_ore"))
        assertEquals(code.damageTypeJsonFor("ruby", "melt"), data.damageTypeJsonFor("ruby", "melt"))
        assertEquals(code.damageTypeIdsFor("ruby"), data.damageTypeIdsFor("ruby"))
        assertEquals(code.clockJsonFor("ruby", "tide"), data.clockJsonFor("ruby", "tide"))
        assertEquals(code.timelineJsonFor("ruby", "surge"), data.timelineJsonFor("ruby", "surge"))
        assertEquals(code.timelineIdsFor("ruby"), data.timelineIdsFor("ruby"))
        assertEquals(code.dimensionTypeJsonFor("ruby", "deep"), data.dimensionTypeJsonFor("ruby", "deep"))
        assertEquals(code.dimensionJsonFor("ruby", "deep_home"), data.dimensionJsonFor("ruby", "deep_home"))
        assertEquals(code.dimensionTypeIdsFor("ruby"), data.dimensionTypeIdsFor("ruby"))
        assertEquals(code.dimensionIdsFor("ruby"), data.dimensionIdsFor("ruby"))
        assertEquals(
            code.tagJsonAt("minecraft", "tags/damage_type/is_fire.json"),
            data.tagJsonAt("minecraft", "tags/damage_type/is_fire.json"),
        )
        assertEquals(code.tagPathsFor("minecraft"), data.tagPathsFor("minecraft"))
        val codeOreGen = code.oreGenDecls.single()
        val dataOreGen = data.oreGenDecls.single()
        assertEquals(codeOreGen.featureJson, dataOreGen.featureJson)
        assertEquals(codeOreGen.placedJson, dataOreGen.placedJson)
        assertEquals(code.oreGenKeysFor("ruby"), data.oreGenKeysFor("ruby"))

        // sanity: the compared artifacts are not trivially empty
        assertTrue(code.recipeJsonFor("ruby", "ruby_raw_ruby")!!.contains("smelting"))
        assertTrue(code.recipeJsonFor("ruby", "ruby_ruby")!!.contains("crafting_shapeless"))
        assertTrue(code.timelineJsonFor("ruby", "surge")!!.contains("\"ruby:high_tide\""), "the marker id is bound to the pack namespace")
        assertTrue(code.dimensionJsonFor("ruby", "deep_home")!!.contains("\"type\":\"ruby:deep\""), "the stem binds the type to the pack namespace")
        assertTrue(
            code.tagJsonAt("minecraft", "tags/damage_type/is_fire.json")!!.contains("\"ruby:melt\""),
            "a bare member id binds to the declaring namespace on both tracks",
        )
    }
}
