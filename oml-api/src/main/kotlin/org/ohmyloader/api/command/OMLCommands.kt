package org.ohmyloader.api.command

/** The argument flavors the high-level command subset understands. */
enum class OMLArgumentType { WORD, STRING, GREEDY_STRING, INTEGER, FLOAT, BOOLEAN }

/**
 * One node of a declared command tree: the root literal, a subcommand, or a typed argument.
 * Built through [OMLCommandRegistry.register]; the version adapter translates the tree into the
 * game's command dispatcher at construction time.
 */
class OMLCommandDeclaration(
    // Constructed by the loader's registration facade; mods build nodes through the DSL.
    val name: String,
    val argumentType: OMLArgumentType?,
) {
    /**
     * Minimum permission level required to execute this node (0 = everyone, vanilla op levels
     * above). Applies to this node and everything below it.
     */
    var permissionLevel: Int = 0

    internal var executes: ((OMLCommandSource) -> Unit)? = null

    /** Whether an executes handler was declared (the adapter's dispatch decision). */
    val hasExecute: Boolean get() = executes != null

    val children = mutableListOf<OMLCommandDeclaration>()

    /** Invoked by the version adapter's bridge at dispatch time; runs the declared handler. */
    fun execute(source: OMLCommandSource) {
        executes?.invoke(source)
    }

    /** Runs when this node terminates the command (a node can hold both children and a handler). */
    fun executes(handler: (OMLCommandSource) -> Unit) {
        executes = handler
    }

    /** A literal subcommand: `/root <name> …`. */
    fun subcommand(name: String, configure: OMLCommandDeclaration.() -> Unit) {
        children += OMLCommandDeclaration(name, null).apply(configure)
    }

    /** A typed argument: `/root <name> <value> …`; values are read in the handler via [OMLCommandSource]. */
    fun argument(name: String, type: OMLArgumentType, configure: OMLCommandDeclaration.() -> Unit) {
        children += OMLCommandDeclaration(name, type).apply(configure)
    }
}

/** Mod-facing command registration facade, bound to the declaring mod's id. */
interface OMLCommandRegistry {
    /** Declares the root literal `/<name>`; a malformed duplicate registration throws. */
    fun register(name: String, configure: OMLCommandDeclaration.() -> Unit)
}

/**
 * One command invocation: who ran it, and the argument values resolved along the executed path.
 * [platform] is the raw version source object — the same escape hatch as [OMLBlock.platform].
 */
// Constructed by the version adapter's bridge at dispatch time.
class OMLCommandSource(
    /** The invoker's display name (the player, or "Server" for the console). */
    val name: String,
    /** True when a player executed the command; console and function callers are not players. */
    val hasPlayer: Boolean,
    private val arguments: Map<String, Any>,
    platformSupplier: () -> Any,
) {
    val platform: Any by lazy(platformSupplier)

    fun getString(name: String): String = argument(name) as String
    fun getInt(name: String): Int = argument(name) as Int
    fun getDouble(name: String): Double = argument(name) as Double
    fun getBoolean(name: String): Boolean = argument(name) as Boolean

    private fun argument(name: String): Any =
        arguments[name] ?: error("command argument '$name' is not part of the executed path")
}
