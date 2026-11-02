package com.appvalence.umay.coremodel

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompassTest {
    private fun sums(offset: Double, noise: Double, count: Int, seed: Int = 1): CompassSums {
        val random = Random(seed)
        var sums = CompassSums()
        repeat(count) {
            val magnetic = random.nextDouble(0.0, 360.0)
            sums = sums.plus(magnetic + offset + (random.nextDouble() - 0.5) * 2 * noise, magnetic)
        }
        return sums
    }

    @Test
    fun `a steady compass gives its offset, wrapping round north`() {
        val calibration = assertNotNull(sums(offset = 355.0, noise = 10.0, count = 50).calibration())
        val error = ((calibration.offsetDeg - 355.0 + 540.0) % 360.0) - 180.0
        assertTrue(kotlin.math.abs(error) < 3.0, "offset ${calibration.offsetDeg}")
        assertTrue(calibration.spreadDeg < 10.0)
        assertEquals(50, calibration.samples)
    }

    @Test
    fun `too few samples, or a compass all over the place, give nothing`() {
        assertNull(sums(offset = 20.0, noise = 5.0, count = 5).calibration())
        assertNull(sums(offset = 20.0, noise = 170.0, count = 200).calibration())
    }

    @Test
    fun `old maps and drafts without it still read`() {
        val map = BuildingMapJson.decode("""{"id":"b","floors":[{"floorIndex":0,"nodes":[],"edges":[]}]}""")
        assertNull(map.floors.single().compass)
        val floor = FloorMap(0, emptyList(), emptyList(), compass = CompassCalibration(12.5, 8.0, 40))
        val back = BuildingMapJson.decode(BuildingMapJson.encode(BuildingMap(id = "b", floors = listOf(floor))))
        assertEquals(floor.compass, back.floors.single().compass)
    }
}
