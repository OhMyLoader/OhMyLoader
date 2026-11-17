package org.ohmyloader.launcher;

import org.junit.jupiter.api.Test;
import org.ohmyloader.api.core.command.OMLArgumentType;
import org.ohmyloader.core.classloader.OMLClassLoader;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * A sealed hierarchy must keep one identity across the new boundary. The outer class is reached
 * through the game loader's parent-first rule today, while the compiler-generated nested objects
 * are referenced from game-side bridge classes; if the session's resource lookup misses one, the
 * game loader's own findClass fallback defines it and the JVM's same-module check throws
 * IncompatibleClassChangeError.
 */
class SessionBoundaryIntegrationTest {

    @Test
    void sealedArgumentTypeAndNestedObjectsStayInTheSession() throws Exception {
        URL[] app = classPathUrls();
        OMLBootstrap.LayerSplit split = OMLBootstrap.split(app);
        ClassLoader supervisor = new URLClassLoader(split.supervisor, ClassLoader.getPlatformClassLoader());
        SessionClassLoader session = new SessionClassLoader(split.session, supervisor);
        OMLClassLoader game = new OMLClassLoader(app, session);

        Class<?> outer = game.loadClass(OMLArgumentType.class.getName());
        Class<?> nested = game.loadClass(OMLArgumentType.class.getName() + "$STRING");

        assertSame(session, outer.getClassLoader(), "the sealed outer class must be session-owned");
        assertSame(session, nested.getClassLoader(), "the nested sealed subclass must be session-owned");
    }

    private static URL[] classPathUrls() throws Exception {
        String[] entries = System.getProperty("java.class.path").split(File.pathSeparator);
        List<URL> urls = new ArrayList<>();
        for (String entry : entries) {
            if (!entry.isBlank()) urls.add(new File(entry).toURI().toURL());
        }
        return urls.toArray(new URL[0]);
    }
}
