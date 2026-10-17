package org.ohmyloader.api26_3

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.phys.BlockHitResult
import org.ohmyloader.api.tick.OMLScheduler
import org.ohmyloader.api.tick.OMLSide
import org.ohmyloader.api26_3.event.*
import java.lang.ref.WeakReference

/**
 * Turns game-hook calls into [Events] firings. Mods do not call this: the version adapter's bridge is
 * the only caller, because injected bytecode can only reach a method by exact owner and descriptor.
 *
 * It lives in the version-facing module rather than in the loader core because its parameters are game
 * types — a parent-first class naming them would resolve a second copy of the game's own classes, which
 * is the split the loader's child-first carve-out exists to prevent.
 */
object EventDispatch {

    // Stateless events are reused: a fresh instance per tick would allocate 20-60 objects a second for
    // nothing, and the tick events have no field a handler could observe differently.
    private val tickEvent = ClientTickEvent()
    private val serverTickEvent = ServerTickEvent()

    @JvmStatic
    fun onClientTick() {
        Events.CLIENT_TICK.fire(tickEvent)
        OMLScheduler.advance(OMLSide.CLIENT)
    }

    /**
     * The tick hook carries the server that ticked, and [Events.SERVER_STARTED] fires on the first tick
     * of **each** instance: a client can leave a world and start another integrated server in the same
     * process, and a latch that stayed set would leave the second server without a start event.
     *
     * Held weakly on purpose: a static strong reference would keep the *last* server of a singleplayer
     * session alive — with its levels and player — for the whole life of the process, and the latch has
     * to reset exactly when that server is gone anyway.
     */
    private var startedServer: WeakReference<Any>? = null

    /** True once per identity, which is what makes the rule testable without building a live server. */
    internal fun startsNow(identity: Any): Boolean {
        if (startedServer?.get() === identity) return false
        startedServer = WeakReference(identity)
        return true
    }

    @JvmStatic
    fun onServerTick(server: MinecraftServer) {
        OMLGame.recordServer(server)
        if (startsNow(server)) {
            Events.SERVER_STARTED.fire(ServerStartedEvent(server))
        }
        Events.SERVER_TICK.fire(serverTickEvent)
        OMLScheduler.advance(OMLSide.SERVER)
    }

    @JvmStatic
    fun onServerStopping(server: MinecraftServer) {
        // Only the server that is stopping: a client starting its next integrated server before this one
        // has finished shutting down must not lose its handle.
        if (OMLGame.server === server) OMLGame.recordServer(null)
        Events.SERVER_STOPPING.fire(ServerStoppingEvent(server))
    }

    @JvmStatic
    fun onPlayerJoined(player: ServerPlayer) = Events.PLAYER_JOINED.fire(PlayerJoinedEvent(player))

    @JvmStatic
    fun onPlayerLeft(player: ServerPlayer) = Events.PLAYER_LEFT.fire(PlayerLeftEvent(player))

    @JvmStatic
    fun onLevelTick(level: Level) = Events.LEVEL_TICK.fire(LevelTickEvent(level))

    @JvmStatic
    fun onChunkStartedTicking(level: ServerLevel, chunk: LevelChunk) =
        Events.CHUNK_STARTED_TICKING.fire(ChunkStartedTickingEvent(level, chunk))

    @JvmStatic
    fun onChunkUnloaded(level: ServerLevel, chunk: LevelChunk) =
        Events.CHUNK_UNLOADED.fire(ChunkUnloadedEvent(level, chunk))

    @JvmStatic
    fun onPlayerRespawned(player: ServerPlayer) =
        Events.PLAYER_RESPAWNED.fire(PlayerRespawnedEvent(player))

    /** Returns the canceled state, which is what lets the caller's hook short-circuit the break. */
    @JvmStatic
    fun onBlockBreak(player: ServerPlayer, position: BlockPos, state: BlockState): Boolean {
        val event = BlockBreakEvent(player, position, state)
        Events.BLOCK_BREAK.fire(event)
        return event.canceled
    }

    @JvmStatic
    fun onEntityAttack(player: ServerPlayer, target: Entity): Boolean {
        val event = EntityAttackEvent(player, target)
        Events.ENTITY_ATTACK.fire(event)
        return event.canceled
    }

    @JvmStatic
    fun onEntityInteract(player: ServerPlayer, target: Entity, hand: InteractionHand): Boolean {
        val event = EntityInteractEvent(player, target, hand)
        Events.ENTITY_INTERACT.fire(event)
        return event.canceled
    }

    @JvmStatic
    fun onBlockUsed(
        player: ServerPlayer,
        hit: BlockHitResult,
        state: BlockState,
        stack: ItemStack,
        hand: InteractionHand,
    ): Boolean {
        val event = BlockUsedEvent(player, hit, state, stack, hand)
        Events.BLOCK_USED.fire(event)
        return event.canceled
    }

    @JvmStatic
    fun onItemUsed(player: ServerPlayer, stack: ItemStack, hand: InteractionHand): Boolean {
        val event = ItemUsedEvent(player, stack, hand)
        Events.ITEM_USED.fire(event)
        return event.canceled
    }

    /**
     * Returns the fired event rather than only its verdict: the game reads its damage parameter again
     * later in the same call, so the bridge needs [LivingHurtEvent.amount] after the handlers ran.
     */
    @JvmStatic
    fun onLivingHurt(entity: LivingEntity, amount: Float, source: DamageSource): LivingHurtEvent {
        val event = LivingHurtEvent(entity, amount, source)
        Events.LIVING_HURT.fire(event)
        return event
    }

    @JvmStatic
    fun onLivingDied(entity: LivingEntity, source: DamageSource): Boolean {
        val event = LivingDiedEvent(entity, source)
        Events.LIVING_DIED.fire(event)
        return event.canceled
    }

    /**
     * Reused across frames while the game's own limit is unchanged — the steady state, since the hook
     * fires every frame with a constant limit. [FrameRateLimitEvent.currentLimit] is immutable, so a
     * changed game limit rebuilds the instance, and [FrameRateLimitEvent.limit] is reset before every
     * fire so a previous frame's handler adjustment cannot leak into the next one.
     */
    private var frameLimitEvent = FrameRateLimitEvent(0)

    @JvmStatic
    fun onFrameLimit(limit: Int): Int {
        var event = frameLimitEvent
        if (event.currentLimit != limit) {
            event = FrameRateLimitEvent(limit)
            frameLimitEvent = event
        }
        event.limit = limit
        Events.FRAME_RATE_LIMIT.fire(event)
        return event.limit
    }

    @JvmStatic
    fun onGuiOpen(screen: Screen?): Boolean {
        val event = GuiOpenEvent(screen)
        Events.GUI_OPEN.fire(event)
        return event.canceled
    }

    @JvmStatic
    fun onChatSent(message: String): Boolean {
        val event = ChatSentEvent(message)
        Events.CHAT_SENT.fire(event)
        return event.canceled
    }

    @JvmStatic
    fun onWorldLoad(world: ClientLevel?) = Events.WORLD_LOAD.fire(WorldLoadEvent(world))

    /** The client's own disconnect: the same event, with no level left to name. */
    @JvmStatic
    fun onWorldDisconnect() = Events.WORLD_LOAD.fire(WorldLoadEvent(null))

    @JvmStatic
    fun onHudRender(graphics: GuiGraphicsExtractor) = Events.HUD_RENDER.fire(HudRenderEvent(graphics))

    /**
     * The three inbound chat packet handlers, dispatched with the text resolved the way the game
     * resolves it: a system packet's content, a disguised packet's message, and for a signed chat the
     * unsigned override when the sender's text was modified and the signed body's text otherwise.
     *
     * Returns the canceled state, or `false` for a packet that carries no readable text — no event is
     * fired for it, so an unexpected packet type degrades to "no ChatReceivedEvent" rather than
     * crashing the game thread.
     */
    @JvmStatic
    fun onChatReceived(packet: Packet<ClientGamePacketListener>): Boolean {
        val text = chatTextOf(packet) ?: return false
        val event = ChatReceivedEvent(text, packet)
        Events.CHAT_RECEIVED.fire(event)
        return event.canceled
    }

    /** Readable text of an inbound chat packet, or null when it carries none. Separate from the fire so
     * the mapping is testable without `EventDefinition`'s unregister-less global listener state. */
    internal fun chatTextOf(packet: Packet<ClientGamePacketListener>): String? =
        when (packet) {
            is ClientboundSystemChatPacket -> packet.content().string
            is ClientboundDisguisedChatPacket -> packet.message().string
            is ClientboundPlayerChatPacket ->
                packet.unsignedContent().orElse(null)?.string ?: packet.body().content()

            else -> null
        }
}
