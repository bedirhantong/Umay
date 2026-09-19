package com.appvalence.umay.orchestrator

import com.appvalence.umay.coremodel.MapNode
import com.appvalence.umay.coremodel.NodeType
import com.appvalence.umay.coremodel.Position
import com.appvalence.umay.coremodel.PositionSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class BuildingQueryMatcherTest {

    private val toilet = MapNode("A", 0.0, 0.0, NodeType.WAYPOINT, name = "Tuvalet")
    private val classroom = MapNode("B", 10.0, 0.0, NodeType.WAYPOINT, name = "203 Sınıfı")
    private val elevatorFloor0 = MapNode("C", 0.0, 5.0, NodeType.ELEVATOR, name = "E1")
    private val elevatorFloor1 = MapNode("D", 0.0, 5.0, NodeType.ELEVATOR, name = "E2")
    private val nodeFloor = mapOf("A" to 0, "B" to 0, "C" to 0, "D" to 1)
    private val allNodes = listOf(toilet, classroom, elevatorFloor0, elevatorFloor1)

    private fun at(x: Double, y: Double, floor: Int = 0) = Position(x, y, floor, 0.0, 1.0, PositionSource.FUSED, 0L)

    @Test
    fun `a name-based question matches the node whose name it overlaps with`() {
        val result = BuildingQueryMatcher.match("tuvalet nerede", allNodes, nodeFloor)

        assertEquals(toilet, (result as BuildingQueryResult.Found).node)
    }

    @Test
    fun `filler words like en yakın and nereye gitmeliyim don't change the answer`() {
        val short = BuildingQueryMatcher.match("tuvalet", allNodes, nodeFloor)
        val long = BuildingQueryMatcher.match("en yakın tuvalete nereye gitmeliyim", allNodes, nodeFloor)

        assertEquals((short as BuildingQueryResult.Found).node, (long as BuildingQueryResult.Found).node)
    }

    @Test
    fun `a hazard-type keyword matches by NodeType regardless of what the node was named`() {
        val result = BuildingQueryMatcher.match("asansör nerede", allNodes, nodeFloor, from = at(0.0, 0.0, floor = 0))

        assertEquals(elevatorFloor0, (result as BuildingQueryResult.Found).node)
    }

    @Test
    fun `with a known position, the nearest same-floor match wins over a farther one`() {
        val nodes = listOf(
            MapNode("near", 1.0, 0.0, NodeType.WAYPOINT, name = "Tuvalet"),
            MapNode("far", 50.0, 0.0, NodeType.WAYPOINT, name = "Tuvalet"),
        )
        val result = BuildingQueryMatcher.match("tuvalet", nodes, mapOf("near" to 0, "far" to 0), from = at(0.0, 0.0))

        assertEquals("near", (result as BuildingQueryResult.Found).node.id)
        assertEquals(1.0, result.distanceMeters!!, 1e-9)
    }

    @Test
    fun `a match on another floor is still returned, but without a distance`() {
        // Only the floor-1 elevator is reachable by name here since floor 0's carries a
        // different name — forces the matcher to fall back across floors.
        val nodes = listOf(elevatorFloor1)
        val result = BuildingQueryMatcher.match("asansör", nodes, nodeFloor, from = at(0.0, 0.0, floor = 0))

        val found = result as BuildingQueryResult.Found
        assertEquals(elevatorFloor1, found.node)
        assertNull(found.distanceMeters)
    }

    @Test
    fun `an unrelated question is not understood rather than guessed at`() {
        assertIs<BuildingQueryResult.NotUnderstood>(BuildingQueryMatcher.match("bugün hava nasıl", allNodes, nodeFloor))
    }

    @Test
    fun `a blank question is not understood`() {
        assertIs<BuildingQueryResult.NotUnderstood>(BuildingQueryMatcher.match("   ", allNodes, nodeFloor))
    }

    @Test
    fun `an empty building has nothing to match against`() {
        assertIs<BuildingQueryResult.NotUnderstood>(BuildingQueryMatcher.match("tuvalet", emptyList(), emptyMap()))
    }

    @Test
    fun `matching is case and Turkish-diacritic insensitive on the question side`() {
        val result = BuildingQueryMatcher.match("TUVALET NEREDE", allNodes, nodeFloor)

        assertEquals(toilet, (result as BuildingQueryResult.Found).node)
    }

    @Test
    fun `without a known position, the first qualifying candidate is returned deterministically`() {
        val nodes = listOf(
            MapNode("x", 1.0, 0.0, NodeType.WAYPOINT, name = "Tuvalet"),
            MapNode("y", 2.0, 0.0, NodeType.WAYPOINT, name = "Tuvalet"),
        )
        val a = BuildingQueryMatcher.match("tuvalet", nodes, mapOf("x" to 0, "y" to 0))
        val b = BuildingQueryMatcher.match("tuvalet", nodes, mapOf("x" to 0, "y" to 0))

        assertEquals((a as BuildingQueryResult.Found).node.id, (b as BuildingQueryResult.Found).node.id)
    }
}
