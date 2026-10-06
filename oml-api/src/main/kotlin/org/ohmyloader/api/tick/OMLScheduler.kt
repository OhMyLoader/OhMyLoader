package org.ohmyloader.api.tick

import org.ohmyloader.api.OmlLog
import java.util.TreeMap

/** Which logical thread a task belongs to. */
enum class OMLSide {
    /** The client's render/logic thread — the side that owns screens, the HUD and the local player. */
    CLIENT,

    /** The server thread, dedicated or integrated. */
    SERVER,
}

/** A queued task; [cancel] is idempotent and safe from any thread. */
interface OMLTask {
    fun cancel()
    val canceled: Boolean
}

/**
 * Tick-based task queue, and the supported way to move work onto the game's own thread.
 *
 * A task never runs earlier than the tick after it was queued, and always runs on the thread that
 * ticks the [OMLSide] it was queued for — so a handler on a network thread can hand state-changing
 * work to the game loop with `schedule(OMLSide.SERVER) { … }` instead of touching game objects from
 * its own thread. A task that throws is logged and dropped; the rest of the queue still runs.
 */
object OMLScheduler {

    /**
     * [interval] is positive for a repeating task (its period in ticks) and -1 for a one-shot. The
     * tick it is due on is the map key, not a field: re-queueing a repeat just inserts the same entry
     * under a later key, so a handle returned to the mod keeps working.
     */
    private class Entry(val interval: Int, val body: () -> Unit) : OMLTask {
        @Volatile
        override var canceled: Boolean = false
            private set

        override fun cancel() {
            canceled = true
        }
    }

    private class Queue {
        val lock = Any()
        var tick = 0L
        val due = TreeMap<Long, MutableList<Entry>>()
    }

    private val client = Queue()
    private val server = Queue()

    private fun queueOf(side: OMLSide): Queue = if (side == OMLSide.CLIENT) client else server

    /** Runs [task] after [delayTicks] ticks of [side]; 0 means the next tick. Safe from any thread. */
    fun schedule(side: OMLSide, delayTicks: Int = 0, task: () -> Unit): OMLTask =
        enqueue(side, delayTicks.coerceAtLeast(0), interval = -1, task)

    /**
     * Runs [task] every [intervalTicks] ticks, first after [initialDelay]. [intervalTicks] must be at
     * least 1: a zero period would re-enter the same tick forever.
     */
    fun repeat(side: OMLSide, intervalTicks: Int, initialDelay: Int = 0, task: () -> Unit): OMLTask {
        require(intervalTicks >= 1) { "repeat interval must be at least 1 tick, got $intervalTicks" }
        return enqueue(side, initialDelay.coerceAtLeast(0), intervalTicks, task)
    }

    /** Ticks this side's queue; driven by the loader's tick hooks, not by mods. */
    fun advance(side: OMLSide) {
        val queue = queueOf(side)
        val ready = ArrayList<Entry>()
        val current: Long
        synchronized(queue.lock) {
            current = ++queue.tick
            val iterator = queue.due.entries.iterator()
            while (iterator.hasNext()) {
                val slot = iterator.next()
                if (slot.key > current) break
                iterator.remove()
                ready += slot.value
            }
        }
        ready.forEach { entry ->
            if (entry.canceled) return@forEach
            try {
                entry.body()
            } catch (t: Throwable) {
                OmlLog.error("OMLScheduler", "a scheduled ${side.name.lowercase()} task threw and was dropped", t)
            }
            if (entry.interval > 0 && !entry.canceled) {
                synchronized(queue.lock) {
                    queue.due.getOrPut(current + entry.interval) { ArrayList() } += entry
                }
            }
        }
    }

    private fun enqueue(side: OMLSide, delayTicks: Int, interval: Int, task: () -> Unit): OMLTask {
        val queue = queueOf(side)
        val entry = Entry(interval, task)
        synchronized(queue.lock) {
            queue.due.getOrPut(queue.tick + delayTicks) { ArrayList() } += entry
        }
        return entry
    }
}
