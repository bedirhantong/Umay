package com.appvalence.umay.navigation

import com.appvalence.umay.coremodel.AlertPriority
import com.appvalence.umay.coremodel.MapNode
import com.appvalence.umay.coremodel.NavigationAlert
import com.appvalence.umay.coremodel.NodeType
import com.appvalence.umay.coremodel.Position
import com.appvalence.umay.coremodel.PositionSource
import com.appvalence.umay.orchestrator.FloorChangeHint
import com.appvalence.umay.orchestrator.NavigationState
import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationPhaseTest {

    private val destination = MapNode("D", 0.0, 10.0, NodeType.DESTINATION)

    private fun state(x: Double, y: Double, floor: Int = 0, alerts: List<NavigationAlert> = emptyList(), pending: FloorChangeHint? = null) =
        NavigationState(
            position = Position(x, y, floor, 0.0, 1.0, PositionSource.FUSED, 0L),
            obstacles = emptyList(),
            alerts = alerts,
            route = emptyList(),
            pendingFloorChange = pending,
        )

    private fun alert(priority: AlertPriority) = NavigationAlert("a", priority, "m", null, 0L)

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
    fun `a critical alert outranks everything but a pending floor change`() {
        assertEquals(NavigationPhase.CRITICAL, navigationPhase(state(0.0, 0.0, alerts = listOf(alert(AlertPriority.CRITICAL))), destination, 0))
        assertEquals(
            NavigationPhase.ON_ROUTE,
            navigationPhase(state(0.0, 0.0, alerts = listOf(alert(AlertPriority.CRITICAL)), pending = FloorChangeHint("S", "Stairs", 1)), destination, 0),
        )
    }
}
