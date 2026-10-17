package org.ohmyloader.adapter.common

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.level.ServerPlayerGameMode
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.phys.BlockHitResult
import org.ohmyloader.api.OmlLog
import org.ohmyloader.api.mixin.CallbackInfoReturnable
import org.ohmyloader.api26_3.EventDispatch
import org.ohmyloader.core.OMLCore
import org.ohmyloader.core.adapter.Refl
import java.lang.reflect.Field

/**
 * 26.3 event bridge: injected hooks call into here, which reads the game's own objects off the call and hands
 * them to the dispatch layer. Every method is invoked from injected bytecode via INVOKESTATIC, so they must
 * stay `@JvmStatic` and keep their FQN and signatures unchanged.
 *
 * Firing lives in [EventDispatch]; this class keeps the `@JvmStatic` surface plus what only the injection side
 * knows: which game method each hook sits in, how a cancel lands in that method's own return value, and the
 * registry freeze point content has to materialize before.
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
     * The game holds `ServerPlayerGameMode.player` protected and the injection DSL has no field operand, so
     * the break handler reads it off the instance it was handed. The lookup is cached per (type, name) and
     * the read allocates nothing; no map is involved, which matters because the player and its game mode
     * reference each other strongly — a map keyed by either one keeps the other alive for the life of the
     * process, and a weak map does not help, since reachability runs through the value.
     */
    private val gameModePlayer: Field? =
        Refl.fieldOrNull(ServerPlayerGameMode::class.java, "player", ServerPlayerGameMode::class.java).also {
            if (it == null) {
                OmlLog.warn(
                    "Events",
                    "ServerPlayerGameMode.player is gone from this game version — BLOCK_BREAK will not fire",
                )
            }
        }

    /** The player a game mode belongs to; null when the version renamed the field away. */
    private fun ownerOf(game: ServerPlayerGameMode): ServerPlayer? = gameModePlayer?.get(game) as? ServerPlayer

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
        extractor: GuiGraphicsExtractor,
        @Suppress("UNUSED_PARAMETER") delta: net.minecraft.client.DeltaTracker,
    ) = EventDispatch.onHudRender(extractor)

    /**
     * [MinecraftServer.tickServer head] Carries the ticking server so the core can tell one server
     * instance from the next (see [EventDispatch.onServerTick]).
     */
    @JvmStatic
    fun onServerTick(server: MinecraftServer) = EventDispatch.onServerTick(server)

    /** [MinecraftServer.stopServer head] */
    @JvmStatic
    fun onServerStopping(server: MinecraftServer) = EventDispatch.onServerStopping(server)

    /**
     * [ServerLevel.tick head] / [ClientLevel.tick head] One dispatch per level that ticks, carrying that
     * level: a server's loaded dimensions each report once per server tick, and the client's rendered level
     * reports once per client tick — so in singleplayer the same dimension arrives twice, as the two
     * level objects the two sides actually hold.
     */
    @JvmStatic
    fun onLevelTick(level: Level) = EventDispatch.onLevelTick(level)

    /**
     * [ServerLevel.startTickingChunk head] The game promoted this chunk to ticking in [level]: the chunk
     * object, its position and its level are one and the same object a mod gets from `levelById`.
     */
    @JvmStatic
    fun onChunkStartedTicking(level: ServerLevel, chunk: LevelChunk) =
        EventDispatch.onChunkStartedTicking(level, chunk)

    /** [ServerLevel.unload head] The chunk is being dropped from [level] and is still readable here. */
    @JvmStatic
    fun onChunkUnloaded(level: ServerLevel, chunk: LevelChunk) = EventDispatch.onChunkUnloaded(level, chunk)

    /** [PlayerList.placeNewPlayer tail] The player is fully in the world at this point. */
    @JvmStatic
    fun onPlayerJoined(player: ServerPlayer) = EventDispatch.onPlayerJoined(player)

    /**
     * [ServerPlayer.permissions tail] The set the game is about to use for this player, handed to OML's
     * override table: identity when the player has no overrides, a wrapper of the game's own set otherwise.
     */
    @JvmStatic
    fun onPlayerPermissions(
        permissions: net.minecraft.server.permissions.PermissionSet,
        player: ServerPlayer,
    ): net.minecraft.server.permissions.PermissionSet =
        org.ohmyloader.api26_3.OMLPermissionGrants.apply(player, permissions)

    /** [PlayerList.remove head] Still readable: the game has not torn the player down yet. */
    @JvmStatic
    fun onPlayerLeft(player: ServerPlayer) {
        org.ohmyloader.api26_3.OMLPermissionGrants.forget(player.uuid)
        EventDispatch.onPlayerLeft(player)
    }

    /**
     * [PlayerList.respawn tail] Receives the player the game is about to return — the instance built for
     * this new life, not the argument it replaced — and hands back the very same object: the transform is
     * how the hook reaches a value that only exists at the return, not a way to change it.
     */
    @JvmStatic
    fun onPlayerRespawned(player: ServerPlayer): ServerPlayer {
        EventDispatch.onPlayerRespawned(player)
        return player
    }

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
        val player = ownerOf(game) ?: return
        if (EventDispatch.onBlockBreak(player, pos, player.level().getBlockState(pos))) cir.setReturnValue(false)
    }

    /**
     * [Player.attack head] Cancelling skips the whole swing, animation and damage.
     *
     * The same click runs this method on the client's player *and* on the server's in singleplayer, so
     * only the server-side call is dispatched: one event per gameplay action.
     */
    @JvmStatic
    fun onEntityAttack(player: Player, target: Entity): Boolean {
        // The same click runs this on the client's player and, in singleplayer, on the server's: only
        // the server-side call is dispatched, so a mod sees one event per gameplay action.
        val serverPlayer = player as? ServerPlayer ?: return false
        return !serverPlayer.level().isClientSide && EventDispatch.onEntityAttack(serverPlayer, target)
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
        val serverPlayer = player as? ServerPlayer ?: return
        if (serverPlayer.level().isClientSide) return
        if (EventDispatch.onEntityInteract(serverPlayer, target, hand)) {
            cir.setReturnValue(InteractionResult.PASS)
        }
    }

    /**
     * [ServerPlayerGameMode.useItemOn head] The server-side half of a right-click on a block: the client
     * predicts its own `MultiPlayerGameMode` and sends the click, so this runs once per click on the
     * server thread in singleplayer and on a dedicated server alike. Cancelling returns `FAIL`, the game's
     * own "the click did nothing" result — the client's prediction is rolled back by the packet the game
     * already schedules for a failed click.
     */
    @JvmStatic
    fun onBlockUsed(
        @Suppress("UNUSED_PARAMETER") game: ServerPlayerGameMode,
        player: ServerPlayer,
        stack: ItemStack,
        hand: InteractionHand,
        hit: BlockHitResult,
        @Suppress("UNUSED_PARAMETER") id: Int,
        cir: CallbackInfoReturnable<InteractionResult>,
    ) {
        val canceled = EventDispatch.onBlockUsed(
            player, hit, player.level().getBlockState(hit.blockPos), stack, hand,
        )
        if (canceled) cir.setReturnValue(InteractionResult.FAIL)
    }

    /** [ServerPlayerGameMode.useItem head] The right-click that hit no block and no entity. */
    @JvmStatic
    fun onItemUsed(
        @Suppress("UNUSED_PARAMETER") game: ServerPlayerGameMode,
        player: ServerPlayer,
        stack: ItemStack,
        hand: InteractionHand,
        @Suppress("UNUSED_PARAMETER") id: Int,
        cir: CallbackInfoReturnable<InteractionResult>,
    ) {
        val canceled = EventDispatch.onItemUsed(player, stack, hand)
        if (canceled) cir.setReturnValue(InteractionResult.FAIL)
    }

    /**
     * A [Events.LIVING_HURT] handler's rewritten amount, for the damage applications this thread is inside.
     * The event fires at HEAD while the game reads its damage parameter only later in the same call, so the
     * number travels between the two hooks here. It is a **stack**, not one slot: a damage application
     * nested inside another (thorns retaliation) pushes its own entry and pops it at its own read, in order.
     * An entry is consumed only by the application that pushed it — the pair of identities has to match —
     * so it can never rewrite an unrelated damage, and whatever its own call never read is released at that
     * call's returns ([onHurtReturned]).
     */
    private val rewrittenHurt = ThreadLocal<ArrayDeque<RewrittenHurt>>()

    private class RewrittenHurt(val entity: LivingEntity, val source: DamageSource, val amount: Float)

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
        val event = EventDispatch.onLivingHurt(entity, amount, source)
        if (event.canceled) {
            cir.setReturnValue(false)
            return
        }
        if (event.amount != amount) {
            val pending = rewrittenHurt.get() ?: ArrayDeque<RewrittenHurt>().also { rewrittenHurt.set(it) }
            pending.addLast(RewrittenHurt(entity, source, event.amount))
        }
    }

    /**
     * [LivingEntity.hurtServer, first read of the damage parameter] Hands the game the amount a
     * [Events.LIVING_HURT] handler wrote. The engine stores the returned value into that parameter slot,
     * so every later read in the same call — shield reduction, damage cooldown, the damage itself — sees
     * the handler's number rather than the one the attacker computed.
     */
    @JvmStatic
    fun onHurtAmount(amount: Float, entity: LivingEntity, source: DamageSource): Float {
        val pending = rewrittenHurt.get() ?: return amount
        val rewritten = pending.lastOrNull() ?: return amount
        if (rewritten.entity !== entity || rewritten.source !== source) return amount
        pending.removeLast()
        return rewritten.amount
    }

    /**
     * [LivingEntity.hurtServer, every return] Releases what the call pushed but its first parameter read
     * never consumed — the invulnerability, dying and fire-immunity guards return before that read. Nothing
     * legit is pending here: a nested hurt can only start once the outer call has already read its parameter.
     */
    @JvmStatic
    fun onHurtReturned() {
        rewrittenHurt.get()?.clear()
    }

    /**
     * [LivingEntity.die head] Cancellable: cancelling stops the death routine, leaving the entity at zero
     * health — the same shape as the game's own totem path.
     */
    @JvmStatic
    fun onLivingDied(entity: LivingEntity, source: DamageSource): Boolean =
        EventDispatch.onLivingDied(entity, source)

    /** [Minecraft.setScreenAndShow head] */
    @JvmStatic
    fun onGuiOpen(screen: Screen?): Boolean = EventDispatch.onGuiOpen(screen)

    /** [Minecraft.setLevel head] */
    @JvmStatic
    fun onWorldLoad(world: ClientLevel?) = EventDispatch.onWorldLoad(world)

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

    /** [ClientPacketListener.handleSystemChat / handleDisguisedChat / handlePlayerChat heads] Inbound chat, with the text resolved by [EventDispatch]. */
    @JvmStatic
    fun onChatReceived(packet: Packet<ClientGamePacketListener>): Boolean =
        EventDispatch.onChatReceived(packet)
}
