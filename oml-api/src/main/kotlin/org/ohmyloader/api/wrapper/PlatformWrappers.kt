package org.ohmyloader.api.wrapper

/**
 * Wrapper for version-specific game objects. These wrapper
 * interfaces form the OML API "stable layer": consistent semantics,
 * small interfaces, growing with the event domains; [platform]
 * serves as the escape hatch — any blocked mod can reach the underlying native
 * object, so OML never imposes a capability ceiling.
 */
interface OMLScreen {
    /** The underlying game object (a `Screen`). */
    val platform: Any
}

/** World wrapper. */
interface OMLWorld {
    /** The underlying game object (a `ClientLevel`). */
    val platform: Any
}
