package org.ohmyloader.adapter

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

    /**
     * The shader manager's reload: wrap of `ShaderManager.reload` that brackets the stage which
     * owns the resource reload's shader work (config load, pipeline compile, pending apply). It is
     * the day-to-day split of the "Minecraft.<init> tail" that the bill used to report as one
     * unknown block.
     */
    private val shaderReloadStarted = AtomicBoolean(false)

    @JvmStatic
    fun shaderReload(
        sharedState: Any?,
        prepareExecutor: Any?,
        barrier: Any?,
        applyExecutor: Any?,
        original: Operation<*>,
        id: Int,
    ): CompletableFuture<*> {
        if (shaderReloadStarted.compareAndSet(false, true)) mark("game.shaderReload.begin")
        val start = System.nanoTime()
        val future = original.call(sharedState, prepareExecutor, barrier, applyExecutor) as CompletableFuture<*>
        return future.whenComplete { _, _ ->
            val elapsed = System.nanoTime() - start
            runCatching {
                addMethod?.invoke(null, "game.shaderReload.ms", elapsed)
                countMethod?.invoke(null, "game.shaderReload")
                markMethod?.invoke(null, "game.shaderReload.done")
            }
        }
    }

    /**
     * `Minecraft.friendsEnabled` / `allowFriendRequests`: answer "off" without touching the session
     * service. Both are plain flag reads in vanilla, but the flag they read comes from
     * `Minecraft.userProperties()`, a `CompletableFuture.join()` on a session-server round trip that
     * `PlayerSocialManager` performs while `Minecraft.<init>` is still running. That join is the
     * 1.7 s `game.ctor.social -> game.ctor.entityRenderers` segment of the bill, and nothing else on
     * the boot path reads the properties: telemetry returns early under `IS_RUNNING_IN_IDE` and the
     * multiplayer/chat gates only run when the player opens those screens. Answering "off" is the
     * same value an offline session would produce, so the friends list stays unavailable — the
     * feature is not used by a headless reboot.
     */
    @JvmStatic
    fun friendsDisabled(original: Operation<*>, id: Int): Boolean = false

    /**
     * The OpenGL side of a pipeline compile: `GlProgram.link` (synchronous `glLinkProgram` on the
     * render thread) and `GlPipelineRecompiler.decompileShaders` (spirv-cross, on the compile
     * executor). Both are totals rather than positions: they overlap the linear segments, and the
     * question they answer is whether the shader path still costs wall time after the SPIR-V cache.
     */
    @JvmStatic
    fun programLink(shaders: Any?, name: Any?, original: Operation<*>, id: Int): Any? {
        val start = System.nanoTime()
        try {
            return original.call(shaders, name)
        } finally {
            runCatching {
                addMethod?.invoke(null, "gl.programLink.ms", System.nanoTime() - start)
                countMethod?.invoke(null, "gl.programLink")
            }
        }
    }

    @JvmStatic
    fun decompileShaders(info: Any?, original: Operation<*>, id: Int): Any? {
        val start = System.nanoTime()
        try {
            return original.call(info)
        } finally {
            runCatching {
                addMethod?.invoke(null, "gl.decompile.ms", System.nanoTime() - start)
                countMethod?.invoke(null, "gl.decompile")
            }
        }
    }
}
