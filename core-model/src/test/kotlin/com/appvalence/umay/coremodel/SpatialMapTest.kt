package com.appvalence.umay.coremodel

import kotlin.test.*

class SpatialMapTest {
    @Test fun `observed opening evidence and partial regions survive map serialization`() {
        val spatial = SpatialMap(
            coordinateFrameId = "ar-session-a",
            openings = listOf(OpeningCandidate(MetricPoint(0.0,0.0),MetricPoint(0.9,0.0),2.05,1.6,4)),
            openingWitnesses = listOf(OpeningClearanceWitness(MetricPoint(0.45,0.0),1.2,4,9)),
            observedRegions = listOf(ObservedRegion(listOf(MetricPoint(0.0,0.0),MetricPoint(2.0,0.0),MetricPoint(2.0,2.0),MetricPoint(0.0,2.0)), areaSquareMeters = 4.0, boundaryComplete = false)),
            integratedFrames = 9,
        )
        val original = BuildingMap("id", floors = listOf(FloorMap(0, emptyList(), emptyList(), reconstruction = spatial)))
        val decoded = BuildingMapJson.decode(BuildingMapJson.encode(original))
        val restored = decoded.floors.single().reconstruction!!
        assertEquals(spatial.openings, restored.openings)
        assertEquals(spatial.openingWitnesses, restored.openingWitnesses)
        assertEquals(spatial.observedRegions, restored.observedRegions)
    }

    @Test fun `metric evidence and review decisions survive building and draft serialization`() {
        val wall = WallSegment(MetricPoint(-1.0,2.0),MetricPoint(3.0,2.0),0.3,1.8,25,4,0.012,GeometryEvidence.OPERATOR_CONFIRMED)
        val spatial = SpatialMap(coordinateFrameId="session-a",floor=FloorSurface(-1.35,4,0.01),walls=listOf(wall),
            confirmedWalls=listOf(wall),voxels=listOf(SurfaceVoxel(1.0,2.0,1.0,4,8)),integratedFrames=8)
        val map = BuildingMap("office",listOf(FloorMap(0,emptyList(),emptyList(),reconstruction=spatial)))
        assertEquals(map,BuildingMapJson.decode(BuildingMapJson.encode(map)))
        val draft = SurveyDraft(mode="CAMERA",currentFloor=0,savedAtMs=1,spatialMaps=mapOf(0 to spatial),coordinateFrameId="session-a")
        assertEquals(draft,SurveyDraftJson.decode(SurveyDraftJson.encode(draft)))
    }

    @Test fun `invalid metric evidence and future schemas are refused`() {
        val wall = WallSegment(MetricPoint(0.0,0.0),MetricPoint(0.0,0.0),0.0,1.0,3,3,0.01)
        val invalid = SpatialMap(coordinateFrameId="a",walls=listOf(wall))
        assertFalse(SpatialValidation.valid(invalid))
        assertFalse(SpatialValidation.valid(SpatialMap(schemaVersion=99,coordinateFrameId="a")))
        val map = BuildingMap("a",listOf(FloorMap(0,emptyList(),emptyList(),reconstruction=invalid)))
        assertFailsWith<IllegalArgumentException> { BuildingMapJson.decode(BuildingMapJson.encode(map)) }
    }

    @Test fun `simple regions preserve interior holes and require consistent area`() {
        val region = ObservedRegion(square(0.0, 0.0, 4.0), holes = listOf(square(1.0, 1.0, 1.0).reversed()),
            areaSquareMeters = 15.0, boundaryComplete = false)
        assertTrue(validRegion(region))
        assertTrue(validRegion(region.copy(outline = region.outline.reversed(), holes = region.holes.map { it.reversed() })))
        assertFalse(validRegion(region.copy(areaSquareMeters = 16.0)))
    }

    @Test fun `zero area and crossing outlines are refused even with finite coordinates`() {
        assertFalse(validRegion(ObservedRegion(listOf(MetricPoint(0.0, 0.0), MetricPoint(1.0, 0.0), MetricPoint(2.0, 0.0)),
            areaSquareMeters = 1.0, boundaryComplete = false)))
        val crossing = listOf(MetricPoint(0.0, 0.0), MetricPoint(4.0, 4.0), MetricPoint(0.0, 4.0), MetricPoint(3.0, 0.0))
        assertFalse(validRegion(ObservedRegion(crossing, areaSquareMeters = 2.0, boundaryComplete = false)))
    }

    @Test fun `outside overlapping nested and touching holes are refused`() {
        val outer = square(0.0, 0.0, 4.0)
        fun withHoles(vararg holes: List<MetricPoint>, area: Double) =
            validRegion(ObservedRegion(outer, holes.toList(), area, false))
        assertFalse(withHoles(square(5.0, 5.0, 1.0), area = 15.0))
        assertFalse(withHoles(square(3.5, 1.0, 1.0), area = 15.0))
        assertFalse(withHoles(square(0.0, 1.0, 1.0), area = 15.0))
        assertFalse(withHoles(square(0.5, 0.5, 2.0), square(1.0, 1.0, 1.0), area = 11.0))
        assertFalse(withHoles(square(0.5, 0.5, 1.5), square(1.5, 1.5, 1.0), area = 12.75))
        assertTrue(withHoles(square(0.5, 0.5, 1.0), square(2.5, 2.5, 1.0), area = 14.0))
    }

    @Test fun `polygon validation bounds the total vertex allocation`() {
        val regions = List(65) { region ->
            val ring = List(512) { i ->
                val angle = 2 * Math.PI * i / 512
                MetricPoint((region % 9) * 4.0 + kotlin.math.cos(angle), (region / 9) * 4.0 + kotlin.math.sin(angle))
            }
            ObservedRegion(ring, areaSquareMeters = 256 * kotlin.math.sin(2 * Math.PI / 512), boundaryComplete = false)
        }
        // All polygons are valid. Only adding the 65th exceeds the aggregate vertex limit.
        assertTrue(SpatialValidation.validObservedRegions(regions.take(64)))
        assertFalse(SpatialValidation.validObservedRegions(regions))
    }

    @Test fun `opening provenance needs independent support and consistent witness counts`() {
        val opening = OpeningCandidate(MetricPoint(0.0, 0.0), MetricPoint(0.9, 0.0), 2.05, 1.6, 1)
        val map = SpatialMap(coordinateFrameId = "a", openings = listOf(opening))
        assertFalse(SpatialValidation.valid(map))
        assertTrue(SpatialValidation.valid(map.copy(openings = listOf(opening.copy(independentFrames = 3)))))
        assertTrue(SpatialValidation.valid(map.copy(openings = listOf(opening.copy(evidence = GeometryEvidence.OBSERVED_CANDIDATE)))))
        assertFalse(SpatialValidation.valid(map.copy(openings = emptyList(),
            openingWitnesses = listOf(OpeningClearanceWitness(MetricPoint(0.4, 0.0), 1.2, 5, 4)))))
    }

    @Test fun `legacy draft migrates without dropping its recorded trajectory`() {
        val original = SurveyDraft(schemaVersion=1,mode="CAMERA",currentFloor=0,savedAtMs=1,
            trajectory=listOf(Position(1.0,2.0,0,0.0,0.5,PositionSource.MAP_MATCHED,1)))
        val restored = SurveyDraftJson.decode(SurveyDraftJson.encode(original))!!
        assertEquals(SurveyDraftJson.SCHEMA_VERSION,restored.schemaVersion)
        assertEquals(original.trajectory,restored.trajectory)
    }

    private fun validRegion(region: ObservedRegion) = SpatialValidation.valid(SpatialMap(coordinateFrameId = "a", observedRegions = listOf(region)))

    private fun square(x: Double, y: Double, size: Double) = listOf(
        MetricPoint(x, y), MetricPoint(x + size, y), MetricPoint(x + size, y + size), MetricPoint(x, y + size),
    )

}
