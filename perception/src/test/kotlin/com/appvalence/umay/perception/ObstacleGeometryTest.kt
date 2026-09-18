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
}
