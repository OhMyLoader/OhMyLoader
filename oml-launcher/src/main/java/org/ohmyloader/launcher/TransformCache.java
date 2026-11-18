package org.ohmyloader.launcher;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * The transform cache the Supervisor owns across sessions.
 *
 * <p>L1 lives in this class's statics (the supervisor loader survives a session swap); L2 is a
 * content-addressed directory under the game cache. The core's classloader reaches both through
 * reflection, so the cache can hold transformed game bytes without creating a class-identity edge
 * between the supervisor and a session.
 *
 * <p>Every L2 entry carries a magic, a format version and the SHA-256 of its payload; a miss,
 * corruption, truncation or hostile key is a plain cache miss, never a class-loading failure.
 */
public final class TransformCache {

    private static final byte[] MAGIC = {'O', 'M', 'L', 'C'};
    private static final byte VERSION = 1;
    private static final int HEADER = 4 + 1 + 32 + 4;
    private static final long MAX_L1_BYTES = 256L * 1024 * 1024;

    /** The fingerprint shape `TransformCacheKey` produces: a lowercase SHA-256 hex digest. */
    private static final String FINGERPRINT = "[0-9a-f]{64}";

    private static final Map<String, byte[]> L1 = new ConcurrentHashMap<>();
    private static final AtomicLong L1_BYTES = new AtomicLong();

    // Reboot-bill instrumentation: which tier answered, and what disk reads cost. Cleared by
    // RebootClock.reset() when a new measurement window opens.
    private static final AtomicLong L1_HITS = new AtomicLong();
    private static final AtomicLong L1_MISSES = new AtomicLong();
    private static final AtomicLong L2_HITS = new AtomicLong();
    private static final AtomicLong L2_READ_NANOS = new AtomicLong();

    /** The cache's contribution to the reboot bill; one line per tier. */
    static java.util.List<String> statsLine() {
        java.util.List<String> lines = new java.util.ArrayList<>(3);
        lines.add("transformcache L1 hits=" + L1_HITS.get() + " misses=" + L1_MISSES.get());
        long l2 = L2_HITS.get();
        lines.add("transformcache L2 hits=" + l2 + " read=" + L2_READ_NANOS.get() / 1_000_000 + " ms");
        return lines;
    }

    /** Starts a fresh measurement window (called from RebootClock.reset). */
    static void resetStats() {
        L1_HITS.set(0);
        L1_MISSES.set(0);
        L2_HITS.set(0);
        L2_READ_NANOS.set(0);
    }

    private TransformCache() {}

    /**
     * Returns the transformed bytes for {@code namespace/internalName}, or null on any miss.
     * Reflection entry point for the session-side classloader.
     */
    public static byte[] get(String namespace, String internalName) {
        try {
            String key = key(namespace, internalName);
            if (key == null) return null;
            byte[] cached = L1.get(key);
            if (cached != null) {
                L1_HITS.incrementAndGet();
                return cached;
            }
            L1_MISSES.incrementAndGet();
            Path path = l2Path(namespace, internalName);
            if (path == null) return null;
            long readStart = System.nanoTime();
            byte[] decoded = decode(read(path));
            L2_READ_NANOS.addAndGet(System.nanoTime() - readStart);
            if (decoded == null) {
                deleteQuietly(path);
                return null;
            }
            L2_HITS.incrementAndGet();
            remember(key, decoded);
            return decoded;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Stores transformed bytes in L1 and, when a cache directory is configured, in L2.
     * Reflection entry point for the session-side classloader.
     */
    public static void put(String namespace, String internalName, byte[] bytes) {
        try {
            if (bytes == null) return;
            String key = key(namespace, internalName);
            if (key == null) return;
            remember(key, bytes);

            Path path = l2Path(namespace, internalName);
            if (path == null) return;
            write(path, encode(bytes));
        } catch (Throwable ignored) {
            // A cache write must never fail a class load; the next run simply transforms again.
        }
    }

    /** The composite key; package-private for the cache contract test. */
    static String key(String namespace, String internalName) {
        if (namespace == null || !namespace.matches("[A-Za-z0-9._-]{1,128}")) return null;
        if (namespace.equals(".") || namespace.equals("..") || namespace.contains("..")) return null;
        if (internalName == null || internalName.isEmpty()) return null;
        for (String segment : internalName.split("/", -1)) {
            if (!segment.matches("[A-Za-z0-9_$-]+") || segment.equals(".") || segment.equals("..")) return null;
        }
        return namespace + "/" + internalName;
    }

    /** The L2 file path; package-private so the corruption test can target the exact file. */
    static Path l2Path(String namespace, String internalName) {
        String dir = System.getProperty("oml.transform.cache.dir");
        if (dir == null || dir.isBlank()) return null;
        String key = key(namespace, internalName);
        if (key == null) return null;
        return Path.of(dir).resolve(namespace).resolve(internalName + ".bin");
    }

    /**
     * Drops every fingerprint directory except {@code keepNamespace}.
     *
     * <p>L2 used to accumulate one directory per fingerprint forever (42 fingerprints, 78 MB
     * during development). Only direct children shaped like a fingerprint are removed, so
     * pointing the property at a shared directory cannot delete unrelated data; the current
     * session's directory is kept. Failures are ignored — a missed eviction only costs disk
     * space, and a concurrent session's entries are regenerated on demand.
     *
     * <p>Reflection entry point for the session-side classloader.
     */
    public static void prune(String keepNamespace) {
        if (keepNamespace == null || !keepNamespace.matches(FINGERPRINT)) return;
        L1.keySet().removeIf(key -> !key.startsWith(keepNamespace + "/"));
        String dir = System.getProperty("oml.transform.cache.dir");
        if (dir == null || dir.isBlank()) return;
        Path root = Path.of(dir);
        try (DirectoryStream<Path> children = Files.newDirectoryStream(root)) {
            for (Path child : children) {
                String name = child.getFileName().toString();
                if (name.equals(keepNamespace) || !name.matches(FINGERPRINT)) continue;
                if (!Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) continue;
                deleteTree(child);
            }
        } catch (IOException ignored) {
            // A missing cache directory or an unreadable entry is a no-op.
        }
    }

    /** Test-only reset of the in-memory tier; L2 stays on disk. */
    static void clearForTests() {
        L1.clear();
        L1_BYTES.set(0);
    }

    private static void deleteTree(Path root) {
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(TransformCache::deleteQuietly);
        } catch (IOException ignored) {
            // Leftovers are harmless; the next read still validates their header.
        }
    }

    private static void remember(String key, byte[] data) {
        if (data.length > MAX_L1_BYTES) return;
        byte[] previous = L1.put(key, data);
        long total = L1_BYTES.addAndGet(data.length - (previous == null ? 0 : previous.length));
        if (total > MAX_L1_BYTES && L1.remove(key, data)) {
            L1_BYTES.addAndGet(-data.length);
        }
    }

    private static byte[] encode(byte[] data) {
        ByteBuffer buffer = ByteBuffer.allocate(HEADER + data.length);
        buffer.put(MAGIC);
        buffer.put(VERSION);
        buffer.put(sha256(data));
        buffer.putInt(data.length);
        buffer.put(data);
        return buffer.array();
    }

    private static byte[] decode(byte[] raw) {
        if (raw == null || raw.length < HEADER) return null;
        ByteBuffer buffer = ByteBuffer.wrap(raw);
        byte[] magic = new byte[MAGIC.length];
        buffer.get(magic);
        if (!Arrays.equals(magic, MAGIC)) return null;
        if (buffer.get() != VERSION) return null;
        byte[] expected = new byte[32];
        buffer.get(expected);
        int length = buffer.getInt();
        if (length < 0 || length != buffer.remaining()) return null;
        byte[] data = new byte[length];
        buffer.get(data);
        if (!MessageDigest.isEqual(expected, sha256(data))) return null;
        return data;
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] read(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            return null;
        }
    }

    private static void write(Path path, byte[] bytes) {
        Path tmp = null;
        try {
            Files.createDirectories(path.getParent());
            tmp = Files.createTempFile(path.getParent(), "oml-", ".tmp");
            Files.write(tmp, bytes, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
            tmp = null;
        } catch (IOException ignored) {
            // Fall through to the temp-file cleanup.
        } finally {
            if (tmp != null) deleteQuietly(tmp);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // A leftover file is harmless; the next read still validates its header.
        }
    }
}
