package org.ohmyloader.api

import org.ohmyloader.api.core.OmlLog
import net.minecraft.client.PreferredGraphicsApi

/**
 * Client-only in-process reboot.
 *
 * <p>Asks the supervisor to request a reboot, then stops the client loop on the client thread.
 * The normal close path saves the world and runs the remaining teardown; the supervisor starts a
 * new session, which adopts the leased window so nothing is recreated
 * ({@code -Doml.window.lease=off} asks for a fresh one instead). A dedicated server has no client
 * loop and returns false from [isSupported].
 *
 * <p>Before any of that, [RebootRestore] records where the player is: the second session opens that
 * save by name and puts the player back, so a reboot no longer needs a `--quickPlaySingleplayer`
 * argument to land the player in the same place.
 */
object OMLReboot {

    val isSupported: Boolean get() = OMLGame.client != null

    /**
     * @param reason what the loader logs as the cause of the reboot.
     * @param reopen a screen the next session should open once its transition lifts (`mods` for
     *   [org.ohmyloader.adapter.OMLModsScreen]'s own Reload button). Null keeps the default
     *   landing: the world the snapshot names, or the main menu.
     */
    fun restart(reason: String = "User requested", reopen: String? = null) {
        val client = OMLGame.client ?: error("OMLReboot is only available on a client")
        OmlLog.info("Reboot", reason)
        // Recorded before the capture so both the in-process swap and the process-restart fallback
        // see it; it is plain text in the supervisor, never a screen object.
        runCatching { RebootRestore.putScreen(reopen) }

        // Where the player is, as text in the supervisor: the next session opens this save and puts
        // the player back. Captured before the close path runs, because that is what saves the world
        // — and no game object leaves this call.
        val captured = runCatching { RebootRestore.capture() }
            .onFailure { OmlLog.error("Reboot", "capturing the player state failed", it) }
            .getOrDefault(false)

        // Keep the next session on this session's actual backend: The game resets the preference after
        // an unclean shutdown, and a matching backend is what the lease requires to adopt a handle.
        pinGraphicsBackend(client)

        // The supervisor is parent-first; request the reboot (and with it the window share) before
        // the game closes, so the old Window.close/SDL_Quit can skip it.
        val control = Class.forName("org.ohmyloader.launcher.SessionControl", true, OMLReboot::class.java.classLoader)
        control.getMethod("requestReboot", String::class.java).invoke(null, reason)

        // beginShare decides whether the lease is actually shared (it refuses a handle SDL no longer
        // knows), so report the decision at the moment it is made instead of inferring it from the
        // next session's hooks. Only when the lease is on: an ordinary launch stays quiet.
        runCatching {
            val lease = Class.forName("org.ohmyloader.launcher.WindowLease", true, OMLReboot::class.java.classLoader)
            val enabled = lease.getMethod("enabled").invoke(null) as Boolean
            if (enabled) {
                OmlLog.info(
                    "Lease",
                    "reboot requested: shared=${lease.getMethod("shared").invoke(null)} " +
                        "handle=${lease.getMethod("handle").invoke(null)} " +
                        "backend=${lease.getMethod("backend").invoke(null)}",
                )
            }
        }

        if (!captured) {
            OmlLog.info("Reboot", "no player state to carry (no world, or no save directory); the next session starts as launched")
        }
        RebootRestore.scheduleClientStop(client)
    }

    /**
     * The window lease as the supervisor records it, for the reboot gate.
     *
     * <p>`adopted` is the fact a gate can act on — "this session took the previous session's
     * handle" — and `surfaceClosed` is its counterpart on the GPU side; neither can be observed from
     * inside the session that produced it.
     */
    fun leaseTrace(): String = RebootRestore.leaseTrace()

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
