package org.ohmyloader.api.event

import org.ohmyloader.api.access.OMLServer
import org.ohmyloader.api.world.OMLPlayer

/**
 * Server about to run its first tick: dispatched once per server start, on the server thread, after
 * the dimensions exist and the player list is up. Content is registered long before this.
 */
class ServerStartedEvent(val server: OMLServer) : Event()

/**
 * Server shutting down: dispatched once, on the server thread, before the worlds are saved and the
 * loop exits — so [ServerStoppingEvent.server] is still fully usable.
 */
class ServerStoppingEvent(val server: OMLServer) : Event()

/** A player has finished entering the world and is fully on the server. */
class PlayerJoinedEvent(val player: OMLPlayer) : Event()

/** A player is leaving; the handle still reads its last-known position and inventory. */
class PlayerLeftEvent(val player: OMLPlayer) : Event()
