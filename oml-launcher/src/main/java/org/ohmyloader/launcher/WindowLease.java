package org.ohmyloader.launcher;

/**
 * The OS window lease the supervisor can keep across sessions.
 *
 * <p>26.3 uses SDL, not GLFW: {@code Window} owns a {@code long} SDL window handle, and the GPU
 * surface is created separately by {@code GpuDevice.createSurface(long)}. The first session records
 * the handle; a reboot marks the lease shared before the old session closes, so the old
 * {@code Window.close}/SDL_Quit are skipped; the next session adopts the handle, then clears the
 * shared flag so a normal exit destroys the window and quits SDL as usual.
 *
 * <p>The lease is <b>opt-in</b> ({@code -Doml.window.lease=on}) because 26.3's graphics-device
 * shutdown still invalidates the native window even when the game's close paths are skipped. The
 * default reboot closes the old window normally and the next session creates a fresh one; the
 * adoption path stays here for the follow-up that preserves the handle through device teardown.
 *
 * <p>The class lives in the supervisor loader and is parent-first to the session, so the game hook
 * and the supervisor see one static lease.
 */
public final class WindowLease {

    private static volatile long handle;
    private static volatile String backend = "";
    private static volatile boolean shared;

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
    }

    /** Records without a backend name (tests and legacy callers). */
    public static void record(long windowHandle) {
        record(windowHandle, "");
    }

    /** True between "reboot requested" and the next session's adoption of the handle. */
    public static boolean shared() {
        return shared;
    }

    /**
     * The lease is opt-in: 26.3's teardown still invalidates the SDL window even when the game's
     * window, surface, backend-unload and SDL_Quit paths are skipped (the graphics device shutdown
     * destroys the native window), so the default reboot recreates the window. Set
     * {@code -Doml.window.lease=on} to exercise the adoption path while that is being solved.
     */
    public static boolean enabled() {
        return "on".equalsIgnoreCase(System.getProperty("oml.window.lease", "off"));
    }

    /**
     * Called by the reboot pipeline before the old session closes.
     *
     * <p>26.3's OpenGL backend creates a hidden utility window during device creation, before
     * {@code Window.createWindow} can adopt anything; a leased window cannot be reused there (the
     * new session's GL device never reaches the adoption hook). The Vulkan backend creates its
     * device without a window, so the next session can adopt the handle and build a fresh surface.
     * Anything that cannot be reused safely falls back to the normal close/create path.
     */
    public static void beginShare() {
        shared = enabled() && backend.contains("VulkanBackend");
    }

    /** Called by the adopting session once it has taken the handle. */
    public static void finishShare() {
        shared = false;
    }
}
