package org.ohmyloader.api26_3

import net.minecraft.server.MinecraftServer
import net.minecraft.core.registries.Registries
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
