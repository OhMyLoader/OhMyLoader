package org.ohmyloader.api.command

/**
 * The argument flavors the high-level command subset understands, each carrying the Kotlin type its
 * values read back as. The version adapter translates each flavor into the game's own argument type.
 */
sealed class OMLArgumentType<T> {
    object WORD : OMLArgumentType<String>()
    object STRING : OMLArgumentType<String>()
    object GREEDY_STRING : OMLArgumentType<String>()
    object INTEGER : OMLArgumentType<Int>()
    object FLOAT : OMLArgumentType<Double>()
    object BOOLEAN : OMLArgumentType<Boolean>()
}

/**
 * A typed handle to one declared argument: returned by [OMLCommandDeclaration.argument] and read
 * through [OMLCommandSource.get]. Carrying the handle (not the name string) is what makes the value's
 * type a compile-time fact at the read site.
 */
class OMLArgument<T>(val name: String, val type: OMLArgumentType<T>)

/**
 * One node of a declared command tree: the root literal, a subcommand, or a typed argument.
 * Built through [OMLCommandRegistry.register]; the version adapter translates the tree into the
 * game's command dispatcher at construction time.
 */
class OMLCommandDeclaration(
    // Constructed by the loader's registration facade; mods build nodes through the DSL.
    val name: String,
    val argumentType: OMLArgumentType<*>?,
) {
    /**
     * Minimum permission level required to execute this node (0 = everyone, vanilla op levels
     * above). Applies to this node and everything below it. A value outside 0..4 is refused with
     * an error when the tree is translated into the game's dispatcher — the command is not
     * registered and the log names the declaring mod.
     */
    var permissionLevel: Int = 0

    /**
     * A fine-grained permission node this command requires, addressed by id (`mymod:build`); the source's own
     * `PermissionSet` answers for it, so a node nothing grants keeps the command out of reach and an
     * `org.ohmyloader.api26_3.grantPermissionNode` puts it back. Null leaves the gate to [permissionLevel].
     */
    var permissionNode: String? = null

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

    /**
     * The answering form of [executes] for a leaf that computes a text: the returned string is
     * replied on the success channel, so computing an answer and answering cannot come apart. A
     * leaf with a conditional failure channel (`replyError`) still wants [executes].
     */
    fun answers(handler: (OMLCommandSource) -> String) {
        executes = { source -> source.reply(handler(source)) }
    }

    /** A literal subcommand: `/root <name> …`. */
    fun subcommand(name: String, configure: OMLCommandDeclaration.() -> Unit) {
        children += OMLCommandDeclaration(name, null).apply(configure)
    }

    /**
     * A typed argument: `/root <name> <value> …`. [configure] also receives the argument's handle —
     * capture it (including in nested nodes' handlers) and read the value with [OMLCommandSource.get].
     */
    fun <T> argument(
        name: String,
        type: OMLArgumentType<T>,
        configure: OMLCommandDeclaration.(OMLArgument<T>) -> Unit,
    ) {
        val argument = OMLArgument(name, type)
        children += OMLCommandDeclaration(name, type).also { it.configure(argument) }
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
    private val replyFn: (String) -> Unit,
    private val replyErrorFn: (String) -> Unit,
    platformSupplier: () -> Any,
) {
    val platform: Any by lazy(platformSupplier)

    /** Reports a successful result back to the invoker (chat for players, stdout for the console). */
    fun reply(message: String) = replyFn(message)

    /** Reports a failure back to the invoker — rendered in red for players. */
    fun replyError(message: String) = replyErrorFn(message)

    /** Reads a declared argument's value; the handle fixes both the identity and the type. */
    operator fun <T> get(argument: OMLArgument<T>): T =
        arguments[argument.name] as? T
            ?: error("command argument '${argument.name}' is not part of the executed path")
}
