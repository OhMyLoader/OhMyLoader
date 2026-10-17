package org.ohmyloader.api26_3

import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.dimension.DimensionType

/*
 * 26.3 builds a level for every entry in its `dimension` table when the server starts, and a mod's content
 * pack feeds that table — so these reads answer the question a mod cannot otherwise ask cheaply: did the game
 * take the declaration, and which dimension type is a running level actually obeying. The type itself is the
 * game's own record, so its fields (`hasSkyLight()`, `height()`, `coordinateScale()`) are read straight off it.
 */

/** Every dimension type the running server holds, vanilla's and the ones a content pack declared. */
val MinecraftServer.dimensionTypeIds: List<String>
    get() = registry(Registries.DIMENSION_TYPE)?.ids() ?: emptyList()

/** The dimension type [id] names (`minecraft:overworld`); null when nothing registered it. */
fun MinecraftServer.dimensionTypeById(id: String): DimensionType? =
    registry(Registries.DIMENSION_TYPE)?.byId(id)

/** The id of the type this level runs on; null when the table holds no key for the level's own type object. */
val Level.dimensionTypeId: String?
    get() = registryAccess().lookupOrThrow(Registries.DIMENSION_TYPE).getKey(dimensionType())?.toString()

/**
 * Forces the chunk holding [position] to stay loaded and ticking — the call `/forceload add` makes, down to
 * the game's own `TicketType.FORCED` at its forced level. Answers whether the ticket table *changed*, so
 * forcing the same chunk twice reports false the second time; [forceLoadedChunkPositions] is the read-back. A forced
 * chunk needs no player nearby, which is how a dimension a mod declared gets to run anything in it.
 */
fun ServerLevel.forceLoadChunk(position: BlockPos): Boolean =
    ChunkPos.containing(position).let { setChunkForced(it.x(), it.z(), true) }

/** Releases the chunk holding [position] through the game's own removal; false when nothing was forced. */
fun ServerLevel.releaseForceLoadedChunk(position: BlockPos): Boolean =
    ChunkPos.containing(position).let { setChunkForced(it.x(), it.z(), false) }

/** The chunks this dimension's ticket table holds as force-loaded, unpacked from the longs the game packs.
 * Named apart from `getForceLoadedChunks()` because that member already owns the shorter property name. */
val ServerLevel.forceLoadedChunkPositions: List<ChunkPos>
    get() = forceLoadedChunks.map { ChunkPos.unpack(it) }

/** Whether [position]'s chunk is force-loaded, asked of that same table rather than remembered. */
fun ServerLevel.isForceLoaded(position: BlockPos): Boolean =
    forceLoadedChunks.contains(ChunkPos.containing(position).pack())
