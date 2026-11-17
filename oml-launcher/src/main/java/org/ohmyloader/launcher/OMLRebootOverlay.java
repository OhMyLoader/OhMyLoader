package org.ohmyloader.launcher;

/**
 * The one transition screen a soft reboot puts on the window, and the only thing that decides what
 * it says.
 *
 * <p>A reboot crosses a boundary no session can see across: the session that starts the swap is
 * collected before the one that finishes it exists, and the pixels in between belong to whoever
 * renders last. The fix is to make that boundary irrelevant — the screen a player sees is not drawn
 * in three instalments by three different layers, it is drawn by <b>one</b> routine reading
 * <b>one</b> state object. This class is that state object.
 *
 * <p>It lives in the supervisor loader on purpose (parent-first, defined once for the life of the
 * process) so both sessions and the in-between code read the *same* instance, not a copy each. In
 * this direction lies the reason it holds nothing but primitives, a {@link String} and a
 * {@code long}: a reference to a {@code Minecraft}, a {@code Screen} or anything else the session
 * loader defines would pin the retiring session's loader alive, and the canary gate would fail for
 * exactly the reason it exists.
 *
 * <p>Progress is a ceiling, not a value: {@link #milestone} can only move it forward, so a late
 * report from a session being torn down can never drag the bar backwards. What is presented is
 * {@link #progress()}, which chases the ceiling on wall-clock time — that is what makes the bar
 * advance smoothly instead of jumping a milestone at a time, and it keeps advancing while nothing
 * draws at all.
 */
public final class OMLRebootOverlay {

    // The milestones, in the order a reboot reaches them. The numbers are the contract the sessions
    // and the supervisor share: each one is the ceiling its phase may fill up to, and no phase is
    // allowed to write past the one after it.
    /** Session 1: the snapshot is taken and the world is being saved and closed. */
    public static final float SAVING = 0.00f;
    /** The window is handed over; the supervisor is collecting the old session. */
    public static final float REBUILDING = 0.15f;
    /** The sandbox for the next session is up and the loader is initialising. */
    public static final float RELOADING = 0.35f;
    /** Session 2: resources are being reloaded. */
    public static final float RESOURCES = 0.55f;
    /** Session 2: the save is open and the chunks are streaming in. */
    public static final float LOADING = 0.75f;
    /** Session 2: the player is in the world and being put back where they were. */
    public static final float RESTORED = 0.92f;
    /** Everything is back; the curtain starts to lift. */
    public static final float DONE = 1.00f;

    /** Time the presented bar takes to cover most of the distance to its ceiling. */
    private static final double TAU_SECONDS = 0.45;

    /**
     * Movement the bar keeps even when the ceiling is out of reach for a while, as a fraction of the
     * whole per second. A reboot that stalls in one phase should look slow, not dead.
     */
    private static final float CREEP_PER_SECOND = 0.012f;

    /** How long the curtain takes to fade out once the reboot is complete. */
    private static final double FADE_SECONDS = 0.45;

    /**
     * How fast the last stretch of the bar fills, as a fraction of the whole per second. Fixed rate,
     * not a chase, so "100%" is a state the bar actually reaches instead of one it approaches.
     */
    private static final double FILL_PER_SECOND = 1.2;

    /** Set by session 1 when the swap begins; cleared when the fade finishes. */
    private static volatile boolean active;

    /** The furthest point any phase has reported. Never moves backwards. */
    private static volatile float ceiling;

    /** What is currently presented, 0..1. Chases {@link #ceiling} on wall-clock time. */
    private static volatile float shown;

    /** Opacity of the whole screen, 1 down to 0 during the fade. */
    private static volatile float alpha = 1f;

    /** The phase the player is reading off the bar. */
    private static volatile String label = "";

    /** When {@link #shown} was last advanced, so a gap of any length costs one step, not a stall. */
    private static volatile long pumpedAt;

    /** When the fade started, or 0 while the curtain is still opaque. */
    private static volatile long fadeStartedAt;

    private OMLRebootOverlay() {}

    // ------------------------------------------------------------------ producer side

    /**
     * Session 1, at the reboot request: put the curtain up.
     *
     * <p>Calling this is what makes the transition exist at all; every other writer only advances a
     * bar that is already on screen. Idempotent, because a reboot request that is re-entered (a key
     * held down, a scheduler that fires twice) must not restart the bar.
     */
    public static synchronized void begin(String phase) {
        if (active) {
            milestone(phase, ceiling);
            return;
        }
        active = true;
        ceiling = SAVING;
        shown = SAVING;
        alpha = 1f;
        label = phase == null ? "" : phase;
        fadeStartedAt = 0L;
        pumpedAt = System.nanoTime();
    }

    /**
     * Raises the ceiling, and with it the phase text.
     *
     * <p>Forward-only by construction: a phase that reports late — a session finishing its close
     * while the supervisor has already moved on — is silently ignored instead of rewinding the bar
     * the player is watching.
     */
    public static synchronized void milestone(String phase, float progress) {
        if (!active) return;
        if (phase != null && !phase.isEmpty()) label = phase;
        if (progress > ceiling) {
            // A new ceiling is a new stretch of bar; restart the clock so the step is measured from
            // here rather than from whenever the last phase happened to report.
            pumpLocked();
            ceiling = Math.min(1f, progress);
        }
    }

    /**
     * The reboot is complete: fill the rest of the bar and start the fade.
     *
     * <p>Separate from {@link #milestone} because reaching the end is a decision — "the player can
     * see their world again" — not a measurement, and it is the only transition that clears
     * {@link #active}.
     */
    public static synchronized void finish() {
        if (!active) return;
        pumpLocked();
        ceiling = DONE;
    }

    /** Drops the curtain without a fade (a cancelled reboot, tests). */
    public static synchronized void reset() {
        active = false;
        ceiling = 0f;
        shown = 0f;
        alpha = 1f;
        label = "";
        fadeStartedAt = 0L;
    }

    // ------------------------------------------------------------------ consumer side

    /** Whether the curtain is on screen this frame. */
    public static synchronized boolean active() {
        pumpLocked();
        return active;
    }

    /** The bar as presented, 0..1. */
    public static synchronized float progress() {
        pumpLocked();
        return shown;
    }

    /** 1 while opaque, falling to 0 through the fade. */
    public static synchronized float alpha() {
        pumpLocked();
        return alpha;
    }

    /** The phase text, e.g. {@code "Rebuilding sandbox"}. */
    public static String label() {
        return label;
    }

    /** The ceiling, for the gate: what the reboot claims it has reached. */
    public static float target() {
        return ceiling;
    }

    /**
     * One line of state for the reboot gate.
     *
     * <p>Read from the supervisor loader, so it is the same line both sessions and the in-between
     * code would produce — the gate can compare a session's claim against the resident truth.
     */
    public static synchronized String trace() {
        pumpLocked();
        return "active=" + active
            + " shown=" + String.format(java.util.Locale.ROOT, "%.3f", shown)
            + " target=" + String.format(java.util.Locale.ROOT, "%.3f", ceiling)
            + " alpha=" + String.format(java.util.Locale.ROOT, "%.2f", alpha)
            + " label=" + label;
    }

    // ------------------------------------------------------------------ clock

    /**
     * Advances the presented bar to wherever the clock says it should be.
     *
     * <p>Time-based rather than frame-based, and that is the point: during the swap nothing draws for
     * however long the old session takes to be collected, and a frame-based integrator would resume
     * from a stale value as if no time had passed. Here the first frame of the next session lands
     * exactly where the elapsed time puts it.
     *
     * <p>The step is capped at one {@link #TAU_SECONDS} so a multi-second pause does not teleport the
     * bar; the creep keeps it moving even when the ceiling is far away.
     */
    private static void pumpLocked() {
        if (!active) return;
        long now = System.nanoTime();
        long elapsed = now - pumpedAt;
        if (elapsed <= 0L) return;
        pumpedAt = now;

        if (fadeStartedAt != 0L) {
            double faded = (now - fadeStartedAt) / 1e9;
            float rest = (float) (1.0 - faded / FADE_SECONDS);
            if (rest <= 0f) {
                alpha = 0f;
                active = false;
                shown = DONE;
                return;
            }
            alpha = Math.min(1f, rest);
            shown = DONE;
            return;
        }

        double seconds = elapsed / 1e9;
        // One step per TAU at most: an unbounded step would make the bar snap after a long gap.
        double step = Math.min(seconds, TAU_SECONDS);

        if (ceiling >= DONE) {
            // Reaching the end is not a chase. An exponential approach never actually arrives — the
            // first version stopped the bar at 99.5% and the fade never started, which the player
            // sees as a permanently stuck transition. So the last stretch is filled at a fixed rate
            // and the curtain lifts the moment it is full.
            shown = Math.min(1f, shown + (float) (FILL_PER_SECOND * step));
            if (shown >= 0.9995f) {
                shown = 1f;
                fadeStartedAt = now;
                alpha = 1f;
            }
            return;
        }

        float distance = ceiling - shown;
        if (distance > 0f) {
            double chase = 1.0 - Math.exp(-step / TAU_SECONDS);
            float moved = shown + distance * (float) chase + (float) (CREEP_PER_SECOND * step);
            shown = Math.min(ceiling, moved);
        }
    }
}
