package org.ohmyloader.launcher;

import java.lang.ref.WeakReference;

/**
 * The session-to-supervisor reboot signal.
 *
 * <p>A session (through {@code OMLReboot}) marks the request and begins the window share
 * <b>before</b> the game closes, so the old {@code Window.close}/SDL_Quit skip the leased window.
 * The supervisor consumes it when the game main returns. The class lives in the persistent
 * supervisor loader, so the session and the supervisor see one flag.
 */
public final class SessionControl {

    private static volatile boolean requested;
    private static volatile String reason = "User requested";
    // Weak on purpose: the canary must be able to observe collection, and the main loader is a
    // child of the session loader the supervisor already tracks. The session registers it after
    // creating it, because the supervisor constructs the session before OMLCore runs.
    private static volatile WeakReference<ClassLoader> mainLoader = new WeakReference<>(null);

    /** Called from the session (through reflection from OMLReboot). */
    public static void requestReboot(String why) {
        reason = why == null || why.isBlank() ? "User requested" : why;
        // The auto-reboot test switch is one-shot: the next session must not request another one.
        System.clearProperty("oml.reboot.afterTicks");
        WindowLease.beginShare();
        requested = true;
    }

    static boolean consumeReboot() {
        boolean current = requested;
        requested = false;
        return current;
    }

    /**
     * Read-only probe used by the session before it returns to the supervisor. The game starts a
     * shutdown watchdog after its main returns; in-process reboot must disarm it instead of letting
     * it kill the process the next session is using.
     */
    public static boolean rebootRequested() {
        return requested;
    }

    /** Called by OMLCore once its game/mod loader exists, so the canary can see both layers. */
    public static void registerMainLoader(ClassLoader loader) {
        mainLoader = new WeakReference<>(loader);
    }

    static ClassLoader mainLoader() {
        return mainLoader.get();
    }

    static String reason() {
        return reason;
    }

    private SessionControl() {}
}
