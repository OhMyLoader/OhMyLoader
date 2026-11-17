package org.ohmyloader.api.core.command

/**
 * Optional declaration entry point for slash commands: when a [Mod][org.ohmyloader.api.core.Mod]-annotated
 * class implements this, OML calls it once after [org.ohmyloader.api.core.OMLModInitializer.onInitialize].
 * The declared trees are translated into the game's command dispatcher per construction (server
 * start and every datapack reload rebuild it, and the declarations re-register each time).
 */
interface OMLCommandProvider {
    fun declareCommands(commands: OMLCommandRegistry)
}
