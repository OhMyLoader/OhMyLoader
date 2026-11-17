package org.ohmyloader.adapter

import com.mojang.renderpearl.api.commands.CommandEncoder
import com.mojang.renderpearl.api.device.GpuBackend
import com.mojang.renderpearl.api.device.GpuSurface
import com.mojang.renderpearl.api.textures.GpuTextureView
import com.mojang.renderpearl.backend.opengl.GlBackend
import com.mojang.renderpearl.backend.vulkan.VulkanBackend
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.ohmyloader.api.hook.Operation
import org.ohmyloader.launcher.WindowLease
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The window lease logic: first-session capture, shared adoption, and skip-on-close. */
class WindowHooksTest {

    @BeforeEach
    fun resetShare() {
        // No property set: the lease is on by default and that is what these tests exercise.
        WindowLease.finishShare()
        WindowHooks.aliveProbe = { true }
        WindowHooks.glAttributeApplicator = { true }
    }

    @AfterEach
    fun clearLeaseSwitch() {
        System.clearProperty("oml.window.lease")
        WindowHooks.aliveProbe = DEFAULT_PROBE
        WindowHooks.glAttributeApplicator = DEFAULT_ATTRIBUTES
    }

    @Test
    fun `the first session records the created handle`() {
        val original = FakeOperation(7L)

        val handle = WindowHooks.createWindow(backend(), 800, 600, "window", original, 0)

        assertEquals(7L, handle)
        assertEquals(1, original.calls)
        assertEquals(7L, WindowLease.handle())
        assertEquals("unknown", WindowLease.backend())
    }

    @Test
    fun `a shared Vulkan lease adopts the handle without creating a window`() {
        val vulkan = VulkanBackend()
        WindowLease.record(42L, vulkan.javaClass.name)
        WindowLease.beginShare()
        val original = FakeOperation(7L)

        val handle = WindowHooks.createWindow(vulkan, 800, 600, "window", original, 0)

        assertEquals(42L, handle)
        assertEquals(0, original.calls)
        assertFalse(WindowLease.shared(), "adoption must clear the share flag for the new session's normal close")
    }

    @Test
    fun `switching the lease off recreates the window instead of adopting`() {
        System.setProperty("oml.window.lease", "off")
        val vulkan = VulkanBackend()
        WindowLease.record(42L, vulkan.javaClass.name)
        WindowLease.beginShare()
        val original = FakeOperation(7L)

        val handle = WindowHooks.createWindow(vulkan, 800, 600, "window", original, 0)

        assertEquals(7L, handle, "a disabled lease must not hand the old handle to the new session")
        assertEquals(1, original.calls)
    }

    @Test
    fun `an OpenGL lease adopts the handle when SDL still knows it`() {
        val gl = GlBackend()
        WindowLease.record(42L, gl.javaClass.name)
        WindowLease.beginShare()
        val original = FakeOperation(9L)

        assertTrue(WindowLease.shared(), "26.3 reaches Window.createWindow once per session, so GL can share too")
        val handle = WindowHooks.createWindow(gl, 800, 600, "window", original, 0)

        assertEquals(42L, handle)
        assertEquals(0, original.calls, "an adopted window must not be created a second time")
        assertFalse(WindowLease.shared())
    }

    @Test
    fun `a lease whose window is gone is recreated`() {
        val gl = GlBackend()
        WindowLease.record(42L, gl.javaClass.name)
        WindowLease.beginShare()
        WindowHooks.aliveProbe = { false }
        val original = FakeOperation(9L)

        val handle = WindowHooks.createWindow(gl, 800, 600, "window", original, 0)

        assertEquals(9L, handle)
        assertEquals(1, original.calls, "a dead handle must fall back to a fresh window instead of an Invalid window crash")
        assertEquals(9L, WindowLease.handle())
        assertFalse(WindowLease.shared())
    }

    @Test
    fun `an adoptable window whose GL attributes cannot be restored is recreated`() {
        val gl = GlBackend()
        WindowLease.record(42L, gl.javaClass.name)
        WindowLease.beginShare()
        WindowHooks.glAttributeApplicator = { false }
        val original = FakeOperation(9L)

        val handle = WindowHooks.createWindow(gl, 800, 600, "window", original, 0)

        assertEquals(9L, handle, "a core-profile context cannot be built on SDL's default attributes")
        assertEquals(1, original.calls, "the attributes are set before SDL_CreateWindow, so only a new window can carry them")
        assertFalse(WindowLease.shared())
    }

    @Test
    fun `a backend change recreates the window instead of adopting it`() {
        val vulkan = VulkanBackend()
        WindowLease.record(42L, vulkan.javaClass.name)
        WindowLease.beginShare()
        val original = FakeOperation(9L)

        val handle = WindowHooks.createWindow(backend(), 800, 600, "window", original, 0)

        assertEquals(9L, handle)
        assertEquals(1, original.calls)
        assertEquals("unknown", WindowLease.backend())
        assertFalse(WindowLease.shared())
    }

    @Test
    fun `close is skipped only while the lease is shared`() {
        val original = FakeOperation<Void>(null)

        WindowHooks.close(original, 0)
        assertEquals(1, original.calls)

        val vulkan = VulkanBackend()
        WindowLease.record(42L, vulkan.javaClass.name)
        WindowLease.beginShare()
        WindowHooks.close(original, 0)
        assertEquals(1, original.calls, "the old session must not destroy the leased window")

        WindowLease.finishShare()
        WindowHooks.close(original, 0)
        assertEquals(2, original.calls)
    }

    @Test
    fun `SDL_Quit is allowed only outside a shared lease`() {
        assertTrue(WindowHooks.quitAllowed())

        val vulkan = VulkanBackend()
        WindowLease.record(42L, vulkan.javaClass.name)
        WindowLease.beginShare()
        assertFalse(WindowHooks.quitAllowed(), "the old session must not quit SDL while the window is leased")

        WindowLease.finishShare()
        assertTrue(WindowHooks.quitAllowed())
    }

    @Test
    fun `the surface is closed even while the window is leased`() {
        val vulkan = VulkanBackend()
        WindowLease.record(42L, vulkan.javaClass.name)
        WindowLease.beginShare()
        val surface = FakeSurface()

        WindowHooks.surfaceClose(surface)

        assertEquals(1, surface.closes, "an abandoned surface strands its swapchain on the leased window")
        assertTrue(WindowLease.surfaceClosed(), "the handover must be recorded where the next session can read it")
        assertEquals(42L, WindowLease.handle(), "closing the surface must not release the window lease")
    }

    @Test
    fun `a surface that throws on close does not fail the shutdown`() {
        val surface = FakeSurface(failing = true)

        WindowHooks.surfaceClose(surface)

        assertEquals(1, surface.closes)
        assertFalse(WindowLease.surfaceClosed(), "a failed close is reported, not hidden")
    }

    private class FakeSurface(private val failing: Boolean = false) : GpuSurface {
        var closes = 0

        override fun close() {
            closes++
            if (failing) throw IllegalStateException("Cannot close a surface while it is acquired")
        }

        override fun isDXGI(): Boolean = false
        override fun configure(configuration: GpuSurface.Configuration) = Unit
        override fun currentConfiguration(): Optional<GpuSurface.Configuration> = Optional.empty()
        override fun supportedPresentModes(): Collection<GpuSurface.PresentMode> =
            listOf(GpuSurface.PresentMode.FIFO)
        override fun isSuboptimal(): Boolean = false
        override fun isAcquired(): Boolean = false
        override fun acquireNextTexture() = Unit
        override fun blitFromTexture(encoder: CommandEncoder, view: GpuTextureView) = Unit
        override fun present() = Unit
    }

    private class FakeOperation<T>(private val value: T?) : Operation<T> {
        var calls = 0

        override fun call(vararg args: Any?): T {
            calls++
            @Suppress("UNCHECKED_CAST")
            return value as T
        }
    }

    private companion object {
        /** The native liveness probe, restored after a test stubs it. */
        val DEFAULT_PROBE: (Long) -> Boolean = WindowHooks.aliveProbe

        /** The SDL attribute restore, restored after a test stubs it. */
        val DEFAULT_ATTRIBUTES: () -> Boolean = WindowHooks.glAttributeApplicator
    }

    private fun backend(): GpuBackend? = null
}
