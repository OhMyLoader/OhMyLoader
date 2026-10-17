package org.ohmyloader.api26_3

import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.storage.LevelData

/*
 * 26.3 keeps two respawn anchors: one per player and one for the world, both as the game's own
 * `LevelData.RespawnData` — a global position plus the view angles. Every write hands the game that record and
 * reports by reading it back, because an anchor that is stored but not where it was set would only show at the
 * moment the player dies. Both refusals matter for the same reason: the game stores any dimension key it is
 * handed, so a typo'd dimension would silently resolve to the overworld at respawn time.
 */

/**
 * The spawn this player returns to, as the game holds it; null means the player returns to the world's spawn.
 *
 * An anchor carries its dimension, which is what makes it per-player state: 26.3 keeps exactly **one** world
 * spawn record for the whole server, so naming a dimension there re-points that single record instead of
 * giving the dimension a spawn of its own.
 */
val ServerPlayer.respawnAnchor: LevelData.RespawnData? get() = respawnConfig?.respawnData()

/**
 * Moves this player's respawn anchor to the dimension [dimensionId] names, which has to be a dimension the
 * server's own registry knows. The config is marked forced because that is what an explicitly chosen position
 * means — the game uses it as given instead of re-checking whether a bed or respawn anchor still stands there.
 * [announce] is the game's own chat message, which `/spawnpoint` also sends. False when the dimension is
 * unknown, this is not a server-side player, or the anchor does not read back where it was set.
 */
fun ServerPlayer.setRespawnAnchor(
    dimensionId: String,
    position: BlockPos,
    yaw: Float = 0f,
    pitch: Float = 0f,
    announce: Boolean = true,
): Boolean {
    val dimension = OMLRegistries.dimension(dimensionId) ?: return false
    val server = level().server
    if (server.registry(Registries.DIMENSION)?.contains(dimensionId) != true) return false
    setRespawnPosition(
        ServerPlayer.RespawnConfig(LevelData.RespawnData.of(dimension, position, yaw, pitch), true),
        announce,
    )
    val anchor = respawnAnchor ?: return false
    return anchor.pos() == position && anchor.yaw() == yaw && anchor.pitch() == pitch &&
        anchor.globalPos().dimension().identifier() == dimension.identifier()
}

/** Drops the player's anchor so they return to the world's spawn, and reports that nothing is left. */
fun ServerPlayer.clearRespawnAnchor(): Boolean {
    setRespawnPosition(null, false)
    return respawnAnchor == null
}

/**
 * The world's spawn — the record players with no anchor of their own return to. It hangs off the server because
 * that is the game's own scope: `ServerLevel`'s read and write both forward to the server, so asking any level
 * answers with this one record. It is the position a respawn **lands on**, which is the requested one moved
 * inside that dimension's world border.
 */
val MinecraftServer.worldSpawn: LevelData.RespawnData get() = overworld().respawnData

/**
 * Re-points the world's spawn at [dimensionId], which has to be a dimension the server's own registry knows:
 * the game resolves the record's dimension through its loaded levels and falls back to the overworld when no
 * level matches, so a typo would only surface as a player standing in the wrong world. One record serves the
 * whole server, so this replaces the spawn of whatever dimension it named before.
 *
 * False when the game will not respawn a player at [position]: the record is kept, then moved inside
 * [dimensionId]'s world border before being handed back, so a position outside the border reads back as the
 * border's center. The angles are what a player is set to on arrival.
 */
fun MinecraftServer.setWorldSpawn(
    dimensionId: String,
    position: BlockPos,
    yaw: Float = 0f,
    pitch: Float = 0f,
): Boolean {
    val dimension = OMLRegistries.dimension(dimensionId) ?: return false
    if (registry(Registries.DIMENSION)?.contains(dimensionId) != true) return false
    overworld().respawnData = LevelData.RespawnData.of(dimension, position, yaw, pitch)
    val held = worldSpawn
    return held.pos() == position && held.yaw() == yaw && held.pitch() == pitch &&
        held.globalPos().dimension().identifier() == dimension.identifier()
}
