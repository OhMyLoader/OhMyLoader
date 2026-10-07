package org.ohmyloader.api26_3.event

import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import org.ohmyloader.api.event.Event

/**
 * The server is about to run its first tick: once per server start, on the server thread, after the
 * dimensions exist and the player list is up. Content was registered long before this.
 */
class ServerStartedEvent(val server: MinecraftServer) : Event()

/**
 * The server is shutting down: once, on the server thread, before the worlds are saved and the loop
 * exits — so [server] is still fully usable.
 */
class ServerStoppingEvent(val server: MinecraftServer) : Event()

/** A player has finished entering the world and is fully on the server. */
class PlayerJoinedEvent(val player: ServerPlayer) : Event()

/** A player is leaving; the instance still reads its last-known position and inventory. */
class PlayerLeftEvent(val player: ServerPlayer) : Event()

/**
 * A player has respawned. [player] is the instance the server built for this new life — the one that
 * was dying is discarded and stops reading live state — so a mod that held a handle from before the
 * death has to pick this one up instead.
 */
class PlayerRespawnedEvent(val player: ServerPlayer) : Event()
