package org.ohmyloader.api.core.tick

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The tick queue's contract: a task runs on the thread that advances its own side, never before the
 * tick it was scheduled for, and a task that throws (or is canceled) takes nothing else down with it.
 * Tests drive [OMLScheduler.advance] directly, so "the next tick" is a call, not a wall-clock wait.
 */
class OMLSchedulerTest {

    @Test
    fun `a scheduled task waits for its delay and then runs once`() {
        var runs = 0
        OMLScheduler.schedule(OMLSide.CLIENT, delayTicks = 2) { runs++ }

        OMLScheduler.advance(OMLSide.CLIENT)
        assertEquals(0, runs, "a two-tick delay must not fire on the first tick")
        OMLScheduler.advance(OMLSide.CLIENT)
        assertEquals(1, runs)
        OMLScheduler.advance(OMLSide.CLIENT)
        assertEquals(1, runs, "a one-shot task never re-enters the queue")
    }

    @Test
    fun `a repeating task keeps its period until canceled`() {
        val fired = mutableListOf<Int>()
        var tick = 0
        val task = OMLScheduler.every(OMLSide.SERVER, intervalTicks = 3, initialDelay = 1) { fired += tick }

        repeat(10) {
            tick++
            OMLScheduler.advance(OMLSide.SERVER)
        }
        assertEquals(listOf(1, 4, 7, 10), fired)

        task.cancel()
        repeat(5) {
            tick++
            OMLScheduler.advance(OMLSide.SERVER)
        }
        assertEquals(4, fired.size, "a canceled repeat stops at the next tick, not after a grace period")
    }

    @Test
    fun `a throwing task is dropped without taking the queue down`() {
        var survivorRan = false
        OMLScheduler.schedule(OMLSide.CLIENT) { throw IllegalStateException("mod bug") }
        OMLScheduler.schedule(OMLSide.CLIENT) { survivorRan = true }

        OMLScheduler.advance(OMLSide.CLIENT)
        assertTrue(survivorRan)
    }

    @Test
    fun `the two sides advance independently`() {
        var client = 0
        var server = 0
        OMLScheduler.schedule(OMLSide.CLIENT) { client++ }
        OMLScheduler.schedule(OMLSide.SERVER) { server++ }

        OMLScheduler.advance(OMLSide.CLIENT)
        assertEquals(1 to 0, client to server)
        OMLScheduler.advance(OMLSide.SERVER)
        assertEquals(1 to 1, client to server)
    }

    /**
     * The reason the scheduler exists: a mod's network handler runs on a Netty thread and must be able
     * to hand work to the tick thread. Enqueueing off-thread has to be seen by the next advance, and
     * the body has to run on the thread that advanced, not on the one that queued it.
     */
    @Test
    fun `a task enqueued from another thread runs on the ticking thread`() {
        var runningThread: Thread? = null
        Thread { OMLScheduler.schedule(OMLSide.SERVER) { runningThread = Thread.currentThread() } }
            .apply { start() }
            .join()

        val tickingThread = Thread.currentThread()
        OMLScheduler.advance(OMLSide.SERVER)
        assertEquals(tickingThread, runningThread)
    }

    @Test
    fun `a zero repeat interval is rejected instead of spinning the same tick forever`() {
        assertFailsWith<IllegalArgumentException> {
            OMLScheduler.every(OMLSide.CLIENT, intervalTicks = 0) { }
        }
    }

    /**
     * The soft-reboot contract: the scheduler is process-wide (parent-first carve-out), so a session
     * change must drop every queued task — a body is defined by the session that queued it, and
     * anything left over would fire stale game objects into the next session and pin its loader.
     * A one-shot due next tick and a repeat with its period are both dropped; a task queued after
     * the change (the next session's) still fires normally.
     */
    @Test
    fun `a session change drops pending and repeating tasks on both sides`() {
        var stale = 0
        var repeating = 0
        OMLScheduler.schedule(OMLSide.CLIENT, delayTicks = 3) { stale++ }
        OMLScheduler.every(OMLSide.SERVER, intervalTicks = 2) { repeating++ }

        OMLScheduler.advance(OMLSide.SERVER) // the repeat's initial delay passes: it is now queued again
        OMLScheduler.clearForSessionChange()
        stale = 0
        repeating = 0 // anything fired before the change belongs to the dying session, not the assertion

        repeat(6) {
            OMLScheduler.advance(OMLSide.CLIENT)
            OMLScheduler.advance(OMLSide.SERVER)
        }
        assertEquals(0 to 0, stale to repeating, "nothing from the old session survives the change")

        var fresh = 0
        OMLScheduler.schedule(OMLSide.CLIENT) { fresh++ }
        OMLScheduler.advance(OMLSide.CLIENT)
        assertEquals(1, fresh, "the next session's own scheduling works normally after the change")
    }
}
