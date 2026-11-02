package com.appvalence.umay.orchestrator

import kotlin.test.Test
import kotlin.test.assertEquals

class RouteMonitorTest {
    // North 20 m, then east 20 m.
    private val route = listOf(RouteSegment(0.0, 0.0, 0.0, 20.0), RouteSegment(0.0, 20.0, 20.0, 20.0))

    @Test
    fun `walking the route is fine, even with a little drift`() {
        val monitor = RouteMonitor()
        val statuses = (0..20).map { monitor.update(if (it % 2 == 0) 1.5 else -1.0, it.toDouble(), route, 0.0, 20.0) }

        assertEquals(setOf(RouteMonitor.Status.ON_ROUTE), statuses.toSet())
    }

    @Test
    fun `a wrong turn into another corridor is noticed after a few steps, not one`() {
        val monitor = RouteMonitor()
        (0..10).forEach { monitor.update(0.0, it.toDouble(), route, 0.0, 20.0) }

        // Turned west at 10 m.
        val statuses = (1..8).map { monitor.update(-it.toDouble(), 10.0, route, 0.0, 20.0) }

        assertEquals(RouteMonitor.Status.ON_ROUTE, statuses[3]) // 4 m out: not yet
        assertEquals(RouteMonitor.Status.OFF_ROUTE, statuses[6])
    }

    @Test
    fun `turning back along the route is noticed as the wrong way`() {
        val monitor = RouteMonitor()
        (0..12).forEach { monitor.update(0.0, it.toDouble(), route, 0.0, 20.0) }

        val statuses = (1..6).map { monitor.update(0.0, 12.0 - it * 0.7, route, 0.0, 20.0) }

        assertEquals(RouteMonitor.Status.WRONG_WAY, statuses.last())
        assertEquals(RouteMonitor.Status.ON_ROUTE, statuses[1])
    }

    @Test
    fun `one wild position doesn't count`() {
        val monitor = RouteMonitor()
        monitor.update(0.0, 5.0, route, 0.0, 20.0)

        assertEquals(RouteMonitor.Status.ON_ROUTE, monitor.update(9.0, 5.0, route, 0.0, 20.0))
        assertEquals(RouteMonitor.Status.ON_ROUTE, monitor.update(0.0, 6.0, route, 0.0, 20.0))
    }
}
