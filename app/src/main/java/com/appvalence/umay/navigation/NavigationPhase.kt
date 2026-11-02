package com.appvalence.umay.navigation

import com.appvalence.umay.coremodel.MapNode
import com.appvalence.umay.orchestrator.NavigationOrchestrator
import com.appvalence.umay.orchestrator.NavigationState
import kotlin.math.hypot

/**
 * The one-word answer to "how's it going?" — shown as the status banner, logged, and put in the notification.
 * [POSITION_UNCERTAIN]: the position estimate is too loose to guide by (see [NavigationOrchestrator.canGuide]).
 */
enum class NavigationPhase { SEARCHING, ON_ROUTE, POSITION_UNCERTAIN, ARRIVED }

fun navigationPhase(state: NavigationState?, destinationNode: MapNode, destinationFloor: Int): NavigationPhase {
    if (state == null) return NavigationPhase.SEARCHING
    if (state.pendingFloorChange != null) return NavigationPhase.ON_ROUTE

    // Arrival can only be judged once the user is actually on the destination's floor
    // — comparing (x, y) across floors is meaningless (independent PDR origins).
    val position = state.position
    if (position.floor != destinationFloor) return NavigationPhase.ON_ROUTE
    val distanceToDestination = hypot(position.x - destinationNode.x, position.y - destinationNode.y)
    if (distanceToDestination <= NavigationOrchestrator.ARRIVAL_DISTANCE_METERS) return NavigationPhase.ARRIVED

    return NavigationPhase.ON_ROUTE
}
