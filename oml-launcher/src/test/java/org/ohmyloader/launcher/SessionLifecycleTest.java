package org.ohmyloader.launcher;

import org.junit.jupiter.api.Test;

import java.net.URL;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The session teardown contract: close() drops the supervisor reference and the loader is collectable. */
class SessionLifecycleTest {

    @Test
    void closingSessionReleasesItsLoader() {
        Session session = sessionWithDetachedLoader();

        assertTrue(session.close(), "close() must close and release the session loader");
    }

    private static Session sessionWithDetachedLoader() {
        SessionClassLoader loader = new SessionClassLoader(new URL[0], SessionLifecycleTest.class.getClassLoader());
        return new Session(loader, null);
    }
}
