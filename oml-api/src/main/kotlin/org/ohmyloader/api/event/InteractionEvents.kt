package org.ohmyloader.api.event

import org.ohmyloader.api.wrapper.OMLScreen
import org.ohmyloader.api.wrapper.OMLWorld

/**
 * GUI open event: dispatched when the game is about to show a GUI (cancellable);
 * cancelling the event prevents the GUI from opening.
 * `screen == null` means the current GUI is being closed.
 */
class GuiOpenEvent(val screen: OMLScreen?) : Event() {
    override val cancellable = true
}

/**
 * Client chat-message send event: dispatched when a player is about to send a
 * chat message (cancellable); cancelling the event prevents the sending.
 */
class ChatSentEvent(val message: String) : Event() {
    override val cancellable = true
}

/**
 * Client world-load event: dispatched upon joining a save or server; [world] is
 * null when disconnecting.
 */
class WorldLoadEvent(val world: OMLWorld?) : Event()
