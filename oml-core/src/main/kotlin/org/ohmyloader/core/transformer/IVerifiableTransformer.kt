package org.ohmyloader.core.transformer

/**
 * Self-checking transformer: moves "broken rule" failures from **runtime crashes** to **startup diagnostics**.
 *
 * Background: the most dangerous failure mode of an injection rule is "changed the wrong thing but didn't crash,
 * so the behavior is merely wrong". It produces no exception and lets the game silently misbehave. A rule must
 * therefore be audited at startup: whether the handler exists, whether the signature is self-consistent, and
 * whether the declared expected hit count is reasonable.
 */
interface IVerifiableTransformer {

    /**
     * Returns a list of issues (each a plain-language sentence including locating info). An empty list means pass.
     * **Do not** throw here: let the caller decide whether to fail or warn.
     *
     * @param loader the runtime class loader (`OMLClassLoader`). The handler may only exist inside it
     *   — e.g. a runtime-generated Mixin bridge class — so this class's own loader would fail to find it,
     *   degrading the self-check to "cannot resolve, skip validation", which is effectively no check.
     *   Passing null uses the loader of this class.
     */
    fun verify(loader: ClassLoader? = null): List<String>
}
