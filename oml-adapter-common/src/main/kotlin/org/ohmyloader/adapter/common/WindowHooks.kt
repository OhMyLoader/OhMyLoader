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
        if (WindowLease.shared() && WindowLease.hasHandle()) {
            if (WindowLease.backend() == backendName) {
                val handle = WindowLease.handle()
                WindowLease.finishShare()
                OmlLog.info("Window", "adopted SDL window handle=$handle backend=$backendName")
                return handle
            }
            // A window is created by a GPU backend and carries that backend's window flags; a new
            // session that selected another backend cannot use it. Destroy the lease and let the
            // original create a window for the new backend.
            OmlLog.info("Window", "backend changed (${WindowLease.backend()} -> $backendName); recreating the SDL window")
            destroySdlWindow(WindowLease.handle())
            WindowLease.finishShare()
        }
        val handle = original.call(backend, width, height, title)
        WindowLease.record(handle, backendName)
        OmlLog.info("Window", "recorded SDL window handle=$handle backend=$backendName")
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
            return
        }
        runCatching { surface?.close() }
    }

    @JvmStatic
    fun close(original: Operation<Void>, id: Int) {
        // The E2E (and any mod) may change the graphics preset after OMLReboot pinned it; pin
        // again here, on the session's last render-thread close, and persist before the next boot.
        pinBackendForNextSession()
        if (WindowLease.shared()) {
            OmlLog.info("Window", "window close skipped for the leased handle")
            return
        }
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
            return
        }
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
        if (WindowLease.shared()) {
            OmlLog.info("Window", "backend unload skipped for the leased window")
            return
        }
        try {
            unloadBackendMethod?.invoke(null)
        } catch (_: Throwable) {
            // Best effort on the normal close path.
        }
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

    private val unloadBackendMethod by lazy {
        runCatching {
            Class.forName("com.mojang.blaze3d.systems.RenderSystem", false, WindowHooks::class.java.classLoader)
                .getMethod("unloadTrackedBackendLibrary")
        }.getOrNull()
    }
}
