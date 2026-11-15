package org.ohmyloader.api26_3

import org.ohmyloader.api.OmlLog
import org.ohmyloader.api.tick.OMLScheduler
import net.minecraft.client.PreferredGraphicsApi

/**
 * Client-only in-process reboot.
 *
 * <p>Asks the supervisor to request a reboot, then stops the client loop on the client thread.
 * The normal close path saves the world and runs the remaining teardown; the supervisor starts a
 * new session. The window is recreated by default; with the opt-in window lease
 * ({@code -Doml.window.lease=on}) the next session adopts the leased handle instead. A dedicated
 * server has no client loop and returns false from [isSupported].
 */
object OMLReboot {

    val isSupported: Boolean get() = OMLGame.client != null

    fun restart(reason: String = "User requested") {
        val client = OMLGame.client ?: error("OMLReboot is only available on a client")
        OmlLog.info("Reboot", reason)

        // Keep the next session on this session's actual backend: 26.3 resets the preference after
        // an unclean shutdown, and a matching backend is what the opt-in lease would require.
        pinGraphicsBackend(client)

        // The supervisor is parent-first; request the reboot (and, when the opt-in lease is on,
        // the window share) before the game closes so the old Window.close/SDL_Quit can skip it.
        val control = Class.forName("org.ohmyloader.launcher.SessionControl", true, OMLReboot::class.java.classLoader)
        control.getMethod("requestReboot", String::class.java).invoke(null, reason)

        OMLScheduler.client.schedule {
            runCatching {
                // Minecraft.run owns the loop flag; the close path is the finally block, not a
                // public stop method. Reflection keeps this single field version-facing.
                val running = client.javaClass.getDeclaredField("running")
                running.isAccessible = true
                running.setBoolean(client, false)
            }.onFailure { OmlLog.error("Reboot", "could not stop the client loop", it) }
        }
    }

    private fun pinGraphicsBackend(client: net.minecraft.client.Minecraft) {
        val backend = runCatching {
            Class.forName("org.ohmyloader.launcher.WindowLease", true, OMLReboot::class.java.classLoader)
                .getMethod("backend").invoke(null) as String
        }.getOrNull().orEmpty()
        val api = if (backend.contains("VulkanBackend", ignoreCase = true)) {
            PreferredGraphicsApi.VULKAN
        } else {
            PreferredGraphicsApi.OPENGL
        }
        client.options.preferredGraphicsBackend().set(api)
    }
}
