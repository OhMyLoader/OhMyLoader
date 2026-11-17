package org.ohmyloader.launcher;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The compiled-SPIR-V cache the Supervisor owns across sessions.
 *
 * <p>Keys are plain hex SHA-256 strings and values are plain {@code byte[]}; this class never sees a
 * game type, so it cannot pin a session's classloader and the canary stays green. The version
 * adapter computes the key and hands the bytes over through reflection (parent delegation), the
 * same way it already reaches {@link TransformCache}.
 *
 * <p>An entry is only ever produced by a compile that actually happened; a hit therefore replaces a
 * shaderc compile (hundreds of milliseconds of CPU each) with a memory copy.
 */
public final class SpirvCache {

    /** Cap on the retained SPIR-V; one session's pipelines are a few tens of MB at most. */
    private static final long MAX_BYTES = 64L * 1024 * 1024;

    private static final Map<String, byte[]> ENTRIES = new ConcurrentHashMap<>();
    private static final AtomicLong BYTES = new AtomicLong();

    // Reboot-bill instrumentation: what the second session got for free. Cleared by
    // RebootClock.reset() when a new measurement window opens.
    private static final AtomicLong HITS = new AtomicLong();
    private static final AtomicLong MISSES = new AtomicLong();

    private SpirvCache() {}

    /**
     * Returns the cached SPIR-V for {@code key}, or null on any miss. Reflection entry point for the
     * session-side adapter.
     */
    public static byte[] get(String key) {
        if (key == null) return null;
        byte[] cached = ENTRIES.get(key);
        if (cached == null) {
            MISSES.incrementAndGet();
            return null;
        }
        HITS.incrementAndGet();
        return cached;
    }

    /** Stores SPIR-V bytes under {@code key}. Reflection entry point for the session-side adapter. */
    public static void put(String key, byte[] bytes) {
        if (key == null || bytes == null || bytes.length == 0) return;
        byte[] previous = ENTRIES.put(key, bytes);
        long total = BYTES.addAndGet(bytes.length - (previous == null ? 0 : previous.length));
        if (total > MAX_BYTES && ENTRIES.remove(key, bytes)) {
            BYTES.addAndGet(-bytes.length);
        }
    }

    /** The cache's contribution to the reboot bill. */
    static List<String> statsLine() {
        return List.of("spirvcache hits=" + HITS.get() + " misses=" + MISSES.get()
            + " bytes=" + BYTES.get());
    }

    /** Starts a fresh measurement window (called from RebootClock.reset). */
    static void resetStats() {
        HITS.set(0);
        MISSES.set(0);
    }
}
