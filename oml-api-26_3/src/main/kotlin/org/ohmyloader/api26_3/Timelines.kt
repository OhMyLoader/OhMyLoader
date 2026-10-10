package org.ohmyloader.api26_3

import net.minecraft.core.Registry
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.world.clock.ClockTimeMarker
import net.minecraft.world.timeline.Timeline

/*
 * A timeline is 26.3's declaration unit for named moments: it names a clock, an optional cycle length, and the
 * markers a clock can be moved onto. The game's own datapack reload is what puts one in the registry — OML only
 * serves the file — so these reads answer from the loaded table, and null or empty is the game's answer for an id
 * nothing registered. The table exists on both sides: the server's is built from the packs, the client's arrives
 * with the registry sync.
 */

/** The clock [timelineId] drives, spelled the way the game resolved it; null when no such timeline is registered. */
fun Registry<Timeline>.clockDrivenBy(timelineId: String): String? =
    byId(timelineId)?.clock()?.getRegisteredName()

/** Every marker [timelineId] declares, id to the tick of the cycle it names — including the ones `/time set` hides. */
fun Registry<Timeline>.timeMarkersOf(timelineId: String): Map<String, Int> {
    val timeline = byId(timelineId) ?: return emptyMap()
    val out = LinkedHashMap<String, Int>()
    timeline.registerTimeMarkers { key: ResourceKey<ClockTimeMarker>, marker: ClockTimeMarker ->
        out[key.identifier().toString()] = marker.ticks()
    }
    return out
}

/** The marker ids that move [clockId], gathered from every timeline driving it. */
fun Registry<Timeline>.markerIdsDriving(clockId: String): List<String> =
    ids().filter { clockDrivenBy(it) == clockId }.flatMap { timeMarkersOf(it).keys }

/** Ids of every timeline the game holds, the ones a mod's content pack declared included. */
val MinecraftServer.timelineIds: List<String> get() = registry(Registries.TIMELINE)?.ids() ?: emptyList()

/** The markers declared for [clockId] on this server; empty when the clock has none or is not registered. */
fun MinecraftServer.markerIdsDriving(clockId: String): List<String> =
    registry(Registries.TIMELINE)?.markerIdsDriving(clockId) ?: emptyList()
