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
            .declareShapelessCrafting(result = "ruby", ingredients = listOf("raw_ruby", "raw_ruby"))

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

            [crafting.ruby]
            type = "shapeless"
            ingredients = ["raw_ruby", "raw_ruby"]
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
        val codeOreGen = code.oreGenDecls.single()
        val dataOreGen = data.oreGenDecls.single()
        assertEquals(codeOreGen.featureJson, dataOreGen.featureJson)
        assertEquals(codeOreGen.placedJson, dataOreGen.placedJson)
        assertEquals(code.oreGenKeysFor("ruby"), data.oreGenKeysFor("ruby"))

        // sanity: the compared artifacts are not trivially empty
        assertTrue(code.recipeJsonFor("ruby", "ruby_raw_ruby")!!.contains("smelting"))
        assertTrue(code.recipeJsonFor("ruby", "ruby_ruby")!!.contains("crafting_shapeless"))
    }
}
