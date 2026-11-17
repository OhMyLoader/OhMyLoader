package org.ohmyloader.adapter.v26_3

import com.mojang.authlib.services.MinecraftServicesDiscoveryService
import net.minecraft.client.Minecraft
import net.minecraft.util.Util
import org.ohmyloader.adapter.common.*
import org.ohmyloader.api.OmlLog
import org.ohmyloader.api.inject.DslValue
import org.ohmyloader.core.RebootMark
import org.ohmyloader.core.spi.IAdapter
import org.ohmyloader.core.transformer.IClassTransformer
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

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
    commandHandover = CommandHandover.COMMANDS_INSTANCE,
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
        // RenderPearl's async shader compile must not run on the game's shared ForkJoin worker:
        // its FFM/stack-guard path crashes Zulu 27's second in-process session. The rule is
        // frontend-specific, so it lives here rather than in the shared transformer.
        ShaderCompileTransformer(),
        // Thread objects held by JNI globals must not carry the disposable loader through their
        // context loader, target or class; replace game/Netty worker factories at creation.
        ThreadHygieneTransformer(),
        // Register the two executors without public accessors while their owner class initializes;
        // teardown then calls them directly instead of creating a reflective field accessor.
        SessionTeardownTransformer(),
        // The creative-screen pagination is 26.3 client behavior: the screen's own shape changes
        // between game versions, so the rules live with this adapter instead of the shared ones.
        CreativeScreenPaginationTransformer(),
        // The reboot bill's game-side probes: entry/exit marks on bootstrap, device, close and
        // reload sites. Measurement only, nothing reads the marks to decide anything.
        RebootTimingTransformer(),
        // The Mods button on the title and pause menus: the player-facing way into OMLModsScreen and
        // the soft reboot, beside `/oml reload`. Version-facing (both screens are 26.3 shapes).
        ModsMenuTransformer(),
        // Network compression and mod payloads are shared bootstrap code (both sides run the same
        // unified jar), so the same transformers register on the client and the server path.
        NetworkCompressionTransformer(idPrefix = "v26_3"),
        NetworkPayloadTransformer(idPrefix = "v26_3"),
    )

    override fun createServerTransformers(): List<IClassTransformer> = listOf(
        ServerHookTransformer(idPrefix = "v26_3", shapes = v26_3HookShapes),
        ThreadHygieneTransformer(),
        SessionTeardownTransformer(),
        NetworkCompressionTransformer(idPrefix = "v26_3"),
        NetworkPayloadTransformer(idPrefix = "v26_3"),
    )

    override fun createContentRegistry(): MinecraftContentRegistry {
        MinecraftContentRegistry.registryAccessor = V26_3RegistryAccessor
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
            // Before the first frame: the transition is painted at the GUI tail from the moment the
            // session starts, and a session that began without a painter would show nothing at all.
            RebootTransition.painter = RebootTransitionPainter::paint
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
