package org.ohmyloader.adapter.v26_3

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.BoolArgumentType
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.ArgumentBuilder
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.tree.LiteralCommandNode
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import org.ohmyloader.api.OmlLog
import org.ohmyloader.api.command.OMLArgumentType
import org.ohmyloader.api.command.OMLCommandDeclaration
import org.ohmyloader.api.command.OMLCommandSource
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
        for (entry in CommandDeclarations.entries) {
            try {
                val builder = build(entry.modId, entry.root, emptyList())
                dispatcher.register(builder as LiteralArgumentBuilder<CommandSourceStack>)
            } catch (t: Throwable) {
                OmlLog.error("Commands", "registering /${entry.root.name} from mod [${entry.modId}] failed", t)
            }
        }
    }

    /** [path] carries the argument chain from root to [node] — the names its handlers may read. */
    private fun build(
        modId: String,
        node: OMLCommandDeclaration,
        path: List<Pair<String, OMLArgumentType>>,
    ): ArgumentBuilder<CommandSourceStack, *> {
        val newPath = path + (node.argumentType?.let { listOf(node.name to it) } ?: emptyList())
        val builder: ArgumentBuilder<CommandSourceStack, *> = when (val type = node.argumentType) {
            null -> LiteralArgumentBuilder.literal(node.name)
            else -> RequiredArgumentBuilder.argument<CommandSourceStack, Any>(
                node.name,
                @Suppress("UNCHECKED_CAST") argumentTypeInstance(type) as com.mojang.brigadier.arguments.ArgumentType<Any>,
            )
        }
        if (node.permissionLevel > 0) {
            builder.requires { permissionCheck(node.permissionLevel).check(it.permissions()) }
        }
        if (node.hasExecute || node.children.isNotEmpty()) {
            builder.executes { context ->
                val source = OMLCommandSource(
                    context.source.getTextName(),
                    context.source.getPlayer() != null,
                    newPath.associate { (name, type) -> name to extract(context, name, type) },
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

    private fun extract(context: CommandContext<CommandSourceStack>, name: String, type: OMLArgumentType): Any =
        when (type) {
            OMLArgumentType.WORD, OMLArgumentType.STRING, OMLArgumentType.GREEDY_STRING ->
                context.getArgument(name, String::class.java)

            OMLArgumentType.INTEGER -> context.getArgument(name, Integer::class.javaPrimitiveType)
            OMLArgumentType.FLOAT -> context.getArgument(name, java.lang.Double.TYPE)
            OMLArgumentType.BOOLEAN -> context.getArgument(name, java.lang.Boolean.TYPE)
        }

    private fun argumentTypeInstance(type: OMLArgumentType): Any =
        when (type) {
            OMLArgumentType.WORD -> StringArgumentType.word()
            OMLArgumentType.STRING -> StringArgumentType.string()
            OMLArgumentType.GREEDY_STRING -> StringArgumentType.greedyString()
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
            else -> Commands.LEVEL_OWNERS
        }
}
