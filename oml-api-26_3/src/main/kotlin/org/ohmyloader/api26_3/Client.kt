package org.ohmyloader.api26_3

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen

/** The screen currently shown, or null when the game world itself is on screen. */
val Minecraft.currentScreen: Screen? get() = gui.screen()

/**
 * Shows [screen]. This is the game's own open path, which means a `GUI_OPEN` handler observes and
 * may cancel it like any other screen swap. Client-authoritative: the server has no say in what a
 * client shows itself; a server-driven UI goes through a menu (`MENU_OPEN` / a declared container's
 * own screen) instead.
 */
fun Minecraft.openScreen(screen: Screen) {
    setScreenAndShow(screen)
}

/**
 * Closes the current screen, so the world takes over. This is the game's own close path — the same
 * one every vanilla close takes, which is why no `GUI_OPEN` accompanies it: the hook sits on the
 * open path, and a close is not an open of anything.
 */
fun Minecraft.closeScreen() {
    gui.setScreen(null)
}

/** Window size in GUI units, which is what HUD drawing addresses. */
val Minecraft.scaledWidth: Int get() = window.guiScaledWidth

val Minecraft.scaledHeight: Int get() = window.guiScaledHeight

/**
 * Draws [text] at a GUI-space position in the game's own default font, with vanilla's chat color codes
 * honored. The HUD surface a `HUD_RENDER` handler is handed is a draw-state extractor: it records what to
 * draw for the frame, and the game's own rendering pass puts it on screen. The color is full-alpha white on
 * purpose — the extractor drops any draw whose ARGB alpha is 0, so an RGB-only literal never appears.
 */
fun GuiGraphicsExtractor.drawText(text: String, x: Int, y: Int) {
    text(Minecraft.getInstance().font, text, x, y, 0xFFFFFFFF.toInt())
}
