package org.ohmyloader.adapter.v26_3

import org.ohmyloader.adapter.common.*
import org.ohmyloader.api.inject.DslValue
import org.ohmyloader.core.spi.IAdapter
import org.ohmyloader.core.transformer.IClassTransformer

/** The anchor shapes the 26.3 jar has — the facts the shared hook declarations branch on. */
internal val v26_3HookShapes = HookShapes(
    hudExtractDesc = "(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V",
    onHudRenderDesc = "(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V",
    hudRenderArgs = listOf(DslValue.Arg(0), DslValue.Arg(1)),
    tabButtonDesc = "(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IILnet/minecraft/world/item/CreativeModeTab;)V",
    tabClampOwner = "net/minecraft/util/Mth",
    tabClampDesc = "(III)I",
    freezeAnchorClass = "net/minecraft/core/registries/BuiltInRegistries",
    freezeCallOwner = "net/minecraft/core/registries/BuiltInRegistries",
    freezeHandlerOwner = "org/ohmyloader/adapter/common/EventBridge",
    freezeHandlerMethod = "onRegistryFreeze",
)

/**
 * 26.3 adapter entry point, exposed to the core through META-INF/services for ServiceLoader.
 * 26.x jars ship readable class and member names (Mojang stopped publishing the official mappings
 * with this line and does not need to), so every anchor is read from the live jar and asserted by
 * [HookShapeTest]. 26.3 shape facts that change what a rule must do: `setScreen` became
 * `setScreenAndShow` (it now also forces a frame), `setLevel` lost its `Reason` parameter,
 * `disconnect()` became `disconnectFromWorld(Component)`, and the frame-rate limit moved off
 * `Minecraft` onto `com.mojang.blaze3d.platform.FramerateLimitTracker`.
 */
class OMLAdapterV26_3 : IAdapter {
    override val versionId = "26.3"

    override val mainClass = "net.minecraft.client.main.Main"

    /**
     * 26.3 ships one unified jar: `net.minecraft.server.Main`, `MinecraftServer` and
     * `DedicatedServer` all live inside the client jar, so the client jar alone carries the dedicated
     * server and the installer has no separate server jar to look for.
     */
    override val serverMainClass = "net.minecraft.server.Main"

    override fun createTransformers(): List<IClassTransformer> = listOf(
        MinecraftHookTransformer(idPrefix = "v26_3", shapes = v26_3HookShapes),
        // Network compression and mod payloads are shared bootstrap code (both sides run the same
        // unified jar), so the same transformers register on the client and the server path.
        NetworkCompressionTransformer(idPrefix = "v26_3"),
        NetworkPayloadTransformer(idPrefix = "v26_3"),
    )

    override fun createServerTransformers(): List<IClassTransformer> = listOf(
        ServerHookTransformer(idPrefix = "v26_3", shapes = v26_3HookShapes),
        NetworkCompressionTransformer(idPrefix = "v26_3"),
        NetworkPayloadTransformer(idPrefix = "v26_3"),
    )

    override fun createContentRegistry() = MinecraftContentRegistry

    override fun createNetworkSender() = OMLNetworkBridge

    override fun onGameReady() {
        recordRunningServer()
    }
}
