package com.appvalence.umay.positioning

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KalmanFilter1DTest {

    @Test
    fun `the first measurement is returned as-is`() {
        val filter = KalmanFilter1D()
        assertEquals(-70.0, filter.update(-70.0))
    }

    @Test
    fun `converges toward a steady signal despite noisy samples`() {
        val filter = KalmanFilter1D()
        val noisySamples = listOf(-70.0, -66.0, -74.0, -68.0, -72.0, -69.0, -71.0, -70.0, -73.0, -67.0)
        var lastEstimate = 0.0
        noisySamples.forEach { lastEstimate = filter.update(it) }

        assertTrue(kotlin.math.abs(lastEstimate - (-70.0)) < 2.0, "expected estimate near -70 but was $lastEstimate")
    }

    @Test
    fun `smooths a single outlier more than the raw measurement would suggest`() {
        val filter = KalmanFilter1D()
        repeat(10) { filter.update(-70.0) }
        val afterOutlier = filter.update(-40.0) // a sudden, implausible 30dB jump

        assertTrue(afterOutlier > -70.0 && afterOutlier < -40.0, "expected a damped move but was $afterOutlier")
        assertTrue(afterOutlier < -55.0, "expected the outlier to be heavily damped but was $afterOutlier")
    }

    @Test
    fun `reset forgets prior state so the next measurement is returned as-is again`() {
        val filter = KalmanFilter1D()
        filter.update(-70.0)
        filter.update(-70.0)
        filter.reset()

        assertEquals(-50.0, filter.update(-50.0))
    }
}
