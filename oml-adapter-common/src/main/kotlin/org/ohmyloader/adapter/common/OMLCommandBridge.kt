package org.ohmyloader.adapter.common

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.BoolArgumentType
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.ArgumentBuilder
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import org.ohmyloader.api.OmlLog
import org.ohmyloader.api.command.OMLArgumentType
import org.ohmyloader.api.command.OMLCommandDeclaration
import org.ohmyloader.api.command.OMLCommandSource
import org.ohmyloader.api26_3.hasPermissionNode
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

    @JvmStatic
    fun register(commands: Any) {
        if (!registered.add(commands)) return
        val dispatcher = (commands as Commands).dispatcher
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
        if (level > 0 && requiredNode != null) {
            builder.requires { permissionCheck(level).check(it.permissions()) && it.hasPermissionNode(requiredNode) }
        } else if (level > 0) {
            builder.requires { permissionCheck(level).check(it.permissions()) }
        } else if (requiredNode != null) {
            builder.requires { it.hasPermissionNode(requiredNode) }
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

    private fun permissionCheck(level: Int): net.minecraft.server.permissions.PermissionCheck =
        when (level) {
            0 -> Commands.LEVEL_ALL
            1 -> Commands.LEVEL_MODERATORS
            2 -> Commands.LEVEL_GAMEMASTERS
            3 -> Commands.LEVEL_ADMINS
            4 -> Commands.LEVEL_OWNERS
            else -> throw IllegalArgumentException(
                "command permission level $level is outside 0..4 (0 = everyone, 1-4 = vanilla op levels)",
            )
        }
}
