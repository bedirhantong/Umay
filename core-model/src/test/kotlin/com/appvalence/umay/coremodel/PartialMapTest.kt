package com.appvalence.umay.coremodel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PartialMapTest {
    @Test fun `observed floor polygons can be saved and reopened before waypoints or walls exist`() {
        val geometry = SpatialMap(coordinateFrameId = "measured-session", observedRegions = listOf(ObservedRegion(
            outline = listOf(MetricPoint(0.0, 0.0), MetricPoint(2.0, 0.0), MetricPoint(2.0, 1.0), MetricPoint(0.0, 1.0)),
            areaSquareMeters = 2.0, boundaryComplete = false,
        )))
        val map = BuildingMap("Partial floor", listOf(FloorMap(0, emptyList(), emptyList(), reconstruction = geometry)))
        assertTrue(geometry.hasGeometry)
        assertTrue(BuildingMapJson.problems(map).isEmpty())
        assertEquals(map, BuildingMapJson.decode(BuildingMapJson.encode(map)))
    }

    @Test fun `a frame identifier without measured geometry does not enable saving an empty map`() {
        val geometry = SpatialMap(coordinateFrameId = "empty-session")
        val map = BuildingMap("Empty floor", listOf(FloorMap(0, emptyList(), emptyList(), reconstruction = geometry)))
        assertFalse(geometry.hasGeometry)
        assertTrue(MapProblem.NoNodes in BuildingMapJson.problems(map))
    }
}
