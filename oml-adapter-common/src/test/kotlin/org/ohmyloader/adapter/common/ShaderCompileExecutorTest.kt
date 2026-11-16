package org.ohmyloader.adapter.common

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The drain that runs before a leased surface is torn down.
 *
 * <p>Reproduced shape: a reboot that lands while the shader pool is inside
 * `vkCreateGraphicsPipelines` destroyed the surface and the device underneath it, and the process
 * died with an access violation in the driver — no Java frame, and the game then recorded the next
 * launch as a crash. The pool cannot be cancelled out of native code, so the teardown has to wait.
 */
class ShaderCompileExecutorTest {

    private val executor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(),
    )

    @AfterEach
    fun stopExecutor() {
        executor.shutdownNow()
        executor.awaitTermination(2, TimeUnit.SECONDS)
    }

    @Test
    fun `an idle pool is reported idle without waiting`() {
        val started = System.nanoTime()
        assertTrue(ShaderCompileExecutor.awaitIdle(executor, 5_000), "an empty pool is quiescent")
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        assertTrue(elapsedMillis < 500, "an empty pool must not burn the timeout, took ${elapsedMillis}ms")
    }

    @Test
    fun `an in-flight compile is waited for`() {
        val running = CountDownLatch(1)
        val release = CountDownLatch(1)
        executor.execute {
            running.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        assertTrue(running.await(5, TimeUnit.SECONDS), "the task must actually be running")
        Thread {
            Thread.sleep(200)
            release.countDown()
        }.start()

        val started = System.nanoTime()
        assertTrue(ShaderCompileExecutor.awaitIdle(executor, 5_000), "the wait must end once the task does")
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        assertTrue(elapsedMillis >= 150, "the wait must cover the running task, returned after ${elapsedMillis}ms")
    }

    @Test
    fun `a task that never returns is reported instead of waited on forever`() {
        val running = CountDownLatch(1)
        executor.execute {
            running.countDown()
            CountDownLatch(1).await(30, TimeUnit.SECONDS)
        }
        assertTrue(running.await(5, TimeUnit.SECONDS), "the task must actually be running")

        val started = System.nanoTime()
        assertFalse(ShaderCompileExecutor.awaitIdle(executor, 200), "a stuck task must report a timeout")
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        assertTrue(elapsedMillis >= 150, "the timeout must be honoured, returned after ${elapsedMillis}ms")
    }
}
