package org.ohmyloader.launcher;

import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The WeakReference probe's two answers: collected, and still reachable with a named thread. */
class SessionCanaryTest {

    private static volatile ClassLoader retained;

    @Test
    void detachedLoaderIsCollected() {
        SessionCanary canary = canaryForDetachedLoader();
        assertTrue(canary.awaitCollected(5_000), "a loader with no strong references must be collectable");
    }

    @Test
    void retainedLoaderIsReported() {
        SessionCanary canary = canaryForRetainedLoader();
        try {
            assertFalse(canary.awaitCollected(300), "a strongly reachable loader must not be reported as collected");
        } finally {
            retained = null;
        }
        assertTrue(canary.awaitCollected(5_000), "clearing the last strong reference must let the probe observe collection");
    }

    @Test
    void leakedThreadIsNamed() throws Exception {
        URLClassLoader loader = new URLClassLoader(new URL[0], null);
        SessionCanary canary = new SessionCanary(loader, null);
        Thread holder = new Thread(() -> {
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException ignored) {
                // The test's interruption is the cleanup.
            }
        }, "session-holder");
        holder.setContextClassLoader(loader);
        holder.start();
        try {
            List<String> leaks = canary.leakedThreads();
            assertTrue(
                leaks.stream().anyMatch(line -> line.contains("session-holder")),
                "diagnostics must name the thread that still holds the session: " + leaks);
        } finally {
            holder.interrupt();
            holder.join(5_000);
            holder.setContextClassLoader(null);
        }
    }

    private static SessionCanary canaryForDetachedLoader() {
        URLClassLoader loader = new URLClassLoader(new URL[0], null);
        return new SessionCanary(loader, null);
    }

    private static SessionCanary canaryForRetainedLoader() {
        URLClassLoader loader = new URLClassLoader(new URL[0], null);
        retained = loader;
        return new SessionCanary(loader, null);
    }
}
