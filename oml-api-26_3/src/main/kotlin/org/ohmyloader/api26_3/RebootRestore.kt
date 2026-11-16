package org.ohmyloader.api26_3

import net.minecraft.client.Minecraft
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.GameType
import org.ohmyloader.api.OmlLog
import org.ohmyloader.api.tick.OMLScheduler

/**
 * The player/world state a soft reboot carries across the session boundary, and the transition's
 * state machine.
 *
 * <p>One rule governs everything here: **nothing may hold a session object across the swap.** The
 * snapshot is written as a plain line of text into the supervisor's
 * [org.ohmyloader.launcher.RebootSnapshot] and read back the same way; the transition is drawn from
 * the supervisor's [org.ohmyloader.launcher.OMLRebootOverlay], which holds nothing but floats.
 *
 * <p>The transition is drawn from the **tail of `Gui.extractRenderState`** — after the HUD, after the
 * vanilla overlay, after the screen, after the saving indicator. That single position is what makes
 * the transition one screen instead of three: session 1 draws it there and the shutdown's own
 * "Saving world" screen lands underneath it; session 2 draws it there and the vanilla loading
 * overlay and level-loading screen land underneath it. Neither session needs to suppress anything,
 * and no screen swap can ever be seen. This module says *what* that frame looks like
 * ([transitionFrame]); the version module says how to draw it.
 *
 * The line is positional and versioned so a mismatch is a loud "ignored" instead of a silently
 * wrong teleport:
 *
 * ```
 * v1 <US> save <US> dimension <US> x <US> y <US> z <US> yaw <US> pitch <US> gameMode
 * ```
 */
object RebootRestore {

    /** ASCII unit separator: cannot appear in a save name, a dimension id or a number. */
    private const val SEP = "\u001f"

    private const val VERSION = "v1"

    /** Frames session 1 keeps drawing after the request, so the overlay is really presented. */
    private const val FAREWELL_TICKS = 3

    /** Caps the wait for a world session 2 never gets, so a failure ends visible, not as a hang. */
    private const val LOAD_TIMEOUT_TICKS = 600

    /** How many ticks the server player may lag the client's first world tick before giving up. */
    private const val APPLY_ATTEMPTS = 100

    /** How long the fade may take before the state machine stops waiting for it. */
    private const val FADE_TIMEOUT_TICKS = 100

    /**
     * How long the reveal may be held while the client still has a screen up. A chunk-loading screen
     * lasts a handful of ticks; a minute is "something else is on screen, show it anyway".
     */
    private const val REVEAL_HOLD_TICKS = 60

    /** Where the restore is in its life cycle. */
    enum class State { IDLE, LOADING, APPLYING, FADING, DONE }

    @Volatile
    var state: State = State.IDLE
        private set

    /** Set once session 2 opened the save from the snapshot rather than from a launch argument. */
    @Volatile
    var openedFromSnapshot: Boolean = false
        private set

    /** One-line, log-safe description of the snapshot this session holds or restored. */
    @Volatile
    var describe: String = "none"
        private set

    /** One-line outcome of the restore, for the gate: `restored=1`, `skipped=...`, `failed=...`. */
    @Volatile
    var outcome: String = "none"
        private set

    // ---------------------------------------------------------------- session mask

    /**
     * Whether this session is inside the **seamless masking window**: from the moment a reboot is
     * requested (session 1) or a handover snapshot is found (session 2), until the restore state
     * machine finishes. While it is up, the join/leave pair the underlying disconnect-reconnect
     * would produce is suppressed at three points — the vanilla chat announcements (swallowed at
     * `PlayerList.broadcastSystemMessage`, see the adapter's rule) and the OML
     * [org.ohmyloader.api26_3.event.Events.PLAYER_JOINED]/[org.ohmyloader.api26_3.event.Events.PLAYER_LEFT]
     * dispatches — so the player never sees themselves leave. [org.ohmyloader.api26_3.event.OMLSessionEvents.RESUMED]
     * is what the window ends with.
     */
    @Volatile
    var maskingWindow: Boolean = false
        internal set

    /** Dispatches of `PLAYER_JOINED` the window swallowed; the gate reads it as positive evidence. */
    @Volatile
    var maskedJoins: Int = 0
        private set

    /** Dispatches of `PLAYER_LEFT` the window swallowed; session 1's count dies with its process. */
    @Volatile
    var maskedLefts: Int = 0
        private set

    /** Vanilla chat announcements the broadcast hook swallowed; session 2 sees the join one itself. */
    @Volatile
    var maskedBroadcasts: Int = 0
        private set

    /** Whether the session has already fired [org.ohmyloader.api26_3.event.OMLSessionEvents.RESUMED]. */
    @Volatile
    var resumedFired: Boolean = false
        private set

    /** [EventDispatch] reports a masked dispatch; the counters are the gate's evidence. */
    internal fun noteMaskedJoin() {
        maskedJoins++
        OmlLog.info("Reboot", "session mask: PLAYER_JOINED suppressed (${describe})")
    }

    /** [EventDispatch] reports a masked dispatch. */
    internal fun noteMaskedLeft() {
        maskedLefts++
        OmlLog.info("Reboot", "session mask: PLAYER_LEFT suppressed")
    }

    /** [EventDispatch] reports a swallowed vanilla announcement; [key] names which one. */
    internal fun noteMaskedBroadcast(key: String) {
        maskedBroadcasts++
        OmlLog.info("Reboot", "session mask: '$key' chat broadcast suppressed")
    }

    /** Session 1 only: whether the farewell frame is still owed. */
    @Volatile
    private var farewellArmed = false

    /** Whether the last frame already had the transition on it, so the up/down edges log once each. */
    @Volatile
    private var painted = false

    private var loadTicks = 0
    private var fadeTicks = 0
    private var applyAttempts = 0

    /** True once the curtain's fade has been started; before that the reveal may still be held. */
    private var liftStarted = false

    /** Where the world put the player on its own, before the snapshot moved them. */
    private var loadedAt: String = "unknown"
    private var snapshot: Snapshot? = null

    /** The decoded line. Pure data, no game types. */
    private data class Snapshot(
        val save: String,
        val dimension: String,
        val x: Double,
        val y: Double,
        val z: Double,
        val yaw: Float,
        val pitch: Float,
        val gameMode: String,
    ) {
        fun encode(): String = listOf(
            VERSION, save, dimension,
            x.toString(), y.toString(), z.toString(),
            yaw.toString(), pitch.toString(), gameMode,
        ).joinToString(SEP)

        fun summary(): String =
            "save=$save dim=$dimension pos=(${fmt(x)}, ${fmt(y)}, ${fmt(z)}) " +
                "rot=(${fmt(yaw)}, ${fmt(pitch)}) mode=$gameMode"

        private fun fmt(v: Double): String = String.format("%.2f", v)
        private fun fmt(v: Float): String = String.format("%.1f", v)
    }

    // ---------------------------------------------------------------- session 1

    /**
     * Session 1, at the reboot request: record where the player is and put the curtain up.
     *
     * <p>Returns false when there is nothing to restore (a reboot from the title screen, or a client
     * without a world). The transition goes up either way — a reboot with no state to carry still
     * crosses the same gap and must look the same.
     */
    fun capture(): Boolean {
        val client = OMLGame.client
        // The mask goes up with the curtain: from here the leaving half of the join/leave pair is a
        // fiction — nothing "leaves", the process is handing over.
        maskingWindow = true
        overlayBegin("Saving world")
        if (client == null) return false
        val player = client.player
        val level = client.level
        if (player == null || level == null) {
            // Nothing to carry, but the bar still has to move: the swap is about to happen.
            overlayMilestone("Handing over", REBUILDING)
            return false
        }
        val save = saveName(client)
        if (save == null) {
            OmlLog.warn("Reboot", "no singleplayer save directory is known; the next session will not restore state")
            overlayMilestone("Handing over", REBUILDING)
            return false
        }
        val mode = runCatching { client.gameMode?.playerMode?.name }.getOrNull() ?: "SURVIVAL"
        val snapshot = Snapshot(
            save = save,
            dimension = level.dimension().identifier().toString(),
            x = player.x,
            y = player.y,
            z = player.z,
            yaw = player.yRot,
            pitch = player.xRot,
            gameMode = mode,
        )
        put(snapshot.encode())
        describe = snapshot.summary()
        farewellArmed = true
        reportGeometry(client, "session-1")
        overlayMilestone("Saving world", REBUILDING)
        OmlLog.info("Reboot", "state snapshot: $describe")
        return true
    }

    /** True while session 1 still owes a presented overlay frame. */
    val farewellPending: Boolean get() = farewellArmed

    /**
     * Session 1: ends the client loop, after enough ticks that the overlay has certainly been
     * presented. The lease trace measures the window, not the pixels, so a transition that never
     * reached the screen would look identical to one that did — hence an explicit frame budget
     * instead of stopping inside the tick that armed it.
     */
    fun scheduleClientStop(client: Minecraft) {
        val delay = if (farewellArmed) FAREWELL_TICKS else 1
        OmlLog.info("Reboot", "holding the transition for $delay ticks")
        OMLScheduler.client.schedule(delay) {
            runCatching {
                // Minecraft.run owns the loop flag; the close path is the finally block, not a
                // public stop method. Reflection keeps this single field version-facing.
                val running = client.javaClass.getDeclaredField("running")
                running.isAccessible = true
                running.setBoolean(client, false)
            }.onFailure { OmlLog.error("Reboot", "could not stop the client loop", it) }
        }
    }

    // ---------------------------------------------------------------- session 2

    /**
     * Session 2, once per client tick: advance the restore state machine and the bar with it.
     *
     * <p>The first tick with a pending snapshot is the one that decides: a client that already owns
     * a world only has its position corrected; one that does not gets the save opened here, by name,
     * with no `--quickPlaySingleplayer` anywhere.
     */
    fun tick() {
        // The session that is leaving must never restore its own snapshot: it is the writer, the
        // line is already stored for whoever comes next, and consuming it here would hijack its own
        // transition.
        if (farewellArmed) return
        val client = OMLGame.client ?: return
        // A session two halves of the same transition: until the resource reload is done the bar
        // sits on the resource stretch, exactly where the supervisor left it.
        if (!client.isGameLoadFinished) {
            overlayMilestone("Reloading resources", RESOURCES)
            return
        }
        when (state) {
            State.IDLE -> begin(client)
            State.LOADING -> awaitWorld(client)
            State.APPLYING -> awaitApplied(client)
            State.FADING -> fade(client)
            State.DONE -> Unit
        }
    }

    private fun begin(client: Minecraft) {
        val encoded = take()
        if (encoded == null) {
            // Not a reboot at all: drop whatever a previous session left, and stay out of the frame.
            overlayReset()
            toDone()
            return
        }
        val parsed = decode(encoded)
        if (parsed == null) {
            OmlLog.warn("Reboot", "ignoring an unreadable reboot snapshot: ${sanitize(encoded)}")
            outcome = "failed=unreadable-snapshot"
            overlayReset()
            toDone()
            return
        }
        // The window is open before anything else can join or broadcast: opening the save below is
        // what places the player, and that placeNewPlayer must already be inside the mask.
        maskingWindow = true
        snapshot = parsed
        describe = parsed.summary()
        loadTicks = 0
        state = State.LOADING
        OmlLog.info("Reboot", "restoring $describe")
        // The leased window keeps the size the retiring session gave it. If this session's idea of
        // the window disagrees with SDL's, the surface is configured for the smaller size and the
        // rest of the window keeps the previous session's last frame — so print both numbers rather
        // than diagnosing that from pixels.
        reportGeometry(client, "startup")
        overlayMilestone("Loading world", LOADING)
        if (client.level != null) {
            // A launch argument already opened a world; the snapshot still owns the position.
            openedFromSnapshot = false
            state = State.APPLYING
            apply(client, parsed)
            return
        }
        openWorld(client, parsed)
    }

    /**
     * One line of geometry: the window's own size, this session's cached framebuffer size, the GUI
     * scale derived from it, and the size SDL reports right now.
     *
     * <p>The last one is what `renderFrame` configures the surface with, so a mismatch against the
     * window is exactly the "picture covers half the window" defect.
     */
    private fun reportGeometry(client: Minecraft, tag: String) {
        val window = client.window
        val live = runCatching {
            val query = window.javaClass.getDeclaredMethod("queryFramebufferSize")
            query.isAccessible = true
            val size = query.invoke(window)
            val width = size.javaClass.getMethod("width").invoke(size)
            val height = size.javaClass.getMethod("height").invoke(size)
            "${width}x$height"
        }.getOrNull() ?: "unknown"
        OmlLog.info(
            "Reboot",
            "geometry $tag fb=${window.width}x${window.height} " +
                "logical=${window.screenWidth}x${window.screenHeight} " +
                "gui=${window.guiScaledWidth}x${window.guiScaledHeight} scale=${window.guiScale} " +
                "sdl=$live handle=${window.handle()}",
        )
    }

    private fun openWorld(client: Minecraft, parsed: Snapshot) {
        openedFromSnapshot = true
        OmlLog.info("Reboot", "opening save '${parsed.save}' from the snapshot")
        val failure = runCatching {
            client.createWorldOpenFlows().openWorld(parsed.save) {
                OmlLog.error("Reboot", "the save '${parsed.save}' could not be opened")
                outcome = "failed=open-world"
            }
        }.exceptionOrNull()
        if (failure != null) {
            OmlLog.error("Reboot", "openWorld('${parsed.save}') threw", failure)
            outcome = "failed=${failure.javaClass.simpleName}"
            toDone()
            overlayFinish()
        }
    }

    private fun awaitWorld(client: Minecraft) {
        loadTicks++
        if (client.player != null && client.level != null) {
            // What the save alone produced, for the record: a restore that moved nobody and one
            // that put the player back where they were look identical in the outcome.
            loadedAt = client.player?.position()?.let { "(${"%.2f".format(it.x)}, ${"%.2f".format(it.y)}, ${"%.2f".format(it.z)})" }
                ?: "unknown"
            overlayMilestone("Restoring position", RESTORED)
            state = State.APPLYING
            applyAttempts = 0
            apply(client, snapshot)
            return
        }
        if (loadTicks > LOAD_TIMEOUT_TICKS) {
            OmlLog.error("Reboot", "the world did not load within $LOAD_TIMEOUT_TICKS ticks")
            outcome = "failed=load-timeout"
            toDone()
            overlayFinish()
        }
    }

    /**
     * Moves the freshly loaded player to the recorded place. The integrated server owns the player,
     * so the teleport is queued on its thread; the client's copy catches up through the ordinary
     * position packets, which is also what makes the follow-up check meaningful.
     *
     * <p>Re-entrant on purpose: the server player can lag the client's first world tick by a tick or
     * two, so "not there yet" is reported as `retry` and tried again instead of failing the restore.
     */
    private fun apply(client: Minecraft, target: Snapshot?) {
        if (target == null) {
            toDone()
            overlayFinish()
            return
        }
        if (applyAttempts++ > APPLY_ATTEMPTS) {
            OmlLog.warn("Reboot", "the server player never appeared; giving up on the position restore")
            outcome = "failed=no-server-player"
            state = State.FADING
            fadeTicks = 0
            restoreAttemptFinished()
            return
        }
        val server = client.singleplayerServer
        if (server == null) {
            // A remote world has no server player to move; the snapshot's position is meaningless.
            outcome = "skipped=no-integrated-server"
            OmlLog.info("Reboot", "no integrated server; leaving the player where the server put it")
            state = State.FADING
            fadeTicks = 0
            restoreAttemptFinished()
            return
        }
        val dimension = runCatching { ResourceKey.create(Registries.DIMENSION, Identifier.parse(target.dimension)) }
            .getOrNull()
        if (dimension == null) {
            outcome = "failed=bad-dimension"
            state = State.FADING
            fadeTicks = 0
            restoreAttemptFinished()
            return
        }
        server.executeIfPossible {
            val level: ServerLevel? = runCatching { server.getLevel(dimension) }.getOrNull()
            val player = runCatching { server.playerList.players.firstOrNull { !it.isRemoved } }.getOrNull()
            if (level == null || player == null) {
                outcome = "retry"
                return@executeIfPossible
            }
            val mode = runCatching { GameType.byName(target.gameMode, GameType.SURVIVAL) }
                .getOrNull() ?: GameType.SURVIVAL
            runCatching { player.setGameMode(mode) }
            // The usual case is that the save already put the player where the snapshot says: the
            // retiring session wrote its playerdata on the way out, from the very state the snapshot
            // was taken from (the log line then reads from == at). Teleporting anyway still pushes a
            // position packet, and the client answers a jump it did not predict with its chunk-loading
            // screen — the "Loading terrain.." flash the transition is supposed to cover. So move only
            // when there is somewhere to move to.
            val wanted = net.minecraft.world.phys.Vec3(target.x, target.y, target.z)
            val before = runCatching { player.position() }.getOrNull()
            val sameDimension =
                runCatching { player.level().dimension().identifier().toString() == target.dimension }
                    .getOrDefault(false)
            val inPlace = sameDimension && before != null && before.distanceTo(wanted) <= 0.5
            val moved = if (inPlace) {
                Result.success(true)
            } else {
                runCatching {
                    player.teleportTo(
                        level, target.x, target.y, target.z,
                        emptySet(), target.yaw, target.pitch, false,
                    )
                }
            }
            val actual = runCatching { player.position() }.getOrNull()
            val wrong = actual == null ||
                actual.distanceTo(net.minecraft.world.phys.Vec3(target.x, target.y, target.z)) > 1.0
            outcome = when {
                moved.isFailure -> "failed=teleport"
                wrong -> "failed=position-mismatch"
                player.level().dimension().identifier() != dimension.identifier() -> "failed=dimension-mismatch"
                else -> "restored=1"
            }
            OmlLog.info(
                "Reboot",
                "restore applied from=$loadedAt ${if (inPlace) "in-place" else "moved=${moved.getOrNull()}"} at=${
                    actual?.let { "(${"%.2f".format(it.x)}, ${"%.2f".format(it.y)}, ${"%.2f".format(it.z)})" }
                } mode=${player.gameMode()} dim=${player.level().dimension().identifier()} -> $outcome",
            )
        }
    }

    private fun awaitApplied(client: Minecraft) {
        when {
            outcome == "retry" -> {
                outcome = "none"
                apply(client, snapshot)
            }
            outcome == "none" -> Unit
            else -> {
                if (outcome.startsWith("failed")) {
                    OmlLog.warn("Reboot", "restore failed ($outcome); lifting the curtain anyway")
                }
                state = State.FADING
                fadeTicks = 0
                restoreAttemptFinished()
            }
        }
    }

    /**
     * The restore attempt is over — the outcome is final and the curtain is on its way up. The
     * masking window closes here and [org.ohmyloader.api26_3.event.OMLSessionEvents.RESUMED] fires.
     *
     * <p>The event fires at the restore, not at the lift: the snapshot's position, view angle and
     * mode are final the moment [outcome] is, and a mod refreshing its caches one or two ticks
     * *before* the reveal is what keeps the visible world free of late-arriving mod machinery. Even
     * a failed restore fires — the world and player objects are new either way, and refreshing
     * stale caches is exactly what the event exists for. [outcome] tells the handler which it was.
     *
     * <p>Called on the client thread, at every transition into FADING; the guards keep it once.
     */
    private fun restoreAttemptFinished() {
        maskingWindow = false
        if (snapshot != null && !resumedFired) {
            resumedFired = true
            OmlLog.info("Reboot", "session resumed: firing OMLSessionEvents.RESUMED ($outcome)")
            EventDispatch.onSessionResumed(outcome, describe)
        }
    }

    /**
     * Drives the curtain's own fade and stays here until it has actually lifted.
     *
     * <p>The fade is the resident layer's clock, not a tick count: `finish()` starts it and
     * [transitionActive] reports when it is over. Ending on a tick count instead would let the gate
     * ask "is the transition gone?" while it was still half-opaque — which is precisely the state a
     * player would describe as "stuck on the red screen".
     */
    private fun fade(client: Minecraft) {
        fadeTicks++
        if (!liftStarted) {
            // The client answers a position packet with its own chunk-loading screen, and that screen
            // draws the **previous** frame blurred underneath — with the leased window, that picture is
            // session 1's last frame. Lifting the curtain while such a screen is up hands the player
            // "Loading terrain.." and then the world, which is the one flash this transition exists to
            // prevent. So the curtain stays until the world itself is what would be on screen.
            val screen = client.currentScreen
            if (screen != null && fadeTicks <= REVEAL_HOLD_TICKS) {
                if (fadeTicks == 1) {
                    OmlLog.info("Reboot", "holding the transition: ${screen.javaClass.simpleName} is still up")
                }
                return
            }
            if (screen != null) {
                OmlLog.warn(
                    "Reboot",
                    "${screen.javaClass.simpleName} was still up after $REVEAL_HOLD_TICKS ticks; lifting anyway",
                )
            }
            liftStarted = true
            outcome = if (outcome == "none") "no-op" else outcome
            OmlLog.info("Reboot", "restore finished: $outcome (lifting the transition)")
            overlayFinish()
            return
        }
        if (!transitionActive()) {
            toDone()
            OmlLog.info("Reboot", "transition lifted; the player has the frame")
            return
        }
        if (fadeTicks > FADE_TIMEOUT_TICKS) {
            // A fade that never completes must not hold the state machine hostage; release and say so.
            OmlLog.warn("Reboot", "the transition did not lift within $FADE_TIMEOUT_TICKS ticks: ${overlayTrace()}")
            overlayReset()
            toDone()
        }
    }

    /**
     * Completes the state machine and makes sure the masking window is closed. The RESUMED event has
     * already fired at the restore ([restoreAttemptFinished]); every path into here is after it.
     */
    private fun toDone() {
        state = State.DONE
        maskingWindow = false
    }

    // ---------------------------------------------------------------- transition frame

    /** One frame of the transition: how opaque it is, and how far along the bar is. */
    class TransitionFrame(val alpha: Float, val progress: Float)

    /**
     * The transition as the painter should draw it this frame, or null when it should not draw.
     *
     * <p>Called on **every** frame of **both** sessions, so the ordinary path costs one reflective
     * boolean. The pixels are drawn by the version module
     * ([org.ohmyloader.adapter.v26_3.RebootTransitionPainter]): the GUI calls it needs are exactly
     * the ones whose shape differs between the shipped jar and the next snapshot, and this module
     * has to link against both.
     */
    fun transitionFrame(): TransitionFrame? {
        if (!overlayActive()) {
            if (painted) {
                painted = false
                OmlLog.info("Reboot", "transition lifted: ${overlayTrace()}")
            }
            return null
        }
        if (!painted) {
            painted = true
            // One line per session: the hook firing is the fact the transition relies on, and it is
            // otherwise invisible in a log that only records state changes.
            OmlLog.info("Reboot", "transition painting at the GUI tail: ${overlayTrace()}")
        }
        val alpha = overlayAlpha().coerceIn(0f, 1f)
        if (alpha <= 0f) return null
        return TransitionFrame(alpha, overlayProgress().coerceIn(0f, 1f))
    }

    // ---------------------------------------------------------------- overlay bridge

    /**
     * The supervisor loader owns the transition state; this module must not link against it (it
     * ships to mods, and a compile dependency on the launcher would tie a mod's classpath to the
     * process owner). Same reflective bridge the reboot signal and the lease already use.
     *
     * <p>The lookups are cached: the painter runs sixty times a second and a `Class.forName` per
     * frame would be a needless cost on the one path that must not stutter.
     */
    private val overlay: Class<*>? by lazy {
        runCatching {
            Class.forName("org.ohmyloader.launcher.OMLRebootOverlay", true, RebootRestore::class.java.classLoader)
        }.getOrNull()
    }

    private fun overlayCall(name: String, vararg args: Any?): Any? = runCatching {
        val types = args.map { if (it == null) Void.TYPE else it.javaClass }.toTypedArray()
        overlay?.getMethod(name, *types)?.invoke(null, *args)
    }.getOrNull()

    private fun overlayFloat(name: String): Float =
        runCatching { overlay?.getMethod(name)?.invoke(null) as? Float }.getOrNull() ?: 0f

    private fun overlayActive(): Boolean =
        runCatching { overlay?.getMethod("active")?.invoke(null) as? Boolean }.getOrNull() == true

    private fun overlayProgress(): Float = overlayFloat("progress")

    private fun overlayAlpha(): Float = overlayFloat("alpha")

    private fun overlayLabel(): String =
        runCatching { overlay?.getMethod("label")?.invoke(null) as? String }.getOrNull().orEmpty()

    private fun overlayBegin(phase: String) {
        runCatching { overlay?.getMethod("begin", String::class.java)?.invoke(null, phase) }
    }

    private fun overlayMilestone(phase: String, progress: Float) {
        runCatching {
            overlay?.getMethod("milestone", String::class.java, Float::class.javaPrimitiveType)?.invoke(null, phase, progress)
        }
    }

    private fun overlayFinish() {
        // The last phase a player reads, before the bar completes: "Restoring position" at 100% would
        // describe work that is already done.
        overlayMilestone("Ready", 1.0f)
        runCatching { overlay?.getMethod("finish")?.invoke(null) }
    }

    private fun overlayReset() {
        runCatching { overlay?.getMethod("reset")?.invoke(null) }
    }

    /** The transition state as the resident layer holds it, for the gate. */
    fun overlayTrace(): String =
        runCatching { overlay?.getMethod("trace")?.invoke(null) as? String }.getOrNull() ?: "overlay unavailable"

    /**
     * Whether the transition is still covering the frame.
     *
     * <p>False once it has fully faded, which is the fact `reboot.curtain_lifted` gates: a screen
     * being null is not the same thing — the transition is not a screen any more, it is painted
     * above the whole GUI pass, so only its own state can say whether it is gone.
     */
    fun transitionActive(): Boolean = overlayActive()

    // Milestone values mirrored for callers that cannot see the launcher's constants.
    private const val REBUILDING = 0.15f
    private const val RESOURCES = 0.55f
    private const val LOADING = 0.75f
    private const val RESTORED = 0.92f

    // ---------------------------------------------------------------- encoding

    private fun decode(encoded: String): Snapshot? {
        val parts = encoded.split(SEP)
        if (parts.size != 9 || parts[0] != VERSION) return null
        return runCatching {
            Snapshot(
                save = parts[1],
                dimension = parts[2],
                x = parts[3].toDouble(),
                y = parts[4].toDouble(),
                z = parts[5].toDouble(),
                yaw = parts[6].toFloat(),
                pitch = parts[7].toFloat(),
                gameMode = parts[8],
            )
        }.getOrNull()
    }

    /** The save the player is in, read off the integrated server's open storage. */
    private fun saveName(client: Minecraft): String? {
        val server = client.singleplayerServer ?: return null
        val field = storageSource ?: return null
        val storage = runCatching { field.get(server) }.getOrNull() ?: return null
        return runCatching { storage.javaClass.getMethod("getLevelId").invoke(storage) as? String }.getOrNull()
    }

    // ---------------------------------------------------------------- supervisor bridge

    private val store: Class<*>?
        get() = runCatching {
            Class.forName("org.ohmyloader.launcher.RebootSnapshot", true, RebootRestore::class.java.classLoader)
        }.getOrNull()

    private fun put(encoded: String) {
        runCatching { store?.getMethod("put", String::class.java)?.invoke(null, encoded) }
    }

    private fun take(): String? =
        runCatching { store?.getMethod("take")?.invoke(null) as? String }.getOrNull()

    /** Whether a session left a snapshot for this one; the supervisor asks this before filtering args. */
    fun pendingSnapshot(): Boolean =
        runCatching { store?.getMethod("pending")?.invoke(null) as? Boolean }.getOrNull() == true

    /** The raw last capture, for the gate: it is the supervisor's copy, not this session's. */
    fun capturedLine(): String? =
        runCatching { store?.getMethod("lastCaptured")?.invoke(null) as? String }.getOrNull()

    // ---------------------------------------------------------------- lease observations

    /** The lease as the supervisor holds it: enabled → adopted → surface closed, in one line. */
    fun leaseTrace(): String = runCatching {
        "enabled=$leaseEnabled adopted=$leaseAdopted surfaceClosed=$leaseSurfaceClosed " +
            "handle=${leaseValue("handle")} backend=${leaseValue("backend")}"
    }.getOrDefault("lease unavailable")

    /** Whether the lease is on — the default; {@code -Doml.window.lease=off} turns it off. */
    val leaseEnabled: Boolean get() = leaseBoolean("enabled")

    /** Whether this session took the previous session's handle instead of creating one. */
    val leaseAdopted: Boolean get() = leaseBoolean("adopted")

    /** Whether the retiring session released its GPU surface before the handover. */
    val leaseSurfaceClosed: Boolean get() = leaseBoolean("surfaceClosed")

    private val lease: Class<*>?
        get() = runCatching {
            Class.forName("org.ohmyloader.launcher.WindowLease", true, RebootRestore::class.java.classLoader)
        }.getOrNull()

    private fun leaseBoolean(method: String): Boolean =
        runCatching { lease?.getMethod(method)?.invoke(null) as? Boolean }.getOrNull() == true

    private fun leaseValue(method: String): Any? =
        runCatching { lease?.getMethod(method)?.invoke(null) }.getOrNull()

    private fun sanitize(encoded: String): String = encoded.replace(SEP, "|").take(200)

    private val storageSource by lazy {
        runCatching { net.minecraft.server.MinecraftServer::class.java.getDeclaredField("storageSource") }
            .onSuccess { it.isAccessible = true }
            .getOrNull()
    }
}
