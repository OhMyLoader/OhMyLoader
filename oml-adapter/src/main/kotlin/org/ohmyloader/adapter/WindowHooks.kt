package org.ohmyloader.adapter

import com.mojang.renderpearl.api.device.GpuBackend
import org.ohmyloader.api.OmlLog
import org.ohmyloader.api.hook.Operation
import org.ohmyloader.launcher.WindowLease

/**
 * SDL window handoff for the supervisor's in-process reboot.
 *
 * <p>The first session records the handle `GpuBackend.createWindow` returned. A reboot marks the
 * lease shared; the old `Window.close` and `Minecraft`'s `SDL_Quit` are skipped, the next session's
 * `createWindow` returns the recorded handle and clears the flag, and its own normal close destroys
 * the window and quits SDL. The GPU surface is not shared: the new `GpuDevice` creates a fresh
 * surface with `createSurface(handle)`.
 */
object WindowHooks {

    @JvmStatic
    fun createWindow(
        backend: GpuBackend?,
        width: Int,
        height: Int,
        title: String,
        original: Operation<Long>,
        id: Int,
    ): Long {
        val backendName = backend?.javaClass?.name ?: "unknown"
        trace(
            "createWindow enter backend=$backendName asked=${width}x$height ${leaseState()}",
        )
        if (WindowLease.shared() && WindowLease.hasHandle()) {
            val leased = WindowLease.handle()
            val sameBackend = WindowLease.backend() == backendName
            // A window carries the flags of the backend that created it, and SDL stops knowing a
            // handle once something destroys it. The second condition is measurable, so decide on
            // it instead of assuming a backend cannot reuse a window.
            if (sameBackend && windowAlive(leased)) {
                // SDL applies the GL attributes when the *context* is created, not when the window
                // is. An adopted window therefore needs them restored here: the next session's
                // device init is what calls SDL_GL_CreateContext, and without this it comes up as
                // the SDL default (2.1 compatibility) while the renderer is built for 3.3 core —
                // every direct-state-access entry point is then a null pointer and the first
                // submission dies with an access violation and no Java frame.
                if (!glAttributeApplicator()) {
                    OmlLog.warn("Window", "could not restore the GL attributes; recreating the SDL window")
                    runCatching { destroySdlWindow(leased) }
                    WindowLease.finishShare()
                    return createFresh(backend, width, height, title, backendName, original)
                }
                WindowLease.finishShare()
                WindowLease.markAdopted()
                // SDL hands out a fresh id per window, and the cache is one session old by now.
                mainWindowIdCache = -1
                OmlLog.info("Window", "adopted SDL window handle=$leased backend=$backendName")
                trace("createWindow adopted handle=$leased after=${leaseState()}")
                return leased
            }
            val why = if (sameBackend) {
                "leased handle=$leased is gone (SDL no longer knows it)"
            } else {
                "backend changed (${WindowLease.backend()} -> $backendName)"
            }
            OmlLog.info("Window", "$why; recreating the SDL window")
            trace("createWindow cannot adopt: $why")
            if (sameBackend) destroySdlWindow(leased)
            WindowLease.finishShare()
        }
        return createFresh(backend, width, height, title, backendName, original)
    }

    private fun createFresh(
        backend: GpuBackend?,
        width: Int,
        height: Int,
        title: String,
        backendName: String,
        original: Operation<Long>,
    ): Long {
        val handle = original.call(backend, width, height, title)
        WindowLease.record(handle, backendName)
        mainWindowIdCache = -1
        OmlLog.info("Window", "recorded SDL window handle=$handle backend=$backendName")
        trace("createWindow created handle=$handle ${leaseState()}")
        return handle
    }

    /**
     * The GL attributes `GlBackend.createWindow` sets immediately before `SDL_CreateWindow`.
     *
     * <p>SDL reads them when the context is created, so they belong to the *next* context rather
     * than to the window that already exists. Values read off the backend's own bytecode:
     * major=3, minor=3, profile=CORE, flags=FORWARD_COMPATIBLE, double buffered.
     */
    private val glContextAttributes = arrayOf(
        17 to 3, // SDL_GL_CONTEXT_MAJOR_VERSION
        18 to 3, // SDL_GL_CONTEXT_MINOR_VERSION
        20 to 1, // SDL_GL_CONTEXT_PROFILE_MASK = SDL_GL_CONTEXT_PROFILE_CORE
        19 to 2, // SDL_GL_CONTEXT_FLAGS = SDL_GL_CONTEXT_FORWARD_COMPATIBLE_FLAG
        22 to 1, // SDL_GL_DOUBLEBUFFER
    )

    private val glSetAttribute by lazy {
        runCatching {
            Class.forName("org.lwjgl.sdl.SDLVideo", false, WindowHooks::class.java.classLoader)
                .getMethod("SDL_GL_SetAttribute", Integer.TYPE, Integer.TYPE)
        }.getOrNull()
    }

    /**
     * Seam over the attribute restore: a JVM with no SDL video classes cannot perform it at all, and
     * the unit tests need the adoption verdict without a window server.
     */
    internal var glAttributeApplicator: () -> Boolean = { applyGlContextAttributes() }

    /** True only when every attribute was accepted; a partial restore is not worth adopting on. */
    private fun applyGlContextAttributes(): Boolean {
        val setter = glSetAttribute ?: return false
        return runCatching {
            for ((name, value) in glContextAttributes) {
                val ok = setter.invoke(null, name, value) as? Boolean ?: false
                if (!ok) return false
            }
            true
        }.getOrDefault(false)
    }

    /**
     * Replacement for the `GpuSurface.close()` call in `Minecraft.close`.
     *
     * <p>The surface is always closed, lease or not. The earlier skip was justified by "the Vulkan
     * surface owns the SDL window", and that is not what the jar does:
     *
     * <ul>
     *   <li>`FrontendGpuSurface.close()` only forwards to its `GpuSurfaceBackend`.
     *   <li>OpenGL's backend is `GlSurface`, whose `close()` is literally `return` — the GL device
     *       keeps its own hidden utility window and GL context, and `Window` owns the main window,
     *       so a GL surface holds no native resource to release at all.
     *   <li>`VulkanGpuSurface.close()` runs `destroySwapchain()`, destroys the per-image acquire
     *       semaphores and then `vkDestroySurfaceKHR(surface)`. It never calls `SDL_DestroyWindow`.
     * </ul>
     *
     * <p>Skipping it therefore bought nothing and cost a native leak per reboot: the abandoned
     * `VkSwapchainKHR`/`VkSurfaceKHR` still own the window, and the next session's
     * `vkCreateSwapchainKHR` on the same handle fails with `VK_ERROR_NATIVE_WINDOW_IN_USE_KHR`.
     * Closing the surface leaves the `SDL_Window` untouched — measured with `SDL_GetWindowFlags`
     * before and after — so the lease survives and the next session builds a fresh surface on it.
     *
     * <p>Nothing accumulates on the OpenGL side, which is worth stating because the no-op invites
     * the opposite conclusion: the hidden utility window and the GL context belong to `GlDevice`,
     * and `GlDevice.close()` destroys both. `RenderSystem.shutdownRenderer()` calls it on every
     * session's close, so each handover leaves neither behind — three consecutive handovers kept
     * handle, GDI and USER object counts flat.
     */
    @JvmStatic
    fun surfaceClose(surface: com.mojang.renderpearl.api.device.GpuSurface?) {
        val name = surface?.javaClass?.name
        trace("surfaceClose APPLIED surface=$name shared=${WindowLease.shared()} ${leaseState()}")
        // A leased session keeps the process alive after this call, so a pipeline build still in
        // flight on the shader pool would keep calling into a device that is about to be destroyed.
        // That is an access violation in the driver with no Java frame (seen on Vulkan), not an
        // exception the game could handle — the teardown waits for the pool instead.
        if (WindowLease.shared()) drainCompilePool()
        val failure = runCatching { surface?.close() }.exceptionOrNull()
        if (failure != null) {
            // A surface that refuses to close must not turn the normal shutdown into a crash: the
            // window is what the next session needs, and the device teardown follows either way.
            OmlLog.warn("Window", "closing the GPU surface failed: ${failure.javaClass.name}: ${failure.message}")
        }
        // Recorded on the supervisor's lease: the session that closes the surface is gone before the
        // next one can be asked whether the handover was clean.
        WindowLease.recordSurfaceClose(failure == null)
        trace("surfaceClose DONE surface=$name ok=${failure == null} ${leaseState()}")
    }

    /**
     * Waits for the session's shader pool to go idle before anything it is calling into is freed.
     *
     * <p>Bounded rather than open-ended: a pipeline build that never returns must not turn a reboot
     * into a hang, and the log line is what tells a timed-out drain apart from a clean one.
     */
    private fun drainCompilePool() {
        val started = System.nanoTime()
        val idle = ShaderCompileExecutor.awaitQuiescent(COMPILE_DRAIN_MILLIS)
        val elapsed = (System.nanoTime() - started) / 1_000_000
        if (!idle) {
            OmlLog.warn(
                "Shader",
                "shader compile pool still busy after ${elapsed}ms; tearing the GPU down anyway",
            )
        } else if (elapsed > 0) {
            OmlLog.info("Shader", "waited ${elapsed}ms for the shader compile pool before teardown")
        }
    }

    /** Long enough for a pipeline build that is already inside the driver; short enough to fail visibly. */
    private const val COMPILE_DRAIN_MILLIS = 20_000L

    @JvmStatic
    fun close(original: Operation<Void>, id: Int) {
        // The E2E (and any mod) may change the graphics preset after OMLReboot pinned it; pin
        // again here, on the session's last render-thread close, and persist before the next boot.
        pinBackendForNextSession()
        if (WindowLease.shared()) {
            OmlLog.info("Window", "window close skipped for the leased handle")
            trace("Window.close SKIPPED ${leaseState()}")
            return
        }
        trace("Window.close APPLIED ${leaseState()}")
        original.call()
    }

    private fun pinBackendForNextSession() {
        val client = runCatching { net.minecraft.client.Minecraft.getInstance() }.getOrNull() ?: return
        val backend = WindowLease.backend()
        val api = if (backend.contains("VulkanBackend", ignoreCase = true)) {
            net.minecraft.client.PreferredGraphicsApi.VULKAN
        } else {
            net.minecraft.client.PreferredGraphicsApi.OPENGL
        }
        runCatching { client.options.preferredGraphicsBackend().set(api) }
        runCatching { client.options.save() }
    }

    /**
     * Window-geometry events, drained before the game applies them.
     *
     * <p>`Window.handleEvent` switches on the event type only — it never asks which SDL window the
     * event is about. That is harmless while the game's own window is the one that just changed size,
     * because its own `WINDOW_PIXEL_SIZE_CHANGED` arrives last and overwrites whatever came before.
     * It stops being harmless for an adopted window: the GL device creates a hidden 320x480 utility
     * window on every session, its pixel-size event is therefore 320x480, and after adoption the main
     * window is never resized, so no event corrects the cache afterwards. The session then believes
     * its framebuffer is 320 pixels wide, configures the surface at 320x480, and the picture occupies
     * the left 37% of an 854-wide window.
     *
     * <p>So: an event that belongs to a different SDL window is dropped here (its type is cleared, so
     * the original switch falls through). This is the event's own window id against the leased
     * handle's, not a guess about which backend made it.
     */
    @JvmStatic
    fun onWindowEvent(event: Any?, window: Any?) {
        if (!WindowLease.enabled() || event == null) return
        val type = intCall(event, "type") ?: return
        // SDL3 window events live at 0x200..; only the geometry ones can redefine the window.
        if (type < 0x202 || type > 0x213) return
        val windowEvent = runCatching { event.javaClass.getMethod("window").invoke(event) }.getOrNull()
        val eventWindowId = windowEvent?.let { intCall(it, "windowID") }
        val mainId = mainWindowId()
        val data1 = windowEvent?.let { intCall(it, "data1") }
        val data2 = windowEvent?.let { intCall(it, "data2") }
        if (eventWindowId != null && mainId > 0 && eventWindowId != mainId) {
            // Clear the type: SDL_EVENT_NONE makes `Window.handleEvent`'s switch take its default
            // arm, which is exactly "this event is not about my window".
            runCatching {
                event.javaClass.getMethod("type", Integer.TYPE).invoke(event, 0)
            }.onFailure { OmlLog.warn("Lease", "could not drop a foreign window event: ${it.message}") }
            reportForeignWindowEvent(type, eventWindowId, mainId, data1, data2)
            return
        }
        OmlLog.info("Lease", "window event type=$type id=$eventWindowId data1=$data1 data2=$data2")
    }

    /** One line per (type, id) pair: a geometry event can arrive every frame while a window moves. */
    private val reportedForeign = HashSet<String>()

    private fun reportForeignWindowEvent(type: Int, eventId: Int, mainId: Int, data1: Int?, data2: Int?) {
        val key = "$type/$eventId"
        if (!reportedForeign.add(key)) return
        OmlLog.info(
            "Lease",
            "dropped window event type=$type id=$eventId (main window id=$mainId) data=$data1 x $data2",
        )
    }

    /** The leased handle's SDL window id; -1 while there is no window or SDL cannot answer. */
    private fun mainWindowId(): Int {
        val cached = mainWindowIdCache
        if (cached > 0) return cached
        val handle = WindowLease.handle()
        if (handle == 0L) return -1
        val id = runCatching { getWindowId?.invoke(null, handle) as? Int }.getOrNull() ?: return -1
        if (id > 0) mainWindowIdCache = id
        return id
    }

    @Volatile
    private var mainWindowIdCache: Int = -1

    private fun intCall(target: Any, name: String): Int? =
        runCatching { target.javaClass.getMethod(name).invoke(target) as? Int }.getOrNull()


    /** True when the normal Minecraft.close path may quit SDL. */
    @JvmStatic
    fun quitAllowed(): Boolean = !WindowLease.shared()

    /**
     * Replacement for `SDLInit.SDL_Quit()` in `Minecraft.close`: same `()V` shape, so the Raw
     * rewrite changes no stack or frames. Reflection keeps the adapter independent of the LWJGL
     * SDL compile classpath.
     */
    @JvmStatic
    fun quit() {
        if (!quitAllowed()) {
            OmlLog.info("Window", "SDL_Quit skipped for the leased window")
            trace("SDL_Quit SKIPPED ${leaseState()}")
            return
        }
        trace("SDL_Quit APPLIED ${leaseState()}")
        try {
            sdlQuit?.invoke(null)
        } catch (_: Throwable) {
            // The window is already being torn down with the process; a failed native call must
            // not turn the normal close path into a crash.
        }
    }

    /** Replacement for RenderSystem.unloadTrackedBackendLibrary in Minecraft.close. */
    @JvmStatic
    fun unloadBackend() {
        // Unloading the backend frees the OpenGL library the *next* session binds its entry points
        // through, so the handle would be inherited with nothing behind it. On a normal exit the
        // lease is not shared and this delegates exactly as vanilla would.
        if (WindowLease.shared()) {
            OmlLog.info("Window", "backend unload skipped for the leased window")
            trace("unloadBackend SKIPPED ${leaseState()}")
            return
        }
        trace("unloadBackend APPLIED ${leaseState()}")
        try {
            unloadBackendMethod?.invoke(null)
        } catch (_: Throwable) {
            // Best effort on the normal close path.
        }
    }

    /**
     * Whether SDL still knows this handle as a window. `SDL_GetWindowFlags` returns 0 for a handle
     * that was destroyed, which is the same condition that makes later calls report
     * "Invalid window" — so this is the adoption gate, not a guess about the backend.
     */
    private fun windowAlive(handle: Long): Boolean = handle != 0L && aliveProbe(handle)

    /**
     * Seam over the native liveness probe: a JVM without an SDL video subsystem cannot answer it,
     * and the tests need both answers without a window server.
     */
    internal var aliveProbe: (Long) -> Boolean = { handle -> sdlWindowAlive(handle) }

    private fun sdlWindowAlive(handle: Long): Boolean {
        val method = getWindowFlags ?: return true
        val flags = runCatching { method.invoke(null, handle) as? Long }.getOrNull() ?: return true
        return flags != 0L
    }

    /** Lease state for the trace line; cheap, no class loading. */
    private fun leaseState(): String =
        "enabled=${WindowLease.enabled()} shared=${WindowLease.shared()} " +
            "handle=${WindowLease.handle()} backend=${WindowLease.backend()}"

    /**
     * Trace of one hook hit, on the lease path only: an ordinary launch must not grow a log line
     * per close step, and the gates read the recorded facts rather than the console.
     */
    private fun trace(message: String) {
        if (!WindowLease.enabled()) return
        OmlLog.info("Lease", message)
    }

    private fun destroySdlWindow(handle: Long) {
        try {
            sdlDestroy?.invoke(null, handle)
        } catch (_: Throwable) {
            // The backend may already have invalidated it; the new window is what matters.
        }
    }

    // Lazy: these resolve on the close/mismatch path, not while the game is constructing its
    // window. Loading RenderSystem/SDL classes during Window.<init> was observed to stall the
    // render thread before the first world tick.
    private val sdlQuit by lazy {
        runCatching {
            Class.forName("org.lwjgl.sdl.SDLInit", false, WindowHooks::class.java.classLoader)
                .getMethod("SDL_Quit")
        }.getOrNull()
    }

    private val sdlDestroy by lazy {
        runCatching {
            Class.forName("org.lwjgl.sdl.SDLVideo", false, WindowHooks::class.java.classLoader)
                .getMethod("SDL_DestroyWindow", java.lang.Long.TYPE)
        }.getOrNull()
    }

    /** `SDL_GetWindowFlags(long)` — 0 for an unknown handle, which is SDL's "Invalid window". */
    private val getWindowFlags by lazy {
        runCatching {
            Class.forName("org.lwjgl.sdl.SDLVideo", false, WindowHooks::class.java.classLoader)
                .getMethod("SDL_GetWindowFlags", java.lang.Long.TYPE)
        }.getOrNull()
    }

    private val getWindowId by lazy {
        runCatching {
            Class.forName("org.lwjgl.sdl.SDLVideo", false, WindowHooks::class.java.classLoader)
                .getMethod("SDL_GetWindowID", java.lang.Long.TYPE)
        }.getOrNull()
    }

    private val unloadBackendMethod by lazy {
        runCatching {
            Class.forName("com.mojang.blaze3d.systems.RenderSystem", false, WindowHooks::class.java.classLoader)
                .getMethod("unloadTrackedBackendLibrary")
        }.getOrNull()
    }
}
