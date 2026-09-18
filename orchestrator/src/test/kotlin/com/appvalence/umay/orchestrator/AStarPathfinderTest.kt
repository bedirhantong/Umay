package com.appvalence.umay.orchestrator

import com.appvalence.umay.coremodel.FloorMap
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
}
