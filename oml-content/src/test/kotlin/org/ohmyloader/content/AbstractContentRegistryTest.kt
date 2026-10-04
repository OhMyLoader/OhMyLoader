package org.ohmyloader.content

import org.ohmyloader.api.content.OMLItemDeclaration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.fail
import kotlin.test.assertTrue

/**
 * The version-independent half of the two-phase content contract: collection order, declaration
 * payloads, and the "not materialized yet" guard on the handles. The materialization half is
 * version-specific and lives with the adapters (its 26.3 counterpart is exercised in game).
 */
class AbstractContentRegistryTest {

    /** Records which declarations arrived and returns fixed platform handles. */
    private class RecordingRegistry : AbstractContentRegistry() {
        val blocks = mutableListOf<BlockDecl>()
        val items = mutableListOf<ItemDecl>()
        var didMaterialize = false

        /** Queue sizes observed at materialization time (before the central drain clears them). */
        var blockQueueSizeAtMaterialize = -1
        var itemQueueSizeAtMaterialize = -1

        override fun doMaterialize() {
            didMaterialize = true
            blockQueueSizeAtMaterialize = collected.size
            itemQueueSizeAtMaterialize = collectedItems.size
            for (decl in collected) {
                blocks += decl
                materialized["${decl.namespace}:${decl.id}"] =
                    org.ohmyloader.api.content.OMLBlock("${decl.namespace}:${decl.id}") { "block-platform" }
            }
            for (decl in collectedItems) {
                items += decl
                materializedItems["${decl.namespace}:${decl.id}"] =
                    org.ohmyloader.api.content.OMLItem("${decl.namespace}:${decl.id}") { "item-platform" }
            }
        }
    }

    @Test
    fun `shaped crafting materializes into a datapack recipe json`() {
        val registry = RecordingRegistry()
        val facade = registry.forNamespace("mymod")

        facade.declareShapedCrafting(
            result = "ruby_block",
            pattern = listOf("RR", "RR"),
            key = mapOf('R' to "ruby"),
        )

        assertEquals(listOf("mymod_ruby_block"), registry.recipeIdsFor("mymod"))
        val json = registry.recipeJsonFor("mymod", "mymod_ruby_block")
            ?: fail("the crafting recipe must be served as datapack json")
        assertTrue("\"type\":\"minecraft:crafting_shaped\"" in json)
        assertTrue("\"pattern\":[\"RR\",\"RR\"]" in json)
        assertTrue("\"R\":{\"item\":\"mymod:ruby\"}" in json, "bare ingredient ids must be qualified")
        assertTrue("\"result\":{\"id\":\"mymod:ruby_block\",\"count\":1}" in json)
    }

    @Test
    fun `shapeless crafting qualifies bare ingredient ids and keeps order`() {
        val registry = RecordingRegistry()
        val facade = registry.forNamespace("mymod")

        facade.declareShapelessCrafting(
            result = "minecraft:ruby",
            ingredients = listOf("ruby", "minecraft:stick"),
            count = 2,
        )

        // the result was namespaced (minecraft:ruby), so the datapack key follows it
        val json = registry.recipeJsonFor("mymod", "minecraft_ruby")
            ?: fail("the crafting recipe must be served as datapack json")
        assertTrue("\"type\":\"minecraft:crafting_shapeless\"" in json)
        assertTrue("\"ingredients\":[{\"item\":\"mymod:ruby\"},{\"item\":\"minecraft:stick\"}]" in json)
        assertTrue("\"count\":2" in json)
    }

    @Test
    fun `two recipes with the same result get distinct datapack keys`() {
        val registry = RecordingRegistry()
        val facade = registry.forNamespace("mymod")

        facade.declareShapelessCrafting("ruby", listOf("ruby"))
        facade.declareShapelessCrafting("ruby", listOf("minecraft:stick"))

        val keys = registry.recipeIdsFor("mymod")
        assertEquals(listOf("mymod_ruby", "mymod_ruby_2"), keys)
        assertTrue(
            registry.recipeJsonFor("mymod", "mymod_ruby_2")!!.contains("stick"),
            "the second recipe must keep its own ingredient list",
        )
    }

    @Test
    fun `malformed shaped patterns fail the declaration`() {
        val registry = RecordingRegistry()
        val facade = registry.forNamespace("mymod")

        assertFailsWith<IllegalStateException> {
            facade.declareShapedCrafting("x", listOf("RR", "R"), mapOf('R' to "ruby"))
        }.let { assertTrue("1-3 cells wide" in it.message!!) }

        assertFailsWith<IllegalStateException> {
            facade.declareShapedCrafting("x", listOf("RX"), mapOf('R' to "ruby"))
        }.let { assertTrue("'X' has no key entry" in it.message!!) }

        assertFailsWith<IllegalStateException> {
            facade.declareShapedCrafting("x", listOf("RRRR"), mapOf('R' to "ruby"))
        }.let { assertTrue("1-3 cells wide" in it.message!!) }

        assertFailsWith<IllegalStateException> {
            facade.declareShapelessCrafting("x", emptyList())
        }.let { assertTrue("1-9 ingredients" in it.message!!) }

        // nothing was queued by the failed declarations
        assertEquals(emptyList(), registry.recipeIdsFor("mymod"))
    }

    @Test
    fun `declarations are collected in order with their specs`() {
        val registry = RecordingRegistry()
        val facade = registry.forNamespace("mymod")

        val block = facade.declareBlock("fancy_block") {
            destroyTime = 1.5f
            requiresCorrectToolForDrops = true
        }
        val item = facade.declareItem("fancy_sword") {
            maxDamage = 250
            attackDamage = 5.0
            minesAndDrops("minecraft:stone", 4.0f)
        }

        registry.materializeAll()

        assertTrue(registry.didMaterialize)
        assertEquals(listOf("mymod:fancy_block"), registry.blocks.map { "${it.namespace}:${it.id}" })
        assertEquals(listOf("mymod:fancy_sword"), registry.items.map { "${it.namespace}:${it.id}" })

        // Specs survive the trip untouched
        val blockDecl = registry.blocks.single()
        assertEquals(1.5f, blockDecl.spec.destroyTime)
        assertTrue(blockDecl.spec.requiresCorrectToolForDrops)
        val itemDecl = registry.items.single()
        assertEquals(250, itemDecl.spec.maxDamage)
        assertEquals(5.0, itemDecl.spec.attackDamage)
        assertEquals(OMLItemDeclaration.ToolRuleKind.MINES_AND_DROPS, itemDecl.spec.toolRules.single().kind)

        // Handles resolve to the materialized platform object
        assertEquals("block-platform", block.platform)
        assertEquals("item-platform", item.platform)
    }

    @Test
    fun `behavior hooks ride the declaration to materialization`() {
        val registry = RecordingRegistry()
        val facade = registry.forNamespace("mymod")
        var stepped = 0
        var hit = 0

        facade.declareBlock("trap") {
            onStepOn { stepped++ }
            onHit { hit++ }
        }

        registry.materializeAll()
        val spec = registry.blocks.single().spec
        assertEquals(1, spec.stepOnHandlers.size)
        assertEquals(1, spec.hitHandlers.size)

        // the stored handlers are the mod's own lambdas, invoked with the adapter-built event
        spec.stepOnHandlers.single()(omlStepOnEvent())
        spec.hitHandlers.single()(omlBlockHitEvent())
        assertEquals(1, stepped)
        assertEquals(1, hit)
    }

    private fun omlStepOnEvent() =
        org.ohmyloader.api.content.OMLStepOnEvent(1, 2, 3, isClient = false, { "level" }, { "entity" })

    private fun omlBlockHitEvent() =
        org.ohmyloader.api.content.OMLBlockHitEvent(1, 2, 3, isClient = false, { "level" }, { "player" })

    @Test
    fun `declarations reach the subclass even when it materializes nothing`() {
        // The queue sizes at materialization time prove declarations reach the subclass before the
        // central drain; the drain itself ("consumed centrally") must not depend on the subclass.
        val registry = RecordingRegistry()
        registry.forNamespace("mymod").declareBlock("plain") { }
        registry.materializeAll()
        assertEquals(1, registry.blockQueueSizeAtMaterialize)
    }

    @Test
    fun `handles refuse to resolve before materialization`() {
        val registry = RecordingRegistry()
        val handle = registry.forNamespace("mymod").declareItem("early_item")

        val error = assertFailsWith<IllegalStateException> { handle.platform }
        assertEquals("item mymod:early_item has not been materialized", error.message)
    }
}
