package org.ohmyloader.api26_3

import net.minecraft.client.Minecraft
import net.minecraft.server.MinecraftServer

/**
 * The running game objects, from whichever side of the process asks. Both are null whenever the object
 * does not exist yet — a mod running at a hook the game is not up for has to handle that, exactly as the
 * game does.
 *
 * Reads are live and nothing is projected: the types here are the game's own, so everything the game can
 * do with a server or a client is available without an OML call in between.
 */
object OMLGame {

    @Volatile
    private var recorded: MinecraftServer? = null

    /**
     * [version adapter] Records the server the loader has just seen running, or clears the record once it
     * has stopped. The game keeps no static of its own for this, so the record is the only server handle on
     * a dedicated server — and holding one past the stop would keep that server's levels and players alive
     * for the life of the process.
     */
    @JvmStatic
    fun recordServer(server: MinecraftServer?) {
        recorded = server
    }

    /** The dedicated server, or the integrated server of a singleplayer game; null outside a running game. */
    val server: MinecraftServer?
        get() = recorded ?: client?.singleplayerServer

    /** The client; null on a dedicated server, which never boots one. */
    val client: Minecraft? get() = runCatching { Minecraft.getInstance() }.getOrNull()
}
