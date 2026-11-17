package org.ohmyloader.adapter

import org.ohmyloader.api.core.OmlLog
import org.ohmyloader.api.core.hook.Operation
import org.ohmyloader.core.RebootMark
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Dedicated executor for asynchronous shader compilation.
 *
 * <p>The game's async pipeline compile reaches Shaderc through an FFM downcall whose include
 * resolver is an FFM upcall back into Java; the JIT nmethod for the FFM string conversion probes
 * the worker's stack guard while the worker is already deep. A dedicated pool with generous stacks
 * keeps that probe far from the frame, and the pool is ours to drain before a reboot tears the GPU
 * surface down — the game's shared background executor is neither.
 *
 * <p>The pool is session-scoped (this class is loaded by the session's OML loader) and is shut down
 * when the game returns, before the supervisor probes the session.
 */
object ShaderCompileExecutor {

    private const val THREADS = 4

    /**
     * The 16 MiB shield: the shaderc call chain goes through FFM downcalls whose include resolver
     * upcalls back into Java, and the JIT'd string conversion probes the stack guard ~100 KB deep.
     * A generous stack keeps that probe far away from the frame it would otherwise hit.
     */
    private const val STACK_BYTES = 16L * 1024 * 1024

    private val threadIds = AtomicInteger()

    private val pool = lazy {
        ThreadPoolExecutor(
            THREADS,
            THREADS,
            0L,
            TimeUnit.MILLISECONDS,
            LinkedBlockingQueue(),
            ThreadFactory { task ->
                Thread(null, task, "OML-Shader-Compile-${threadIds.incrementAndGet()}", STACK_BYTES).apply {
                    isDaemon = true
                    contextClassLoader = ShaderCompileExecutor::class.java.classLoader
                }
            },
        ).also {
            OmlLog.info("Shader", "dedicated shader compile executor: $THREADS thread(s), 16 MiB stacks")
        }
    }

    /**
     * [PipelineBuilder.compilePipeline] wrapper: the original method receives the dedicated
     * executor instead of the game's shared background executor.
     */
    @JvmStatic
    fun compilePipeline(
        pipeline: Any?,
        subpass: Any?,
        source: Any?,
        executor: Executor?,
        original: Operation<*>,
        id: Int,
    ): CompletableFuture<*> {
        val start = System.nanoTime()
        val future = original.call(pipeline, subpass, source, pool.value) as CompletableFuture<Any?>
        // Bill instrumentation: the compile is async, so its cost shows up as an accumulator over
        // the segments rather than a position on the timeline.
        return future.whenComplete { _, _ ->
            try {
                RebootMark.add("game.shaderCompile.ms", System.nanoTime() - start)
                RebootMark.count("game.shaderCompile")
                RebootMark.markLatest("game.shaderCompile.done")
            } catch (_: Throwable) {
                // Bill instrumentation must never change a compile completion.
            }
        } as CompletableFuture<*>
    }

    /**
     * Waits until no compile is running and none is queued, or the timeout expires.
     *
     * <p>A reboot tears the GPU surface, and the device behind it, down while this pool may still
     * be inside `vkCreateGraphicsPipelines`. That call cannot be cancelled — `shutdownNow()` only
     * interrupts Java code, and the worker is in native code — so the teardown has to wait for it
     * instead. Measured on 26.3 + Vulkan: closing the surface under an in-flight pipeline build
     * exits the process with an access violation in the driver, no Java frame, and the game then
     * treats the next launch as a crash and resets the graphics backend to OpenGL.
     *
     * <p>The pool is *not* shut down here. This runs before the game has finished closing, and a
     * rejection would surface as a `RejectedExecutionException` inside the renderer; draining is
     * all the teardown needs. Zero cost when the pool was never used or is already idle.
     */
    @JvmStatic
    fun awaitQuiescent(timeoutMillis: Long): Boolean =
        if (!pool.isInitialized()) true else awaitIdle(pool.value, timeoutMillis)

    /**
     * The wait rule itself, over any pool: a JVM that never compiled a shader must not create the
     * four threads just to answer "idle", and the rule is worth testing without them.
     */
    internal fun awaitIdle(executor: ThreadPoolExecutor, timeoutMillis: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (executor.queue.isEmpty() && executor.activeCount == 0) return true
            Thread.sleep(10)
        }
        return executor.queue.isEmpty() && executor.activeCount == 0
    }

    /** Called by OMLCore when the game returns, before the session canary. */
    @JvmStatic
    fun shutdown() {
        if (pool.isInitialized()) {
            pool.value.shutdownNow()
        }
    }
}
