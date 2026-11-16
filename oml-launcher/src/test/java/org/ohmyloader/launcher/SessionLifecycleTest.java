package org.ohmyloader.launcher;

import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.net.URL;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The session teardown contract: retire() drops the supervisor reference and the loader is collectable. */
class SessionLifecycleTest {

    @Test
    void retiringSessionReleasesItsLoader() {
        Session session = sessionWithDetachedLoader();

        WeakReference<ClassLoader> probe = session.retire();
        assertTrue(
            SessionCanary.awaitCollected(probe, 5_000, true, SessionLifecycleTest.class.getClassLoader()),
            "retire() must close and release the session loader");
    }

    private static Session sessionWithDetachedLoader() {
        SessionClassLoader loader = new SessionClassLoader(new URL[0], SessionLifecycleTest.class.getClassLoader());
        return new Session(loader, null);
    }
}
