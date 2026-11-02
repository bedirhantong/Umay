package com.appvalence.umay.coremodel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MapEditsTest {

    // Giriş — Koridor — Oda in a line on floor 0, Koridor also down to Merdiven on floor 1.
    private val map = BuildingMap(
        id = "Ofis",
        floors = listOf(
            FloorMap(
                0,
                listOf(
                    MapNode("a", 0.0, 0.0, NodeType.WAYPOINT, "Giriş"),
                    MapNode("b", 0.0, 5.0, NodeType.WAYPOINT, "Koridor"),
                    MapNode("c", 0.0, 10.0, NodeType.WAYPOINT, "Oda"),
                ),
                listOf(MapEdge("a", "b", true, 5.0), MapEdge("b", "c", true, 5.0)),
            ),
            FloorMap(1, listOf(MapNode("s", 0.0, 0.0, NodeType.STAIRS, "Merdiven")), emptyList()),
        ),
        floorTransitions = listOf(FloorTransition("c", 0, "s", 1, 15.0)),
    )

    private fun node(map: BuildingMap, id: String) = map.floors.flatMap { it.nodes }.firstOrNull { it.id == id }

    @Test
    fun `a place can be renamed and retyped, but never to a blank name`() {
        val renamed = MapEdits.rename(map, "a", "  Ana giriş ")
        assertEquals("Ana giriş", node(renamed, "a")!!.name)
        assertSame(map, MapEdits.rename(map, "a", "   "))
        assertEquals(NodeType.DOOR, node(MapEdits.setType(map, "a", NodeType.DOOR), "a")!!.type)
    }

    @Test
    fun `deleting a place the route passes through keeps the corridor walkable`() {
        val edited = MapEdits.deletePlace(map, "b")

        val bend = node(edited, "b")!!
        assertTrue(bend.isCorridorBend)
        assertEquals(2, edited.floors[0].edges.size)
        assertTrue(BuildingMapJson.problems(edited).isEmpty())
    }

    @Test
    fun `deleting a dead end removes it and its connection`() {
        val edited = MapEdits.deletePlace(map, "a")

        assertNull(node(edited, "a"))
        assertEquals(listOf(MapEdge("b", "c", true, 5.0)), edited.floors[0].edges)
        assertTrue(BuildingMapJson.problems(edited).isEmpty())
    }

    @Test
    fun `a place that leads to another floor is kept as a bend, so the crossing survives`() {
        val edited = MapEdits.deletePlace(map, "c")

        assertTrue(node(edited, "c")!!.isCorridorBend)
        assertEquals(1, edited.floorTransitions.size)
    }

    @Test
    fun `removing a connection strands what was only reachable through it`() {
        val edited = MapEdits.disconnect(map, "b", "a")

        assertEquals(listOf("Giriş"), MapEdits.unreachablePlaces(edited).map { it.name })
        assertTrue(MapEdits.unreachablePlaces(map).isEmpty())
    }

    @Test
    fun `two places on one floor can be connected, at their straight-line distance`() {
        val edited = MapEdits.connect(map, "a", "c")

        assertEquals(10.0, edited.floors[0].edges.single { it.fromId == "a" && it.toId == "c" }.distanceMeters, 1e-9)
        assertSame(edited, MapEdits.connect(edited, "c", "a"))
        assertSame(map, MapEdits.connect(map, "a", "s"))
    }

    @Test
    fun `erasing walls forgets only the cells within reach`() {
        val walls = OccupancyRaster.of(1.0, 0.0, 0.0, 3, 1, byteArrayOf(OccupancyRaster.OCCUPIED, OccupancyRaster.OCCUPIED, OccupancyRaster.FREE))
        val withWalls = map.copy(floors = map.floors.map { if (it.floorIndex == 0) it.copy(occupancy = walls) else it })

        val erased = MapEdits.eraseWalls(withWalls, 0, x = 0.5, y = 0.5, radiusMeters = 0.6)

        assertEquals(
            listOf(OccupancyRaster.UNKNOWN, OccupancyRaster.OCCUPIED, OccupancyRaster.FREE),
            erased.floors[0].occupancy!!.decodeCells()!!.toList(),
        )
        assertNull(MapEdits.clearWalls(withWalls, 0).floors[0].occupancy)
    }

    @Test
    fun `a chain of corridor bends between two places is one link, removed whole`() {
        val floor = FloorMap(
            0,
            listOf(
                MapNode("a", 0.0, 0.0, NodeType.WAYPOINT, "Giriş"),
                MapNode("k1", 0.0, 5.0, NodeType.CORRIDOR),
                MapNode("k2", 5.0, 5.0, NodeType.CORRIDOR),
                MapNode("c", 5.0, 10.0, NodeType.WAYPOINT, "Oda"),
            ),
            listOf(MapEdge("a", "k1", true, 5.0), MapEdge("k1", "k2", true, 5.0), MapEdge("k2", "c", true, 5.0)),
        )
        val bent = BuildingMap("Ofis", listOf(floor))

        val link = MapEdits.placeLinks(floor).single()
        assertEquals(setOf("a", "c"), setOf(link.fromId, link.toId))
        assertEquals(15.0, link.lengthMeters, 1e-9)

        val edited = MapEdits.disconnect(bent, link)
        assertTrue(edited.floors[0].edges.isEmpty())
        assertEquals(listOf("a", "c"), edited.floors[0].nodes.map { it.id })
        assertTrue(BuildingMapJson.problems(edited).isEmpty())
    }

    @Test
    fun `a straight edge between two places is a link of its own`() {
        assertEquals(2, MapEdits.placeLinks(map.floors[0]).size)
    }

    @Test
    fun `erasing reconstructed geometry invalidates stale vector fits and clearing cannot enable legacy routing`() {
        val wall = WallSegment(MetricPoint(0.0,0.5),MetricPoint(3.0,0.5),0.3,1.8,20,4,0.01)
        val geometry = SpatialMap(coordinateFrameId="a",walls=listOf(wall),voxels=listOf(SurfaceVoxel(0.5,0.5,1.0,4,4)))
        val raster = OccupancyRaster.of(1.0,0.0,0.0,3,1,ByteArray(3){OccupancyRaster.OCCUPIED})
        val scanned = map.copy(floors=map.floors.map { if (it.floorIndex == 0) it.copy(occupancy=raster,reconstruction=geometry) else it })
        val erased = MapEdits.eraseWalls(scanned,0,0.5,0.5,0.6).floors.first()
        assertTrue(erased.reconstruction!!.walls.isEmpty())
        assertTrue(erased.reconstruction!!.voxels.isEmpty())
        assertEquals(OccupancyRaster.OCCUPIED,erased.occupancy!!.decodeCells()!![1])
        val cleared = MapEdits.clearWalls(scanned,0).floors.first()
        assertTrue(cleared.reconstruction!!.walls.isEmpty())
        assertTrue(cleared.edges.isEmpty())
        assertEquals("a",cleared.reconstruction!!.coordinateFrameId)
    }

    @Test
    fun `erasing a jamb invalidates its distant corner and opening even without an occupancy raster`() {
        val jamb = wall(0.0, 4.0)
        val otherJamb = wall(5.0, 8.0)
        val opening = OpeningCandidate(MetricPoint(4.0, 0.5), MetricPoint(5.0, 0.5), 2.0, 1.6, 4)
        val unrelatedOpening = OpeningCandidate(MetricPoint(14.0, 10.5), MetricPoint(15.0, 10.5), 2.0, 1.6, 4)
        val farCorner = MetricPoint(4.0, 0.5)
        val unrelatedCorner = MetricPoint(8.0, 0.5)
        val geometry = SpatialMap(
            coordinateFrameId = "measured-frame",
            walls = listOf(jamb, otherJamb),
            confirmedWalls = listOf(jamb),
            corners = listOf(farCorner, unrelatedCorner),
            openings = listOf(opening, unrelatedOpening),
            openingWitnesses = listOf(OpeningClearanceWitness(MetricPoint(4.5, 0.5), 1.2, 4, 4)),
            observedRegions = listOf(region()),
            voxels = listOf(SurfaceVoxel(0.5, 0.5, 1.0, 4, 4), SurfaceVoxel(6.0, 0.5, 1.0, 4, 4)),
        )
        val scanned = reconstructed(geometry)

        val erased = MapEdits.eraseWalls(scanned, 0, 0.5, 0.5, 0.2).floors.first()

        assertNull(erased.occupancy)
        assertEquals(listOf(otherJamb), erased.reconstruction!!.walls)
        assertTrue(erased.reconstruction!!.confirmedWalls.isEmpty())
        assertEquals(listOf(unrelatedCorner), erased.reconstruction!!.corners)
        assertEquals(listOf(unrelatedOpening), erased.reconstruction!!.openings)
        assertTrue(erased.reconstruction!!.openingWitnesses.isEmpty())
        assertTrue(erased.reconstruction!!.observedRegions.isEmpty())
        assertEquals(listOf(geometry.voxels.last()), erased.reconstruction!!.voxels)
        assertEquals("measured-frame", erased.reconstruction!!.coordinateFrameId)
        assertTrue(ReconstructionIssue.PARTIAL_COVERAGE in erased.reconstruction!!.issues)
        assertSame(scanned.floors[1], MapEdits.eraseWalls(scanned, 0, 0.5, 0.5, 0.2).floors[1])
    }

    @Test
    fun `erasing inside an opening invalidates clearance without touching its endpoints or raster cells`() {
        val opening = OpeningCandidate(MetricPoint(1.0, 0.5), MetricPoint(2.0, 0.5), 2.0, 1.6, 4)
        val geometry = SpatialMap(
            coordinateFrameId = "a",
            walls = listOf(wall(0.0, 1.0), wall(2.0, 3.0)),
            openings = listOf(opening),
            openingWitnesses = listOf(OpeningClearanceWitness(MetricPoint(1.5, 0.5), 1.2, 4, 4)),
            observedRegions = listOf(region()),
        )
        val unknown = OccupancyRaster.of(1.0, 0.0, 0.0, 3, 1, ByteArray(3))

        val erased = MapEdits.eraseWalls(reconstructed(geometry, unknown), 0, 1.5, 0.5, 0.1).floors.first()

        assertSame(unknown, erased.occupancy)
        assertEquals(geometry.walls, erased.reconstruction!!.walls)
        assertTrue(erased.reconstruction!!.openings.isEmpty())
        assertTrue(erased.reconstruction!!.openingWitnesses.isEmpty())
        assertTrue(erased.reconstruction!!.observedRegions.isEmpty())
    }

    @Test
    fun `erasing only clearance evidence retains unrelated witnesses`() {
        val near = OpeningClearanceWitness(MetricPoint(0.5, 0.5), 1.2, 4, 4)
        val far = OpeningClearanceWitness(MetricPoint(4.5, 0.5), 1.2, 4, 4)
        val scanned = reconstructed(SpatialMap(coordinateFrameId = "a", openingWitnesses = listOf(near, far)))

        val erased = MapEdits.eraseWalls(scanned, 0, 0.5, 0.5, 0.1).floors.first()

        assertEquals(listOf(far), erased.reconstruction!!.openingWitnesses)
    }

    @Test
    fun `geometry-only region edits preserve unknown holes and invalidate edited observed boundaries`() {
        val hole = listOf(MetricPoint(1.0, 1.0), MetricPoint(1.0, 2.0), MetricPoint(2.0, 2.0), MetricPoint(2.0, 1.0))
        val scanned = reconstructed(SpatialMap(coordinateFrameId = "a", observedRegions = listOf(region().copy(holes = listOf(hole)))))

        assertSame(scanned, MapEdits.eraseWalls(scanned, 0, 1.5, 1.5, 0.1))
        val erased = MapEdits.eraseWalls(scanned, 0, 0.5, 0.5, 0.1).floors.first()
        assertTrue(erased.reconstruction!!.observedRegions.isEmpty())
    }

    @Test
    fun `clearing walls removes every derived geometry and evidence layer`() {
        val wall = wall(0.0, 1.0)
        val geometry = SpatialMap(
            coordinateFrameId = "a",
            walls = listOf(wall), confirmedWalls = listOf(wall), rejectedWalls = listOf(wall),
            corners = listOf(MetricPoint(0.0, 0.5)),
            openings = listOf(OpeningCandidate(MetricPoint(1.0, 0.5), MetricPoint(2.0, 0.5), 2.0, 1.6, 4)),
            openingWitnesses = listOf(OpeningClearanceWitness(MetricPoint(1.5, 0.5), 1.2, 4, 4)),
            observedRegions = listOf(region()),
            voxels = listOf(SurfaceVoxel(0.5, 0.5, 1.0, 4, 4)),
        )
        val cleared = MapEdits.clearWalls(reconstructed(geometry), 0).floors.first()

        assertEquals(geometry.copy(walls = emptyList(), confirmedWalls = emptyList(), rejectedWalls = emptyList(),
            corners = emptyList(), openings = emptyList(), openingWitnesses = emptyList(),
            observedRegions = emptyList(), voxels = emptyList()), cleared.reconstruction)
        assertTrue(cleared.edges.isEmpty())
    }

    private fun reconstructed(geometry: SpatialMap, raster: OccupancyRaster? = null) = map.copy(
        floors = map.floors.map { if (it.floorIndex == 0) it.copy(occupancy = raster, reconstruction = geometry) else it },
    )

    private fun wall(start: Double, end: Double) = WallSegment(
        MetricPoint(start, 0.5), MetricPoint(end, 0.5), 0.3, 1.8, 20, 4, 0.01,
    )

    private fun region() = ObservedRegion(
        outline = listOf(MetricPoint(0.0, 0.0), MetricPoint(3.0, 0.0), MetricPoint(3.0, 3.0), MetricPoint(0.0, 3.0)),
        areaSquareMeters = 9.0,
        boundaryComplete = true,
    )
}
