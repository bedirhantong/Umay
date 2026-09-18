package com.appvalence.umay.perception

import com.appvalence.umay.perception.ObstacleTracker.RawDetection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ObstacleTrackerTest {

    @Test
    fun `first sighting of an obstacle has no velocity yet`() {
        val tracker = ObstacleTracker()
        val result = tracker.update(
            listOf(RawDetection(centerXPx = 500f, centerYPx = 500f, distanceMeters = 3.0, bearingDeg = 0.0)),
            timestampMs = 0L,
        )
        assertEquals(1, result.size)
        assertNull(result.single().relativeVelocityMps)
    }

    @Test
    fun `a person moving closer across frames gets a negative relative velocity`() {
        val tracker = ObstacleTracker()
        tracker.update(
            listOf(RawDetection(centerXPx = 500f, centerYPx = 500f, distanceMeters = 3.0, bearingDeg = 0.0)),
            timestampMs = 0L,
        )
        val result = tracker.update(
            listOf(RawDetection(centerXPx = 505f, centerYPx = 500f, distanceMeters = 2.0, bearingDeg = 0.0)),
            timestampMs = 1000L,
        )
        val tracked = result.single()
        assertTrue(tracked.relativeVelocityMps!! < 0.0)
    }

    @Test
    fun `the same obstacle keeps the same id across frames`() {
        val tracker = ObstacleTracker()
        val first = tracker.update(
            listOf(RawDetection(centerXPx = 500f, centerYPx = 500f, distanceMeters = 3.0, bearingDeg = 0.0)),
            timestampMs = 0L,
        ).single()
        val second = tracker.update(
            listOf(RawDetection(centerXPx = 510f, centerYPx = 500f, distanceMeters = 2.9, bearingDeg = 0.0)),
            timestampMs = 500L,
        ).single()
        assertEquals(first.id, second.id)
    }

    @Test
    fun `a detection far from any existing track starts a new id`() {
        val tracker = ObstacleTracker(matchRadiusPx = 50f)
        val first = tracker.update(
            listOf(RawDetection(centerXPx = 100f, centerYPx = 100f, distanceMeters = 3.0, bearingDeg = 0.0)),
            timestampMs = 0L,
        ).single()
        val second = tracker.update(
            listOf(RawDetection(centerXPx = 900f, centerYPx = 900f, distanceMeters = 3.0, bearingDeg = 0.0)),
            timestampMs = 200L,
        ).single()
        assertTrue(first.id != second.id)
    }
}
