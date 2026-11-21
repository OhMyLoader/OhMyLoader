package org.ohmyloader.core.spi

import org.ohmyloader.core.transformer.IClassTransformer
import java.util.concurrent.ExecutorService

/**
 * Version-adapter SPI: each supported game version provides one implementation; core
 * discovers it via ServiceLoader, and beyond that core has zero dependency on any
 * specific version.
 */
interface IAdapter {
    /** Game version identifier, e.g. "26.4-snapshot-10". */
    val versionId: String

    /** This version's **client** entry class (dotted name — it is the argument to `Class.forName`). */
    val mainClass: String

    /**
     * This version's **dedicated server** entry class (also dotted); null means that
     * version's server startup is not yet supported.
     *
     * The entry class is version-specific; which entry starts is decided by the `oml.side`
     * system property.
     */
    val serverMainClass: String? get() = null

    /** The **client** bytecode transformers for this version. */
    fun createTransformers(): List<IClassTransformer>

    /**
     * Bytecode transformers loaded in **server mode**; empty by default.
     *
     * Kept separate from [createTransformers] rather than merged into one batch because the
     * two sides target different classes (client hooks `Minecraft`, server hooks
     * `MinecraftServer`): merging would let every rule silently miss on the other side,
     * which is the most dangerous failure mode of this engine.
     */
    fun createServerTransformers(): List<IClassTransformer> = emptyList()

    /**
     * Content-declaration registry: translates mod declarations before the game's main logic
     * starts and the version registry freezes. Returns null when that version does not yet
     * support content registration.
     */
    fun createContentRegistry(): org.ohmyloader.api.core.content.ContentRegistryFactory? = null

    /**
     * The version's custom-payload send path (client→server and server→player). Null when that
     * version does not yet route mod payloads; mods calling a send method then get a clear error
     * rather than a packet dropped without a trace.
     */
    fun createNetworkSender(): org.ohmyloader.api.core.network.OMLNetworkSender? = null

    /**
     * Game-ready hook (tail of Minecraft initialization, or the dedicated server's own constructor path):
     * post-processing that needs the game runtime environment (e.g. content materialization) runs here,
     * before mods initialize.
     */
    fun onGameReady() {}

    /**
     * Version-specific teardown of game-owned executors and sound/network resources. The core
     * version-neutral teardown cannot name these classes; doing it here keeps the calls typed
     * instead of creating reflection accessors that the VM can keep in its metadata.
     */
    fun shutdownSessionResources(clientSide: Boolean) {}

    /** The telemetry executor, when this version owns one in the current session. */
    fun telemetryExecutor(clientSide: Boolean): ExecutorService? = null

    /** Periodic JFR tasks registered by this version's profiler, for withdrawal at teardown. */
    fun jfrPeriodicTasks(): List<Runnable> = emptyList()

    /** JFR event classes registered by this version's profiler. */
    fun jfrCustomEvents(): List<Class<*>> = emptyList()

    /** OpenAL event types subscribed by this version's device tracker. */
    fun openAlEventTypes(): IntArray? = null

    /** Hands control to the version's client or dedicated-server entry point. */
    fun launch(args: Array<String>, serverSide: Boolean) {
        val entry = (if (serverSide) serverMainClass else mainClass)
            ?: error("server startup is not supported for $versionId")
        val type = Class.forName(entry, true, javaClass.classLoader)
        type.getMethod("main", Array<String>::class.java).invoke(null, args)
    }

    /** Invalidates the vanilla client's post-main shutdown watchdog for an in-process reboot. */
    fun disarmClientShutdownWatchdog() {}
}
