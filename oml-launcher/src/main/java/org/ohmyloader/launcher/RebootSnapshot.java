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

    /** The most recent capture, consumed or not. */
    public static String lastCaptured() {
        return lastCaptured;
    }

    /** Drops both the pending line and the diagnostic copy (tests, and a reboot that was cancelled). */
    public static void clear() {
        pending = null;
        lastCaptured = null;
    }
}
