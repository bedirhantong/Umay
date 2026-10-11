package com.appvalence.umay.orchestrator

import com.appvalence.umay.coremodel.OccupancyRaster
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A north–south corridor between walls at x = [left] and x = [right], 20 m long, at 10 cm. */
internal fun corridor(left: Double, right: Double, seen: (Double, Double) -> Boolean = { _, _ -> true }): OccupancyRaster {
    val origin = -3.0
    val size = 60
    val cells = ByteArray(size * 200) { i ->
        val x = origin + (i % size + 0.5) * 0.1
        val y = (i / size + 0.5) * 0.1
        when {
            !seen(x, y) -> OccupancyRaster.UNKNOWN
            x < left || x > right -> OccupancyRaster.OCCUPIED
            else -> OccupancyRaster.FREE
        }
    }
    return OccupancyRaster.of(0.1, origin, 0.0, size, 200, cells)
}

class PassageTest {
    @Test
    fun `a chair in the middle of a wide corridor leaves room both sides`() {
        val gap = Passage(corridor(-1.5, 1.5)).widestGap(0.0, 10.0, 0.0, 1.0, widthMeters = 0.5)!!
        assertTrue(abs(gap.meters - 1.25) < 0.15, "${gap.meters}")
    }

    @Test
    fun `the roomier side is the one named, from the walker's point of view`() {
        // Walking north, a chair against the east wall: room on the walker's left.
        val gap = Passage(corridor(-1.5, 1.5)).widestGap(1.0, 10.0, 0.0, 1.0, widthMeters = 0.5)!!
        assertEquals(TurnDirection.LEFT, gap.side)
        assertTrue(gap.meters > Passage.PASSABLE_METERS)
        // Walking south past the same chair, the room is on the right.
        assertEquals(TurnDirection.RIGHT, Passage(corridor(-1.5, 1.5)).widestGap(1.0, 10.0, 0.0, -1.0, 0.5)!!.side)
    }

    @Test
    fun `something nearly as wide as the corridor leaves no way past`() {
        val gap = Passage(corridor(-1.0, 1.0)).widestGap(0.0, 10.0, 0.0, 1.0, widthMeters = 1.6)!!
        assertTrue(gap.meters < Passage.PASSABLE_METERS, "${gap.meters}")
    }

    @Test
    fun `floor the map never saw is no room, and all unseen is no answer`() {
        // Only the corridor's west half was ever seen.
        val halfSeen = Passage(corridor(-1.5, 1.5) { x, _ -> x < 0.0 })
        assertEquals(TurnDirection.LEFT, halfSeen.widestGap(0.0, 10.0, 0.0, 1.0, 0.5)!!.side)
        assertNull(Passage(corridor(-1.5, 1.5) { _, _ -> false }).widestGap(0.0, 10.0, 0.0, 1.0, 0.5))
    }
}
