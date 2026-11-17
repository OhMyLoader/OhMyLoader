package org.ohmyloader.api

/**
 * Scopes the registry-declaration phase: the version-facing registration helpers
 * (`org.ohmyloader.api.game.OMLRegistration`) derive every registry id's namespace from the mod
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

    /**
     * Registers an action that runs once, after **every** mod's declaration call has returned and
     * before the registries freeze - the moment a cross-mod ordering (a creative-tab manager
     * resolving placement constraints against all registered tabs) becomes answerable. Actions
     * run in registration order; a failing action aborts the launch like a declaration failure.
     */
    fun onAllDeclarationsComplete(action: () -> Unit) {
        completionActions.add(action)
    }

    private val completionActions = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /** Loader-internal: runs the completion actions registered by [onAllDeclarationsComplete]. */
    fun completeAllDeclarations() {
        completionActions.forEach { it() }
        completionActions.clear()
    }

    /** Loader-internal: opens the declaration scope for [modId] on this thread. */
    fun enter(modId: String) {
        currentModId.set(modId)
    }

    /** Loader-internal: closes the declaration scope. */
    fun exit() {
        currentModId.set(null)
    }
}
