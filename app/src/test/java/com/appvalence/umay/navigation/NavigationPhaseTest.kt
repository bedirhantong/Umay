package com.appvalence.umay.navigation

import com.appvalence.umay.coremodel.MapNode
import com.appvalence.umay.coremodel.NodeType
import com.appvalence.umay.coremodel.Position
import com.appvalence.umay.coremodel.PositionSource
import com.appvalence.umay.orchestrator.FloorChangeHint
import com.appvalence.umay.orchestrator.NavigationState
import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationPhaseTest {

    private val destination = MapNode("D", 0.0, 10.0, NodeType.DESTINATION)

    private fun state(x: Double, y: Double, floor: Int = 0, pending: FloorChangeHint? = null) =
        NavigationState(
            position = Position(x, y, floor, 0.0, 1.0, PositionSource.FUSED, 0L),
            alerts = emptyList(),
            route = emptyList(),
            pendingFloorChange = pending,
        )

    @Test
    fun `searching until the first position arrives`() {
        assertEquals(NavigationPhase.SEARCHING, navigationPhase(null, destination, destinationFloor = 0))
    }

    @Test
    fun `arrived when close to the destination on its floor`() {
        assertEquals(NavigationPhase.ARRIVED, navigationPhase(state(0.0, 9.0), destination, destinationFloor = 0))
    }

    @Test
    fun `never arrived on the wrong floor, however close the coordinates`() {
        assertEquals(NavigationPhase.ON_ROUTE, navigationPhase(state(0.0, 10.0, floor = 1), destination, destinationFloor = 0))
    }

    @Test
    fun `waiting on a floor change keeps the walk on route, even next to the destination`() {
        assertEquals(
            NavigationPhase.ON_ROUTE,
            navigationPhase(state(0.0, 9.5, pending = FloorChangeHint("S", "Stairs", 1)), destination, 0),
        )
    }
}
