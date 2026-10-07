package org.ohmyloader.api26_3.event

import net.minecraft.client.gui.GuiGraphicsExtractor
import org.ohmyloader.api.event.Event

/**
 * One logical client frame completed, always on the client's main thread.
 */
class ClientTickEvent : Event()

/**
 * One logical server frame completed, on the server's own thread — a dedicated server's or, in
 * singleplayer, the integrated server's. [org.ohmyloader.api26_3.EventDispatch] pairs it with
 * [ServerStartedEvent], which names the instance that ticked.
 */
class ServerTickEvent : Event()

/**
 * The render loop asking for its frame limit (mutable): [currentLimit] is the value the game holds,
 * and writing [limit] is what takes effect — the returned number is the one the game then sleeps on.
 */
class FrameRateLimitEvent(val currentLimit: Int) : Event() {
    var limit: Int = currentLimit
}

/**
 * The HUD pass is beginning, once per frame while a world is loaded, before the HUD is extracted.
 * [graphics] is the frame's draw surface, so a mod draws through the game's own object.
 */
class HudRenderEvent(val graphics: GuiGraphicsExtractor) : Event()
