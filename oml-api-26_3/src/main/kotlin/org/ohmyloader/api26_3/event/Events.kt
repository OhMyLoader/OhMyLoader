package org.ohmyloader.api26_3.event

import org.ohmyloader.api.event.EventDefinition

/**
 * The 26.3 event registry: mods subscribe through [EventDefinition.register], the version adapter
 * dispatches through [EventDefinition.fire]. Payload fields are the game's own types — this module
 * exists so a mod can hold a `ServerPlayer` rather than a translation of one.
 */
object Events {
    /** Dispatched once per logical client tick. */
    val CLIENT_TICK = EventDefinition<ClientTickEvent>("client_tick")

    /** Dispatched once per logical server tick, on the server thread (dedicated or integrated). */
    val SERVER_TICK = EventDefinition<ServerTickEvent>("server_tick")

    /**
     * Frame-rate limit query (mutable): every frame; handlers may override
     * [FrameRateLimitEvent.limit].
     */
    val FRAME_RATE_LIMIT = EventDefinition<FrameRateLimitEvent>("frame_rate_limit")

    /**
     * GUI open (cancellable): right before a GUI is shown;
     * [GuiOpenEvent.screen] being null means the current GUI is being closed.
     */
    val GUI_OPEN = EventDefinition<GuiOpenEvent>("gui_open")

    /** Player sending a chat message (cancellable). */
    val CHAT_SENT = EventDefinition<ChatSentEvent>("chat_sent")

    /**
     * Chat message received (cancellable; cancelling prevents it from being displayed). Dispatches on the
     * client's game thread — the packet handlers re-dispatch there before this fires.
     */
    val CHAT_RECEIVED = EventDefinition<ChatReceivedEvent>("chat_received")

    /** World load/disconnect: [WorldLoadEvent.world] being null indicates a disconnect. */
    val WORLD_LOAD = EventDefinition<WorldLoadEvent>("world_load")

    /**
     * HUD render callback: once per frame while a world is loaded, before the HUD is extracted.
     */
    val HUD_RENDER = EventDefinition<HudRenderEvent>("hud_render")

    /** Server ready to run its first tick. */
    val SERVER_STARTED = EventDefinition<ServerStartedEvent>("server_started")

    /** Server shutting down. */
    val SERVER_STOPPING = EventDefinition<ServerStoppingEvent>("server_stopping")

    /** A player finished entering the world. */
    val PLAYER_JOINED = EventDefinition<PlayerJoinedEvent>("player_joined")

    /** A player is leaving the world. */
    val PLAYER_LEFT = EventDefinition<PlayerLeftEvent>("player_left")

    /** A player has finished respawning, on the instance the server made for this life. */
    val PLAYER_RESPAWNED = EventDefinition<PlayerRespawnedEvent>("player_respawned")

    /** A level starts one tick — one dispatch per loaded dimension on a server, one for the client's own. */
    val LEVEL_TICK = EventDefinition<LevelTickEvent>("level_tick")

    /** A chunk became a ticking chunk of one server level (observed; the game already promoted it). */
    val CHUNK_STARTED_TICKING = EventDefinition<ChunkStartedTickingEvent>("chunk_started_ticking")

    /** A chunk is leaving one server level, while it is still readable. */
    val CHUNK_UNLOADED = EventDefinition<ChunkUnloadedEvent>("chunk_unloaded")

    /** A player is about to destroy a block (cancellable). */
    val BLOCK_BREAK = EventDefinition<BlockBreakEvent>("block_break")

    /** A player's attack landed on an entity (cancellable). */
    val ENTITY_ATTACK = EventDefinition<EntityAttackEvent>("entity_attack")

    /** A player right-clicked an entity (cancellable). */
    val ENTITY_INTERACT = EventDefinition<EntityInteractEvent>("entity_interact")

    /** A player right-clicked a block with an item or an empty hand (cancellable). */
    val BLOCK_USED = EventDefinition<BlockUsedEvent>("block_used")

    /** A player used the held item on nothing (cancellable). */
    val ITEM_USED = EventDefinition<ItemUsedEvent>("item_used")

    /** Damage is about to be applied to a living entity (cancellable, [LivingHurtEvent.amount] is writable). */
    val LIVING_HURT = EventDefinition<LivingHurtEvent>("living_hurt")

    /** A player is about to open a menu (cancellable). */
    val MENU_OPEN = EventDefinition<MenuOpenEvent>("menu_open")

    /** A player closed a menu (opening another one closes the first). */
    val MENU_CLOSED = EventDefinition<MenuClosedEvent>("menu_closed")

    /** A living entity's death routine is starting (cancellable). */
    val LIVING_DIED = EventDefinition<LivingDiedEvent>("living_died")
}
