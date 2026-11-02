package com.appvalence.umay.orchestrator

import com.appvalence.umay.coremodel.OccupancyRaster
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WallSideTest {

    /** A 10 m × 6 m area at 0.1 m cells: open floor, with walls where [wall] says. */
    private fun area(wall: (x: Double, y: Double) -> Boolean, seen: (x: Double, y: Double) -> Boolean = { _, _ -> true }): OccupancyRaster {
        val width = 60
        val height = 100
        val cells = ByteArray(width * height) { i ->
            val x = (i % width + 0.5) * 0.1
            val y = (i / width + 0.5) * 0.1
            when {
                !seen(x, y) -> OccupancyRaster.UNKNOWN
                wall(x, y) -> OccupancyRaster.OCCUPIED
                else -> OccupancyRaster.FREE
            }
        }
        return OccupancyRaster.of(0.1, 0.0, 0.0, width, height, cells)
    }

    @Test
    fun `a wall along the left of a northward walk is on the left`() {
        // Walking north along x = 3 with a wall at x = 2.
        val side = WallSide(area({ x, _ -> x in 2.0..2.1 })).along(3.0, 0.5, 3.0, 9.5)

        assertEquals(TurnDirection.LEFT, side)
    }

    @Test
    fun `walking the other way, the same wall is on the right`() {
        assertEquals(TurnDirection.RIGHT, WallSide(area({ x, _ -> x in 2.0..2.1 })).along(3.0, 9.5, 3.0, 0.5))
    }

    @Test
    fun `walls on both sides, or no wall at all, give no hint`() {
        assertNull(WallSide(area({ x, _ -> x in 2.0..2.1 || x in 3.9..4.0 })).along(3.0, 0.5, 3.0, 9.5))
        assertNull(WallSide(area({ _, _ -> false })).along(3.0, 0.5, 3.0, 9.5))
    }

    @Test
    fun `an unseen side is never taken for open floor`() {
        val side = WallSide(area({ x, _ -> x in 2.0..2.1 }, seen = { x, _ -> x < 3.5 })).along(3.0, 0.5, 3.0, 9.5)

        assertNull(side)
    }
}
