package org.ohmyloader.core.adapter

import org.ohmyloader.api.event.Events
import java.util.*
import kotlin.test.*

/**
 * The version-independent chat-text extraction mechanism, exercised against fake packets: the
 * candidate-getter order, the `Optional` unwrapping, the Component→`getString()` bridge and the
 * signed-body fallback. The real packets are 26.3 classes; the fake versions here only have to
 * expose the same *shape* the mechanism is written against.
 *
 * Extraction is tested directly against [EventDispatch.chatText] — `EventDefinition.register` has
 * no unregister, so firing the global event per test would let one test's listener state poison
 * the others depending on run order.
 */
class EventDispatchChatTest {

    private class Component(private val text: String) {
        fun getString(): String = text
    }

    private class SystemChat(val content: String) {
        fun content(): String = content
    }

    private class DisguisedChat(val message: Component) {
        fun message(): Component = message
    }

    private class PlayerChat(private val body: Body) {
        fun unsignedContent(): Optional<Component> = Optional.empty()
        fun body(): Body = body
    }

    private class Body(val content: String) {
        fun content(): String = content
    }

    private class ModifiedPlayerChat(private val body: Body) {
        fun unsignedContent(): Optional<Component> = Optional.of(Component("edited text"))
        fun body(): Body = body
    }

    private class Alien

    private val getters = listOf("content", "message", "unsignedContent")
    private val bodyGetters = "body" to "content"

    @Test
    fun `a plain string getter wins`() {
        assertEquals("hello", EventDispatch.chatText(SystemChat("hello"), getters, bodyGetters))
    }

    @Test
    fun `a component getter is asked for getString`() {
        assertEquals("hi", EventDispatch.chatText(DisguisedChat(Component("hi")), getters, bodyGetters))
    }

    @Test
    fun `an absent optional falls through to the body fallback`() {
        assertEquals("raw text", EventDispatch.chatText(PlayerChat(Body("raw text")), getters, bodyGetters))
    }

    @Test
    fun `a present optional beats the body`() {
        assertEquals("edited text", EventDispatch.chatText(ModifiedPlayerChat(Body("raw text")), getters, bodyGetters))
    }

    @Test
    fun `an unparsable packet yields no text`() {
        assertNull(EventDispatch.chatText(Alien(), getters, bodyGetters))
    }

    @Test
    fun `onChatReceived fires the event and returns its canceled state`() {
        // The one fire-path test: both assertions live in a single test so the unregister-less
        // listener accumulation can never reorder the cancel observation across tests.
        val received = mutableListOf<String>()
        Events.CHAT_RECEIVED.register { received += it.message }
        assertFalse(EventDispatch.onChatReceived(SystemChat("hello"), getters, bodyGetters))
        Events.CHAT_RECEIVED.register { it.cancel() }
        assertTrue(EventDispatch.onChatReceived(SystemChat("hello"), getters, bodyGetters))
        assertEquals(listOf("hello", "hello"), received)
    }
}
