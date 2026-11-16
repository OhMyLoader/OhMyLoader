package org.ohmyloader.launcher;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.net.URL;

/**
 * The persistent half of the Supervisor/Session split.
 *
 * <p>This class is loaded by the supervisor loader that {@link OMLBootstrap} creates once for the
 * process. That loader stays alive across reboots: the window lease, the L1 transform cache and the
 * reboot signal are statics on classes it defines, and a session swap must not recreate them.
 *
 * <p>A session is a child {@link SessionClassLoader} carrying every OML module (core/api/content,
 * adapters) and, below it, the game loader and mods. The session loader is disposable; only the
 * supervisor loader and this class survive.
 */
public final class OMLSupervisor {

    /** The live session held for the reboot boundary; the only supervisor-side strong loader ref. */
    private static volatile Session activeSession;

    /**
     * Starts the first session and then cycles while {@link SessionControl} reports a reboot.
     * A dedicated server's main returns as soon as its server thread is running; it has no reboot
     * signal, so the loop returns without draining — the session ends with the process, and the
     * core's shutdown hook is what tears it down.
     */
    public static void run(String[] args, URL[] sessionJars) throws Exception {
        Session session = startSession(args, sessionJars);
        activeSession = session;
        System.out.println("[OMLSupervisor] session started");
        if (Boolean.getBoolean("oml.canary")) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (!drainSession()) {
                    System.err.println(
                        "[OMLSupervisor] session loader is still reachable; a real reboot must fall back to a process restart");
                }
            }, "oml-session-canary"));
        }

        while (true) {
            if (!SessionControl.consumeReboot()) {
                // On a dedicated server this return is not the end of the session: the server thread
                // keeps running and the core's shutdown hook owns the teardown. Draining here would
                // run the canary against a session the game is still using.
                if (serverSide()) return;
                drainSession();
                return;
            }
            String reason = SessionControl.reason();
            System.out.println("[OMLSupervisor] reboot requested: " + reason);
            // The transition is already on the window — the retiring session put it up and its last
            // presented frame is the one the leased window keeps. These two milestones are the only
            // thing this layer contributes to it, and they are what makes the bar move while no
            // session exists at all.
            OMLRebootOverlay.milestone("Collecting the old session", OMLRebootOverlay.REBUILDING);
            // Drop the local before the probe: this frame stays on the stack (blocked in join)
            // for the whole GC wait, so any reference left in its slot is a live root.
            session = null;
            boolean collected = stopSession();
            if (!collected) {
                // A leaked session must not be papered over by starting another one: the old
                // loader, its native callbacks and its class metadata would all survive. Refuse
                // the in-process swap; the launcher/process owner has to restart the process.
                System.err.println(
                    "[OMLSupervisor] the old session is still reachable after close; refusing the in-process reboot");
                throw new IllegalStateException(
                    "session classloader was not collected within 5s; process restart is required");
            }
            session = startSession(nextSessionArgs(args), sessionJars);
            activeSession = session;
            // The next session's first frame takes the bar from here; the milestone is set before
            // the session is even given control so the bar never stalls between the two phases.
            OMLRebootOverlay.milestone("Starting the next session", OMLRebootOverlay.RELOADING);
            System.out.println("[OMLSupervisor] rebooted session started");
        }
    }

    /**
     * The launch arguments the session after a reboot gets.
     *
     * <p>Only one thing is ever removed, and only when the retiring session left a snapshot: the
     * quick-play switches. They exist to put the player into a world from the command line, which is
     * precisely what the snapshot now does by name — leaving them in would race two world opens, and
     * the gate would no longer be able to tell which one loaded the world. Everything else (the game
     * directory, the asset index, the access token) is passed through untouched, so the second
     * session starts exactly like the first.
     */
    static String[] nextSessionArgs(String[] args) {
        if (!RebootSnapshot.pending()) return args;
        java.util.List<String> kept = new java.util.ArrayList<>(args.length);
        boolean dropped = false;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("--quickPlaySingleplayer")
                || arg.equals("--quickPlayMultiplayer")
                || arg.equals("--quickPlayRealms")) {
                // The value is the next argument; dropping it with the switch keeps the rest aligned.
                i++;
                dropped = true;
                continue;
            }
            if (arg.startsWith("--quickPlaySingleplayer=")
                || arg.startsWith("--quickPlayMultiplayer=")
                || arg.startsWith("--quickPlayRealms=")) {
                dropped = true;
                continue;
            }
            kept.add(arg);
        }
        if (dropped) {
            System.out.println(
                "[OMLSupervisor] dropped the quick-play arguments: the snapshot names the save to reopen");
        }
        return kept.toArray(new String[0]);
    }

    /**
     * The real reboot teardown boundary. The game must already have stopped; this closes the
     * session's jar handles, drops the supervisor reference and runs the canary.
     */
    static boolean stopSession() {
        Session retiring = activeSession;
        activeSession = null;
        if (retiring == null) return true;
        // Retire first (close jars, drop the loader) and keep only the weak probe in this frame.
        // The GC wait then runs on a fresh thread, so neither this frame nor a C2 register that
        // once held the loader can pin it across System.gc().
        WeakReference<ClassLoader> probe = retiring.retire();
        retiring = null;
        // LWJGL lives on this loader, not the session's, so its static bindings survive the swap and
        // the next session inherits entry points that belong to a context that no longer exists.
        // Clear them while the retiring session is provably dead and before anything is collected.
        LwjglStatics.reset(OMLSupervisor.class.getClassLoader());
        return SessionCanary.awaitCollected(probe, 5_000, true, OMLSupervisor.class.getClassLoader());
    }

    /** Shutdown observation: drop the reference and probe, but leave jar handles to process exit. */
    static boolean drainSession() {
        Session session = activeSession;
        if (session == null) return true;
        activeSession = null;
        return session.drain(false);
    }

    /**
     * Starts one session and returns its handle. Its parent is this class's loader — the persistent
     * supervisor — never a per-session loader.
     */
    private static Session startSession(String[] args, URL[] sessionJars) throws Exception {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        ClassLoader supervisor = OMLSupervisor.class.getClassLoader();
        SessionClassLoader sessionLoader = new SessionClassLoader(sessionJars, supervisor);
        Session session = new Session(sessionLoader, supervisor);
        Thread.currentThread().setContextClassLoader(sessionLoader);
        try {
            Class<?> core = Class.forName("org.ohmyloader.core.OMLCore", true, sessionLoader);
            Method start = core.getMethod("start", String[].class);
            start.invoke(null, (Object) args);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
        return session;
    }

    /** `oml.side=server` — the dedicated-server launch path; see {@code OMLCore.start}. */
    private static boolean serverSide() {
        return "server".equalsIgnoreCase(System.getProperty("oml.side", "client"));
    }

    private OMLSupervisor() {}
}
