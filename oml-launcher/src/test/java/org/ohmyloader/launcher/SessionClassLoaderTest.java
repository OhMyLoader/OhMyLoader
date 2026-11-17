package org.ohmyloader.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ohmyloader.core.probe.SessionProbe;

import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/** The session loader's ownership rule: OML classes are session-first; the launcher shell is parent-first. */
class SessionClassLoaderTest {

    @Test
    void omlClassesAreSessionFirstEvenWhenTheParentHasThem(@TempDir Path dir) throws Exception {
        URL classes = copyClass(dir, SessionProbe.class);
        ClassLoader parent = SessionClassLoaderTest.class.getClassLoader();

        try (SessionClassLoader session = new SessionClassLoader(new URL[]{classes}, parent)) {
            Class<?> loaded = session.loadClass(SessionProbe.class.getName());

            assertSame(session, loaded.getClassLoader(), "the session must own non-launcher OML classes");
            assertNotSame(SessionProbe.class, loaded);
        }
    }

    @Test
    void launcherClassesStayParentFirst(@TempDir Path dir) throws Exception {
        URL classes = copyClass(dir, SessionCanary.class);
        ClassLoader parent = SessionClassLoaderTest.class.getClassLoader();

        try (SessionClassLoader session = new SessionClassLoader(new URL[]{classes}, parent)) {
            Class<?> loaded = session.loadClass(SessionCanary.class.getName());

            assertSame(SessionCanary.class, loaded, "the launcher shell must keep a single identity");
            assertSame(parent, loaded.getClassLoader());
        }
    }

    private static URL copyClass(Path root, Class<?> type) throws Exception {
        String path = type.getName().replace('.', '/') + ".class";
        Path target = root.resolve(path);
        Files.createDirectories(target.getParent());
        try (InputStream in = type.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, "class bytes for " + type.getName());
            Files.copy(in, target);
        }
        return root.toUri().toURL();
    }
}
