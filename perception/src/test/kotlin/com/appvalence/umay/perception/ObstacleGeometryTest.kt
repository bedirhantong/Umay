package com.appvalence.umay.perception

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ObstacleGeometryTest {

    @Test
    fun `a taller bounding box implies a closer person`() {
        val far = ObstacleGeometry.estimateDistanceMeters(boundingBoxHeightPx = 200f, imageHeightPx = 1000)
        val near = ObstacleGeometry.estimateDistanceMeters(boundingBoxHeightPx = 800f, imageHeightPx = 1000)
        assertTrue(near < far)
    }

    @Test
    fun `zero-height bounding box yields an infinite distance rather than a crash`() {
        val distance = ObstacleGeometry.estimateDistanceMeters(boundingBoxHeightPx = 0f, imageHeightPx = 1000)
        assertEquals(Double.POSITIVE_INFINITY, distance)
    }

    @Test
    fun `a box centered in frame has zero bearing`() {
        val bearing = ObstacleGeometry.estimateBearingDegrees(boundingBoxCenterXPx = 500f, imageWidthPx = 1000)
        assertEquals(0.0, bearing)
    }

    @Test
    fun `a box on the right half of the frame has a positive bearing`() {
        val bearing = ObstacleGeometry.estimateBearingDegrees(boundingBoxCenterXPx = 900f, imageWidthPx = 1000)
        assertTrue(bearing > 0.0)
    }

    @Test
    fun `a box on the left half of the frame has a negative bearing`() {
        val bearing = ObstacleGeometry.estimateBearingDegrees(boundingBoxCenterXPx = 100f, imageWidthPx = 1000)
        assertTrue(bearing < 0.0)
    }

    @Test
    fun `width comes from the box, the same way as distance`() {
        // 480 px across 51° (an upright frame): a box 100 px wide at 2 m.
        val width = ObstacleGeometry.estimateWidthMeters(boundingBoxWidthPx = 100f, imageWidthPx = 480, distanceMeters = 2.0)
        assertTrue(kotlin.math.abs(width - 0.4) < 0.02, "$width")
    }

    @Test
    fun `a box running off the frame has no width to trust`() {
        assertEquals(null, ObstacleGeometry.widthIfWhollySeen(0f, 200f, 480, 2.0))
        assertEquals(null, ObstacleGeometry.widthIfWhollySeen(300f, 480f, 480, 2.0))
        assertTrue(ObstacleGeometry.widthIfWhollySeen(100f, 200f, 480, 2.0)!! > 0.0)
    }
}
