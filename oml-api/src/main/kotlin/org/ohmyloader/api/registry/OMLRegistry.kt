package org.ohmyloader.api.registry

/**
 * A read-only view of one of the game's built-in registries, addressed by registry id. Mod content
 * materializes before the registry freezes, so a mod's own blocks and items read back from here like
 * any vanilla entry.
 */
interface OMLRegistry {
    /** Every registered id, in the game's own registration order. */
    val ids: List<String>

    fun contains(id: String): Boolean

    /** The ids carrying [tagId] (`minecraft:planks`); empty for a tag this registry does not have. */
    fun tagged(tagId: String): List<String>

    /** The tags [id] belongs to; empty for an id the registry does not know or an entry with no tags. */
    fun tagsOf(id: String): List<String>
}

/** The registries a version adapter publishes to mods; a mod never implements this. */
interface OMLRegistryProvider {
    val blocks: OMLRegistry

    val items: OMLRegistry

    val mobEffects: OMLRegistry

    val sounds: OMLRegistry

    val entityTypes: OMLRegistry
}

/**
 * Entry point to the game's built-in registries. These read the game's static registry tables, so they
 * work on either side, from any thread, and whether or not a world exists.
 *
 * The tables are filled by the game's own bootstrap, which a mod's initialization runs ahead of: reading
 * from here during initialization fails the way reading the game's registry early fails, so content reads
 * belong to an event that has a running game behind them.
 */
object OMLRegistries {

    /**
     * Installed by the version adapter before mods initialize. Reading a registry with none installed
     * reports a failure instead of an empty list, because "the game has no blocks" and "no adapter is
     * loaded" must not look the same to a mod.
     */
    @JvmField
    @Volatile
    var provider: OMLRegistryProvider? = null

    /** Blocks, e.g. `minecraft:stone`. */
    val blocks: OMLRegistry get() = provider?.blocks ?: notInstalled()

    /** Items, e.g. `minecraft:iron_ingot`. */
    val items: OMLRegistry get() = provider?.items ?: notInstalled()

    /** Mob effects, e.g. `minecraft:poison`. */
    val mobEffects: OMLRegistry get() = provider?.mobEffects ?: notInstalled()

    /** Sound events, e.g. `minecraft:block.stone.place`. */
    val sounds: OMLRegistry get() = provider?.sounds ?: notInstalled()

    /** Entity types, e.g. `minecraft:cow`. */
    val entityTypes: OMLRegistry get() = provider?.entityTypes ?: notInstalled()

    private fun notInstalled(): Nothing =
        error("registry reads are not installed — no version adapter is loaded")
}
