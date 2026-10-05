package org.ohmyloader.api.client

/**
 * Registration entry point for client key bindings, bound to the declaring mod's id: a registered
 * binding's game name is `key.<modId>.<id>`, and it appears in the controls screen under the shared
 * `oml` category. Bindings are applied lazily on the first client tick, so registration may happen
 * at any point during mod initialization.
 */
interface OMLKeyBindingRegistry {
    /**
     * Registers one binding. [defaultKey] is an `InputConstants` key name (e.g. `"key.keyboard.k"`);
     * an unresolvable name degrades to an unbound key rather than failing startup.
     */
    fun register(id: String, defaultKey: String, onPress: () -> Unit)
}

/**
 * Optional declaration entry point for key bindings: when a [Mod][org.ohmyloader.api.Mod]-annotated
 * class implements this, OML calls it once after [org.ohmyloader.api.OMLModInitializer.onInitialize].
 * Client-only — never invoked on a dedicated server.
 */
interface OMLKeyBindingProvider {
    fun declareKeyBindings(keyBindings: OMLKeyBindingRegistry)
}
