package org.ohmyloader.api26_3

import net.minecraft.core.Holder
import net.minecraft.server.MinecraftServer
import net.minecraft.world.clock.ClockInstance
import net.minecraft.world.clock.ClockManager
import net.minecraft.world.clock.ServerClockManager
import net.minecraft.world.clock.WorldClock
import net.minecraft.world.level.Level

/*
 * 26.3 keeps time in clocks addressed by registry id instead of one day-time counter: a clock is a tick
 * counter with a rate, and the named moments `/time` refers to (`minecraft:day`, `minecraft:noon`) are markers
 * registered against it. The counting state is readable on either side — the server pushes it to clients
 * whenever it changes — while the writes and the marker table live on the server, so a client's
 * `clockManager()` answers with no write path at all.
 */

/** The clock this dimension runs on, as the game counts it. Null when the dimension type names no clock. */
val Level.clockInstance: ClockInstance?
    get() = clock?.let { clockManager().instanceOf(it) }

/** The game's own record for [clock]; null when this manager does not hold it — the game would otherwise
 * throw. */
fun ClockManager.instanceOf(clock: Holder<WorldClock>): ClockInstance? =
    runCatching { getInstance(clock) }.getOrNull()

/**
 * The clock [clockId] (`minecraft:overworld`) names in the server's own table, whether or not a dimension is
 * running on it right now; null when no such clock is registered.
 */
fun MinecraftServer.clockById(clockId: String): Holder<WorldClock>? = OMLRegistries.clock(this, clockId)

/**
 * Puts the clock at absolute [ticks] and reports whether the game holds it there. The clock has to be one this
 * manager knows: an unregistered holder moves nothing and answers false.
 */
fun ServerClockManager.applyTotalTicks(clock: Holder<WorldClock>, ticks: Long): Boolean {
    setTotalTicks(clock, ticks)
    return getInstance(clock).totalTicks() == ticks
}

/** Moves the clock by [ticks], negative to wind it back, and reports whether it landed there. */
fun ServerClockManager.applyTicks(clock: Holder<WorldClock>, ticks: Int): Boolean {
    val before = getInstance(clock).totalTicks()
    addTicks(clock, ticks)
    return getInstance(clock).totalTicks() == before + ticks
}

/**
 * Stops or resumes the clock, leaving its position where it is; reports whether the game now reports it
 * paused. The game's own `advance_time` game rule freezes every clock too, without pausing any of them, and a
 * pause reaches clients as a zero rate.
 */
fun ServerClockManager.applyPaused(clock: Holder<WorldClock>, paused: Boolean): Boolean {
    setPaused(clock, paused)
    return getInstance(clock).isPaused() == paused
}

/**
 * Sets the counting speed to [rate] (ticks added per game tick: 1.0 normally, 2.0 twice as fast); reports
 * whether the game holds that rate. The window is the game's own — 0.00001 to 1000, what `/time rate` accepts
 * — and a rate outside it moves nothing, because the manager would otherwise store a number the command
 * refuses to create.
 */
fun ServerClockManager.applyRate(clock: Holder<WorldClock>, rate: Float): Boolean {
    if (rate < CLOCK_RATE_MIN || rate > CLOCK_RATE_MAX) return false
    setRate(clock, rate)
    return getInstance(clock).rate() == rate
}

/** Marker ids this clock offers. */
fun ServerClockManager.markersOf(clock: Holder<WorldClock>): List<String> =
    commandTimeMarkersForClock(clock).map { it.identifier().toString() }.toList()

/**
 * Whether the clock sits on [markerId] now, by the game's own test. A marker is a point in the cycle — exact,
 * or periodic when it declares a period — not a window, so this is true for one tick of each period. False for
 * a marker this clock does not carry.
 */
fun ServerClockManager.atMarker(clock: Holder<WorldClock>, markerId: String): Boolean {
    val key = OMLRegistries.clockMarker(markerId) ?: return false
    return isAtTimeMarker(clock, key)
}

/**
 * Moves the clock to [markerId]'s next occurrence and answers whether it is there now: a clock already on the
 * marker counts as success, an unknown or foreign marker as failure.
 */
fun ServerClockManager.applyMarker(clock: Holder<WorldClock>, markerId: String): Boolean {
    val key = OMLRegistries.clockMarker(markerId) ?: return false
    return moveToTimeMarker(clock, key) != ServerClockManager.MoveResult.NO_TIME_MARKER_FOUND
}

private const val CLOCK_RATE_MIN = 1.0E-5f
private const val CLOCK_RATE_MAX = 1000.0f
