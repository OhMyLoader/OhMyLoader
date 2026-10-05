package org.ohmyloader.adapter.snapshot

import org.ohmyloader.core.OMLCore
import org.ohmyloader.core.adapter.EventDispatch

/**
 * 26.4-snapshot-2 event bridge: injected hooks call into here, which converts version-specific objects into
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
    ) = EventDispatch.onHudRender(extractor)

    @JvmStatic
    fun onServerTick() = EventDispatch.onServerTick()

    @JvmStatic
    fun onFrameLimit(limit: Int): Int = EventDispatch.onFrameLimit(limit)

    @JvmStatic
    fun onGuiOpen(screen: Any?): Boolean = EventDispatch.onGuiOpen(screen)

    @JvmStatic
    fun onChatSent(message: String): Boolean = EventDispatch.onChatSent(message)

    @JvmStatic
    fun onWorldLoad(world: Any?) = EventDispatch.onWorldLoad(world)

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
     * freeze. 26.4-snapshot-2's bootStrap order is `createContents()` -> `freeze()` -> `validate(REGISTRY)`, so
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
     * delegation carries only the 26.4-snapshot-2 accessor facts. `ClientboundSystemChatPacket.content()` /
     * `ClientboundDisguisedChatPacket.message()` (Component → `getString()`), and
     * `ClientboundPlayerChatPacket.unsignedContent()` — an `Optional<Component>` present only when
     * the sender's message was modified, with the fallback to `body().content()` (a plain String).
     */
    @JvmStatic
    fun onChatReceived(packet: Any): Boolean =
        EventDispatch.onChatReceived(packet, listOf("content", "message", "unsignedContent"), "body" to "content")
}
