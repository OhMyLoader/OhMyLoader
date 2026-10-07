package org.ohmyloader.api.world

/**
 * One of the game's clocks. 26.3 keeps time in clocks addressed by registry id instead of one day-time
 * counter: a clock is a tick counter with a rate, and the named moments the game and `/time` refer to
 * (`minecraft:day`, `minecraft:noon`) are markers registered against it.
 *
 * The counting state is readable on both sides — the server pushes it to clients whenever it changes —
 * while the writes and the marker table live on the server, which is where the game keeps them.
 */
interface OMLClock {
    /** The underlying game object (a `ClockInstance`). */
    val platform: Any

    /** Registry id of this clock, e.g. `minecraft:overworld`. */
    val clockId: String

    /** Ticks the clock has counted; the game adds [rate] of them per game tick unless it is paused. */
    val totalTicks: Long

    /** The fraction of a tick the game has accumulated and not yet counted. */
    val partialTick: Float

    /** Ticks the clock adds per game tick — 1.0 normally, 2.0 twice as fast, 0.5 half. */
    val rate: Float

    /** Whether the clock stands still; the game reports a pause to clients as a zero rate. */
    val isPaused: Boolean

    /** Marker ids this clock offers; empty on a client-side view, which is not given the table. */
    val markerIds: List<String>

    /**
     * Whether the clock sits on [markerId] now, by the game's own test. A marker is a point in the cycle
     * — exact, or periodic when it declares a period — not a window, so this is true for one tick of each
     * period. False on a client-side view and for a marker this clock does not carry.
     */
    fun isAtMarker(markerId: String): Boolean

    /**
     * Moves the clock to [markerId]'s next occurrence and answers whether it is there now: a clock already
     * on the marker counts as success, an unknown or foreign marker as failure. Server-side only, so a
     * client-side view answers false and moves nothing.
     */
    fun moveToMarker(markerId: String): Boolean

    /** Puts the clock at absolute [ticks]; false on a client-side view. */
    fun setTotalTicks(ticks: Long): Boolean

    /** Moves the clock by [ticks], negative to wind it back; false on a client-side view. */
    fun addTicks(ticks: Int): Boolean

    /**
     * Stops or resumes the clock, leaving its position where it is; false on a client-side view. The
     * game's own `advance_time` game rule freezes every clock too, without pausing any of them.
     */
    fun setPaused(paused: Boolean): Boolean

    /**
     * Sets the counting speed to [rate]; false on a client-side view. The window is the game's own —
     * 0.00001 to 1000 — and OML refuses outside it, because the game's clock manager would store any
     * number the command line would not accept.
     */
    fun setRate(rate: Float): Boolean
}
