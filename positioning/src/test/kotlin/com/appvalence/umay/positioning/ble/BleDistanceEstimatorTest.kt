package com.appvalence.umay.positioning.ble

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BleDistanceEstimatorTest {

    @Test
    fun `rssi equal to the measured power estimates exactly one meter`() {
        val distance = BleDistanceEstimator.estimateMeters(rssi = -59, measuredPowerAtOneMeter = -59)
        assertApproxEquals(1.0, distance, tolerance = 0.001)
    }

    @Test
    fun `a weaker rssi than the measured power implies a larger distance`() {
        val near = BleDistanceEstimator.estimateMeters(rssi = -59, measuredPowerAtOneMeter = -59)
        val far = BleDistanceEstimator.estimateMeters(rssi = -85, measuredPowerAtOneMeter = -59)
        assertTrue(far > near)
    }

    @Test
    fun `missing measured power yields the unknown-distance sentinel`() {
        val distance = BleDistanceEstimator.estimateMeters(rssi = -70, measuredPowerAtOneMeter = null)
        assertEquals(BleDistanceEstimator.UNKNOWN_DISTANCE_METERS, distance)
    }

    @Test
    fun `stays within plausible indoor range instead of exploding to kilometers`() {
        // A weak but plausible indoor RSSI, e.g. through a couple of walls.
        val distance = BleDistanceEstimator.estimateMeters(rssi = -95, measuredPowerAtOneMeter = -59)
        assertTrue(distance < 100.0, "expected a plausible indoor distance but was ${distance}m")
    }

    private fun assertApproxEquals(expected: Double, actual: Double, tolerance: Double) {
        assertTrue(kotlin.math.abs(expected - actual) <= tolerance, "expected $expected but was $actual")
    }
}
