package org.ohmyloader.api26_3.event

import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import org.ohmyloader.api.event.Event

/**
 * A chat message arrived and is about to be displayed (cancellable; cancelling drops it). [message]
 * is the text with format codes resolved the way the game resolves them for display; [packet] is the
 * inbound packet itself, so a mod can tell an action-bar message from a chat line, or read the
 * message's chat type binding, rather than what OML chose to pre-digest.
 */
class ChatReceivedEvent(
    val message: String,
    val packet: Packet<ClientGamePacketListener>,
) : Event() {
    override val cancellable = true
}
