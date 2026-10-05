package org.ohmyloader.api

/**
 * Unified metadata marker for a mod.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class Mod(
    val id: String,
    val name: String = "",
    val version: String = "1.0.0",
    /**
     * Mods this one requires, each `"modId"` or `"modId@<constraint>"` — the constraint is one of
     * `>=`, `<=`, `>`, `<`, `=` followed by a dotted version (`"other@>=1.2.0"`). A dependency that
     * is absent, or whose version violates the constraint, aborts loading with an error naming both
     * sides; declared dependencies also order initialization (dependents initialize after their
     * dependencies).
     */
    val dependencies: Array<String> = [],
)

/**
 * Unified lifecycle entry point: after game initialization
 * completes, any [Mod]-annotated class implementing this interface is
 * instantiated and its [onInitialize] is called.
 */
fun interface OMLModInitializer {
    fun onInitialize(context: ModContext)
}
