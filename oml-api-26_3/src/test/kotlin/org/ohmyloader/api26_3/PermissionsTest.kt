package org.ohmyloader.api26_3

import net.minecraft.server.permissions.Permission
import net.minecraft.server.permissions.PermissionLevel
import net.minecraft.server.permissions.PermissionSet
import java.util.*
import kotlin.test.*

/**
 * The node-addressed half of the permission surface, pinned against a set the test writes itself. The
 * game's own answer for a real player — including the wrapper the `permissions()` hook hands back — is what
 * the real-machine gates check, since no unit test runs a server.
 */
class PermissionsTest {

    private val player = UUID.randomUUID()

    /** A set that answers only what the test hands it, so a read cannot pass by accident. */
    private fun setAnswering(vararg nodeIds: String, level: PermissionLevel? = null): PermissionSet {
        val allowed = nodeIds.mapNotNull { permissionNode(it) }.toSet()
        val levelNode = level?.let { Permission.HasCommandLevel(it) }
        return PermissionSet { permission -> permission in allowed || permission == levelNode }
    }

    @Test
    fun `a node id resolves the way the game spells its own atoms`() {
        val node = permissionNode("minecraft:chat/send_messages") ?: fail("the game's own chat node must resolve")
        assertTrue(node is Permission.Atom, "an atom is what the game's chat and selector nodes are")
        assertEquals("minecraft:chat/send_messages", permissionNodeId(node))
        assertEquals(
            "minecraft:chat/send_messages",
            permissionNodeId(permissionNode("chat/send_messages") ?: fail("a bare path still names the node")),
            "the game fills its own namespace, so both spellings are one node",
        )
        assertNull(permissionNode("bad id with spaces"), "an id the game cannot parse must not look like a node")
        assertNull(permissionNodeId(Permission.HasCommandLevel(permissionLevelOf("owners")!!)))
    }

    @Test
    fun `reads answer from the set the game would consult`() {
        val set = setAnswering("mymod:build", level = permissionLevelOf("all") ?: fail("the name is the game's own"))
        assertTrue(set.hasNode("mymod:build"))
        assertFalse(set.hasNode("mymod:fly"), "a node the set does not hold is simply not held")
        assertFalse(set.hasNode("not a node"), "an unparseable id answers false rather than throwing")
        assertTrue(set.hasCommandLevel("ALL"), "the game's level names are matched its own way, case-free")
        assertFalse(set.hasCommandLevel("owners"))
        assertFalse(set.hasCommandLevel("no-such-level"))
    }

    @Test
    fun `an override records the forced answer and can be dropped again`() {
        assertFalse(OMLPermissionGrants.overrides(player).isNotEmpty())
        assertTrue(OMLPermissionGrants.put(player, "mymod:build", true))
        assertFalse(OMLPermissionGrants.put(player, "mymod:build", true), "the second grant changed nothing")
        assertTrue(OMLPermissionGrants.put(player, "mymod:build", false), "flipping the answer is a change")
        assertEquals(mapOf("mymod:build" to false), OMLPermissionGrants.overrides(player))
        assertFalse(OMLPermissionGrants.put(player, "bad id", true), "an unparseable node is refused")
        assertTrue(OMLPermissionGrants.remove(player, "mymod:build"))
        assertFalse(OMLPermissionGrants.remove(player, "mymod:build"))
        assertEquals(emptyMap(), OMLPermissionGrants.overrides(player))
    }
}
