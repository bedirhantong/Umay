package com.appvalence.umay.orchestrator

import com.appvalence.umay.coremodel.OccupancyRaster
import kotlin.math.floor
import kotlin.math.hypot

/**
 * Whether there's room to walk past something standing in a corridor, from
 * the floor's wall map: the free floor beside it, on each side, across the
 * direction of travel. Something that leaves room on one side is walked
 * round, not a reason to send the walker another way.
 *
 * Conservative: floor the map never saw counts as no room, so with a poor or
 * missing wall map the answer is "don't know" (null) and the route changes as
 * it always did.
 */
class Passage(private val raster: OccupancyRaster) {
    private val cells: ByteArray? = raster.decodeCells()

    /** Which side has room, and how much. */
    data class Gap(val side: TurnDirection, val meters: Double)

    /**
     * The roomier side of an obstacle [widthMeters] wide centred at ([x], [y]), for a
     * walker heading along ([dirX], [dirY]); null when neither side was ever seen.
     */
    fun widestGap(x: Double, y: Double, dirX: Double, dirY: Double, widthMeters: Double): Gap? {
        cells ?: return null
        val length = hypot(dirX, dirY)
        if (length == 0.0) return null
        val ux = dirX / length
        val uy = dirY / length
        // To the walker's left: rotate the heading 90° counter-clockwise.
        val lx = -uy
        val ly = ux
        val left = gapToward(x, y, ux, uy, lx, ly, widthMeters / 2)
        val right = gapToward(x, y, ux, uy, -lx, -ly, widthMeters / 2)
        return when {
            left == null && right == null -> null
            (left ?: -1.0) >= (right ?: -1.0) -> Gap(TurnDirection.LEFT, left ?: 0.0)
            else -> Gap(TurnDirection.RIGHT, right ?: 0.0)
        }
    }

    /**
     * Free floor out from the obstacle's side, the narrowest of a few cuts across its
     * depth (a walker passes its whole length, not one line); null when unseen.
     */
    private fun gapToward(x: Double, y: Double, ux: Double, uy: Double, sx: Double, sy: Double, halfWidth: Double): Double? {
        var narrowest = Double.MAX_VALUE
        var seen = false
        for (along in DEPTH_CUTS) {
            val cx = x + ux * along + sx * halfWidth
            val cy = y + uy * along + sy * halfWidth
            var reach = 0.0
            while (reach < MAX_LOOK_METERS) {
                val cell = cellAt(cx + sx * reach, cy + sy * reach)
                if (cell == OccupancyRaster.UNKNOWN) break
                seen = true
                if (cell == OccupancyRaster.OCCUPIED) break
                reach += raster.cellSizeMeters
            }
            narrowest = minOf(narrowest, reach)
        }
        return if (seen) narrowest else null
    }

    private fun cellAt(x: Double, y: Double): Byte {
        val column = floor((x - raster.originX) / raster.cellSizeMeters).toInt()
        val row = floor((y - raster.originY) / raster.cellSizeMeters).toInt()
        if (column !in 0 until raster.width || row !in 0 until raster.height) return OccupancyRaster.UNKNOWN
        return cells!![row * raster.width + column]
    }

    companion object {
        /** Room a cane user walks through comfortably: a doorway's width, about. */
        const val PASSABLE_METERS = 0.8

        // Beyond this the gap is plenty; no need to look farther.
        private const val MAX_LOOK_METERS = 2.0
        private val DEPTH_CUTS = listOf(-0.3, 0.0, 0.3)
    }
}
