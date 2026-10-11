package org.ohmyloader.api26_3

import net.minecraft.commands.CommandSourceStack
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.permissions.Permission
import net.minecraft.server.permissions.PermissionLevel
import net.minecraft.server.permissions.LevelBasedPermissionSet
import net.minecraft.server.permissions.PermissionSet
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/*
 * 26.3 gates authority on **nodes**, not only on the 0..4 op level: the game's own checks are
 * `Permission.Atom` ids (`minecraft:chat/send_messages`, `minecraft:commands/entity_selectors`) plus
 * `Permission.HasCommandLevel(level)`, and a `PermissionSet` is nothing but `hasPermission(node)`. Every
 * consumer reaches a player's set through `ServerPlayer.permissions()`, so an override applied there is what
 * the game itself consults — chat and command gates included.
 *
 * Overrides address atoms by id. The game's command-level checks are left to the game: a level is not an id,
 * and OML does not invent a spelling for something the game already spells.
 */

/** The node [id] names, in the shape the game's own atom nodes have; null for a string the game cannot parse. */
fun permissionNode(id: String): Permission? = atomNode(id)

private fun atomNode(id: String): Permission.Atom? =
    OMLRegistries.location(id)?.let { Permission.Atom.create(it) }

/** One of the game's command levels by its own lower-case name (`all`, `moderators`, …); null for another string. */
fun permissionLevelOf(name: String): PermissionLevel? =
    PermissionLevel.values().firstOrNull { it.serializedName.equals(name, ignoreCase = true) }

/** Whether this set answers [nodeId]. An id the game cannot parse answers false, not an exception. */
fun PermissionSet.hasNode(nodeId: String): Boolean =
    permissionNode(nodeId)?.let { hasPermission(it) } ?: false

/** Whether this set carries at least the command level [name] — the check the game's own `/op` gates make. */
fun PermissionSet.hasCommandLevel(name: String): Boolean =
    permissionLevelOf(name)?.let { hasPermission(Permission.HasCommandLevel(it)) } ?: false

/** The node id a permission carries, or null when it is a command level rather than an atom. */
fun permissionNodeId(node: Permission): String? = (node as? Permission.Atom)?.id()?.toString()

/** Whether this player's effective set — the one the game gates chat and commands on — answers [nodeId]. */
fun ServerPlayer.hasPermissionNode(nodeId: String): Boolean = permissions().hasNode(nodeId)

/** Whether this command source's set answers [nodeId]. */
fun CommandSourceStack.hasPermissionNode(nodeId: String): Boolean = permissions().hasNode(nodeId)

/**
 * Forces [nodeId] to be answered true for this player, whatever its op level would give, and reports whether
 * an override was actually written — re-granting the same node reports false, because nothing changed.
 */
fun ServerPlayer.grantPermissionNode(nodeId: String): Boolean =
    OMLPermissionGrants.put(uuid, nodeId, true)

/** Forces [nodeId] to be answered false for this player, the check the game's own chat gate then honours. */
fun ServerPlayer.revokePermissionNode(nodeId: String): Boolean =
    OMLPermissionGrants.put(uuid, nodeId, false)

/** Drops the override so the node follows the game's own set again; false when there was none to drop. */
fun ServerPlayer.clearPermissionNodeGrant(nodeId: String): Boolean =
    OMLPermissionGrants.remove(uuid, nodeId)

/** The overrides held for this player, node id to forced answer — OML's record, not the game's. */
val ServerPlayer.permissionNodeOverrides: Map<String, Boolean>
    get() = OMLPermissionGrants.overrides(uuid)

/**
 * The per-player node overrides the game's `permissions()` answers are wrapped with.
 *
 * Keyed by player id rather than by the `ServerPlayer` object, so a reconnect to the same identity is what a
 * mod means by "still granted" and no stale entity instance is held; entries are dropped when the player
 * leaves. With no overrides for a player the game's own set is returned **unchanged**, so a run where no mod
 * grants anything pays no wrapper and no allocation.
 */
object OMLPermissionGrants {

    private val overrides = ConcurrentHashMap<UUID, MutableMap<String, Boolean>>()

    internal fun put(player: UUID, nodeId: String, allowed: Boolean): Boolean {
        val key = atomNode(nodeId)?.id()?.toString() ?: return false
        val table = overrides.computeIfAbsent(player) { ConcurrentHashMap() }
        val changed = table.put(key, allowed) != allowed
        if (table.isEmpty()) overrides.remove(player)
        return changed
    }

    internal fun remove(player: UUID, nodeId: String): Boolean {
        val key = atomNode(nodeId)?.id()?.toString() ?: return false
        val table = overrides[player] ?: return false
        val dropped = table.remove(key) != null
        if (table.isEmpty()) overrides.remove(player)
        return dropped
    }

    internal fun overrides(player: UUID): Map<String, Boolean> =
        overrides[player]?.toMap() ?: emptyMap()

    /** Drops everything overridden for [player]. The loader calls it on disconnect, so overrides cannot pile up. */
    fun forget(player: UUID) {
        overrides.remove(player)
    }

    /**
     * The set the game is told a player holds: [base] from the game, plus this player's overrides. Called by
     * the `ServerPlayer.permissions()` transform, so the answer reaches every consumer the game has.
     */
    @JvmStatic
    fun apply(player: ServerPlayer, base: PermissionSet): PermissionSet {
        val table = overrides[player.uuid] ?: return base
        return Overridden(base, table)
    }

    private class Overridden(
        private val base: PermissionSet,
        private val table: Map<String, Boolean>,
    ) : LevelBasedPermissionSet {
        override fun hasPermission(permission: Permission): Boolean {
            val id = permissionNodeId(permission) ?: return base.hasPermission(permission)
            return table[id] ?: base.hasPermission(permission)
        }

        /**
         * The operator level the game holds underneath, because an override changes node answers and never the
         * ladder. The interface is carried rather than wrapped for one reason: `permissions()` is read by casts
         * as well as by `hasPermission` — the game's own command gate and every other mod in the run — and a
         * wrapper that only answered `PermissionSet` would break them. A base that carries no level answers the
         * lowest one, which is what the game means by "no authority".
         */
        override fun level(): PermissionLevel =
            (base as? LevelBasedPermissionSet)?.level() ?: PermissionLevel.ALL
    }
}
