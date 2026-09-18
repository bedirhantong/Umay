package com.appvalence.umay.positioning.baro

import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals

class BarometricFloorTrackerTest {

    private val sampleIntervalMs = 200L

    /** Feeds [altitudes] (one per sample) and returns every non-zero floor change reported. */
    private fun BarometricFloorTracker.feed(altitudes: List<Double>, startMs: Long = 0L): List<Int> =
        altitudes.mapIndexed { i, meters -> onPressure(PressureAltitude.hpaFromMeters(meters), startMs + i * sampleIntervalMs) }
            .filter { it != 0 }

    private fun steady(meters: Double, seconds: Int) = List(seconds * 5) { meters }
    private fun ramp(from: Double, to: Double, seconds: Int): List<Double> {
        val n = seconds * 5
        return List(n) { i -> from + (to - from) * (i + 1) / n }
    }

    @Test
    fun `walking up one flight counts as one floor up`() {
        val tracker = BarometricFloorTracker()

        assertEquals(listOf(1), tracker.feed(steady(0.0, 3) + ramp(0.0, 3.2, 8) + steady(3.2, 4)))
    }

    @Test
    fun `an elevator ride down two floors counts as two floors`() {
        val tracker = BarometricFloorTracker()

        assertEquals(listOf(-2), tracker.feed(steady(6.4, 3) + ramp(6.4, 0.0, 5) + steady(0.0, 4)))
    }

    @Test
    fun `two separate flights count separately`() {
        val tracker = BarometricFloorTracker()
        val walk = steady(0.0, 3) + ramp(0.0, 3.2, 8) + steady(3.2, 10) + ramp(3.2, 6.4, 8) + steady(6.4, 4)

        assertEquals(listOf(1, 1), tracker.feed(walk))
    }

    @Test
    fun `slow pressure drift over an hour never fakes a floor change`() {
        val tracker = BarometricFloorTracker()
        // 8 m over an hour — well over two floors in total, but at weather/ventilation speed.
        val hour = List(3600 * 5) { i -> 8.0 * i / (3600 * 5) }

        assertEquals(emptyList(), tracker.feed(hour))
    }

    @Test
    fun `sensor noise on one floor never counts`() {
        val tracker = BarometricFloorTracker()

        assertEquals(emptyList(), tracker.feed(List(600) { i -> 0.4 * sin(i * 1.7) }))
    }

    @Test
    fun `a manual correction makes the current altitude the reference, so small climbs don't add up`() {
        val withoutReset = BarometricFloorTracker()
        val withReset = BarometricFloorTracker()
        // A short half-level (1.8 m, under the threshold) the operator marks by hand as a
        // new floor, then a few more steps up (0.8 m): together they'd pass the threshold.
        val firstPart = steady(0.0, 3) + ramp(0.0, 1.8, 5) + steady(1.8, 4)
        val secondPart = ramp(1.8, 2.6, 3) + steady(2.6, 4)

        withoutReset.feed(firstPart)
        withReset.feed(firstPart)
        withReset.resetBaseline()

        assertEquals(listOf(1), withoutReset.feed(secondPart, startMs = 12_000L), "the two parts add up without a reset")
        assertEquals(emptyList(), withReset.feed(secondPart, startMs = 12_000L))
    }
}
