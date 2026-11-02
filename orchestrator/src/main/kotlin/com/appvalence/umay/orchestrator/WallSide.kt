package com.appvalence.umay.orchestrator

import com.appvalence.umay.coremodel.OccupancyRaster
import kotlin.math.floor
import kotlin.math.hypot

/**
 * Which side of a leg a wall runs along, from the floor's wall map — for
 * "duvarı solunda tutarak ilerle", the shorelining a cane user already knows.
 * Only said when one side has wall close by along most of the leg and the
 * other is clearly open: a corridor with walls both sides gets no hint, and
 * where the map hasn't seen, nothing is assumed.
 */
class WallSide(private val raster: OccupancyRaster) {
    private val cells: ByteArray? = raster.decodeCells()

    /** [TurnDirection.LEFT] or RIGHT walking from (fromX, fromY) to (toX, toY); null for no clear side. */
    fun along(fromX: Double, fromY: Double, toX: Double, toY: Double): TurnDirection? {
        cells ?: return null
        val length = hypot(toX - fromX, toY - fromY)
        if (length < MIN_LEG_METERS) return null
        // Unit vector along the leg, and to its left (rotate 90° counter-clockwise).
        val ux = (toX - fromX) / length
        val uy = (toY - fromY) / length
        val lx = -uy
        val ly = ux
        var samples = 0
        var leftWalls = 0
        var rightWalls = 0
        var leftOpen = 0
        var rightOpen = 0
        var distance = EDGE_SKIP_METERS
        while (distance <= length - EDGE_SKIP_METERS) {
            val x = fromX + ux * distance
            val y = fromY + uy * distance
            samples++
            when (look(x, y, lx, ly)) {
                Seen.WALL -> leftWalls++
                Seen.OPEN -> leftOpen++
                Seen.UNKNOWN -> Unit
            }
            when (look(x, y, -lx, -ly)) {
                Seen.WALL -> rightWalls++
                Seen.OPEN -> rightOpen++
                Seen.UNKNOWN -> Unit
            }
            distance += SAMPLE_SPACING_METERS
        }
        if (samples == 0) return null
        val needed = samples * MAJORITY
        return when {
            leftWalls >= needed && rightOpen >= needed -> TurnDirection.LEFT
            rightWalls >= needed && leftOpen >= needed -> TurnDirection.RIGHT
            else -> null
        }
    }

    private enum class Seen { WALL, OPEN, UNKNOWN }

    /**
     * Walking out from (x, y) in direction (dx, dy): a wall within reach, floor seen
     * all the way to it, or not known (a cell the map never saw comes first).
     */
    private fun look(x: Double, y: Double, dx: Double, dy: Double): Seen {
        var reach = raster.cellSizeMeters
        while (reach <= WALL_REACH_METERS) {
            when (cellAt(x + dx * reach, y + dy * reach)) {
                OccupancyRaster.OCCUPIED -> return Seen.WALL
                OccupancyRaster.FREE -> Unit
                else -> return Seen.UNKNOWN
            }
            reach += raster.cellSizeMeters
        }
        return Seen.OPEN
    }

    private fun cellAt(x: Double, y: Double): Byte {
        val column = floor((x - raster.originX) / raster.cellSizeMeters).toInt()
        val row = floor((y - raster.originY) / raster.cellSizeMeters).toInt()
        if (column !in 0 until raster.width || row !in 0 until raster.height) return OccupancyRaster.UNKNOWN
        return cells!![row * raster.width + column]
    }

    companion object {
        /** Arm's reach with a cane: a wall farther than this isn't one to follow. */
        const val WALL_REACH_METERS = 1.3
        const val SAMPLE_SPACING_METERS = 0.5
        const val MAJORITY = 0.7
        private const val MIN_LEG_METERS = 3.0

        // The ends of a leg are at a place, often a doorway or junction; judge the middle.
        private const val EDGE_SKIP_METERS = 1.0
    }
}
