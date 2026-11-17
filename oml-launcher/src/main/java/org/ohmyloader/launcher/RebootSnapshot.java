package org.ohmyloader.launcher;

/**
 * The player/world snapshot a reboot carries into the next session.
 *
 * <p>This is the <b>only</b> thing the two sessions exchange about where the player was, and it is
 * deliberately a {@link String}: the supervisor loader defines this class, the session loader is
 * discarded at every swap, and anything richer than text would be a handle into the dying session.
 * A {@code ServerPlayer}, a {@code ClientLevel}, an {@code Identifier} — each of those is defined by
 * the session's own loader, so keeping one here would pin the whole session alive and make the
 * canary fail exactly as a leaked class loader does. Text has no loader.
 *
 * <p>The encoding lives with the producer ({@code org.ohmyloader.api26_3.RebootRestore}), which is
 * the side that knows the game types; this class only holds the line and hands it over once.
 */
public final class RebootSnapshot {

    /** The line the next session has not consumed yet, or null. */
    private static volatile String pending;

    /** The last line any session produced, for diagnostics after the handoff. */
    private static volatile String lastCaptured;

    /**
     * A reboot requested from the main menu: there is no world to describe, so nothing is encoded
     * and [pending] stays null — but the next session must still know that the transition it finds
     * is a reboot's, not a leftover, and fade it instead of dropping it. Kept beside the line so a
     * title reboot never looks like a handover (the gate's "did a snapshot cross?" stays false).
     */
    private static volatile boolean titlePending;

    /**
     * A screen the next session should open once its transition is lifted ({@code mods} today).
     * Kept as plain text for the same reason as the snapshot: whoever reads it must not need a
     * session class to understand it.
     */
    private static volatile String screenPending;

    private RebootSnapshot() {}

    /** Session 1: hand the encoded snapshot to whichever session starts next. */
    public static void put(String encoded) {
        if (encoded == null || encoded.isEmpty()) return;
        pending = encoded;
        lastCaptured = encoded;
    }

    /** Session 2: look without consuming, so a retry inside the same session still sees it. */
    public static String peek() {
        return pending;
    }

    /** Session 2: consume the snapshot; a second session must not restore the same line twice. */
    public static String take() {
        String encoded = pending;
        pending = null;
        return encoded;
    }

    /** Whether a session left a snapshot for the next one. */
    public static boolean pending() {
        return pending != null;
    }

    /** Session 1, main menu: the transition crosses, the world state does not. */
    public static void putTitle() {
        titlePending = true;
    }

    /** Session 2: consume the main-menu marker; false for a world handover or a plain launch. */
    public static boolean takeTitle() {
        boolean current = titlePending;
        titlePending = false;
        return current;
    }

    /** Look without consuming (diagnostics and the gate). */
    public static boolean titlePending() {
        return titlePending;
    }

    /** Session 1: the next session should reopen this screen after the transition lifts. */
    public static void putScreen(String screen) {
        screenPending = (screen == null || screen.isEmpty()) ? null : screen;
    }

    /** Session 2: consume the screen marker; null when the reboot did not ask for one. */
    public static String takeScreen() {
        String current = screenPending;
        screenPending = null;
        return current;
    }

    /** Peek: the process-restart fallback copies it into the child's command line. */
    public static String peekScreen() {
        return screenPending;
    }

    /** The most recent capture, consumed or not. */
    public static String lastCaptured() {
        return lastCaptured;
    }

    /** Drops both the pending line and the diagnostic copy (tests, and a reboot that was cancelled). */
    public static void clear() {
        pending = null;
        lastCaptured = null;
        titlePending = false;
        screenPending = null;
    }
}
