package org.ohmyloader.adapter.v26_3

import io.netty.channel.EventLoopGroup
import net.minecraft.TracingExecutor
import net.minecraft.client.sounds.SoundEngineExecutor
import net.minecraft.util.Util
import org.ohmyloader.api.hook.Operation
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 26.3 bridge to the supervisor's ThreadHygiene. The bridge itself is session code, but the
 * objects it creates (plain Threads, ForkJoin workers, factories, UEH) are loaded by the
 * persistent supervisor and therefore do not reference the disposable session loader.
 */
object ThreadHygieneBridge {

    private val type: Class<*> = Class.forName(
        "org.ohmyloader.launcher.ThreadHygiene",
        true,
        ThreadHygieneBridge::class.java.classLoader,
    )

    @Volatile
    private var soundExecutor: SoundEngineExecutor? = null

    @Volatile
    private var telemetry: Executor? = null

    @JvmStatic
    fun registerSoundExecutor(executor: SoundEngineExecutor) {
        soundExecutor = executor
    }

    @JvmStatic
    fun shutdownSoundExecutor() {
        val executor = soundExecutor
        soundExecutor = null
        executor?.shutDown()
    }

    @JvmStatic
    fun registerTelemetryExecutor(executor: Executor) {
        telemetry = executor
    }

    /** Hands the executor to the core teardown once; the bridge must not pin it afterwards. */
    @JvmStatic
    fun takeTelemetryExecutor(): ExecutorService? {
        val executor = telemetry
        telemetry = null
        return executor as? ExecutorService
    }

    @Volatile
    private var eventLoopGroup: EventLoopGroup? = null

    @JvmStatic
    fun registerEventLoopGroup(group: EventLoopGroup): EventLoopGroup {
        eventLoopGroup = group
        return group
    }

    @JvmStatic
    fun shutdownEventLoopGroup() {
        val group = eventLoopGroup
        eventLoopGroup = null
        group?.shutdownGracefully()
        runCatching { group?.awaitTermination(3, TimeUnit.SECONDS) }
    }

    private val jfrTasks = mutableListOf<Runnable>()
    private var jfrCustomEvents: List<Class<*>> = emptyList()
    private var openAlEventTypes: IntArray? = null

    @JvmStatic
    fun trackJfrTask(task: Runnable): Runnable {
        jfrTasks += task
        return task
    }

    @JvmStatic
    fun takeJfrTasks(): List<Runnable> {
        val tasks = jfrTasks.toList()
        jfrTasks.clear()
        return tasks
    }

    @JvmStatic
    fun registerJfrCustomEvents(events: List<Class<*>>) {
        jfrCustomEvents = events.toList()
    }

    @JvmStatic
    fun takeJfrCustomEvents(): List<Class<*>> {
        val events = jfrCustomEvents
        jfrCustomEvents = emptyList()
        return events
    }

    @JvmStatic
    fun registerOpenAlEventTypes(types: IntArray) {
        openAlEventTypes = types
    }

    @JvmStatic
    fun takeOpenAlEventTypes(): IntArray? {
        val types = openAlEventTypes
        openAlEventTypes = null
        return types
    }

    private var watchdogCounter: AtomicInteger? = null

    @JvmStatic
    fun registerWatchdogCounter(counter: AtomicInteger) {
        watchdogCounter = counter
    }

    @JvmStatic
    fun disarmWatchdog() {
        watchdogCounter?.set(Int.MIN_VALUE)
        watchdogCounter = null
    }

    @JvmStatic
    fun makeExecutor(name: String, original: Operation<*>, id: Int): TracingExecutor =
        TracingExecutor(newPool(name, Util.maxAllowedExecutorThreads()))

    @JvmStatic
    fun makeIoExecutor(name: String, daemon: Boolean, original: Operation<*>, id: Int): TracingExecutor =
        TracingExecutor(newPool(name, Util.maxAllowedExecutorThreads()))

    @JvmStatic
    fun startTimerHack(original: Operation<*>, id: Int) {
        type.getMethod("startTimerHack").invoke(null)
    }

    @JvmStatic
    fun clearServerThread() {
        type.getMethod("clearCurrentServerThread").invoke(null)
    }

    @JvmStatic
    fun soundThread(original: Operation<*>, id: Int): Thread =
        replace(original.call() as Thread)

    @JvmStatic
    fun threadFactory(original: Operation<*>, id: Int): ThreadFactory =
        type.getMethod("wrapFactory", ThreadFactory::class.java).invoke(null, original.call()) as ThreadFactory

    @JvmStatic
    fun defaultThread(runnable: Runnable, original: Operation<*>, id: Int): Thread =
        replace(original.call(runnable) as Thread)

    private fun newPool(name: String, parallelism: Int): ForkJoinPool =
        type.getMethod("newForkJoinPool", String::class.java, Int::class.javaPrimitiveType)
            .invoke(null, name, parallelism) as ForkJoinPool

    private fun replace(thread: Thread): Thread =
        type.getMethod("replace", Thread::class.java).invoke(null, thread) as Thread
}
