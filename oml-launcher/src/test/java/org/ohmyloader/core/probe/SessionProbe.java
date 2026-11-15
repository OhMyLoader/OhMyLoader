package org.ohmyloader.core.probe;

/** A class outside the launcher package used to prove the session loader's child-first rule. */
public final class SessionProbe {

    public static String value() {
        return "session";
    }

    private SessionProbe() {}
}
