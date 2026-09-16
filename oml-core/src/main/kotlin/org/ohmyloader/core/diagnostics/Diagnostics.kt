package org.ohmyloader.core.diagnostics

import java.util.*

/**
 * Timing and scale statistics for the transformation pipeline: how many classes were transformed and how long it
 * took, where the time went (parse / version conversion / merging / injection / write-back), with totals and
 * averages plus the slowest few classes — only concrete class names tell you what to optimize. Recording is
 * always on (negligible next to ASM parsing; "wanting to see" is only realized afterwards); printing
 * is gated by the system property `oml.diagnostics` (any non-empty value other than `false`), read once on first
 * touch. A first report prints once [FIRST_REPORT_AT] classes have been transformed, then every [REPORT_EVERY]
 * classes — the report must be visible while running, since a hard-killed game (`TerminateProcess` on Windows)
 * never runs the shutdown hook — with a final report on normal exit as fallback. Phases **overlap** (conversion
 * includes merge and inject): numbers compare horizontally only, never summed.
 */
internal class TimingRecorder {

    private class Entry(val name: String, val nanos: Long, val changed: Boolean)

    /** Class loading may be concurrent (multiple threads each create a loader), so recording must be serialized. */
    private val lock = Any()
    private var classes = 0
    private var modified = 0
    private var totalNanos = 0L
    private val entries = mutableListOf<Entry>()

    /** Phase name → cumulative nanos; a `LinkedHashMap` keeps the report in **first-appearance order** for stable output. */
    private val phases = LinkedHashMap<String, Long>()

    /** @return the total class count after recording (callers use it to decide whether to print a report, avoiding an extra lock) */
    fun recordClass(internalName: String, nanos: Long, changed: Boolean): Int = synchronized(lock) {
        classes++
        if (changed) modified++
        totalNanos += nanos
        entries += Entry(internalName, nanos, changed)
        classes
    }

    fun recordPhase(phase: String, nanos: Long) = synchronized(lock) {
        phases[phase] = (phases[phase] ?: 0L) + nanos
    }

    /**
     * The report (one line per entry). Returns an empty list when **nothing has been recorded** —
     * so "not enabled" and "enabled but nothing to report" look identical to callers and need no
     * separate checks. Each section appears only when it has content.
     *
     * @param top how many of the slowest classes to list
     */
    fun lines(top: Int = 10): List<String> = synchronized(lock) {
        if (classes == 0 && phases.isEmpty()) return emptyList()
        val out = mutableListOf<String>()
        if (classes > 0) {
            out += "[oml] Rewrite stats: $classes classes ($modified modified), " +
                "total ${duration(totalNanos)}, avg ${duration(totalNanos / classes)}"
        }
        if (phases.isNotEmpty()) {
            out += "[oml]   Phase accumulation (overlapping, compare only horizontally): " +
                phases.entries.joinToString(" / ") { "${it.key} ${duration(it.value)}" }
        }
        if (entries.isNotEmpty()) {
            val slowest = entries.sortedByDescending { it.nanos }.take(top)
            out += "[oml]   Slowest ${slowest.size} classes: "
            for (entry in slowest) {
                out += "[oml]     ${duration(entry.nanos)}  ${entry.name}" + if (entry.changed) " (modified)" else ""
            }
        }
        out
    }
}

/** Human-readable duration: three tiers of seconds / milliseconds / microseconds. */
private fun duration(nanos: Long): String = when {
    nanos >= 1_000_000_000L -> String.format(Locale.ROOT, "%.2fs", nanos / 1e9)
    nanos >= 1_000_000L -> String.format(Locale.ROOT, "%.1fms", nanos / 1e6)
    else -> String.format(Locale.ROOT, "%.0fµs", nanos / 1e3)
}

/**
 * Process-level facade for [TimingRecorder].
 *
 * The switch is the system property `oml.diagnostics` (any non-empty value other than `false`).
 * Run tasks pass `-PomlDiagnostics=1` to enable it.
 */
internal object Diagnostics {

    /** The first report is emitted once this many classes have been transformed — sufficient to cover the startup burst. */
    private const val FIRST_REPORT_AT = 500

    /** Subsequent cumulative reports are emitted every this many classes. */
    private const val REPORT_EVERY = 5_000

    private val recorder = TimingRecorder()

    /** Switch: **read once on first touch** (this path sits on class load and does not re-read the system property). */
    private val enabled: Boolean =
        System.getProperty("oml.diagnostics")?.let { it.isNotEmpty() && it != "false" } == true

    private var nextReportAt = FIRST_REPORT_AT

    init {
        // Fallback: on a normal exit, print one final cumulative report (hard kills never reach this, so the main path is the milestones above)
        if (enabled) {
            runCatching { Runtime.getRuntime().addShutdownHook(Thread({ printReport() }, "oml-diagnostics")) }
        }
    }

    fun recordClass(internalName: String, nanos: Long, changed: Boolean) {
        val count = recorder.recordClass(internalName, nanos, changed)
        if (enabled && count >= nextReportAt) {
            nextReportAt = count + REPORT_EVERY
            printReport()
        }
    }

    fun recordPhase(phase: String, nanos: Long) {
        recorder.recordPhase(phase, nanos)
    }

    /** Prints the report (does nothing while the switch is off). */
    fun printReport() {
        if (!enabled) return
        recorder.lines().forEach { println(it) }
    }

    /** Report lines (without printing) — for tests. */
    fun lines(top: Int = 10): List<String> = recorder.lines(top)
}
