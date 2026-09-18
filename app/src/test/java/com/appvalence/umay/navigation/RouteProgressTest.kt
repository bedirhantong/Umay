package com.appvalence.umay.navigation

import com.appvalence.umay.coremodel.FloorTransition
import com.appvalence.umay.coremodel.MapNode
import com.appvalence.umay.coremodel.NodeType
import com.appvalence.umay.coremodel.Position
import com.appvalence.umay.coremodel.PositionSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouteProgressTest {

    private val a = MapNode("A", 0.0, 0.0, NodeType.WAYPOINT)
    private val b = MapNode("B", 0.0, 10.0, NodeType.WAYPOINT)
    private val c = MapNode("C", 0.0, 20.0, NodeType.WAYPOINT)
    private val sameFloor = mapOf("A" to 0, "B" to 0, "C" to 0)

    private fun at(x: Double, y: Double, floor: Int = 0) = Position(x, y, floor, 0.0, 1.0, PositionSource.FUSED, 0L)

    @Test
    fun `remaining distance is the leg to the next node plus every leg after it`() {
        val progress = routeProgress(listOf(a, b, c), nextRouteIndex = 1, position = at(0.0, 4.0), nodeFloor = sameFloor, floorTransitions = emptyList())!!

        assertEquals(b, progress.nextNode)
        assertEquals(6.0, progress.metersToNext!!, 1e-9)
        assertEquals(16.0, progress.metersToDestination, 1e-9)
    }

    @Test
    fun `a floor crossing counts its transition cost and leaves the next-node distance unknown`() {
        val stairsTop = MapNode("S", 50.0, 50.0, NodeType.STAIRS)
        val nodeFloor = mapOf("A" to 0, "S" to 1)
        val transition = FloorTransition("A", 0, "S", 1, costMeters = 12.0)

        val progress = routeProgress(listOf(a, stairsTop), nextRouteIndex = 1, position = at(0.0, 0.0), nodeFloor = nodeFloor, floorTransitions = listOf(transition))!!

        assertNull(progress.metersToNext)
        assertEquals(12.0, progress.metersToDestination, 1e-9)
    }

    @Test
    fun `minutes round up so a short walk never reads as zero`() {
        val progress = routeProgress(listOf(a, b), nextRouteIndex = 1, position = at(0.0, 9.0), nodeFloor = sameFloor, floorTransitions = emptyList())!!

        assertEquals(1, progress.minutesToDestination)
    }

    @Test
    fun `no progress for a route with nothing left to walk`() {
        assertNull(routeProgress(listOf(a), nextRouteIndex = 1, position = at(0.0, 0.0), nodeFloor = sameFloor, floorTransitions = emptyList()))
    }
}
