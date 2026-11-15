package org.ohmyloader.core

import org.ohmyloader.api.OmlLog
import java.lang.reflect.Field
import java.util.concurrent.TimeUnit

/**
 * Session-side teardown for the executors the vanilla close path leaves behind.
 *
 * <p>All calls are reflective because this class is version-neutral: 26.3 names are asserted by
 * the adapter hook-shape tests, and a missing field/method on a future line must degrade to a
 * logged skip rather than fail the reboot. The supervisor's TCCL sweep runs afterwards and deals
 * with threads that live outside the game classes (JNA Cleaner, service threads).
 */
internal object SessionTeardown {

    fun run(loader: ClassLoader) {
        shutdownDownloadPool(loader)
        shutdownSoundEngine(loader)
        shutdownLocalEventLoopGroup(loader)
        interruptTimerHack()
    }

    private fun shutdownDownloadPool(loader: ClassLoader) {
        runCatching {
            val util = Class.forName("net.minecraft.util.Util", true, loader)
            val pool = util.getMethod("nonCriticalIoPool").invoke(null) ?: return
            pool.javaClass.getMethod(
                "shutdownAndAwait",
                Long::class.javaPrimitiveType,
                TimeUnit::class.java,
            ).invoke(pool, 3_000L, TimeUnit.MILLISECONDS)
            OmlLog.info("Teardown", "download executor shut down")
        }.onFailure { OmlLog.warn("Teardown", "download executor shutdown failed") }
    }

    private fun shutdownSoundEngine(loader: ClassLoader) {
        runCatching {
            val minecraft = Class.forName("net.minecraft.client.Minecraft", true, loader)
                .getMethod("getInstance")
                .invoke(null) ?: return
            val soundManager = field(minecraft, "soundManager") ?: return
            val soundEngine = field(soundManager, "soundEngine") ?: return
            val executor = field(soundEngine, "executor") ?: return
            executor.javaClass.getMethod("shutDown").invoke(executor)
            OmlLog.info("Teardown", "sound engine executor shut down")
        }.onFailure { OmlLog.warn("Teardown", "sound engine executor shutdown failed") }
    }

    private fun shutdownLocalEventLoopGroup(loader: ClassLoader) {
        runCatching {
            val holderClass = Class.forName("net.minecraft.server.network.EventLoopGroupHolder", true, loader)
            val local = holderClass.getMethod("local").invoke(null) ?: return
            // `local()` returns an anonymous subclass; `group` is declared on the holder base
            // class, so resolve it there instead of on local.javaClass.
            val groupField = holderClass.getDeclaredField("group").apply { isAccessible = true }
            val group = groupField.get(local) ?: return
            val eventLoopGroup = Class.forName("io.netty.channel.EventLoopGroup", true, loader)
            eventLoopGroup.getMethod("shutdownGracefully").invoke(group)
            runCatching {
                eventLoopGroup.getMethod(
                    "awaitTermination",
                    Long::class.javaPrimitiveType,
                    TimeUnit::class.java,
                ).invoke(group, 3_000L, TimeUnit.MILLISECONDS)
            }
            OmlLog.info("Teardown", "local event loop group shut down")
        }.onFailure { OmlLog.warn("Teardown", "local event loop group shutdown failed") }
    }

    private fun interruptTimerHack() {
        for (thread in Thread.getAllStackTraces().keys) {
            if (thread.name == "Timer hack thread") thread.interrupt()
        }
    }

    private fun field(instance: Any, name: String): Any? =
        runCatching {
            val declared: Field = instance.javaClass.getDeclaredField(name)
            declared.isAccessible = true
            declared.get(instance)
        }.getOrNull()
}
