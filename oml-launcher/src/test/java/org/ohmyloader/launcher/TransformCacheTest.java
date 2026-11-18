package org.ohmyloader.launcher;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The L1/L2 store's contract: round-trip, disk fallback, integrity, and hostile keys. */
class TransformCacheTest {

    @TempDir
    Path dir;

    private String previousDir;

    @BeforeEach
    void setUp() {
        previousDir = System.getProperty("oml.transform.cache.dir");
        System.setProperty("oml.transform.cache.dir", dir.toString());
        TransformCache.clearForTests();
    }

    @AfterEach
    void tearDown() {
        TransformCache.clearForTests();
        if (previousDir == null) System.clearProperty("oml.transform.cache.dir");
        else System.setProperty("oml.transform.cache.dir", previousDir);
    }

    @Test
    void l1RoundTrip() {
        byte[] bytes = {1, 2, 3, 4};

        TransformCache.put("ns", "a/B", bytes);

        assertArrayEquals(bytes, TransformCache.get("ns", "a/B"));
    }

    @Test
    void l2RoundTripAfterL1Clear() {
        byte[] bytes = {9, 8, 7};

        TransformCache.put("ns", "a/B", bytes);
        TransformCache.clearForTests();

        assertArrayEquals(bytes, TransformCache.get("ns", "a/B"), "the disk tier must survive an in-memory clear");
    }

    @Test
    void aCorruptFileIsAMissAndIsRemoved() throws Exception {
        byte[] bytes = {5, 6, 7};
        TransformCache.put("ns", "a/B", bytes);
        Path file = TransformCache.l2Path("ns", "a/B");
        assertNotNull(file);
        byte[] raw = Files.readAllBytes(file);
        raw[0] ^= 0x7f;
        Files.write(file, raw, StandardOpenOption.TRUNCATE_EXISTING);
        TransformCache.clearForTests();

        assertNull(TransformCache.get("ns", "a/B"));
        assertFalse(Files.exists(file), "a corrupt entry must not be served twice");
    }

    @Test
    void namespacesSeparateEntries() {
        TransformCache.put("ns1", "a/B", new byte[]{1});

        assertNull(TransformCache.get("ns2", "a/B"));
    }

    @Test
    void pruningKeepsTheCurrentAndTheMostRecentPreviousFingerprint() throws Exception {
        String oldest = "a".repeat(64);
        String previous = "b".repeat(64);
        String current = "c".repeat(64);
        Path unrelated = Files.createDirectories(dir.resolve("keep-me"));
        TransformCache.put(oldest, "a/B", new byte[]{1});
        TransformCache.put(previous, "a/B", new byte[]{2});
        TransformCache.put(current, "a/B", new byte[]{3});
        long now = System.currentTimeMillis();
        Files.setLastModifiedTime(dir.resolve(oldest), FileTime.fromMillis(now - 10_000));
        Files.setLastModifiedTime(dir.resolve(previous), FileTime.fromMillis(now - 5_000));

        TransformCache.prune(current);

        assertFalse(Files.exists(dir.resolve(oldest)), "fingerprints older than the toggle window are evicted");
        assertTrue(
            Files.exists(dir.resolve(previous)),
            "the most recent previous fingerprint survives so a mod toggle (A -> B -> A) reuses it"
        );
        assertTrue(Files.exists(dir.resolve(current)), "the active fingerprint must survive");
        assertTrue(Files.exists(unrelated), "directories outside the fingerprint shape are not the cache's to delete");
        assertArrayEquals(new byte[]{3}, TransformCache.get(current, "a/B"));
        assertArrayEquals(new byte[]{2}, TransformCache.get(previous, "a/B"), "the kept previous tier stays in L1");
        assertNull(TransformCache.get(oldest, "a/B"), "evicted entries are cleared from the in-memory tier too");
    }

    @Test
    void hostileKeysAreRejected() {
        assertNull(TransformCache.key("..", "a/B"));
        assertNull(TransformCache.key("ns", "../a/B"));
        assertNull(TransformCache.key("ns", "a//B"));
        assertNull(TransformCache.key("ns", "a/./B"));
        assertNull(TransformCache.key("ns/escape", "a/B"));
        assertEquals("ns/a/B", TransformCache.key("ns", "a/B"));
    }
}
