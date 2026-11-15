package org.ohmyloader.adapter.common

import org.ohmyloader.api.OmlLog
import org.ohmyloader.api.hook.Operation
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
 * <p>26.3's RenderPearl frontend compiles shaders through a shared ForkJoin worker. That worker
 * reaches Shaderc through an FFM downcall whose include resolver is an FFM upcall back into Java;
 * the JIT nmethod for the FFM string conversion probes the worker's stack guard while the worker is
 * already ~100 KB deep. On Zulu 27 that guard hit is not turned into a StackOverflowError, and
 * the process dies with 0xC0000005.
 *
 * <p>Running those tasks on a small pool whose threads are created with a 16 MiB stack moves the
 * guard far away from the shader-compilation call chain without any JVM launch option. The pool is
 * session-scoped (this class is loaded by the session's OML loader) and is shut down when the game
 * returns, before the supervisor probes the session.
 */
object ShaderCompileExecutor {

    private const val THREADS = 4
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
            OmlLog.info(
                "Shader",
                "dedicated shader compile executor: $THREADS thread(s), ${STACK_BYTES / (1024 * 1024)} MiB stack",
            )
        }
    }

    /**
     * [PipelineBuilder.compilePipeline] wrapper: the original method receives the dedicated
     * executor instead of the game's shared background executor.
     */
    @JvmStatic
    fun compilePipeline(
        pipeline: Any?,
        source: Any?,
        executor: Executor?,
        original: Operation<*>,
        id: Int,
    ): CompletableFuture<*> =
        original.call(pipeline, source, pool.value) as CompletableFuture<*>

    /** Called by OMLCore when the game returns, before the session canary. */
    @JvmStatic
    fun shutdown() {
        if (pool.isInitialized()) {
            pool.value.shutdownNow()
        }
    }
}
