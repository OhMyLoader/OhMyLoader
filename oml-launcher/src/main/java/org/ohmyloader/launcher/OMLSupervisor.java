package org.ohmyloader.launcher;

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
     * signal, so the loop drains and returns exactly as the pre-reboot launch did.
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
                drainSession();
                return;
            }
            String reason = SessionControl.reason();
            System.out.println("[OMLSupervisor] reboot requested: " + reason);
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
            session = startSession(args, sessionJars);
            activeSession = session;
            System.out.println("[OMLSupervisor] rebooted session started");
        }
    }

    /**
     * The real reboot teardown boundary. The game must already have stopped; this closes the
     * session's jar handles, drops the supervisor reference and runs the canary.
     */
    static boolean stopSession() {
        Session session = activeSession;
        if (session == null) return true;
        activeSession = null;
        return session.close();
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

    private OMLSupervisor() {}
}
