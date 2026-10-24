package org.ohmyloader.adapter.snapshot

import org.ohmyloader.adapter.common.*
import org.ohmyloader.api.inject.DslValue
import org.ohmyloader.core.spi.IAdapter
import org.ohmyloader.core.transformer.IClassTransformer

/** The anchor shapes the 26.4-snapshot-5 jar has — the facts the shared hook declarations branch on. */
internal val snapshotHookShapes = HookShapes(
    hudExtractDesc = "(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V",
    onHudRenderDesc = "(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V",
    hudRenderArgs = listOf(DslValue.Arg(0)),
    tabButtonDesc = "(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/world/item/CreativeModeTab;)V",
    tabClampOwner = "java/lang/Math",
    tabClampDesc = "(JII)I",
    freezeAnchorClass = "net/minecraft/core/registries/BuiltinRegistryBuilder",
    freezeCallOwner = "net/minecraft/core/registries/BuiltinRegistryBuilder",
    freezeHandlerOwner = "org/ohmyloader/adapter/snapshot/SnapshotEventBridge",
    freezeHandlerMethod = "onRegistryFreeze",
)

/**
 * 26.4-snapshot-5 adapter entry point, exposed to the core through META-INF/services for ServiceLoader.
 * 26.x jars ship readable class and member names (Mojang stopped publishing the official mappings
 * with this line and does not need to), so every anchor is read from the live jar and asserted by
 * [HookShapeTest]. 26.4-snapshot-5 shape facts that change what a rule must do: the registry
 * bootstrap moved onto `BuiltinRegistryBuilder`, whose `freeze()` is a private **instance** method
 * (the redirect handler is [SnapshotEventBridge.onRegistryFreeze], receiving the builder); the Hud
 * extraction pass dropped its `DeltaTracker` parameter; the creative-tab button pass dropped its
 * two int coordinates and clamps through `Math.clamp(JII)I` instead of `Mth.clamp(III)I`.
 */
class OMLAdapterSnapshot : IAdapter {
    override val versionId = "26.4-snapshot-5"

    override val mainClass = "net.minecraft.client.main.Main"

    /**
     * 26.4-snapshot-5 ships one unified jar: `net.minecraft.server.Main`, `MinecraftServer` and
     * `DedicatedServer` all live inside the client jar, so the client jar alone carries the dedicated
     * server and the installer has no separate server jar to look for.
     */
    override val serverMainClass = "net.minecraft.server.Main"

    override fun createTransformers(): List<IClassTransformer> = listOf(
        MinecraftHookTransformer(idPrefix = "snapshot", shapes = snapshotHookShapes),
        // Network compression and mod payloads are shared bootstrap code (both sides run the same
        // unified jar), so the same transformers register on the client and the server path.
        NetworkCompressionTransformer(idPrefix = "snapshot"),
        NetworkPayloadTransformer(idPrefix = "snapshot"),
    )

    override fun createServerTransformers(): List<IClassTransformer> = listOf(
        ServerHookTransformer(idPrefix = "snapshot", shapes = snapshotHookShapes),
        NetworkCompressionTransformer(idPrefix = "snapshot"),
        NetworkPayloadTransformer(idPrefix = "snapshot"),
    )

    override fun createContentRegistry() = MinecraftContentRegistry

    override fun createNetworkSender() = OMLNetworkBridge

    override fun onGameReady() {
        recordRunningServer()
    }
}
