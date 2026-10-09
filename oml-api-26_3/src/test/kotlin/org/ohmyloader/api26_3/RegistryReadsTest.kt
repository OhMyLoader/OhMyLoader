package org.ohmyloader.api26_3

import net.minecraft.core.MappedRegistry
import net.minecraft.core.RegistrationInfo
import net.minecraft.core.Registry
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.tags.TagKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The id-addressed reads the whole 26.3-facing surface rests on, pinned against a registry the test builds
 * itself rather than the game's statics — those are filled by the game's own bootstrap, which a unit test
 * does not run.
 *
 * The contract worth pinning is the difference between "no such id" and "a value": `Registry.getValue` answers
 * a defaulted registry's default for an unknown id, which would turn a typo in a mod's id into air placed or
 * silence played.
 */
class RegistryReadsTest {

    private val registryKey: ResourceKey<Registry<String>> =
        ResourceKey.createRegistryKey(Identifier.parse("oml:probe"))

    private val alphaKey = ResourceKey.create(registryKey, Identifier.parse("oml:alpha"))

    private val betaKey = ResourceKey.create(registryKey, Identifier.parse("oml:beta"))

    /** Tags bind while writes are open, and freezing is what binds the holders — the order the game's own bootstrap uses. */
    private fun probeRegistry(): Registry<String> {
        val table = MappedRegistry<String>(registryKey, RegistrationInfo.BUILT_IN.lifecycle())
        val alpha = table.register(alphaKey, "alpha", RegistrationInfo.BUILT_IN)
        table.register(betaKey, "beta", RegistrationInfo.BUILT_IN)
        table.bindTags(mapOf(TagKey.create(registryKey, Identifier.parse("oml:group")) to listOf(alpha)))
        return table.freeze()
    }

    @Test
    fun `an unregistered id answers null rather than a default value`() {
        val table = probeRegistry()

        assertEquals("alpha", table.byId("oml:alpha"))
        assertNull(table.byId("oml:missing"), "a lookup miss must not look like a value")
        assertNull(table.holderById("oml:missing"))
        // `keySet` is not registration order, so the read is compared as a set.
        assertEquals(setOf("oml:alpha", "oml:beta"), table.ids().toSet())
        assertEquals("oml:alpha", table.idOf("alpha"))
    }

    @Test
    fun `tag reads go through the game's own tag key`() {
        val table = probeRegistry()

        assertEquals(listOf("oml:alpha"), table.membersOf(registryKey, "oml:group"))
        assertEquals(emptyList(), table.membersOf(registryKey, "oml:no-such-tag"))
        assertEquals(listOf("oml:group"), table.tagsOf("oml:alpha"))
        assertEquals(emptyList(), table.tagsOf("oml:missing"))
    }

    @Test
    fun `an id is parsed the way the game parses it`() {
        // A missing namespace is not a failure: the game fills in its own, so `stone` and
        // `minecraft:stone` are the same lookup. Only characters the game rejects answer null.
        assertEquals("minecraft:stone", OMLRegistries.location("stone").toString())
        assertEquals("oml:probe", OMLRegistries.location("oml:probe").toString())
        assertNull(OMLRegistries.location("bad id with spaces"))
    }
}
