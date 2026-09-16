package org.ohmyloader.core.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for the rewrite timing report ([TimingRecorder]).
 *
 * This layer is a purely functional record-and-render, so assertions feed literal numbers to the
 * text output — it does not run the real pipeline (that would only turn this case into an
 * integration test that is slow to compile and weak in assertions).
 */
class TimingRecorderTest {

    private val ms = 1_000_000L

    @Test
    fun `an empty recorder reports nothing`() {
        // An empty table is returned when there is nothing: that way "not enabled" and "enabled but
        // nothing to report" are the same thing for the caller
        assertEquals(emptyList(), TimingRecorder().lines())
    }

    @Test
    fun `the summary counts classes, modified ones and the totals`() {
        val recorder = TimingRecorder()
        recorder.recordClass("a/A", 100 * ms, changed = true)
        recorder.recordClass("b/B", 300 * ms, changed = false)
        recorder.recordClass("c/C", 200 * ms, changed = true)

        val summary = recorder.lines()[0]

        assertTrue(summary.contains("3 classes"), summary)
        assertTrue(summary.contains("2 modified"), summary)
        assertTrue(summary.contains("total 600.0ms"), summary)
        assertTrue(summary.contains("avg 200.0ms"), summary)
    }

    @Test
    fun `the slowest classes come first`() {
        val recorder = TimingRecorder()
        recorder.recordClass("a/A", 100 * ms, changed = false)
        recorder.recordClass("b/B", 300 * ms, changed = true)
        recorder.recordClass("c/C", 200 * ms, changed = false)

        val text = recorder.lines().joinToString("\n")
        val order = listOf("b/B", "c/C", "a/A").map { text.indexOf(it) }

        assertTrue(order.all { it >= 0 }, text)
        assertEquals(order.sorted(), order, "the report must be ordered slowest to fastest: $text")
        assertTrue(text.contains("300.0ms  b/B (modified)"), text)
    }

    @Test
    fun `top limits how many classes are listed`() {
        val recorder = TimingRecorder()
        repeat(5) { recorder.recordClass("x/C$it", (it + 1) * ms, changed = false) }

        val text = recorder.lines(top = 2).joinToString("\n")

        assertTrue(text.contains("Slowest 2 classes"), text)
        assertTrue(text.contains("x/C4"), text)
        assertTrue(!text.contains("x/C0"), "classes beyond the limit should not appear in the report: $text")
    }

    @Test
    fun `phases accumulate under their own names and keep first-seen order`() {
        val recorder = TimingRecorder()
        recorder.recordPhase("merge", 10 * ms)
        recorder.recordPhase("inject", 5 * ms)
        recorder.recordPhase("merge", 20 * ms)

        val phaseLine = recorder.lines().first { it.contains("Phase accumulation") }

        assertTrue(phaseLine.contains("merge 30.0ms"), phaseLine)
        assertTrue(phaseLine.contains("inject 5.0ms"), phaseLine)
        assertTrue(
            phaseLine.indexOf("merge") < phaseLine.indexOf("inject"),
            "phases are ordered by first appearance for stable output: $phaseLine",
        )
    }

    @Test
    fun `durations render in microseconds, milliseconds and seconds`() {
        val recorder = TimingRecorder()
        recorder.recordClass("tiny/T", 500_000L, changed = false)
        recorder.recordClass("small/S", 2 * ms, changed = false)
        recorder.recordClass("big/B", 1_500 * ms, changed = false)

        val text = recorder.lines().joinToString("\n")

        assertTrue(text.contains("500µs"), text)
        assertTrue(text.contains("2.0ms"), text)
        assertTrue(text.contains("1.50s"), text)
    }

    @Test
    fun `the headline total mixes units correctly`() {
        val recorder = TimingRecorder()
        recorder.recordClass("a/A", 900 * ms, changed = false)
        recorder.recordClass("b/B", 1_200 * ms, changed = false)

        // The total crosses one second, so it renders in seconds; average is 1.05s
        val summary = recorder.lines()[0]

        assertTrue(summary.contains("total 2.10s"), summary)
        assertTrue(summary.contains("avg 1.05s"), summary)
    }
}
