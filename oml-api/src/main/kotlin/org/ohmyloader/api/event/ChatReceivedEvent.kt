package org.ohmyloader.api.event

/**
 * Client chat-message receive event: dispatched when a chat message from the
 * server/system is received and about to be displayed (cancellable; cancelling
 * prevents it from being displayed). [message] is the plain text with format
 * codes stripped.
 */
class ChatReceivedEvent(val message: String) : Event() {
    override val cancellable = true
}
