package org.ohmyloader.api26_3.event

import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.multiplayer.ClientLevel
import org.ohmyloader.api.event.Event

/**
 * The game is about to show a GUI (cancellable); cancelling keeps it closed. A null [screen] means
 * the current GUI is being closed rather than one being opened.
 */
class GuiOpenEvent(val screen: Screen?) : Event() {
    override val cancellable = true
}

/** The local player is about to send a chat message (cancellable; cancelling drops the send). */
class ChatSentEvent(val message: String) : Event() {
    override val cancellable = true
}

/**
 * A client level became the rendered one — joining a save or a server. Null means the reverse: this
 * side's disconnect (see also [org.ohmyloader.api26_3.event.Events.WORLD_LOAD]).
 */
class WorldLoadEvent(val world: ClientLevel?) : Event()
