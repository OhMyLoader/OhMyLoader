package org.ohmyloader.core.adapter

import org.ohmyloader.api.event.Events
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The frame-limit event's reuse semantics: the event instance is reused while the game's limit is
 * unchanged, so a handler's adjustment of [org.ohmyloader.api.event.FrameRateLimitEvent.limit]
 * must be reset before every fire — a leaked adjustment would silently cap the next frame's
 * limit at whatever the last listener chose. (One self-contained test: `EventDefinition.register`
 * has no unregister, so the listeners accumulate — harmless here because this is the only test
 * against this event in the JVM.)
 */
class EventDispatchFrameLimitTest {

    @Test
    fun `onFrameLimit fires per call and resets a previous handler adjustment`() {
        val seen = mutableListOf<Int>()
        Events.FRAME_RATE_LIMIT.register { seen += it.limit }

        assertEquals(260, EventDispatch.onFrameLimit(260))

        // a handler lowers the limit; the next fire with the same game limit must observe the
        // game's value again, not the previous frame's adjustment
        Events.FRAME_RATE_LIMIT.register { it.limit = 30 }
        assertEquals(30, EventDispatch.onFrameLimit(260))

        // the recorder runs before the adjuster, so it observed the reset value on both fires
        assertEquals(listOf(260, 260), seen)
    }
}
