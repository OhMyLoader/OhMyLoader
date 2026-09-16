package org.ohmyloader.adapter.v26_3

import org.ohmyloader.core.spi.IAdapter
import org.ohmyloader.core.transformer.IClassTransformer

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
        MinecraftHookTransformer(),
        // Network compression is shared bootstrap code (both sides run the same unified jar),
        // so the same transformer registers on the client and the server path.
        NetworkCompressionTransformer(),
    )

    override fun createServerTransformers(): List<IClassTransformer> = listOf(
        ServerHookTransformer(),
        NetworkCompressionTransformer(),
    )

    override fun createContentRegistry() = MinecraftContentRegistry
}