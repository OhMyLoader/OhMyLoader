package org.ohmyloader.adapter

import com.mojang.authlib.services.MinecraftServicesDiscoveryService
import net.minecraft.client.Minecraft
import net.minecraft.util.Util
import org.ohmyloader.api.OmlLog
import org.ohmyloader.core.RebootMark
import org.ohmyloader.core.spi.IAdapter
import org.ohmyloader.core.transformer.IClassTransformer
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/**
 * The adapter entry point, exposed to the core through META-INF/services for ServiceLoader.
 * 26.x jars ship readable class and member names (Mojang stopped publishing the official mappings
 * with this line and does not need to), so every anchor is read from the live jar and asserted by
 * [HookShapeTest]. Shape facts of this line that change what a rule must do: `setScreen` became
 * `setScreenAndShow` (it now also forces a frame), `setLevel` lost its `Reason` parameter,
 * `disconnect()` became `disconnectFromWorld(Component)`, the frame-rate limit moved off
 * `Minecraft` onto `com.mojang.blaze3d.platform.FramerateLimitTracker`, the Hud extraction pass
 * takes only the extractor, the creative-tab button clamps through `Math.clamp(JII)I`, the registry
 * bootstrap runs on `BuiltinRegistryBuilder`, and the command dispatcher is handed over through
 * `ParseBox.fromFullTree`.
 */
class OMLAdapter : IAdapter {
    override val versionId = "26.4-snapshot-8"

    override val mainClass = "net.minecraft.client.main.Main"

    /**
     * The game ships one unified jar: `net.minecraft.server.Main`, `MinecraftServer` and
     * `DedicatedServer` all live inside the client jar, so the client jar alone carries the dedicated
     * server and the installer has no separate server jar to look for.
     */
    override val serverMainClass = "net.minecraft.server.Main"

    override fun createTransformers(): List<IClassTransformer> = listOf(
        MinecraftHookTransformer(idPrefix = "oml"),
        // RenderPearl's async shader compile must not run on the game's shared ForkJoin worker:
        // its FFM/stack-guard path crashes Zulu 27's second in-process session.
        ShaderCompileTransformer(),
        // Thread objects held by JNI globals must not carry the disposable loader through their
        // context loader, target or class; replace game/Netty worker factories at creation.
        ThreadHygieneTransformer(),
        // Register the two executors without public accessors while their owner class initializes;
        // teardown then calls them directly instead of creating a reflective field accessor.
        SessionTeardownTransformer(),
        // The creative-screen pagination is client behavior; the screen's shape lives with the
        // adapter, so the rules do too.
        CreativeScreenPaginationTransformer(),
        // The reboot bill's game-side probes: entry/exit marks on bootstrap, device, close and
        // reload sites. Measurement only, nothing reads the marks to decide anything.
        RebootTimingTransformer(),
        // The Mods button on the title and pause menus: the player-facing way into OMLModsScreen and
        // the soft reboot, beside `/oml reload`.
        ModsMenuTransformer(),
        // Network compression and mod payloads are shared bootstrap code (both sides run the same
        // unified jar), so the same transformers register on the client and the server path.
        NetworkCompressionTransformer(idPrefix = "oml"),
        NetworkPayloadTransformer(idPrefix = "oml"),
    )

    override fun createServerTransformers(): List<IClassTransformer> = listOf(
        ServerHookTransformer(idPrefix = "oml"),
        ThreadHygieneTransformer(),
        SessionTeardownTransformer(),
        NetworkCompressionTransformer(idPrefix = "oml"),
        NetworkPayloadTransformer(idPrefix = "oml"),
    )

    override fun createContentRegistry(): MinecraftContentRegistry {
        MinecraftContentRegistry.registryAccessor = MinecraftRegistryAccessor
        return MinecraftContentRegistry
    }

    override fun createNetworkSender() = OMLNetworkBridge

    override fun onGameReady() {
        recordRunningServer()
        // The mods-directory baseline and the `/oml reload` node. Both are plain data; the client
        // side is where the directory is meaningful, and the command is declared on both so a
        // dedicated server answers rather than "unknown command".
        runCatching { ModsMenuHook.install() }
            .onFailure { OmlLog.warn("Mods", "installing the mods menu failed: $it") }
    }

    override fun launch(args: Array<String>, serverSide: Boolean) {
        RebootMark.mark("game.main.begin")
        if (serverSide) {
            net.minecraft.server.Main.main(args)
        } else {
            // The transition is painted at the GUI tail from the session's first frame; the painter
            // is a plain static call now, so nothing has to be installed first.
            net.minecraft.client.main.Main.main(args)
        }
    }

    override fun disarmClientShutdownWatchdog() {
        ThreadHygieneBridge.disarmWatchdog()
    }

    override fun shutdownSessionResources(clientSide: Boolean) {
        runCatching {
            Util.nonCriticalIoPool().shutdownAndAwait(3, TimeUnit.SECONDS)
            RebootMark.mark("teardown.download")
        }.onFailure { OmlLog.warn("Teardown", "download executor shutdown failed") }

        runCatching {
            val service = MinecraftServicesDiscoveryService::class.java
            if (service.classLoader !== javaClass.classLoader) return@runCatching
            val executor = MinecraftServicesDiscoveryService.DISCOVERY_EXECUTOR
            executor.shutdownNow()
            // The worker is a daemon blocked in the discovery HTTP call; interrupting it does not
            // end that call, so waiting for termination is waiting for a network timeout (2s in the
            // measured bill) and buys nothing. ThreadHygiene already keeps the retired loader
            // unreachable from it, which is what the canary checks; a short grace is all a healthy
            // worker needs.
            executor.awaitTermination(50, TimeUnit.MILLISECONDS)
            RebootMark.mark("teardown.auth")
        }.onFailure { OmlLog.warn("Teardown", "auth discovery executor shutdown failed") }

        runCatching {
            ThreadHygieneBridge.shutdownEventLoopGroup()
            RebootMark.mark("teardown.eventloop")
        }.onFailure { OmlLog.warn("Teardown", "local event loop group shutdown failed") }

        runCatching {
            ShaderCompileExecutor.shutdown()
            RebootMark.mark("teardown.shaderexec")
        }.onFailure { OmlLog.warn("Teardown", "shader executor shutdown failed") }

        if (clientSide) {
            runCatching {
                Minecraft.getInstance().getSoundManager().destroy()
                RebootMark.mark("teardown.sound.destroy")
            }.onFailure { OmlLog.warn("Teardown", "sound engine shutdown failed") }
            runCatching {
                ThreadHygieneBridge.shutdownSoundExecutor()
                RebootMark.mark("teardown.sound.executor")
            }.onFailure { OmlLog.warn("Teardown", "sound executor shutdown failed") }
        }
    }

    override fun telemetryExecutor(clientSide: Boolean): ExecutorService? =
        if (clientSide) ThreadHygieneBridge.takeTelemetryExecutor() else null

    override fun jfrPeriodicTasks(): List<Runnable> = ThreadHygieneBridge.takeJfrTasks()

    override fun jfrCustomEvents(): List<Class<*>> = ThreadHygieneBridge.takeJfrCustomEvents()

    override fun openAlEventTypes(): IntArray? = ThreadHygieneBridge.takeOpenAlEventTypes()
}
