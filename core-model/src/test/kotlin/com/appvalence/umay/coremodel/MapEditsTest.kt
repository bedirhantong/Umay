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
}
