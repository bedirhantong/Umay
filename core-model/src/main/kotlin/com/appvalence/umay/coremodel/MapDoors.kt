package com.appvalence.umay.coremodel

import kotlinx.serialization.Serializable
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A doorway in a floor's wall map: where it is ([nodeId]'s position), how wide,
 * and which way one walks through it ([passageBearingDeg], clockwise from +y).
 * The wall runs across that bearing.
 */
@Serializable
data class DoorOpening(
    val nodeId: String,
    val widthMeters: Double = MapDoors.DEFAULT_WIDTH_METERS,
    val passageBearingDeg: Double,
)

/**
 * Makes a floor's doors part of its wall map. The camera sees a closed door as
 * wall, so a map surveyed with the doors shut would show no way through; every
 * [NodeType.DOOR] place gets a [DoorOpening] from the direction the survey
 * walked through it, and the wall map gets the matching gap. Applying it again
 * is harmless: openings are recomputed and an already-open gap stays open.
 */
object MapDoors {
    const val DEFAULT_WIDTH_METERS = 0.9

    // How far either side of the door line the gap is cleared: a door frame plus
    // the wall's thickness as the camera smears it.
    private const val DEPTH_METERS = 0.35

    fun apply(map: BuildingMap): BuildingMap = map.copy(floors = map.floors.map(::apply))

    fun apply(floor: FloorMap): FloorMap {
        val nodesById = floor.nodes.associateBy { it.id }
        val previous = floor.doors.associateBy { it.nodeId }
        val doors = floor.nodes.filter { it.type == NodeType.DOOR }.mapNotNull { door ->
            val bearing = passageBearing(door, floor.edges, nodesById) ?: return@mapNotNull null
            DoorOpening(door.id, previous[door.id]?.widthMeters ?: DEFAULT_WIDTH_METERS, bearing)
        }
        val carved = floor.occupancy?.let { raster -> doors.fold(raster) { walls, door -> carve(walls, nodesById.getValue(door.nodeId), door) } }
        return floor.copy(doors = doors, occupancy = carved)
    }

    /** The direction walked through [door]: along its edges (averaged, if two line up). */
    private fun passageBearing(door: MapNode, edges: List<MapEdge>, nodesById: Map<String, MapNode>): Double? {
        val neighbours = edges.mapNotNull { edge ->
            when (door.id) {
                edge.fromId -> nodesById[edge.toId]
                edge.toId -> nodesById[edge.fromId]
                else -> null
            }
        }
        if (neighbours.isEmpty()) return null
        // Sum of unit vectors pointing "through" the door: toward the first neighbour,
        // away from the others, so a door between two places averages both legs.
        var dx = 0.0
        var dy = 0.0
        neighbours.forEachIndexed { i, neighbour ->
            val vx = neighbour.x - door.x
            val vy = neighbour.y - door.y
            val length = sqrt(vx * vx + vy * vy)
            if (length < 1e-6) return@forEachIndexed
            val sign = if (i == 0) 1.0 else -1.0
            dx += sign * vx / length
            dy += sign * vy / length
        }
        if (dx * dx + dy * dy < 1e-9) return null
        return (Math.toDegrees(atan2(dx, dy)) + 360.0) % 360.0
    }

    private fun carve(raster: OccupancyRaster, door: MapNode, opening: DoorOpening): OccupancyRaster {
        val cells = raster.decodeCells() ?: return raster
        val size = raster.cellSizeMeters
        val bearing = Math.toRadians(opening.passageBearingDeg)
        // Passage direction (through the door) and the wall direction across it.
        val px = sin(bearing)
        val py = cos(bearing)
        val wx = py
        val wy = -px
        val halfWidth = opening.widthMeters / 2
        val reach = halfWidth + DEPTH_METERS
        val firstColumn = floor((door.x - reach - raster.originX) / size).toInt().coerceAtLeast(0)
        val lastColumn = floor((door.x + reach - raster.originX) / size).toInt().coerceAtMost(raster.width - 1)
        val firstRow = floor((door.y - reach - raster.originY) / size).toInt().coerceAtLeast(0)
        val lastRow = floor((door.y + reach - raster.originY) / size).toInt().coerceAtMost(raster.height - 1)
        var changed = false
        for (row in firstRow..lastRow) {
            for (column in firstColumn..lastColumn) {
                val cx = raster.originX + (column + 0.5) * size - door.x
                val cy = raster.originY + (row + 0.5) * size - door.y
                val across = cx * wx + cy * wy
                val along = cx * px + cy * py
                if (kotlin.math.abs(across) <= halfWidth && kotlin.math.abs(along) <= DEPTH_METERS) {
                    val index = row * raster.width + column
                    if (cells[index] == OccupancyRaster.OCCUPIED) {
                        cells[index] = OccupancyRaster.FREE
                        changed = true
                    }
                }
            }
        }
        return if (changed) OccupancyRaster.of(size, raster.originX, raster.originY, raster.width, raster.height, cells) else raster
    }
}
