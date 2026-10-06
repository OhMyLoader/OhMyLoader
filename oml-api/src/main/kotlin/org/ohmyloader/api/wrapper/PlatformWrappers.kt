package org.ohmyloader.api.wrapper

import org.ohmyloader.api.world.OMLBlockPos

/**
 * Wrapper for version-specific game objects. These wrapper
 * interfaces form the OML API "stable layer": consistent semantics,
 * small interfaces, growing with the event domains; [platform]
 * serves as the escape hatch — any blocked mod can reach the underlying native
 * object, so OML never imposes a capability ceiling.
 */
interface OMLScreen {
    /** The underlying game object (a `Screen`). */
    val platform: Any

    /** Registry-independent identity: the class name the game instantiated, e.g. `InventoryScreen`. */
    val screenName: String
}

/**
 * A world — the client's rendered level or a server-side level, depending on where the event was
 * dispatched (see [isClient]). Block queries always work; block writes only take effect on a
 * server-side world, because the client's level holds a prediction the server would overwrite.
 */
interface OMLWorld {
    /** The underlying game object (a `ClientLevel` or `ServerLevel`). */
    val platform: Any

    /** Registry id of this dimension, e.g. `minecraft:overworld`. */
    val dimensionId: String

    val isClient: Boolean

    /**
     * Registry id of the block at [pos], e.g. `minecraft:stone`. The game's own getter answers air for
     * a position that is not loaded, so this reports `minecraft:air` there rather than failing.
     */
    fun blockAt(pos: OMLBlockPos): String

    /**
     * Places [blockId] at [pos] with vanilla's neighbour-update flags; false when the id is unknown
     * or the position is not loaded.
     */
    fun setBlock(pos: OMLBlockPos, blockId: String): Boolean
}

/** HUD draw-surface wrapper, handed out by [org.ohmyloader.api.event.HudRenderEvent]. */
interface OMLGuiGraphics {
    /** The underlying game object (a `GuiGraphicsExtractor`). */
    val platform: Any

    /** Width of the window this frame draws into, in GUI units. */
    val width: Int

    /** Height of the window this frame draws into, in GUI units. */
    val height: Int

    /** Draws [text] at a GUI-space position with vanilla's chat colour codes honoured. */
    fun drawText(text: String, x: Int, y: Int)
}
