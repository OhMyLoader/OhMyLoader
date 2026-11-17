package org.ohmyloader.adapter.v26_3

import org.ohmyloader.api.hook.Operation
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The game-side probe of the reboot bill: injected call sites forward named marks here, and the
 * probe relays them to the supervisor loader's [RebootClock] through parent delegation — the same
 * path the injected hook bytecode already uses to reach adapter singletons.
 *
 * Measurement only: every call is a runCatching no-op when the clock is unreachable, so a missing
 * mark can never change what the game does.
 */
object RebootProbe {

    private val markMethod by lazy {
        runCatching {
            Class.forName("org.ohmyloader.launcher.RebootClock", true, RebootProbe::class.java.classLoader)
                .getMethod("mark", String::class.java)
        }.getOrNull()
    }
    private val markLatestMethod by lazy {
        runCatching {
            Class.forName("org.ohmyloader.launcher.RebootClock", true, RebootProbe::class.java.classLoader)
                .getMethod("markLatest", String::class.java)
        }.getOrNull()
    }
    private val addMethod by lazy {
        runCatching {
            Class.forName("org.ohmyloader.launcher.RebootClock", true, RebootProbe::class.java.classLoader)
                .getMethod("add", String::class.java, Long::class.javaPrimitiveType)
        }.getOrNull()
    }
    private val countMethod by lazy {
        runCatching {
            Class.forName("org.ohmyloader.launcher.RebootClock", true, RebootProbe::class.java.classLoader)
                .getMethod("count", String::class.java)
        }.getOrNull()
    }

    @JvmStatic
    fun mark(name: String) {
        runCatching { markMethod?.invoke(null, name) }
    }

    /** Texture-atlas stitching: wrap of `SpriteLoader.loadAndStitch` that times completion. */
    private val stitchStarted = AtomicBoolean(false)

    @JvmStatic
    fun loadAndStitch(
        loader: Any?,
        id: Any?,
        mips: Int,
        executor: Any?,
        metadata: Any?,
        original: Operation<*>,
        id2: Int,
    ): CompletableFuture<*> {
        if (stitchStarted.compareAndSet(false, true)) mark("game.stitch.begin")
        val start = System.nanoTime()
        val future = original.call(loader, id, mips, executor, metadata) as CompletableFuture<*>
        return future.whenComplete { _, _ ->
            val elapsed = System.nanoTime() - start
            runCatching {
                addMethod?.invoke(null, "game.stitch.ms", elapsed)
                countMethod?.invoke(null, "game.stitch")
                markLatestMethod?.invoke(null, "game.stitch.done")
            }
        }
    }
}
