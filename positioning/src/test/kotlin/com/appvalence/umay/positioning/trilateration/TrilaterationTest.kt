package com.appvalence.umay.positioning.trilateration

import com.appvalence.umay.positioning.trilateration.Trilateration.AnchorReading
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrilaterationTest {

    @Test
    fun `fewer than 3 readings is underdetermined`() {
        val readings = listOf(
            AnchorReading(0.0, 0.0, 5.0),
            AnchorReading(10.0, 0.0, 5.0),
        )
        assertNull(Trilateration.solve(readings))
    }

    @Test
    fun `collinear anchors are degenerate`() {
        val readings = listOf(
            AnchorReading(0.0, 0.0, 5.0),
            AnchorReading(5.0, 0.0, 5.0),
            AnchorReading(10.0, 0.0, 5.0),
        )
        assertNull(Trilateration.solve(readings))
    }

    @Test
    fun `recovers an exact position from three noiseless readings`() {
        val targetX = 6.0
        val targetY = 4.0
        val anchorPoints = listOf(0.0 to 0.0, 10.0 to 0.0, 0.0 to 10.0)
        val readings = anchorPoints.map { (ax, ay) ->
            AnchorReading(ax, ay, hypot(targetX - ax, targetY - ay))
        }

        val solution = Trilateration.solve(readings)!!
        assertApprox(targetX, solution.x)
        assertApprox(targetY, solution.y)
    }

    @Test
    fun `stays close to the true position with noisy distances from four readings`() {
        val targetX = 12.0
        val targetY = 7.5
        val anchorPoints = listOf(0.0 to 0.0, 20.0 to 0.0, 20.0 to 15.0, 0.0 to 15.0)
        val noise = listOf(0.3, -0.2, 0.15, -0.25)
        val readings = anchorPoints.zip(noise).map { (point, noiseMeters) ->
            val (ax, ay) = point
            AnchorReading(ax, ay, hypot(targetX - ax, targetY - ay) + noiseMeters)
        }

        val solution = Trilateration.solve(readings)!!
        assertApprox(targetX, solution.x, tolerance = 1.0)
        assertApprox(targetY, solution.y, tolerance = 1.0)
    }

    private fun assertApprox(expected: Double, actual: Double, tolerance: Double = 0.01) {
        assertTrue(kotlin.math.abs(expected - actual) <= tolerance, "expected $expected but was $actual")
    }
}
