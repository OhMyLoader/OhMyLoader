package org.ohmyloader.adapter.common

import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.level.ServerPlayerGameMode
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import org.ohmyloader.api.mixin.CallbackInfoReturnable
import org.ohmyloader.core.OMLCore
import org.ohmyloader.core.adapter.EventDispatch
import java.util.WeakHashMap

/**
 * 26.3 event bridge: injected hooks call into here, which converts version-specific objects into
 * unified events and dispatches them. Every method is invoked from injected bytecode via
 * INVOKESTATIC, so they must stay `@JvmStatic` and keep their FQN and signatures unchanged.
 *
 * Events whose semantics are identical across versions are lowered to the core's [EventDispatch];
 * this class keeps the thin `@JvmStatic` delegations plus the version-specific parts — the chat
 * accessor names and the registry freeze point.
 */
object EventBridge {
    /** [Commands handover] Registers the mod commands into a freshly built dispatcher (see the hook). */
    @JvmStatic
    fun onCommandsReady(commands: net.minecraft.commands.Commands) = OMLCommandBridge.register(commands)

    /** Return-transform half of [onCommandsReady]: observes and passes the Commands instance through. */
    @JvmStatic
    fun onCommandsReadyReturn(commands: net.minecraft.commands.Commands): net.minecraft.commands.Commands {
        OMLCommandBridge.register(commands)
        return commands
    }


    private val contentRegistry = MinecraftContentRegistry

    /**
     * The game keeps `ServerPlayerGameMode.player` protected, which the API's typed projections cannot
     * read, so the association is recorded from the constructor argument at injection time. Both sides
     * of the entry are unreachable together once the player leaves, so the weak keys let the pair go.
     */
    private val gameModeOwners = WeakHashMap<ServerPlayerGameMode, ServerPlayer>()

    /** [ServerPlayerGameMode constructor head] */
    @JvmStatic
    fun onGameModeCreated(game: ServerPlayerGameMode, player: ServerPlayer) {
        gameModeOwners[game] = player
    }

    @JvmStatic
    fun onClientTick() {
        OMLKeyBindingsBridge.applyPending()
        EventDispatch.onClientTick()
    }

    /**
     * [keyPress head] Dispatches declared key bindings. Vanilla's own gating is re-applied here
     * rather than in bytecode: only press actions, and only when no screen is open — the same
     * no-screen branch the game routes vanilla key mappings through. A null Minecraft instance
     * (headless tests) dispatches: the game cannot be running, so the screen gate is vacuous.
     */
    @JvmStatic
    fun onKeyPress(window: Long, action: Int, event: net.minecraft.client.input.KeyEvent) {
        if (action != com.mojang.blaze3d.platform.InputConstants.PRESS) return
        // getInstance carries a non-null contract and throws before the game is up; a null
        // instance (headless) just means the screen gate is vacuous.
        val mc = runCatching { net.minecraft.client.Minecraft.getInstance() }.getOrNull()
        if (mc != null && mc.gui.screen() != null) return
        OMLKeyBindingsBridge.dispatchPress(event)
    }

    /** [Hud.extractRenderState head] Fires the HUD render callback with the frame's draw surface. */
    @JvmStatic
    fun onHudRender(
        extractor: net.minecraft.client.gui.GuiGraphicsExtractor,
        @Suppress("UNUSED_PARAMETER") delta: net.minecraft.client.DeltaTracker,
    ) = EventDispatch.onHudRender(GameProjections.guiGraphics(extractor))

    /**
     * [MinecraftServer.tickServer head] Carries the ticking server so the core can tell one server
     * instance from the next (see [EventDispatch.onServerTick]).
     */
    @JvmStatic
    fun onServerTick(server: MinecraftServer) = EventDispatch.onServerTick(OMLGameBridge.handle(server))

    /** [MinecraftServer.stopServer head] */
    @JvmStatic
    fun onServerStopping(server: MinecraftServer) = EventDispatch.onServerStopping(OMLGameBridge.handle(server))

    /** [PlayerList.placeNewPlayer tail] The player is fully in the world at this point. */
    @JvmStatic
    fun onPlayerJoined(player: ServerPlayer) = EventDispatch.onPlayerJoined(PlayerWrap(player))

    /** [PlayerList.remove head] Still readable: the game has not torn the player down yet. */
    @JvmStatic
    fun onPlayerLeft(player: ServerPlayer) = EventDispatch.onPlayerLeft(PlayerWrap(player))

    /**
     * [ServerPlayerGameMode.destroyBlock head] Cancelling returns false from the break, which is the
     * signal vanilla's own `destroyAndAck` uses to resend the block to the client — so the player sees
     * the block snap back without the mod sending anything.
     */
    @JvmStatic
    fun onBlockBreak(
        game: ServerPlayerGameMode,
        pos: BlockPos,
        @Suppress("UNUSED_PARAMETER") id: Int,
        cir: CallbackInfoReturnable<Boolean>,
    ) {
        val player = gameModeOwners[game] ?: return
        val blockKey = GameProjections.blockKey(player.level().getBlockState(pos).block)
        if (EventDispatch.onBlockBreak(PlayerWrap(player), pos.toApi(), blockKey)) cir.setReturnValue(false)
    }

    /**
     * [Player.attack head] Cancelling skips the whole swing, animation and damage.
     *
     * The same click runs this method on the client's player *and* on the server's in singleplayer, so
     * only the server-side call is dispatched: one event per gameplay action.
     */
    @JvmStatic
    fun onEntityAttack(player: Player, target: Entity): Boolean {
        if (player.level().isClientSide) return false
        return EventDispatch.onEntityAttack(PlayerWrap(player), GameProjections.entity(target))
    }

    /**
     * [Player.interactOn head] Cancelling returns `InteractionResult.PASS`, which is what the game does
     * when the entity declines the interaction — the click falls through to the block behind it.
     *
     * Server-side dispatch only, as in [onEntityAttack]: a client-side call would report a target the
     * server never agreed to interact with.
     */
    @JvmStatic
    fun onEntityInteract(
        player: Player,
        target: Entity,
        hand: InteractionHand,
        @Suppress("UNUSED_PARAMETER") id: Int,
        cir: CallbackInfoReturnable<InteractionResult>,
    ) {
        if (player.level().isClientSide) return
        val handName = if (hand == InteractionHand.OFF_HAND) "off_hand" else "main_hand"
        if (EventDispatch.onEntityInteract(PlayerWrap(player), GameProjections.entity(target), handName)) {
            cir.setReturnValue(InteractionResult.PASS)
        }
    }

    /**
     * [LivingEntity.hurtServer head, on the base class] Hooking the declaration in `LivingEntity` is what
     * makes this fire exactly once per damage application: `ServerPlayer` and `Player` both override
     * `hurtServer`, but each override super-calls up the chain and the chain ends here. An override that
     * returns before reaching the base (a creative player, an invulnerable entity) fires nothing —
     * vanilla was not going to apply the damage either.
     */
    @JvmStatic
    fun onLivingHurt(
        entity: LivingEntity,
        source: DamageSource,
        amount: Float,
        @Suppress("UNUSED_PARAMETER") id: Int,
        cir: CallbackInfoReturnable<Boolean>,
    ) {
        val attacker = source.entity?.let { GameProjections.entity(it) }
        if (EventDispatch.onLivingHurt(GameProjections.living(entity), amount, source.type().msgId(), attacker)) {
            cir.setReturnValue(false)
        }
    }

    /**
     * [LivingEntity.die head] Cancellable: cancelling stops the death routine, leaving the entity at zero
     * health — the same shape as the game's own totem path.
     */
    @JvmStatic
    fun onLivingDied(entity: LivingEntity, source: DamageSource): Boolean =
        EventDispatch.onLivingDied(GameProjections.living(entity), source.type().msgId())

    /** [Minecraft.setScreenAndShow head] */
    @JvmStatic
    fun onGuiOpen(screen: Any?): Boolean = EventDispatch.onGuiOpen(screen?.let(GameProjections::screen))

    /** [Minecraft.setLevel head] */
    @JvmStatic
    fun onWorldLoad(world: Any?) = EventDispatch.onWorldLoad(world?.let { GameProjections.world(it as Level) })

    @JvmStatic
    fun onFrameLimit(limit: Int): Int = EventDispatch.onFrameLimit(limit)

    /** [ClientPacketListener.sendChat head] */
    @JvmStatic
    fun onChatSent(message: String): Boolean = EventDispatch.onChatSent(message)

    /** Disconnect (world = null; semantics aligned across versions). */
    @JvmStatic
    fun onWorldDisconnect() = EventDispatch.onWorldDisconnect()

    /**
     * [resource pack repo openAllSelected pre-hook] Invoked at the head of
     * PackRepository.openAllSelected(): both the Minecraft constructor's initial resource reload and
     * the F3+T / resource pack screen reload open the selected packs through it. The mod resource
     * pack is mounted into `sources` and selected here, so this very reload already contains the mod
     * block states, block models, item definitions and textures.
     */
    @JvmStatic
    fun onPackRepositoryReload(repo: Any?) {
        if (repo == null) return
        // Re-read the content packs as they exist on disk (crafting recipes ride vanilla's reload)
        // and drop the cached asset index, so edited textures are re-scanned in the same pass.
        OMLCore.reloadContentPacks()
        ModAssetInjector.invalidateIndex()
        ModAssetInjector.ensureInjected(repo)
    }

    /**
     * [registry freeze point] The `freeze()` call inside BuiltInRegistries.bootStrap is redirected
     * here: materialize the mod content first (the registry is still writable), then invoke the real
     * freeze. 26.3's bootStrap order is `createContents()` -> `freeze()` -> `validate(REGISTRY)`, so
     * content added here is also covered by vanilla's own validation pass.
     *
     * `freeze()` is private static in the game jar, hence the reflective call through the OML game
     * class loader (the game classes are not on the parent loader's classpath).
     */
    @JvmStatic
    fun onRegistryFreeze() {
        contentRegistry.materializeAll()
        val loader = OMLCore.gameClassLoader()
        Class.forName("net.minecraft.core.registries.BuiltInRegistries", true, loader)
            .getDeclaredMethod("freeze").apply { isAccessible = true }.invoke(null)
    }

    /**
     * Inbound chat: the extraction *mechanism* (candidate getters, Optional unwrapping,
     * Component→`getString()`, signed-body fallback) lives in the core's [EventDispatch]; this
     * delegation carries only the 26.3 accessor facts. `ClientboundSystemChatPacket.content()` /
     * `ClientboundDisguisedChatPacket.message()` (Component → `getString()`), and
     * `ClientboundPlayerChatPacket.unsignedContent()` — an `Optional<Component>` present only when
     * the sender's message was modified, with the fallback to `body().content()` (a plain String).
     */
    @JvmStatic
    fun onChatReceived(packet: Any): Boolean =
        EventDispatch.onChatReceived(packet, listOf("content", "message", "unsignedContent"), "body" to "content")
}
