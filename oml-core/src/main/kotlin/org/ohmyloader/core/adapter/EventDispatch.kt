package org.ohmyloader.core.adapter

import org.ohmyloader.api.access.OMLServer
import org.ohmyloader.api.event.*
import org.ohmyloader.api.tick.OMLSide
import org.ohmyloader.api.tick.OMLScheduler
import org.ohmyloader.api.world.OMLBlockPos
import org.ohmyloader.api.world.OMLEntity
import org.ohmyloader.api.world.OMLLivingEntity
import org.ohmyloader.api.world.OMLPlayer
import org.ohmyloader.api.wrapper.OMLGuiGraphics
import org.ohmyloader.api.wrapper.OMLScreen
import org.ohmyloader.api.wrapper.OMLWorld
import java.util.*

/**
 * Version-independent event dispatch: converts game hooks into unified events and fires them. Events whose
 * semantics are identical across versions are implemented here; the adapter keeps a thin `EventBridge`
 * delegate exposing the same `@JvmStatic` methods (injected bytecode targets the adapter's EventBridge via
 * INVOKESTATIC, so the owner/signature must stay fixed while the logic lives here).
 * Layering rule: this class holds only mechanisms whose semantics are **identical across versions**; any
 * version-specific handling (chat parsing, the registry-freeze point, the resource-pack repository hook)
 * stays in the adapter's EventBridge. When adding an API "supported by only one version", only add the event
 * definition to api and the hook/handler to that adapter; core stays untouched.
 *
 * Game objects reach this layer already projected: the adapter turns them into the typed
 * [org.ohmyloader.api.world] / [org.ohmyloader.api.wrapper] handles, because core never compiles against a
 * game jar and cannot read a member of one.
 */
object EventDispatch {

    // ClientTickEvent is stateless; reuse the instance to avoid an allocation per tick
    private val tickEvent = ClientTickEvent()

    @JvmStatic
    fun onClientTick() {
        Events.CLIENT_TICK.fire(tickEvent)
        OMLScheduler.advance(OMLSide.CLIENT)
    }

    // ServerTickEvent is stateless; reuse the instance to avoid an allocation per tick
    private val serverTickEvent = ServerTickEvent()

    /**
     * [server identity] — the tick hook carries the server that ticked, and `SERVER_STARTED` fires on the
     * first tick of **each** instance: a client can leave a world and start another integrated server in
     * the same process, and a latch that stayed set would leave the second server without a start event.
     */
    private var startedServer: Any? = null

    @JvmStatic
    fun onServerTick(server: OMLServer) {
        if (startedServer !== server.platform) {
            startedServer = server.platform
            Events.SERVER_STARTED.fire(ServerStartedEvent(server))
        }
        Events.SERVER_TICK.fire(serverTickEvent)
        OMLScheduler.advance(OMLSide.SERVER)
    }

    @JvmStatic
    fun onServerStopping(server: OMLServer) {
        Events.SERVER_STOPPING.fire(ServerStoppingEvent(server))
    }

    @JvmStatic
    fun onPlayerJoined(player: OMLPlayer) {
        Events.PLAYER_JOINED.fire(PlayerJoinedEvent(player))
    }

    @JvmStatic
    fun onPlayerLeft(player: OMLPlayer) {
        Events.PLAYER_LEFT.fire(PlayerLeftEvent(player))
    }

    /** Returns the canceled state so the adapter's `CancellableReturn` handler can short-circuit the break. */
    @JvmStatic
    fun onBlockBreak(player: OMLPlayer, position: OMLBlockPos, blockId: String): Boolean {
        val event = BlockBreakEvent(player, position, blockId)
        Events.BLOCK_BREAK.fire(event)
        return event.canceled
    }

    @JvmStatic
    fun onEntityAttack(player: OMLPlayer, target: OMLEntity): Boolean {
        val event = EntityAttackEvent(player, target)
        Events.ENTITY_ATTACK.fire(event)
        return event.canceled
    }

    @JvmStatic
    fun onEntityInteract(player: OMLPlayer, target: OMLEntity, hand: String): Boolean {
        val event = EntityInteractEvent(player, target, hand)
        Events.ENTITY_INTERACT.fire(event)
        return event.canceled
    }

    @JvmStatic
    fun onLivingHurt(entity: OMLLivingEntity, amount: Float, damageType: String, attacker: OMLEntity?): Boolean {
        val event = LivingHurtEvent(entity, amount, damageType, attacker)
        Events.LIVING_HURT.fire(event)
        return event.canceled
    }

    @JvmStatic
    fun onLivingDied(entity: OMLLivingEntity, damageType: String): Boolean {
        val event = LivingDiedEvent(entity, damageType)
        Events.LIVING_DIED.fire(event)
        return event.canceled
    }

    /**
     * Reused across frames while the game's own limit is unchanged (the steady state — the
     * FramerateLimitTracker hook fires every frame with a constant limit), following the same
     * instance-reuse pattern as the tick events. [FrameRateLimitEvent.currentLimit] is immutable,
     * so a changed game limit rebuilds the instance; [FrameRateLimitEvent.limit] is reset before
     * every fire so a previous frame's handler adjustment never leaks into the next one.
     * Render-thread only — the same single-thread contract as the tick path.
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
    fun onGuiOpen(screen: OMLScreen?): Boolean {
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
    fun onWorldLoad(world: OMLWorld?) {
        Events.WORLD_LOAD.fire(WorldLoadEvent(world))
    }

    /** On disconnect (world = null). */
    @JvmStatic
    fun onWorldDisconnect() {
        Events.WORLD_LOAD.fire(WorldLoadEvent(null))
    }

    @JvmStatic
    fun onHudRender(graphics: OMLGuiGraphics) {
        Events.HUD_RENDER.fire(HudRenderEvent(graphics))
    }

    /**
     * Tries each candidate name in turn to invoke a zero-argument getter (fallback for when
     * getter names differ across targets). Intended for reuse by adapter-specific logic in
     * the adapter's EventBridge (e.g. chat-text extraction).
     */
    fun invokeGetter(target: Any, vararg names: String): Any? {
        for (name in names) {
            val method = try {
                target.javaClass.getMethod(name)
            } catch (e: NoSuchMethodException) {
                continue
            }
            return method.invoke(target)
        }
        return null
    }

    /**
     * Fires [Events.CHAT_RECEIVED] with the human text extracted from an inbound chat packet.
     * The extraction is the version-independent mechanism; the adapter passes its version's
     * accessor names (see [chatText]). Returns the event's canceled state; `false` when no text
     * could be extracted — the event is not fired for unparsable packets, so an accessor rename
     * on a future version degrades to "no ChatReceivedEvent" instead of crashing a network thread.
     */
    fun onChatReceived(packet: Any, textGetters: List<String>, bodyGetters: Pair<String, String>): Boolean {
        val text = chatText(packet, textGetters, bodyGetters) ?: return false
        val event = ChatReceivedEvent(text)
        Events.CHAT_RECEIVED.fire(event)
        return event.canceled
    }

    /**
     * Extracts the human text from a chat packet, all reflectively and tolerant: [textGetters]
     * are tried in order, the first non-null hit wins — an `Optional` value is unwrapped (absent
     * counts as a miss) and a non-String component is asked for `getString()`. When none matches,
     * the [bodyGetters] chain (`first` on the packet, `second` on the result) recovers the raw
     * text a signed body carries as a plain String. `internal` so the mechanism is testable
     * without the event system's unregister-less global listener state.
     */
    internal fun chatText(packet: Any, textGetters: List<String>, bodyGetters: Pair<String, String>): String? {
        for (name in textGetters) {
            val value = invokeGetter(packet, name) ?: continue
            val component = if (value is Optional<*>) value.orElse(null) else value
            val text = when (component) {
                null -> null
                is String -> component
                else -> invokeGetter(component, "getString") as? String
            }
            if (text != null) return text
        }
        val body = invokeGetter(packet, bodyGetters.first) ?: return null
        return invokeGetter(body, bodyGetters.second) as? String
    }
}
