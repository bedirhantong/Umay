package com.appvalence.umay.positioning.compass

import com.appvalence.umay.coremodel.CompassCalibration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompassHintTest {
    private fun reading(game: Double, magnetic: Double, reliable: Boolean = true) = HeadingReading(0L, game, magnetic, reliable)

    @Test
    fun `the gyro's heading is turned onto the map by the compass and the floor's offset`() {
        // The floor's map is 20° clockwise of magnetic north; the gyro's frame is 100° anticlockwise of it.
        val hint = CompassHint(CompassCalibration(offsetDeg = 20.0, spreadDeg = 10.0, samples = 50))
        repeat(10) { hint.add(reading(game = 150.0 + it, magnetic = 50.0 + it)) }

        val (offset, sigma) = hint.offset()!!

        // map = magnetic + 20 = game - 100 + 20: map minus gyro = -80 = 280.
        assertEquals(280.0, offset, 1e-6)
        assertEquals(CompassHint.MIN_SIGMA_DEG, sigma)
    }

    @Test
    fun `an unsure compass is waited for, and one twitching about says nothing`() {
        val hint = CompassHint(CompassCalibration(0.0, 10.0, 50))
        repeat(20) { hint.add(reading(0.0, 10.0, reliable = false)) }
        assertNull(hint.offset())

        val twitchy = CompassHint(CompassCalibration(0.0, 10.0, 50))
        repeat(20) { twitchy.add(reading(0.0, if (it % 2 == 0) 0.0 else 120.0)) }
        assertTrue(twitchy.isReady)
        assertNull(twitchy.offset())
    }

    @Test
    fun `a floor whose compass varied a lot is trusted less`() {
        val hint = CompassHint(CompassCalibration(0.0, spreadDeg = 38.0, samples = 50))
        repeat(10) { hint.add(reading(0.0, 0.0)) }
        assertTrue(hint.offset()!!.second > 37.0)
    }
}
