package org.ohmyloader.core.adapter

import org.ohmyloader.api.access.OMLServer
import org.ohmyloader.api.event.Events
import org.ohmyloader.api.tick.OMLSide
import org.ohmyloader.api.tick.OMLScheduler
import org.ohmyloader.api.world.OMLPlayer
import org.ohmyloader.api.wrapper.OMLWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The server-side dispatch chain: `SERVER_STARTED` is derived from the tick hook, so it must fire on the
 * first tick of **each** server instance and never twice for one — a client that leaves a world and opens
 * another integrated server in the same process would otherwise get a start event for the first world only.
 * The same tests cover the scheduler being driven by the tick, which is the only thing that moves a queued
 * task forward.
 */
class EventDispatchLifecycleTest {

    private class FakeServer(override val platform: Any) : OMLServer {
        override val dimensionIds: List<String> = emptyList()
        override val players: List<OMLPlayer> = emptyList()
        override val tickCount: Int = 0
        override val isDedicated: Boolean = true
        override fun playerByName(name: String): OMLPlayer? = null
        override fun level(dimensionId: String): OMLWorld? = null
        override fun broadcast(text: String) {}
        override fun gameRule(ruleId: String): String? = null
        override fun setGameRule(ruleId: String, value: String): Boolean = false
    }

    @Test
    fun `SERVER_STARTED fires on the first tick of each server instance`() {
        val started = mutableListOf<Any>()
        Events.SERVER_STARTED.register { started += it.server.platform }

        val first = FakeServer(Any())
        val second = FakeServer(Any())
        EventDispatch.onServerTick(first)
        EventDispatch.onServerTick(first)
        assertEquals(1, started.size, "a latch that stayed set would drop the second server's start event")

        EventDispatch.onServerTick(second)
        assertEquals(listOf(first.platform, second.platform), started)
    }

    @Test
    fun `a stop is dispatched against the server that is shutting down`() {
        val stopping = mutableListOf<Any>()
        Events.SERVER_STOPPING.register { stopping += it.server.platform }
        val server = FakeServer(Any())

        EventDispatch.onServerStopping(server)
        assertTrue(server.platform in stopping)
    }

    @Test
    fun `the server tick drives the server queue and the client tick drives its own`() {
        var serverRuns = 0
        var clientRuns = 0
        OMLScheduler.schedule(OMLSide.SERVER) { serverRuns++ }
        OMLScheduler.schedule(OMLSide.CLIENT) { clientRuns++ }

        EventDispatch.onServerTick(FakeServer(Any()))
        assertEquals(1 to 0, serverRuns to clientRuns, "a server tick must not run the client's queue")

        EventDispatch.onClientTick()
        assertEquals(1 to 1, serverRuns to clientRuns)
    }
}
