package org.ohmyloader.adapter

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.screens.PauseScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component
import org.ohmyloader.api.core.OmlLog
import org.ohmyloader.api.core.command.OMLCommandDeclaration
import org.ohmyloader.api.core.command.OMLCommandSource
import org.ohmyloader.api.core.tick.OMLScheduler
import org.ohmyloader.api.OMLReboot
import org.ohmyloader.api.RebootRestore
import org.ohmyloader.api.currentScreen
import org.ohmyloader.core.OMLCore

/**
 * The two player-facing ways into a soft reboot, and the Mods button that leads to both.
 *
 * <p>**The button.** `TitleScreen.init` and `PauseScreen.init` are hooked at their last return, so
 * the sentence in the log is literal: the screen has finished laying itself out and the button is
 * added beside the vanilla rows, bottom-right, at whatever size the window currently has. Vanilla
 * clears a screen's widgets at the start of every `init`, so a resize rebuilds the button with the
 * rest of the menu and it never doubles up.
 *
 * <p>**`/oml reload`.** The node is declared here (the handler needs game types) and handed to the
 * core, which owns the `/oml` tree. A dedicated server cannot reboot a client loop it does not
 * have, so it answers instead of failing; in singleplayer the request is queued onto the client
 * thread, because `OMLReboot` captures the player and stops the render loop.
 *
 * <p>Nothing here holds a session object across the swap: the screen is rebuilt next session, and
 * the reboot itself goes through [OMLReboot], whose state lives in the supervisor's plain data.
 */
internal object ModsMenuHook {

    private const val BUTTON_WIDTH = 80
    private const val BUTTON_HEIGHT = 20
    private const val MARGIN = 4

    /**
     * Game-ready, once per session: the baseline the "mods changed" hint compares against, and the
     * `/oml reload` node. Both sides get the command (the server's answer is a refusal); only a
     * client has a directory worth listing.
     */
    @JvmStatic
    fun install() {
        if (!OMLCore.isServerSide()) {
            runCatching { ModsInventory.captureBaseline() }
                .onFailure { OmlLog.warn("Mods", "capturing the mods directory baseline failed: $it") }
            org.ohmyloader.api.event.Events.CLIENT_TICK.register { reopenRequestedScreen() }
        }
        runCatching { OMLCore.addBuiltinCommand(reloadCommand()) }
            .onFailure { OmlLog.warn("Mods", "registering /oml reload failed: $it") }
    }

    /**
     * A reboot started from the Mods screen asks the next session to land back on it, so the player
     * sees the list they were reading instead of the main menu / the world.
     *
     * <p>The marker comes from two places: the supervisor's text slot for an in-process swap, and
     * `-Doml.reopen=<id>` for the process-restart fallback (a new process has no supervisor statics
     * from the old one). The old session cannot misfire here: it stays in [RebootRestore.State.IDLE]
     * — its own client ticks are blocked while the farewell is armed — and the screen is only opened
     * once the new session's transition has finished.
     */
    private fun reopenRequestedScreen() {
        if (reopenHandled) return
        val state = RebootRestore.state
        if (state == RebootRestore.State.IDLE) return
        if (state != RebootRestore.State.DONE) return
        val client = Minecraft.getInstance()
        if (!client.isGameLoadFinished) return
        val requested = RebootRestore.takeScreen()
            ?: System.getProperty(REOPEN_PROPERTY)?.also { System.clearProperty(REOPEN_PROPERTY) }
        reopenHandled = true
        if (requested != REOPEN_MODS) return
        // Only a real menu makes a sensible parent. In-world the list is opened over the HUD
        // (parent null), and a transient loading/message screen must not become the "back" target.
        val parent = client.currentScreen?.takeIf {
            it is TitleScreen || it is net.minecraft.client.gui.screens.PauseScreen
        }
        OmlLog.info("Mods", "reopening the Mods screen after the reload (parent=${parent?.javaClass?.simpleName})")
        client.setScreenAndShow(OMLModsScreen(parent))
    }

    private var reopenHandled = false

    /** `-Doml.reopen=<id>`: the process-restart fallback's copy of the same request. */
    private const val REOPEN_PROPERTY = "oml.reopen"
    private const val REOPEN_MODS = "mods"

    /**
     * `TitleScreen.init` / `PauseScreen.init` tail: adds the Mods button at the bottom-right corner,
     * where neither menu has a vanilla control. The widget is an [UnfocusedButton], so a press does
     * not stick the focus border.
     */
    @JvmStatic
    fun onScreenInit(screen: Any?) {
        val current = screen as? Screen ?: return
        if (current !is TitleScreen && current !is PauseScreen) return
        val minecraft = Minecraft.getInstance()
        // `init` is also reached from a resize of a screen that is already behind another one; the
        // button belongs to whichever screen the player is actually looking at.
        if (minecraft.currentScreen !== current) return
        val button = UnfocusedButton(
            current.width - MARGIN - BUTTON_WIDTH,
            current.height - MARGIN - BUTTON_HEIGHT,
            BUTTON_WIDTH,
            BUTTON_HEIGHT,
            Component.literal("Mods"),
        ) {
            minecraft.setScreenAndShow(OMLModsScreen(current))
        }
        addRenderableWidget(current, button)
    }

    /** The declaration handed to the core; the handler lives here because it names game types. */
    private fun reloadCommand(): OMLCommandDeclaration = OMLCommandDeclaration("reload", null).apply {
        executes { source -> requestReload(source) }
    }

    private fun requestReload(source: OMLCommandSource) {
        // A dedicated server has no client loop to restart: say so instead of arming a reboot that
        // can never happen. Checked by side first, so this path never touches a client class.
        if (OMLCore.isServerSide()) {
            source.replyError("This dedicated server cannot reload itself — restart the server process instead.")
            return
        }
        val client = Minecraft.getInstance()
        if (client.singleplayerServer == null) {
            source.replyError("Reloading the game is only available in singleplayer or on the main menu.")
            return
        }
        // The command runs on the integrated server's thread and can be typed by a LAN guest; only
        // the local player's own request may restart the local game.
        val caller = runCatching { (source.platform as? CommandSourceStack)?.player }.getOrNull()
        val local = client.player
        if (caller != null && local != null && caller.uuid != local.uuid) {
            source.replyError("Only the local player can reload this game.")
            return
        }
        source.reply("Reloading OML mods…")
        OMLScheduler.client.schedule(1) {
            runCatching { OMLReboot.restart("/oml reload") }
                .onFailure { OmlLog.error("Mods", "/oml reload could not start the reboot", it) }
        }
    }

    /**
     * `Screen.addRenderableWidget` is protected and its erasure takes `GuiEventListener`; reflection
     * keeps the game class untouched (an access rewrite for one call site would hand every mod a
     * wider surface than this needs).
     */
    private val addWidget: java.lang.reflect.Method? by lazy {
        runCatching {
            Screen::class.java
                .getDeclaredMethod("addRenderableWidget", net.minecraft.client.gui.components.events.GuiEventListener::class.java)
                .apply { isAccessible = true }
        }.getOrNull()
    }

    private fun addRenderableWidget(screen: Screen, widget: AbstractWidget) {
        val method = addWidget
        if (method == null) {
            OmlLog.warn("Mods", "Screen.addRenderableWidget is gone; the Mods button cannot be placed")
            return
        }
        runCatching { method.invoke(screen, widget) }
            .onFailure { OmlLog.warn("Mods", "adding the Mods button to ${screen.javaClass.simpleName} failed: $it") }
    }
}
