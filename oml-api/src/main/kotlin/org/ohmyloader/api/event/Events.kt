package org.ohmyloader.api.event

/**
 * Unified event registry: mods subscribe via [EventDefinition.register], and
 * version adapters dispatch via [EventDefinition.fire].
 */
object Events {
    /** Dispatched once per logical tick. */
    val CLIENT_TICK = EventDefinition<ClientTickEvent>("client_tick")

    /** Dispatched once per logical server tick (dedicated server only). */
    val SERVER_TICK = EventDefinition<ServerTickEvent>("server_tick")

    /**
     * Frame-rate limit query (mutable): dispatched every frame; handlers may
     * override `limit`.
     */
    val FRAME_RATE_LIMIT = EventDefinition<FrameRateLimitEvent>("frame_rate_limit")

    /**
     * GUI open (cancellable): dispatched right before a GUI is shown;
     * [GuiOpenEvent.screen] being null means the current GUI is being closed.
     */
    val GUI_OPEN = EventDefinition<GuiOpenEvent>("gui_open")

    /** Player sending a chat message (cancellable). */
    val CHAT_SENT = EventDefinition<ChatSentEvent>("chat_sent")

    /** Chat message received (cancellable; cancelling prevents it from being displayed). */
    val CHAT_RECEIVED = EventDefinition<ChatReceivedEvent>("chat_received")

    /** World load/disconnect: [WorldLoadEvent.world] being null indicates a disconnect. */
    val WORLD_LOAD = EventDefinition<WorldLoadEvent>("world_load")

    /**
     * HUD render callback: dispatched once per frame while a world is loaded, before the HUD is
     * extracted.
     */
    val HUD_RENDER = EventDefinition<HudRenderEvent>("hud_render")
}
