package org.ohmyloader.adapter

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.BoolArgumentType
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.ArgumentBuilder
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component
import net.minecraft.server.permissions.Permission
import net.minecraft.server.permissions.PermissionCheck
import net.minecraft.server.permissions.PermissionLevel
import net.minecraft.server.permissions.PermissionProviderCheck
import net.minecraft.server.permissions.PermissionSet
import org.ohmyloader.api.core.OmlLog
import org.ohmyloader.api.core.command.OMLArgumentType
import org.ohmyloader.api.core.command.OMLCommandDeclaration
import org.ohmyloader.api.core.command.OMLCommandSource
import org.ohmyloader.api.hasNode
import org.ohmyloader.core.command.CommandDeclarations

/**
 * Translates the collected command trees into the game's Brigadier dispatcher. The dispatcher is
 * rebuilt per resource load, so [register] runs for every construction; identity-keyed dedup keeps
 * a dispatcher that both hook points hand over registered exactly once. A mod handler exception is
 * reported to the invoker and logged — commands have an error channel, and a broken command must
 * not kill the tick it ran in.
 */
object OMLCommandBridge {

    private val registered = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())

    /**
     * The dispatcher itself is the dedup key, not the `Commands` wrapper: the 26.3 line hands the
     * wrapper over and the bridge unwraps it (in the 26.3 adapter, shared bytecode must not name
     * `getDispatcher`), while the snapshot line hands the dispatcher over directly.
     */
    @JvmStatic
    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        if (!registered.add(dispatcher)) return
        OmlLog.info("Commands", "dispatcher handover: ${CommandDeclarations.entries.size} mod command root(s)")
        for ((modId, root) in CommandDeclarations.entries) {
            try {
                dispatcher.register(build(modId, root, emptyList()) as LiteralArgumentBuilder<CommandSourceStack>)
                // Aliases are full trees of their own: same shape, same handler, same gates. The
                // game's own completion offers them like any literal, and a duplicate alias name
                // collides the same way a duplicate root does — named, not guessed.
                for (alias in root.aliases) {
                    dispatcher.register(
                        build(modId, root.aliasCopy(alias), emptyList()) as LiteralArgumentBuilder<CommandSourceStack>,
                    )
                }
            } catch (t: Throwable) {
                OmlLog.error("Commands", "registering /${root.name} from mod [$modId] failed", t)
            }
        }
    }

    /**
     * Registers the mod commands into the dispatcher a live `Commands` instance parses with.
     *
     * The construction-time handover at `ParseBox.fromFullTree` sees only the declarations that
     * exist while the instance is built; on the dedicated server the real instance is built before
     * the mods' late declarations (the built-in `/oml` node, for one) land. The execution entry
     * point therefore re-runs this registration — the same laziness the 26.3 line got from its
     * `performPrefixedCommand` hook.
     */
    @JvmStatic
    fun registerFor(commands: net.minecraft.commands.Commands) {
        runCatching { register(dispatcherOf(commands)) }
            .onFailure { OmlLog.warn("Commands", "reading the dispatcher off Commands failed: $it") }
    }

    private val parseBoxField = runCatching {
        net.minecraft.commands.Commands::class.java.getDeclaredField("parseBox").apply { isAccessible = true }
    }.getOrNull()

    private val dispatcherField = runCatching {
        net.minecraft.commands.CommandBox::class.java.getDeclaredField("dispatcher").apply { isAccessible = true }
    }.getOrNull()

    @Suppress("UNCHECKED_CAST")
    private fun dispatcherOf(commands: net.minecraft.commands.Commands): CommandDispatcher<CommandSourceStack> {
        val box = parseBoxField?.get(commands) ?: error("Commands.parseBox is gone from this game version")
        return dispatcherField?.get(box) as CommandDispatcher<CommandSourceStack>
    }

    /** [path] carries the argument chain from root to [node] — the names its handlers may read. */
    private fun build(
        modId: String,
        node: OMLCommandDeclaration,
        path: List<Pair<String, OMLArgumentType<*>>>,
    ): ArgumentBuilder<CommandSourceStack, *> {
        val newPath = path + (node.argumentType?.let { listOf(node.name to it) } ?: emptyList())
        val builder = when (val type = node.argumentType) {
            null -> LiteralArgumentBuilder.literal(node.name)
            else -> {
                val required = RequiredArgumentBuilder.argument<CommandSourceStack, Any>(
                    node.name,
                    @Suppress("UNCHECKED_CAST")
                    argumentTypeInstance(type) as com.mojang.brigadier.arguments.ArgumentType<Any>,
                )
                // Suggestions ride Brigadier's own provider: the game offers the strings as the
                // player types and filters them itself, so a suggestion list never widens what the
                // WORD parser accepts.
                val suggestions = (type as? OMLArgumentType.WithSuggestions)?.suggestions
                if (suggestions != null) {
                    required.suggests { context, builder2 ->
                        // The same source shape the executes path hands the handler — suggestions
                        // see the invoker too (an online-player list, say).
                        val source = OMLCommandSource(
                            context.source.textName,
                            context.source.player != null,
                            emptyMap(),
                            {}, {},
                        ) { context.source }
                        for (candidate in suggestions(source)) builder2.suggest(candidate)
                        builder2.buildFuture()
                    }
                }
                required
            }
        }
        val level = node.permissionLevel
        // The game's own tiers are 0..4; a value outside that range must be refused here, not mapped
        // onto the closest tier — the `level > 0` gates below would silently drop the gate for a
        // negative level (everyone can run the command) and read 5+ as owner.
        require(level in 0..4) {
            "command node '$node' declares permission level $level, which is outside 0..4 " +
                "(0 = everyone, 1-4 = vanilla op levels)"
        }
        val requiredNode = node.permissionNode
        if (level > 0 || requiredNode != null) {
            // The game's tree walks every requirement as a PermissionProviderCheck (it refuses a bare
            // predicate), so the gate is expressed as a PermissionCheck over the source's set. The
            // node half reads OML's override-aware set, which is what makes a mod grant work here too.
            builder.requires(PermissionProviderCheck(OmlCommandCheck(level, requiredNode)))
        }
        if (node.hasExecute || node.children.isNotEmpty()) {
            builder.executes { context ->
                val source = OMLCommandSource(
                    context.source.textName,
                    context.source.player != null,
                    newPath.associate { [name, type] -> name to extract(context, name, type) },
                    { context.source.sendSuccess({ Component.literal(it) }, false) },
                    { context.source.sendFailure(Component.literal(it)) },
                ) { context.source }
                runCatching { node.execute(source) }.onFailure {
                    context.source.sendFailure(Component.literal("[$modId] command failed: ${it.message}"))
                    OmlLog.error("Commands", "command /${node.name} from mod [$modId] failed", it)
                }
                Command.SINGLE_SUCCESS
            }
        }
        for (child in node.children) {
            builder.then(build(modId, child, newPath).build())
        }
        return builder
    }

    private fun extract(context: CommandContext<CommandSourceStack>, name: String, type: OMLArgumentType<*>): Any =
        when (type) {
            OMLArgumentType.WORD, OMLArgumentType.STRING, OMLArgumentType.GREEDY_STRING,
            is OMLArgumentType.WithSuggestions,
                -> context.getArgument(name, String::class.java)

            OMLArgumentType.INTEGER -> context.getArgument(name, Integer::class.javaPrimitiveType)
            OMLArgumentType.FLOAT -> context.getArgument(name, java.lang.Double.TYPE)
            OMLArgumentType.BOOLEAN -> context.getArgument(name, java.lang.Boolean.TYPE)
        }

    private fun argumentTypeInstance(type: OMLArgumentType<*>): Any =
        when (type) {
            OMLArgumentType.WORD -> StringArgumentType.word()
            OMLArgumentType.STRING -> StringArgumentType.string()
            OMLArgumentType.GREEDY_STRING -> StringArgumentType.greedyString()
            is OMLArgumentType.WithSuggestions -> StringArgumentType.word()
            OMLArgumentType.INTEGER -> IntegerArgumentType.integer()
            OMLArgumentType.FLOAT -> DoubleArgumentType.doubleArg()
            OMLArgumentType.BOOLEAN -> BoolArgumentType.bool()
        }

    /**
     * The vanilla `LEVEL_*` checks are exactly `Require(HasCommandLevel(...))` (26.3's `Commands`
     * static init builds them this way), and that construction is the one shape both tracked jars
     * share — the constants themselves were dropped from `Commands` in the snapshot line.
     */
    private fun permissionCheck(level: Int): PermissionCheck =
        PermissionCheck.Require(Permission.HasCommandLevel(PermissionLevel.entries[level]))

    /**
     * A command node's gate: the vanilla op level (when [level] > 0) plus optionally a permission node
     * from OML's override-aware set. Runtime-only — the game only asks a requirement's `codec()` when a
     * tree is serialized as data, which mod commands never are; the client learns "restricted" as a
     * flag, not the check itself.
     */
    private class OmlCommandCheck(
        private val level: Int,
        private val nodeId: String?,
    ) : PermissionCheck {

        override fun check(permissions: PermissionSet): Boolean {
            if (level > 0 && !permissionCheck(level).check(permissions)) return false
            return nodeId == null || permissions.hasNode(nodeId)
        }

        override fun codec(): com.mojang.serialization.MapCodec<out PermissionCheck> =
            throw UnsupportedOperationException("OML command permission checks are runtime-only")
    }
}
