package org.ohmyloader.launcher;

/**
 * The OS window lease the supervisor can keep across sessions.
 *
 * <p>The tracked game uses SDL, not GLFW: {@code Window} owns a {@code long} SDL handle and the GPU
 * surface is created separately ({@code GpuDevice.createSurface(long)}). The first session records
 * the handle; a reboot marks the lease shared before the old session closes, so the old
 * {@code Window.close}/SDL_Quit are skipped; the next session adopts the handle, then clears the
 * shared flag so a normal exit destroys the window and quits SDL as usual.
 *
 * <p>The lease is <b>on by default</b>: the handle survives as long as the retiring session
 * releases its GPU surface and the renderer's context attributes are restored before the window is
 * adopted, and the reboot gate exercises that path on every reboot. {@code -Doml.window.lease=off} is
 * the escape hatch — the older behaviour, where the retiring session closes its window and the next
 * one builds a fresh one. The class is parent-first to the session, so the game hook and the
 * supervisor see one static lease.
 */
public final class WindowLease {

    private static volatile long handle;
    private static volatile String backend = "";
    private static volatile boolean shared;
    /** Whether the current session took the previous session's handle instead of creating one. */
    private static volatile boolean adopted;
    /** Whether the retiring session released its GPU surface before handing the window over. */
    private static volatile boolean surfaceClosed;

    private WindowLease() {}

    /** The recorded SDL window handle, or 0 before the first session created a window. */
    public static long handle() {
        return handle;
    }

    /** The GPU backend class that created the leased window; a different backend cannot reuse it. */
    public static String backend() {
        return backend;
    }

    public static boolean hasHandle() {
        return handle != 0L;
    }

    /** Records the handle created by the first session; 0 means the game failed to create one. */
    public static void record(long windowHandle, String backendName) {
        if (windowHandle == 0L) return;
        handle = windowHandle;
        backend = backendName == null ? "" : backendName;
        adopted = false;
    }

    /** Records without a backend name (tests and legacy callers). */
    public static void record(long windowHandle) {
        record(windowHandle, "");
    }

    /** The adopting session took the recorded handle; false means it created a new window. */
    public static void markAdopted() {
        adopted = true;
    }

    public static boolean adopted() {
        return adopted;
    }

    /**
     * Records that the retiring session released its GPU surface. The fact has to live here rather
     * than in the hook: the session that closes the surface is gone by the time the next one can be
     * asked whether the transition was clean, and this is the only layer both sessions share.
     */
    public static void recordSurfaceClose(boolean ok) {
        surfaceClosed = ok;
    }

    public static boolean surfaceClosed() {
        return surfaceClosed;
    }

    /** True between "reboot requested" and the next session's adoption of the handle. */
    public static boolean shared() {
        return shared;
    }

    /**
     * On unless {@code -Doml.window.lease=off}: keeping one OS window across the swap is what makes
     * the reboot look like a scene change instead of a relaunch, and the gate covers it. Anything
     * other than an explicit {@code off} keeps the adoption path live, so a typo cannot silently
     * turn a feature off.
     */
    public static boolean enabled() {
        return !"off".equalsIgnoreCase(System.getProperty("oml.window.lease", "on"));
    }

    /**
     * Called by the reboot pipeline before the old session closes.
     *
     * <p>Whether a handle can be reused is decided by whether SDL still knows it — not by a
     * hard-coded backend name — so the adoption probe checks that and falls back to creating a
     * window otherwise.
     */
    public static void beginShare() {
        shared = enabled() && hasHandle();
    }

    /** Called by the adopting session once it has taken the handle. */
    public static void finishShare() {
        shared = false;
    }
}
