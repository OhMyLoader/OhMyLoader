package org.ohmyloader.api

/**
 * Unified metadata marker for a mod.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class Mod(
    val id: String,
    val name: String = "",
    val version: String = "1.0.0"
)

/**
 * Unified lifecycle entry point: after game initialization
 * completes, any [Mod]-annotated class implementing this interface is
 * instantiated and its [onInitialize] is called.
 */
fun interface OMLModInitializer {
    fun onInitialize(context: ModContext)
}
