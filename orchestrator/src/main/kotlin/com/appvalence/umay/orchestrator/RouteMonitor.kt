package com.appvalence.umay.orchestrator

import kotlin.math.hypot

/** One straight stretch of the route on the walker's floor. */
data class RouteSegment(val fromX: Double, val fromY: Double, val toX: Double, val toY: Double) {
    fun distanceTo(x: Double, y: Double): Double {
        val dx = toX - fromX
        val dy = toY - fromY
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared == 0.0) return hypot(x - fromX, y - fromY)
        val t = (((x - fromX) * dx + (y - fromY) * dy) / lengthSquared).coerceIn(0.0, 1.0)
        return hypot(x - (fromX + t * dx), y - (fromY + t * dy))
    }
}

/**
 * Notices a walker leaving the route, from one position per footstep:
 * - [Status.OFF_ROUTE]: farther than [offRouteMeters] from every stretch of
 *   the route on this floor for [samplesNeeded] steps in a row — a wrong turn
 *   into another corridor;
 * - [Status.WRONG_WAY]: on the route but getting farther from the next route
 *   point for [wrongWaySteps] steps in a row, by over [wrongWayMeters] since the
 *   closest it came — walking back the way they came.
 * A single noisy position never counts. Not thread-safe.
 */
class RouteMonitor(
    private val offRouteMeters: Double = OFF_ROUTE_METERS,
    private val samplesNeeded: Int = OFF_ROUTE_STEPS,
    private val wrongWaySteps: Int = WRONG_WAY_STEPS,
    private val wrongWayMeters: Double = WRONG_WAY_METERS,
) {
    enum class Status { ON_ROUTE, OFF_ROUTE, WRONG_WAY }

    private var offCount = 0
    private var closestToTarget = Double.MAX_VALUE
    private var lastToTarget = Double.MAX_VALUE
    private var recedingCount = 0

    /** A new leg (or route): the target changed, so the distance to it starts over. */
    fun reset() {
        offCount = 0
        closestToTarget = Double.MAX_VALUE
        lastToTarget = Double.MAX_VALUE
        recedingCount = 0
    }

    fun update(x: Double, y: Double, route: List<RouteSegment>, targetX: Double, targetY: Double): Status {
        if (route.isEmpty()) return Status.ON_ROUTE
        val fromRoute = route.minOf { it.distanceTo(x, y) }
        offCount = if (fromRoute > offRouteMeters) offCount + 1 else 0
        if (offCount >= samplesNeeded) return Status.OFF_ROUTE

        val toTarget = hypot(targetX - x, targetY - y)
        recedingCount = if (toTarget > lastToTarget) recedingCount + 1 else 0
        lastToTarget = toTarget
        closestToTarget = minOf(closestToTarget, toTarget)
        return if (recedingCount >= wrongWaySteps && toTarget - closestToTarget > wrongWayMeters) Status.WRONG_WAY else Status.ON_ROUTE
    }

    companion object {
        // A corridor's width plus what positioning can be off by: closer than this is "on it".
        const val OFF_ROUTE_METERS = 4.0
        const val OFF_ROUTE_STEPS = 3
        const val WRONG_WAY_STEPS = 5
        const val WRONG_WAY_METERS = 2.5
    }
}
