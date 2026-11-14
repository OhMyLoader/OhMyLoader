package org.ohmyloader.api

/**
 * Scopes the registry-declaration phase: the version-facing registration helpers
 * (`org.ohmyloader.api26_3.OMLRegistration`) derive every registry id's namespace from the mod
 * whose [OMLRegistryInitializer.onDeclareRegistries] is currently running, so a mod passes plain
 * ids (`"iron_chest"`) instead of spelling `modid:` on every call.
 *
 * The loader enters/exits the scope around each mod's declaration call; helpers refuse to run
 * outside it — outside the phase the namespace would be a guess, and a wrong namespace silently
 * brands every registered object.
 */
object RegistrationPhase {

    private val currentModId = ThreadLocal<String?>()

    /** The mod currently declaring registries, or `null` outside [OMLRegistryInitializer.onDeclareRegistries]. */
    fun currentModId(): String? = currentModId.get()

    /** Loader-internal: opens the declaration scope for [modId] on this thread. */
    fun enter(modId: String) {
        currentModId.set(modId)
    }

    /** Loader-internal: closes the declaration scope. */
    fun exit() {
        currentModId.set(null)
    }
}
