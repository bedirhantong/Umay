package com.appvalence.umay.navigation

import com.appvalence.umay.coremodel.FloorTransition
import com.appvalence.umay.coremodel.MapNode
import com.appvalence.umay.coremodel.Position
import com.appvalence.umay.positioning.trilateration.BeaconMapBuilder
import kotlin.math.ceil
import kotlin.math.hypot

/** What's left of the route, in the terms a person actually thinks in: the next place, and how far to the end. */
data class RouteProgress(
    val nextNode: MapNode,
    /** Null when the next node is on another floor — (x, y) across floors aren't comparable. */
    val metersToNext: Double?,
    val metersToDestination: Double,
    val minutesToDestination: Int,
)

// Visually impaired pedestrians walk noticeably slower than the ~1.3 m/s sighted
// average — especially indoors, with a cane, through unfamiliar corridors — so
// estimates use a deliberately conservative pace rather than promise an arrival
// time that turns out optimistic.
const val ASSUMED_WALKING_SPEED_MPS = 0.8

/**
 * Remaining distance along [route] from [position], starting with the leg
 * toward `route[nextRouteIndex]`. A leg between floors counts its
 * [FloorTransition.costMeters] (falling back to the survey default), the same
 * way routing weighed it. Null when there's nothing left to walk.
 */
fun routeProgress(
    route: List<MapNode>,
    nextRouteIndex: Int,
    position: Position,
    nodeFloor: Map<String, Int>,
    floorTransitions: List<FloorTransition>,
): RouteProgress? {
    if (route.size < 2 || nextRouteIndex !in route.indices) return null
    val next = route[nextRouteIndex]

    fun transitionCost(from: MapNode, to: MapNode): Double =
        floorTransitions.firstOrNull {
            (it.fromNodeId == from.id && it.toNodeId == to.id) || (it.fromNodeId == to.id && it.toNodeId == from.id)
        }?.costMeters ?: BeaconMapBuilder.FLOOR_TRANSITION_COST_METERS

    val metersToNext = if (nodeFloor[next.id] == position.floor) hypot(next.x - position.x, next.y - position.y) else null
    // Off-floor next node: the leg toward it is a floor crossing, so charge it the
    // same way as any other transition rather than pretend it's free.
    var total = metersToNext ?: route.getOrNull(nextRouteIndex - 1)?.let { transitionCost(it, next) } ?: 0.0
    for (i in nextRouteIndex until route.lastIndex) {
        val from = route[i]
        val to = route[i + 1]
        total += if (nodeFloor[from.id] == nodeFloor[to.id]) hypot(to.x - from.x, to.y - from.y) else transitionCost(from, to)
    }

    return RouteProgress(
        nextNode = next,
        metersToNext = metersToNext,
        metersToDestination = total,
        minutesToDestination = ceil(total / ASSUMED_WALKING_SPEED_MPS / 60.0).toInt(),
    )
}
