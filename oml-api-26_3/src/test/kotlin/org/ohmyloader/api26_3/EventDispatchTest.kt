package org.ohmyloader.api26_3

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.network.protocol.game.ClientboundTickingStatePacket
import org.ohmyloader.api26_3.EventDispatch.chatTextOf
import org.ohmyloader.api26_3.EventDispatch.startsNow
import org.ohmyloader.api26_3.event.Events
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The dispatch rules that a real-machine run cannot show on its own: the frame-limit instance reuse, the
 * per-server-instance start latch, and which inbound packets carry readable chat.
 *
 * The disguised and signed chat branches are covered on the real machine (the verification mod receives a
 * player's own message back, which is what a unit test cannot hand it); what is
 * tested here is the state and the shape decisions this object owns — a start latch that never resets
 * drops the second server's event, a frame-limit event reused without resetting hands the next frame the
 * previous frame's adjusted number, and a packet with no readable text must fire nothing at all.
 */
class EventDispatchTest {

    @Test
    fun `SERVER_STARTED fires on the first tick of each server instance`() {
        val first = Any()
        val second = Any()

        assertTrue(startsNow(first), "the first tick of an instance is its start")
        assertFalse(startsNow(first), "a latch that stayed set would drop the second server's start event")
        assertTrue(startsNow(second), "two integrated servers in one process are two starts")
    }

    @Test
    fun `the reused frame-limit event is reset before every fire`() {
        val seen = mutableListOf<Int>()
        Events.FRAME_RATE_LIMIT.register { seen += it.limit }

        assertEquals(260, EventDispatch.onFrameLimit(260))

        // A handler lowers the limit; the next fire with the same game limit must observe the game's
        // value again, never the previous frame's adjustment.
        Events.FRAME_RATE_LIMIT.register { it.limit = 30 }
        assertEquals(30, EventDispatch.onFrameLimit(260))
        assertEquals(listOf(260, 260), seen, "the recorder runs before the adjuster, so both fires reset")
    }

    /**
     * The disguised and signed branches stay with the real machine, where the packets are the ones a server
     * actually sent; what is pinned here is that a component's *text* — not its style or its
     * `Component` object — is what the event carries.
     */
    @Test
    fun `a system packet's component content arrives as plain text`() {
        val styled = Component.literal("hello ").withStyle(ChatFormatting.RED).append(Component.literal("world"))
        val packet = ClientboundSystemChatPacket(styled, false)

        assertEquals(
            "hello world",
            chatTextOf(packet),
            "styles are stripped, as they are for the game's own display text",
        )

        val seen = mutableListOf<String>()
        Events.CHAT_RECEIVED.register { seen += it.message }
        assertFalse(EventDispatch.onChatReceived(packet))
        assertEquals(listOf("hello world"), seen)
    }

    @Test
    fun `a packet that carries no readable chat fires nothing`() {
        // The degrade path matters more than it looks: an accessor rename on a future version must cost
        // a ChatReceivedEvent, not a crash on the network thread.
        assertEquals(null, chatTextOf(ClientboundTickingStatePacket(1.0f, false)))
        assertFalse(EventDispatch.onChatReceived(ClientboundTickingStatePacket(1.0f, false)))
    }
}
