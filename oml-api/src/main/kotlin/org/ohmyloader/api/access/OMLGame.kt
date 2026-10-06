package org.ohmyloader.api.access

import org.ohmyloader.api.world.OMLPlayer
import org.ohmyloader.api.wrapper.OMLScreen
import org.ohmyloader.api.wrapper.OMLWorld

/**
 * The logical server, from whichever side of the process asks: a dedicated server's own instance, or
 * the integrated server of a singleplayer game. Reads are live, so a handle held across ticks stays
 * useful as long as the server itself is running.
 */
interface OMLServer {
    /** The underlying game object (a `MinecraftServer`). */
    val platform: Any

    /** Registry ids of the loaded dimensions. */
    val dimensionIds: List<String>

    /** Players currently on the server; the integrated server's list is the singleplayer player. */
    val players: List<OMLPlayer>

    /** Ticks this server has run; a fresh world starts at its own counter, so use it for deltas, not absolutes. */
    val tickCount: Int

    /** False for the integrated server of a singleplayer game. */
    val isDedicated: Boolean

    fun playerByName(name: String): OMLPlayer?

    /** Shows [text] in the chat of every connected player. */
    fun broadcast(text: String)
}

/** The client side of the process: null on a dedicated server, which has none. */
interface OMLClient {
    /** The underlying game object (a `Minecraft`). */
    val platform: Any

    /** The player this client controls, or null at the main menu. */
    val player: OMLPlayer?

    /** The level being rendered, or null outside a world. */
    val world: OMLWorld?

    /** The screen currently shown, or null when the game world itself is on screen. */
    val currentScreen: OMLScreen?

    /** Window size in GUI units, which is what HUD drawing addresses. */
    val guiWidth: Int
    val guiHeight: Int
}

/**
 * Resolves the running game objects. The version adapter installs a provider before mods initialize;
 * a mod never implements this.
 */
interface OMLGameProvider {
    /** The running server, or null when this process has none (client at the main menu, server stopped). */
    val server: OMLServer?

    /** The client, or null on a dedicated server. */
    val client: OMLClient?
}

/**
 * Entry point to live game state, on either side. Both members are null whenever the object does not
 * exist yet — a mod running at a hook the object is not up for has to handle that, exactly as the
 * game does.
 */
object OMLGame {

    @JvmField
    @Volatile
    var provider: OMLGameProvider? = null

    val server: OMLServer? get() = provider?.server

    val client: OMLClient? get() = provider?.client
}
