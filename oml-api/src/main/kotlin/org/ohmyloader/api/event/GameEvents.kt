package org.ohmyloader.api.event

import org.ohmyloader.api.wrapper.OMLGuiGraphics

/**
 * Per-tick client notification event. Dispatched every time the game's main
 * loop completes one logical frame, always on the client's main thread.
 */
class ClientTickEvent : Event()

/**
 * Per-tick server notification event: dispatched every time the **dedicated
 * server**'s main loop completes one logical frame, always on the server's main
 * thread.
 *
 * Symmetric to (but **not the same as**) [ClientTickEvent] — the client and
 * server are two independent execution paths, and a subscriber only receives
 * events on the side it lives on. The server hook sits on the `MinecraftServer`
 * main loop.
 */
class ServerTickEvent : Event()

/**
 * Frame-rate limit query event: dispatched every frame when the game's main loop
 * asks the render-limiting logic for its value.
 * [limit] is initialized to the game's current value; handlers may override
 * [limit] to take effect (a canonical example of a mutable event).
 */
class FrameRateLimitEvent(val currentLimit: Int) : Event() {
    var limit: Int = currentLimit
}

/**
 * HUD render callback: dispatched once per frame while a world is loaded, before the HUD is
 * extracted. [graphics.platform] is the game's draw surface for this frame.
 */
class HudRenderEvent(val graphics: OMLGuiGraphics) : Event()
