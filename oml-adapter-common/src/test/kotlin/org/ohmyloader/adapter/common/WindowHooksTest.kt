package org.ohmyloader.adapter.common

import com.mojang.renderpearl.api.device.GpuBackend
import com.mojang.renderpearl.backend.vulkan.VulkanBackend
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.ohmyloader.api.hook.Operation
import org.ohmyloader.launcher.WindowLease
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The window lease logic: first-session capture, shared adoption, and skip-on-close. */
class WindowHooksTest {

    @BeforeEach
    fun resetShare() {
        System.setProperty("oml.window.lease", "on")
        WindowLease.finishShare()
    }

    @AfterEach
    fun clearLeaseSwitch() {
        System.clearProperty("oml.window.lease")
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
    fun `an OpenGL lease falls back to a fresh window`() {
        WindowLease.record(42L, "com.mojang.renderpearl.backend.opengl.GlBackend")
        WindowLease.beginShare()
        val original = FakeOperation(9L)

        assertFalse(WindowLease.shared(), "the OpenGL device creates a window before the adoption hook can run")
        val handle = WindowHooks.createWindow(backend(), 800, 600, "window", original, 0)

        assertEquals(9L, handle)
        assertEquals(1, original.calls)
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

    private class FakeOperation<T>(private val value: T?) : Operation<T> {
        var calls = 0

        override fun call(vararg args: Any?): T {
            calls++
            @Suppress("UNCHECKED_CAST")
            return value as T
        }
    }

    private fun backend(): GpuBackend? = null
}
