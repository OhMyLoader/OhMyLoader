package org.ohmyloader.core.spi

import org.ohmyloader.core.transformer.IClassTransformer

/**
 * Version-adapter SPI: each supported game version provides one implementation; core
 * discovers it via ServiceLoader, and beyond that core has zero dependency on any
 * specific version.
 */
interface IAdapter {
    /** Game version identifier, e.g. "26.3". */
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
    fun createContentRegistry(): org.ohmyloader.api.content.ContentRegistryFactory? = null

    /**
     * The version's custom-payload send path (client→server and server→player). Null when that
     * version does not yet route mod payloads; mods calling a send method then get a clear error
     * rather than a packet dropped without a trace.
     */
    fun createNetworkSender(): org.ohmyloader.api.network.OMLNetworkSender? = null

    /**
     * Game-ready hook (tail of Minecraft initialization): post-processing that needs the game
     * runtime environment (e.g. content materialization) runs here.
     */
    fun onGameReady() {}
}