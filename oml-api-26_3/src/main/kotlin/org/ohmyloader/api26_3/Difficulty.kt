package org.ohmyloader.api26_3

import net.minecraft.server.MinecraftServer
import net.minecraft.world.Difficulty

/** The server's difficulty id — `peaceful` / `easy` / `normal` / `hard`. */
val MinecraftServer.difficultyId: String
    get() = worldData.difficulty.serializedName

/**
 * Writes the server difficulty through the same `setDifficulty(difficulty, force = true)` call the
 * `/difficulty` command takes. False for an unknown id; a locked difficulty is left locked by the
 * game and reads back unchanged.
 */
fun MinecraftServer.setDifficulty(id: String): Boolean {
    val difficulty = Difficulty.byName(id.lowercase()) ?: return false
    setDifficulty(difficulty, true)
    return difficultyId == id.lowercase()
}
