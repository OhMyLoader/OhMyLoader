package org.ohmyloader.launcher;

import java.net.URL;
import java.net.URLClassLoader;

/**
 * The disposable half of the Supervisor/Session split.
 *
 * <p>Every {@code org.ohmyloader.*} class except the launcher shell is session-first: it is defined
 * here, not by the supervisor, so discarding this loader also discards the OML core/API/content
 * statics that point at the game. The launcher package stays parent-first because the shell is the
 * process entry point and must remain loadable from the supervisor side.
 *
 * <p>The game loader OMLCore creates is a child of this loader. Its own child-first prefixes still
 * define the adapters and {@code oml-api} in the game loader (those signatures name game
 * types); it reaches their bytes through this loader's resources, so the package ownership is
 * unchanged from the pre-split layout.
 */
final class SessionClassLoader extends URLClassLoader {

    private static final String OML_PREFIX = "org.ohmyloader.";
    private static final String LAUNCHER_PREFIX = "org.ohmyloader.launcher.";

    SessionClassLoader(URL[] urls, ClassLoader parent) {
        super(urls, parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                if (name.startsWith(OML_PREFIX) && !name.startsWith(LAUNCHER_PREFIX)) {
                    try {
                        loaded = findClass(name);
                    } catch (ClassNotFoundException notInSession) {
                        loaded = super.loadClass(name, false);
                    }
                } else {
                    loaded = super.loadClass(name, false);
                }
            }
            if (resolve) resolveClass(loaded);
            return loaded;
        }
    }
}
