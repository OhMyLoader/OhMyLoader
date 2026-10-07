package org.ohmyloader.adapter.common

import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.server.MinecraftServer
import org.ohmyloader.api.access.OMLClient
import org.ohmyloader.api.access.OMLGameProvider
import org.ohmyloader.api.access.OMLServer
import org.ohmyloader.api.world.OMLPlayer
import org.ohmyloader.api.wrapper.OMLScreen
import org.ohmyloader.api.wrapper.OMLWorld
import org.ohmyloader.core.OMLCore

/**
 * Installs the live game handles behind [OMLGame]. A client never injects the server constructor hook,
 * so the integrated server is read through `Minecraft` — the same lookup the network bridge uses, and
 * the reason one bridge serves both sides.
 */
object OMLGameBridge : OMLGameProvider {

    override val server: OMLServer?
        get() = (OMLCore.serverInstance as? MinecraftServer)?.let(::ServerHandle)
            ?: runCatching { Minecraft.getInstance().singleplayerServer }.getOrNull()?.let(::ServerHandle)

    /** `getInstance()` throws before the game is up; a dedicated server has no client to reach. */
    override val client: OMLClient?
        get() = runCatching { Minecraft.getInstance() }.getOrNull()?.let(::ClientHandle)

    /** Handle for a server instance the injection pass handed us — projecting the argument is exact, unlike [server]'s fallback chain. */
    fun handle(server: MinecraftServer): OMLServer = ServerHandle(server)
}

internal class ServerHandle(private val server: MinecraftServer) : OMLServer {
    override val platform: Any get() = server
    override val dimensionIds: List<String> get() = server.getAllLevels().map { it.dimension().identifier().toString() }
    override val players: List<OMLPlayer> get() = server.getPlayerList().getPlayers().map(::PlayerWrap)
    override val tickCount: Int get() = server.tickCount
    override val isDedicated: Boolean get() = server.isDedicatedServer()

    override fun playerByName(name: String): OMLPlayer? =
        server.getPlayerList().getPlayerByName(name)?.let(::PlayerWrap)

    /** Only loaded levels resolve; a dimension the server has not loaded is null, not an empty world. */
    override fun level(dimensionId: String): OMLWorld? =
        GameProjections.dimensionKey(dimensionId)?.let(server::getLevel)?.let(GameProjections::world)

    override fun broadcast(text: String) {
        server.getPlayerList().broadcastAll(ClientboundSystemChatPacket(Component.literal(text), false))
    }
}

private class ClientHandle(private val minecraft: Minecraft) : OMLClient {
    override val platform: Any get() = minecraft
    override val player: OMLPlayer? get() = minecraft.player?.let(::PlayerWrap)
    override val world: OMLWorld? get() = minecraft.level?.let(GameProjections::world)
    override val currentScreen: OMLScreen? get() = minecraft.gui.screen()?.let(GameProjections::screen)
    override val guiWidth: Int get() = minecraft.window.guiScaledWidth
    override val guiHeight: Int get() = minecraft.window.guiScaledHeight
}
