package com.appvalence.umay.orchestrator

import com.appvalence.umay.coremodel.BuildingMap
import com.appvalence.umay.coremodel.FloorMap
import com.appvalence.umay.coremodel.FloorTransition
import com.appvalence.umay.coremodel.MapEdge
import com.appvalence.umay.coremodel.MapNode
import com.appvalence.umay.coremodel.NodeType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AStarPathfinderTest {

    // A -- B -- C
    //      |
    //      D
    private val nodes = listOf(
        MapNode("A", 0.0, 0.0, NodeType.CORRIDOR),
        MapNode("B", 10.0, 0.0, NodeType.CORRIDOR),
        MapNode("C", 20.0, 0.0, NodeType.CORRIDOR),
        MapNode("D", 10.0, 10.0, NodeType.CORRIDOR),
    )
    private val edges = listOf(
        MapEdge("A", "B", accessible = true, distanceMeters = 10.0),
        MapEdge("B", "C", accessible = true, distanceMeters = 10.0),
        MapEdge("B", "D", accessible = true, distanceMeters = 10.0),
    )
    private val floorMap = FloorMap(floorIndex = 0, nodes = nodes, edges = edges)

    @Test
    fun `finds the direct path along connected nodes`() {
        val path = AStarPathfinder.findPath(floorMap, "A", "C")
        assertEquals(listOf("A", "B", "C"), path?.map { it.id })
    }

    @Test
    fun `start equal to goal returns a single-node path`() {
        val path = AStarPathfinder.findPath(floorMap, "A", "A")
        assertEquals(listOf("A"), path?.map { it.id })
    }

    @Test
    fun `an unreachable node (no edges) returns null`() {
        val isolatedFloorMap = floorMap.copy(nodes = nodes + MapNode("E", 100.0, 100.0, NodeType.CORRIDOR))
        assertNull(AStarPathfinder.findPath(isolatedFloorMap, "A", "E"))
    }

    @Test
    fun `an unknown node id returns null`() {
        assertNull(AStarPathfinder.findPath(floorMap, "A", "does-not-exist"))
    }

    @Test
    fun `a blocked edge forces a detour or fails if none exists`() {
        // B-C is the only way to reach C, so blocking it makes C unreachable.
        val path = AStarPathfinder.findPath(floorMap, "A", "C", blockedEdges = setOf("B" to "C"))
        assertNull(path)
    }

    @Test
    fun `an inaccessible edge, such as stairs, is never used`() {
        val floorMapWithStairs = floorMap.copy(
            edges = edges + MapEdge("A", "C", accessible = false, distanceMeters = 1.0),
        )
        val path = AStarPathfinder.findPath(floorMapWithStairs, "A", "C")
        assertEquals(listOf("A", "B", "C"), path?.map { it.id })
    }

    @Test
    fun `blocking works regardless of which direction the edge was stored in`() {
        val path = AStarPathfinder.findPath(floorMap, "A", "C", blockedEdges = setOf("C" to "B"))
        assertNull(path)
    }

    @Test
    fun `findPathAcrossFloors routes through a stairs-elevator transition`() {
        val floor0 = FloorMap(
            floorIndex = 0,
            nodes = listOf(MapNode("A", 0.0, 0.0, NodeType.CORRIDOR), MapNode("B", 1.0, 0.0, NodeType.CORRIDOR)),
            edges = listOf(MapEdge("A", "B", accessible = true, distanceMeters = 1.0)),
        )
        val floor1 = FloorMap(
            floorIndex = 1,
            nodes = listOf(MapNode("C", 0.0, 0.0, NodeType.CORRIDOR), MapNode("D", 1.0, 0.0, NodeType.CORRIDOR)),
            edges = listOf(MapEdge("C", "D", accessible = true, distanceMeters = 1.0)),
        )
        val transitions = listOf(FloorTransition(fromNodeId = "B", fromFloorIndex = 0, toNodeId = "C", toFloorIndex = 1, costMeters = 15.0))
        val buildingMap = BuildingMap(id = "test", floors = listOf(floor0, floor1), floorTransitions = transitions)

        val path = AStarPathfinder.findPathAcrossFloors(buildingMap, "A", "D")

        assertEquals(listOf("A", "B", "C", "D"), path?.map { it.id })
    }

    @Test
    fun `findPathAcrossFloors respects a blocked transition edge`() {
        val floor0 = FloorMap(
            floorIndex = 0,
            nodes = listOf(MapNode("A", 0.0, 0.0, NodeType.CORRIDOR), MapNode("B", 1.0, 0.0, NodeType.CORRIDOR)),
            edges = listOf(MapEdge("A", "B", accessible = true, distanceMeters = 1.0)),
        )
        val floor1 = FloorMap(floorIndex = 1, nodes = listOf(MapNode("C", 0.0, 0.0, NodeType.CORRIDOR)), edges = emptyList())
        val transitions = listOf(FloorTransition(fromNodeId = "B", fromFloorIndex = 0, toNodeId = "C", toFloorIndex = 1, costMeters = 15.0))
        val buildingMap = BuildingMap(id = "test", floors = listOf(floor0, floor1), floorTransitions = transitions)

        val path = AStarPathfinder.findPathAcrossFloors(buildingMap, "A", "C", blockedEdges = setOf("B" to "C"))

        assertNull(path)
    }

    @Test
    fun `findPathAcrossFloors picks the true cheapest route between two floor-crossing branches`() {
        // Two branches from A to D, each internally consistent (edge distances match
        // node coordinates, as real surveyed data would be) but on a floor-1 coordinate
        // system offset far from floor 0's — simulating the independent PDR origin each
        // floor gets during survey. Branch via B1/C1 has cheap same-floor legs but an
        // expensive transition + long floor-1 leg; branch via B2/C2 is the reverse and
        // has the lower total cost.
        val floor0 = FloorMap(
            floorIndex = 0,
            nodes = listOf(
                MapNode("A", 0.0, 0.0, NodeType.CORRIDOR),
                MapNode("B1", 10.0, 0.0, NodeType.CORRIDOR),
                MapNode("B2", 50.0, 0.0, NodeType.CORRIDOR),
            ),
            edges = listOf(
                MapEdge("A", "B1", accessible = true, distanceMeters = 10.0),
                MapEdge("A", "B2", accessible = true, distanceMeters = 50.0),
            ),
        )
        val floor1 = FloorMap(
            floorIndex = 1,
            nodes = listOf(
                MapNode("C1", 1000.0, 0.0, NodeType.CORRIDOR),
                MapNode("C2", 1000.0, 80.0, NodeType.CORRIDOR),
                MapNode("D", 1000.0, 81.0, NodeType.CORRIDOR),
            ),
            edges = listOf(
                MapEdge("C1", "D", accessible = true, distanceMeters = 81.0),
                MapEdge("C2", "D", accessible = true, distanceMeters = 1.0),
            ),
        )
        val transitions = listOf(
            FloorTransition(fromNodeId = "B1", fromFloorIndex = 0, toNodeId = "C1", toFloorIndex = 1, costMeters = 50.0),
            FloorTransition(fromNodeId = "B2", fromFloorIndex = 0, toNodeId = "C2", toFloorIndex = 1, costMeters = 1.0),
        )
        val buildingMap = BuildingMap(id = "test", floors = listOf(floor0, floor1), floorTransitions = transitions)

        val path = AStarPathfinder.findPathAcrossFloors(buildingMap, "A", "D")

        // Via B1/C1: 10 + 50 + 81 = 141. Via B2/C2: 50 + 1 + 1 = 52 — the true optimum.
        assertEquals(listOf("A", "B2", "C2", "D"), path?.map { it.id })
    }
}
