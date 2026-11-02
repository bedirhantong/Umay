package com.appvalence.umay.positioning.baro

import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BarometricFloorChangeDetectorTest {

    private val sampleIntervalMs = 200L

    /** Feeds [altitudes] (meters, one per sample) and returns the indices at which the detector confirmed. */
    private fun BarometricFloorChangeDetector.feed(altitudes: List<Double>, startMs: Long = 0L): List<Int> =
        altitudes.mapIndexedNotNull { i, meters ->
            i.takeIf { onPressure(PressureAltitude.hpaFromMeters(meters), startMs + i * sampleIntervalMs) }
        }

    private fun steady(meters: Double, seconds: Int) = List(seconds * 5) { meters }
    private fun ramp(from: Double, to: Double, seconds: Int): List<Double> {
        val n = seconds * 5
        return List(n) { i -> from + (to - from) * (i + 1) / n }
    }

    @Test
    fun `altitude and pressure conversions round trip`() {
        assertEquals(12.0, PressureAltitude.metersFromHpa(PressureAltitude.hpaFromMeters(12.0)), 1e-6)
    }

    @Test
    fun `confirms once after climbing a floor and settling`() {
        val detector = BarometricFloorChangeDetector()
        detector.feed(steady(0.0, 3))
        detector.arm(fromFloor = 0, toFloor = 1)

        val confirmations = detector.feed(ramp(0.0, 3.2, 8) + steady(3.2, 4), startMs = 3_000L)

        assertEquals(1, confirmations.size)
        assertTrue(confirmations.single() >= 8 * 5, "must wait for the climb to settle, not confirm mid-staircase")
        assertFalse(detector.isArmed)
    }

    @Test
    fun `does not confirm while still climbing a tall floor`() {
        val detector = BarometricFloorChangeDetector()
        detector.feed(steady(0.0, 3))
        detector.arm(fromFloor = 0, toFloor = 1)

        // 4.5 m storey: well past the threshold halfway up, but never settles within this feed.
        val confirmations = detector.feed(ramp(0.0, 4.5, 12), startMs = 3_000L)

        assertEquals(emptyList(), confirmations)
    }

    @Test
    fun `climbing when the route expects going down does not confirm`() {
        val detector = BarometricFloorChangeDetector()
        detector.feed(steady(0.0, 3))
        detector.arm(fromFloor = 1, toFloor = 0)

        assertEquals(emptyList(), detector.feed(ramp(0.0, 3.2, 8) + steady(3.2, 4), startMs = 3_000L))
    }

    @Test
    fun `an elevator going down two floors confirms`() {
        val detector = BarometricFloorChangeDetector()
        detector.feed(steady(10.0, 3))
        detector.arm(fromFloor = 3, toFloor = 1)

        assertEquals(1, detector.feed(ramp(10.0, 3.6, 5) + steady(3.6, 4), startMs = 3_000L).size)
    }

    @Test
    fun `pressure noise on the same floor never confirms`() {
        val detector = BarometricFloorChangeDetector()
        detector.arm(fromFloor = 0, toFloor = 1)

        val noisy = List(200) { i -> 0.4 * sin(i * 1.7) }

        assertEquals(emptyList(), detector.feed(noisy))
    }

    @Test
    fun `a new route halfway up the stairs still counts the whole climb`() {
        val detector = BarometricFloorChangeDetector()
        detector.feed(steady(0.0, 3))
        detector.arm(fromFloor = 0, toFloor = 1)
        detector.feed(ramp(0.0, 1.6, 4), startMs = 3_000L)
        // Rerouted on the stairs: for a moment the route expects no floor change, then does again.
        detector.disarm()
        detector.arm(fromFloor = 0, toFloor = 1)

        assertEquals(1, detector.feed(ramp(1.6, 3.2, 4) + steady(3.2, 4), startMs = 7_000L).size)
    }

    @Test
    fun `an old baseline is not reused`() {
        val detector = BarometricFloorChangeDetector()
        detector.feed(steady(0.0, 3))
        detector.arm(fromFloor = 0, toFloor = 1)
        detector.disarm()
        // Two minutes on this floor while the weather lowered the pressure by two metres' worth.
        detector.feed(steady(2.2, 120), startMs = 3_000L)
        detector.arm(fromFloor = 0, toFloor = 1)

        assertEquals(emptyList(), detector.feed(steady(2.2, 4), startMs = 123_000L))
    }

    @Test
    fun `nothing is confirmed unless armed`() {
        val detector = BarometricFloorChangeDetector()

        assertEquals(emptyList(), detector.feed(steady(0.0, 3) + ramp(0.0, 3.2, 8) + steady(3.2, 4)))
    }
}
