package org.ohmyloader.api26_3

import net.minecraft.commands.CommandSourceStack
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import org.ohmyloader.api.command.OMLCommandSource
import org.ohmyloader.api.content.OMLBlockHitEvent
import org.ohmyloader.api.content.OMLBlockTickEvent
import org.ohmyloader.api.content.OMLStepOnEvent

/**
 * Typed accessors for the surfaces that have to stay version-agnostic in `oml-api`: the behavior
 * payloads carry `Any` fields there because that module cannot name a game type, and a command source
 * hides the raw stack behind `platform`. These extensions are the official way to unwrap them — a mod
 * using them never touches a raw version object or a cast of one.
 */

/** The server level the block callback ran in; null on the client's copy of the level. */
val OMLBlockTickEvent.serverLevel: ServerLevel? get() = level as? ServerLevel

/** The server level the block callback ran in; null on the client's copy of the level. */
val OMLStepOnEvent.serverLevel: ServerLevel? get() = level as? ServerLevel

/** The entity standing on the block, typed — null when the game handed over something else. */
val OMLStepOnEvent.stepper: Entity? get() = entity as? Entity

/** The server level the block callback ran in; null on the client's copy of the level. */
val OMLBlockHitEvent.serverLevel: ServerLevel? get() = level as? ServerLevel

/** The player punching the block, typed — null when the game handed over something else. */
val OMLBlockHitEvent.hitBy: Player? get() = player as? Player

/**
 * The server level behind anything that stands in one. `ServerPlayer.level()` is declared as the
 * base `Level`, so the downcast is the game's own shape — this just gives it one spelling.
 */
val Entity.serverLevel: ServerLevel? get() = level() as? ServerLevel

/** See [Entity.serverLevel]. */
val Level.serverLevel: ServerLevel? get() = this as? ServerLevel

/**
 * The live player behind a command invocation — null for console and function callers. Resolved the
 * way the game forces mods to: the stack's own player handle is re-fetched through the player list,
 * because the game replaces the instance on a respawn and a removed one answers nothing.
 */
val OMLCommandSource.player: ServerPlayer?
    get() = (platform as? CommandSourceStack)?.player?.let { OMLGame.server?.playerList?.getPlayer(it.uuid) }
