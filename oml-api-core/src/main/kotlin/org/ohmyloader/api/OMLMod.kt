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

/**
 * The registry-declaration phase: at the game's registry freeze point — before the vanilla
 * registries are frozen and before content-pack materialization — every [Mod] class implementing
 * this interface is instantiated and its [onDeclareRegistries] is called, once per launch. This is
 * the only moment a mod may register into the vanilla registries (blocks, items, block entity
 * types, menus, creative tabs, …) by direct game-API calls; [onInitialize] runs after game
 * initialization, when the registries are already frozen.
 *
 * Game classes are reachable here: mod code runs on the game class loader. Registration failures
 * are logged per mod and do not abort the launch, but a mod that failed here will misbehave at
 * runtime (missing blocks, unresolvable menus), so treat the log as the gate.
 */
interface OMLRegistryInitializer {
    fun onDeclareRegistries(context: ModContext)
}
