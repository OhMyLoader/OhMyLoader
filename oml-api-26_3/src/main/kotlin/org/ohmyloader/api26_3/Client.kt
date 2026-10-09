package org.ohmyloader.api26_3

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen

/** The screen currently shown, or null when the game world itself is on screen. */
val Minecraft.currentScreen: Screen? get() = gui.screen()

/** Window size in GUI units, which is what HUD drawing addresses. */
val Minecraft.scaledWidth: Int get() = window.guiScaledWidth

val Minecraft.scaledHeight: Int get() = window.guiScaledHeight

/**
 * Draws [text] at a GUI-space position in the game's own default font, with vanilla's chat colour codes
 * honoured. The HUD surface a `HUD_RENDER` handler is handed is a draw-state extractor: it records what to
 * draw for the frame, and the game's own rendering pass puts it on screen.
 */
fun GuiGraphicsExtractor.drawText(text: String, x: Int, y: Int) {
    text(Minecraft.getInstance().font, text, x, y, 0xFFFFFF)
}
