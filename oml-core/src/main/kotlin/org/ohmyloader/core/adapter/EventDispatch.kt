package org.ohmyloader.core.adapter

import org.ohmyloader.api.event.*
import org.ohmyloader.api.wrapper.OMLScreen
import org.ohmyloader.api.wrapper.OMLWorld
import java.util.Optional

/**
 * Version-independent event dispatch: converts game hooks into unified events and fires them. Events whose
 * semantics are identical across versions are implemented here; the adapter keeps a thin `EventBridge`
 * delegate exposing the same `@JvmStatic` methods (injected bytecode targets the adapter's EventBridge via
 * INVOKESTATIC, so the owner/signature must stay fixed while the logic lives here).
 * Layering rule: this class holds only mechanisms whose semantics are **identical across versions**; any
 * version-specific handling (chat parsing, the registry-freeze point, the resource-pack repository hook)
 * stays in the adapter's EventBridge. When adding an API "supported by only one version", only add the event
 * definition to api and the hook/handler to that adapter; core stays untouched.
 */
object EventDispatch {

    // ClientTickEvent is stateless; reuse the instance to avoid an allocation per tick
    private val tickEvent = ClientTickEvent()

    @JvmStatic
    fun onClientTick() {
        Events.CLIENT_TICK.fire(tickEvent)
    }

    // ServerTickEvent is stateless; reuse the instance to avoid an allocation per tick
    private val serverTickEvent = ServerTickEvent()

    @JvmStatic
    fun onServerTick() {
        Events.SERVER_TICK.fire(serverTickEvent)
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
    fun onGuiOpen(screen: Any?): Boolean {
        val event = GuiOpenEvent(screen?.let { ScreenWrap(it) })
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
    fun onWorldLoad(world: Any?) {
        Events.WORLD_LOAD.fire(WorldLoadEvent(world?.let { WorldWrap(it) }))
    }

    /** On disconnect (world = null). */
    @JvmStatic
    fun onWorldDisconnect() {
        Events.WORLD_LOAD.fire(WorldLoadEvent(null))
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

    private class ScreenWrap(override val platform: Any) : OMLScreen
    private class WorldWrap(override val platform: Any) : OMLWorld
}
