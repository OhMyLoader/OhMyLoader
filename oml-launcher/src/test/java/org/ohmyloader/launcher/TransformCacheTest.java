package org.ohmyloader.launcher;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

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
    void hostileKeysAreRejected() {
        assertNull(TransformCache.key("..", "a/B"));
        assertNull(TransformCache.key("ns", "../a/B"));
        assertNull(TransformCache.key("ns", "a//B"));
        assertNull(TransformCache.key("ns", "a/./B"));
        assertNull(TransformCache.key("ns/escape", "a/B"));
        assertEquals("ns/a/B", TransformCache.key("ns", "a/B"));
    }
}
