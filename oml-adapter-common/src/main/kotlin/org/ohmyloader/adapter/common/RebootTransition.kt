package org.ohmyloader.adapter.common

import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * The seam the reboot transition is drawn through.
 *
 * <p>The shared adapter decides *when* the transition is painted — the tail of
 * `Gui.extractRenderState`, which is the only point above the HUD, the vanilla overlay, the screen
 * and the saving indicator. It cannot decide *how*: the GUI calls that draw the vanilla loading
 * screen (`GuiGraphicsExtractor.blit`'s parameter list, the pipeline holder it takes, the texture
 * handle's type) are exactly the ones that differ between the shipped game jar and the next
 * snapshot, and this module is bytecode-checked against both.
 *
 * <p>So the version module installs a painter at session start and this is the one indirection
 * between them. A session with nothing installed draws nothing, which is also what a server-side
 * session sees.
 */
object RebootTransition {

    /** Installed by the version module before the game's first frame; null otherwise. */
    @Volatile
    var painter: ((GuiGraphicsExtractor) -> Unit)? = null

    /** True once a painter is installed, so a gate can tell "not drawn" from "cannot draw". */
    val installed: Boolean get() = painter != null
}
