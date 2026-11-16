package org.ohmyloader.adapter.common

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
            "createWindow enter backend=$backendName ${leaseState()}",
        )
        if (WindowLease.shared() && WindowLease.hasHandle()) {
            val leased = WindowLease.handle()
            val sameBackend = WindowLease.backend() == backendName
            // A window carries the flags of the backend that created it, and SDL stops knowing a
            // handle once something destroys it. The second condition is measurable, so decide on
            // it instead of assuming a backend cannot reuse a window.
            if (sameBackend && windowAlive(leased)) {
                WindowLease.finishShare()
                OmlLog.info("Window", "adopted SDL window handle=$leased backend=$backendName")
                trace("createWindow adopted handle=$leased after=${leaseState()}")
                probeWindow("after-adopt", leased)
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
        val handle = original.call(backend, width, height, title)
        WindowLease.record(handle, backendName)
        OmlLog.info("Window", "recorded SDL window handle=$handle backend=$backendName")
        trace("createWindow created handle=$handle ${leaseState()}")
        probeWindow("after-create", handle)
        return handle
    }

    /**
     * Replacement for the `GpuSurface.close()` call in `Minecraft.close`. The Vulkan surface owns
     * the SDL window, so closing it would invalidate the lease before the next session can adopt it.
     */
    @JvmStatic
    fun surfaceClose(surface: com.mojang.renderpearl.api.device.GpuSurface?) {
        if (WindowLease.shared()) {
            OmlLog.info("Window", "surface close skipped for the leased window")
            trace("surfaceClose SKIPPED surface=${surface?.javaClass?.name} ${leaseState()}")
            probeWindow("before-surface-skip", WindowLease.handle())
            return
        }
        trace("surfaceClose APPLIED surface=${surface?.javaClass?.name} ${leaseState()}")
        runCatching { surface?.close() }
        probeWindow("after-surface-close", WindowLease.handle())
    }

    @JvmStatic
    fun close(original: Operation<Void>, id: Int) {
        // The E2E (and any mod) may change the graphics preset after OMLReboot pinned it; pin
        // again here, on the session's last render-thread close, and persist before the next boot.
        pinBackendForNextSession()
        if (WindowLease.shared()) {
            OmlLog.info("Window", "window close skipped for the leased handle")
            trace("Window.close SKIPPED ${leaseState()}")
            probeWindow("before-window-close-skip", WindowLease.handle())
            return
        }
        trace("Window.close APPLIED ${leaseState()}")
        original.call()
        probeWindow("after-window-close", WindowLease.handle())
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
            probeWindow("before-quit-skip", WindowLease.handle())
            return
        }
        trace("SDL_Quit APPLIED ${leaseState()}")
        try {
            sdlQuit?.invoke(null)
        } catch (_: Throwable) {
            // The window is already being torn down with the process; a failed native call must
            // not turn the normal close path into a crash.
        }
        probeWindow("after-quit", WindowLease.handle())
    }

    /** Replacement for RenderSystem.unloadTrackedBackendLibrary in Minecraft.close. */
    @JvmStatic
    fun unloadBackend() {
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
        probeWindow("after-backend-unload", WindowLease.handle())
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
     * Trace of one hook hit. Only emitted with {@code -Doml.window.lease=on}: the default path must
     * stay byte-for-byte the behaviour the canary and E2E gates were verified against.
     */
    private fun trace(message: String) {
        if (!WindowLease.enabled()) return
        OmlLog.info("Lease", message)
    }

    /**
     * Asks SDL whether the handle is still a live window. {@code SDL_GetWindowFlags} returns 0 and
     * sets the error string for an unknown handle, which is exactly the "Invalid window" the
     * adoption path is suspected of producing — so this measures it instead of inferring it.
     */
    private fun probeWindow(where: String, handle: Long) {
        if (!WindowLease.enabled()) return
        if (handle == 0L) {
            OmlLog.info("Lease", "probe $where handle=0 (no lease)")
            return
        }
        val flags = runCatching { getWindowFlags?.invoke(null, handle) as? Long }.getOrNull()
        val id = runCatching { getWindowId?.invoke(null, handle) as? Int }.getOrNull()
        val error = runCatching { if (flags == null || flags == 0L) getError?.invoke(null) as? String else null }
            .getOrNull()
        val verdict = when {
            flags == null -> "probe-unavailable"
            flags == 0L -> "INVALID"
            else -> "valid"
        }
        OmlLog.info(
            "Lease",
            "probe $where handle=$handle id=$id flags=${flags?.let { String.format("0x%08x", it) }} " +
                "verdict=$verdict error=${error ?: "-"}",
        )
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

    private val getError by lazy {
        runCatching {
            Class.forName("org.lwjgl.sdl.SDLError", false, WindowHooks::class.java.classLoader)
                .getMethod("SDL_GetError")
        }.getOrNull()
    }

    private val unloadBackendMethod by lazy {
        runCatching {
            Class.forName("com.mojang.blaze3d.systems.RenderSystem", false, WindowHooks::class.java.classLoader)
                .getMethod("unloadTrackedBackendLibrary")
        }.getOrNull()
    }
}
