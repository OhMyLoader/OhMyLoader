package org.ohmyloader.launcher;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * The reboot stopwatch: a millisecond-level timeline of one soft reboot, across the session
 * boundary.
 *
 * <p>Statics on this class live on the supervisor loader, so marks written by the retiring
 * session, the supervisor and the next session all land on one wall clock that started at the
 * reboot request. Every mark prints as it happens (with its offset from {@code t0}) and
 * {@link #summary()} prints the whole bill — one line per segment (delta between consecutive
 * marks) plus the concurrent accumulators (things that overlap the segments, like GC rounds or
 * async shader/texture work measured as totals rather than positions).
 *
 * <p>Measurement only: no reader of the marks changes behaviour, and everything degrades to a
 * no-op if the reflective caller disappears. Session-side code reaches this class through parent
 * delegation ({@code Class.forName(..., RebootMark.classLoader)}), the same way it already
 * reaches {@link OMLRebootOverlay} and {@code SessionControl}.
 */
public final class RebootClock {

    /** The session replaces System.out with its own stream; write from the process fd directly. */
    private static final PrintStream RAW_OUT =
        new PrintStream(new FileOutputStream(FileDescriptor.out), true);

    /** First-occurrence-wins marks, offset in ns from {@code t0}; the bill reads them in order. */
    private static final LinkedHashMap<String, Long> MARKS = new LinkedHashMap<>();

    /** Totals of work that overlaps the linear timeline (GC rounds, async stitch/compile batches). */
    private static final Map<String, Long> ACCUMULATED = new ConcurrentHashMap<>();

    /** Call counts, paired with accumulators by name ("canary.gc" <-> "canary.gc.count"). */
    private static final Map<String, Long> COUNTS = new ConcurrentHashMap<>();

    private static long t0;

    private RebootClock() {}

    /** Wipes the timeline. Called when the next reboot request opens a new measurement window. */
    public static synchronized void reset() {
        MARKS.clear();
        ACCUMULATED.clear();
        COUNTS.clear();
        t0 = 0;
        TransformCache.resetStats();
        SpirvCache.resetStats();
    }

    /**
     * Records a mark at "now". The first occurrence of a name wins (a milestone reported on every
     * tick marks once, at the moment it first became true); the printout shows the offset from
     * {@code t0}, so the log itself is the timeline.
     */
    public static synchronized void mark(String name) {
        long now = System.nanoTime();
        if (MARKS.containsKey(name)) return;
        if (t0 == 0) t0 = now;
        long at = now - t0;
        MARKS.put(name, at);
        RAW_OUT.println("[OML-Clock] +" + TimeUnit.NANOSECONDS.toMillis(at) + " ms  " + name);
    }

    /**
     * Records (or advances) a mark whose natural semantics are "latest": the last atlas stitch
     * completion, the last async shader batch. The entry moves to the end of the bill, so it never
     * drags an earlier neighbour's delta negative when its timestamp advances.
     */
    public static synchronized void markLatest(String name) {
        long now = System.nanoTime();
        if (t0 == 0) t0 = now;
        long at = now - t0;
        boolean first = MARKS.remove(name) == null;
        MARKS.put(name, at);
        RAW_OUT.println("[OML-Clock] +" + TimeUnit.NANOSECONDS.toMillis(at) + " ms  " + name
            + (first ? "" : "  (latest)"));
    }

    /** Accumulates nanoseconds of overlapping work under {@code key}. */
    public static synchronized void add(String key, long nanos) {
        if (nanos <= 0) return;
        ACCUMULATED.merge(key, nanos, Long::sum);
    }

    /** Adds one to a named counter (paired with an accumulator or standalone). */
    public static synchronized void count(String key) {
        COUNTS.merge(key, 1L, Long::sum);
    }

    /** Prints the bill: per-segment deltas in mark order, then the accumulators. */
    public static synchronized void summary() {
        RAW_OUT.println("[OML-Clock] ================ soft-reboot bill ================");
        Long previous = null;
        String previousName = null;
        for (Map.Entry<String, Long> entry : MARKS.entrySet()) {
            if (previous != null) {
                RAW_OUT.println(String.format("  %6d ms  %s -> %s",
                    TimeUnit.NANOSECONDS.toMillis(entry.getValue() - previous),
                    previousName, entry.getKey()));
            }
            previous = entry.getValue();
            previousName = entry.getKey();
        }
        if (previous != null) {
            RAW_OUT.println("  TOTAL " + TimeUnit.NANOSECONDS.toMillis(previous) + " ms  (" + previousName + ")");
        }
        for (Map.Entry<String, Long> entry : ACCUMULATED.entrySet()) {
            String key = entry.getKey();
            Long count = COUNTS.get(key);
            RAW_OUT.println("  accum " + key + " = " + TimeUnit.NANOSECONDS.toMillis(entry.getValue())
                + " ms" + (count != null ? "  (" + count + " calls)" : ""));
        }
        for (Map.Entry<String, Long> entry : COUNTS.entrySet()) {
            if (!ACCUMULATED.containsKey(entry.getKey())) {
                RAW_OUT.println("  count " + entry.getKey() + " = " + entry.getValue());
            }
        }
        for (String line : TransformCache.statsLine()) {
            RAW_OUT.println("  " + line);
        }
        for (String line : SpirvCache.statsLine()) {
            RAW_OUT.println("  " + line);
        }
        RAW_OUT.println("[OML-Clock] =============== end soft-reboot bill ==============");
    }
}
