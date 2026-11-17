package org.ohmyloader.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The split contract: OML modules are session-owned; the shell, third-party libraries and the
 * native image are supervisor-owned.
 */
class OMLSupervisorTest {

    @Test
    void omlModulesAreSessionAndLibrariesAreSupervisor(@TempDir Path dir) throws Exception {
        URL core = url(dir, "oml-core-0.1.0.jar");
        URL api = url(dir, "oml-api-0.1.0.jar");
        URL api26 = url(dir, "oml-api-game-0.1.0.jar");
        URL adapter = url(dir, "oml-adapter-0.1.0.jar");
        URL asm = url(dir, "asm-9.11.1.jar");
        URL kotlin = url(dir, "kotlin-stdlib-2.5.0.jar");
        URL launcher = url(dir, "oml-launcher-0.1.0.jar");
        URL nativeLib = url(dir, "oml-native-0.1.0.jar");

        OMLBootstrap.LayerSplit split = OMLBootstrap.split(new URL[]{core, api, api26, adapter, asm, kotlin, launcher, nativeLib});

        assertTrue(contains(split.session, core));
        assertTrue(contains(split.session, api));
        assertTrue(contains(split.session, api26));
        assertTrue(contains(split.session, adapter));
        assertTrue(contains(split.supervisor, asm));
        assertTrue(contains(split.supervisor, kotlin));
        assertTrue(contains(split.supervisor, nativeLib));
        assertFalse(contains(split.session, launcher));
        assertFalse(contains(split.supervisor, launcher));
    }

    @Test
    void aClassDirectoryIsSessionOwned(@TempDir Path dir) throws Exception {
        URL classes = dir.toUri().toURL();

        OMLBootstrap.LayerSplit split = OMLBootstrap.split(new URL[]{classes});

        assertTrue(contains(split.session, classes));
        assertFalse(contains(split.supervisor, classes));
    }

    private static URL url(Path dir, String name) throws Exception {
        return dir.resolve(name).toUri().toURL();
    }

    private static boolean contains(URL[] urls, URL wanted) {
        for (URL url : urls) if (url.equals(wanted)) return true;
        return false;
    }
}
