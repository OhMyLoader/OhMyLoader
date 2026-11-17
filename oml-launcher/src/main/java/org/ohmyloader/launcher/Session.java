package org.ohmyloader.launcher;

import java.io.IOException;
import java.lang.ref.WeakReference;

/**
 * One Supervisor-owned session: the disposable classloader graph plus the probe that decides
 * whether it can actually be released.
 *
 * <p>The session keeps the only supervisor-side strong reference to the loader. {@link #drain()}
 * drops that reference before asking the canary, so the probe measures what the game/core left
 * behind, not the supervisor itself. {@link #retire()} is the real reboot boundary: the game must
 * already have stopped, otherwise closing its jar handles would break its remaining resource reads.
 * It returns only a {@link WeakReference} so the caller can start the GC probe after every frame
 * that named the old loader has returned.
 */
final class Session {

    private SessionClassLoader loader;
    private final SessionCanary canary;
    private boolean drained;
    private boolean collected;

    Session(SessionClassLoader loader, ClassLoader supervisor) {
        this.loader = loader;
        this.canary = new SessionCanary(loader, supervisor);
    }

    /**
     * Drops this session's strong reference and runs the canary. Idempotent; the first verdict is
     * kept. Safe to call from a JVM shutdown hook, where the game may still be winding down.
     */
    synchronized boolean drain() {
        return drain(false);
    }

    /**
     * @param reboot true only for the in-process swap: old shutdown hooks and other session-scoped
     *               registrations must be removed. The final process shutdown keeps them because
     *               the test verdict and the game's own hooks still have to run.
     */
    synchronized boolean drain(boolean reboot) {
        if (drained) return collected;
        drained = true;
        loader = null;
        // Diagnostic switch: skip the GC probe when a JVM-level crash is being narrowed down.
        collected = "off".equalsIgnoreCase(System.getProperty("oml.canary", "on")) ||
            canary.awaitCollected(5_000, reboot);
        return collected;
    }

    /**
     * Full teardown for an in-process reboot: close the session's jar handles and drop the last
     * strong loader reference. The game must have stopped before this is called.
     *
     * <p>Returns the canary's weak probe instead of running it here: this frame (and the
     * {@code closeLoader} frame inside it) may still hold an oop in a stack slot or register, and
     * an inlined caller can keep it live for the whole GC wait. The caller must discard this
     * session and run {@link SessionCanary#awaitCollected(WeakReference, long, boolean, ClassLoader)}
     * afterwards.
     */
    synchronized WeakReference<ClassLoader> retire() {
        closeLoader();
        loader = null;
        drained = true;
        return canary.probe();
    }

    private void closeLoader() {
        SessionClassLoader current = loader;
        if (current == null) return;
        try {
            current.close();
        } catch (IOException ignored) {
            // The canary's reachability verdict is the signal; a jar handle that refuses to close
            // must not turn a reboot into a process exit.
        }
        // Drop the local before close() enters the GC probe; an inlined closeLoader can otherwise
        // leave this oop live in a stack slot for the whole wait loop.
        current = null;
    }
}
