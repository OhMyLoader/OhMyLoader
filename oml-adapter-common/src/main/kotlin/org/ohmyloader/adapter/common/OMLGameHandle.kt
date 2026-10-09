package org.ohmyloader.adapter.common

import net.minecraft.server.MinecraftServer
import org.ohmyloader.api26_3.OMLGame
import org.ohmyloader.core.OMLCore

/**
 * Publishes the server that is already up when mods initialize. Core runs the adapter's game-ready hook after
 * the injected server-constructor call registered the instance and before the first tick, and a dedicated
 * server is known to no other path: the game keeps no static of its own, so without this a mod reading the
 * handle during its own init would see null for a server that exists.
 */
fun recordRunningServer() {
    OMLGame.recordServer(OMLCore.serverInstance as? MinecraftServer)
}
