package org.ohmyloader.api.world

/** Block-shaped position: integer coordinates of a block in a world. Positions compare by value. */
data class OMLBlockPos(val x: Int, val y: Int, val z: Int)

/** World position in block units. Compares by value. */
data class OMLVec3(val x: Double, val y: Double, val z: Double)
