package org.ohmyloader.core.content

import org.ohmyloader.api.content.OMLItemDeclaration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
