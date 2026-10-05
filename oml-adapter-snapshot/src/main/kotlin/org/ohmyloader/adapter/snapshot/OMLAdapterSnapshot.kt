package org.ohmyloader.adapter.snapshot

import org.ohmyloader.core.spi.IAdapter
import org.ohmyloader.core.transformer.IClassTransformer

/**
 * 26.4-snapshot-2 adapter entry point, exposed to the core through META-INF/services for ServiceLoader.
 * 26.x jars ship readable class and member names (Mojang stopped publishing the official mappings
 * with this line and does not need to), so every anchor is read from the live jar and asserted by
 * [HookShapeTest]. 26.4-snapshot-2 shape facts that change what a rule must do: `setScreen` is
 * `setScreenAndShow` (renamed in 26.3; it also forces a frame), `setLevel` takes no `Reason`
 * parameter, `disconnect()` is `disconnectFromWorld(Component)`, and the frame-rate limit lives
 * on `com.mojang.blaze3d.platform.FramerateLimitTracker`, not on `Minecraft`.
 */
class OMLAdapterSnapshot : IAdapter {
    override val versionId = "26.4-snapshot-2"

    override val mainClass = "net.minecraft.client.main.Main"

    /**
     * 26.4-snapshot-2 ships one unified jar: `net.minecraft.server.Main`, `MinecraftServer` and
     * `DedicatedServer` all live inside the client jar, so the client jar alone carries the dedicated
     * server and the installer has no separate server jar to look for.
     */
    override val serverMainClass = "net.minecraft.server.Main"

    override fun createTransformers(): List<IClassTransformer> = listOf(
        MinecraftHookTransformer(),
        // Network compression and mod payloads are shared bootstrap code (both sides run the same
        // unified jar), so the same transformers register on the client and the server path.
        NetworkCompressionTransformer(),
        NetworkPayloadTransformer(),
    )

    override fun createServerTransformers(): List<IClassTransformer> = listOf(
        ServerHookTransformer(),
        NetworkCompressionTransformer(),
        NetworkPayloadTransformer(),
    )

    override fun createContentRegistry() = MinecraftContentRegistry

    override fun createNetworkSender() = OMLNetworkBridge
}