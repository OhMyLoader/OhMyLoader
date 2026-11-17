package org.ohmyloader.launcher;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The JVM floor is the one check that must never regress silently: on the wrong build the symptom
 * is a random 0xC0000005 hours into a client run, not an error anyone can read. The boundary cases
 * are the exact floor (27.0.2 passes), the build the defect lives in (27.0.1 fails), and anything
 * newer passes outright.
 */
class OMLBootstrapVersionTest {

    @Test
    void theExactFloorPasses() {
        assertTrue(OMLBootstrap.isSupportedJvm(Runtime.Version.parse("27.0.2")));
    }

    @Test
    void theDefectiveBuildFails() {
        assertFalse(OMLBootstrap.isSupportedJvm(Runtime.Version.parse("27.0.1")));
        assertFalse(OMLBootstrap.isSupportedJvm(Runtime.Version.parse("27")));
    }

    @Test
    void anythingNewerPasses() {
        assertTrue(OMLBootstrap.isSupportedJvm(Runtime.Version.parse("27.0.3")));
        // "27.1.0" is not a shape Runtime.Version.parse accepts on 27 (interim without the
        // feature+update pair it expects is rejected); the next feature line is the real case.
        assertTrue(OMLBootstrap.isSupportedJvm(Runtime.Version.parse("28")));
    }

    @Test
    void theMinimumConstantParsesAndPassesItsOwnRule() {
        assertTrue(OMLBootstrap.isSupportedJvm(OMLBootstrap.MINIMUM_JVM));
    }
}
