package com.appvalence.umay.orchestrator

import com.appvalence.umay.coremodel.DetectedObstacle
import com.appvalence.umay.coremodel.MapNode
import com.appvalence.umay.coremodel.NavigationAlert
import com.appvalence.umay.coremodel.Position

/**
 * A floor crossing the route needs ahead: there's no barometer-based floor
 * detection, so [NavigationOrchestrator] can't tell on its own when the user
 * has taken the stairs/elevator — the UI shows a "kat değiştirdim" control
 * whenever this is non-null, and confirming it is what actually moves
 * tracking onto [targetFloor] (see [com.appvalence.umay.positioning.pdr.PdrIntegrator.changeFloor]).
 */
data class FloorChangeHint(
    val targetNodeId: String,
    val targetNodeName: String,
    val targetFloor: Int,
)

/**
 * Snapshot combining the latest fused position, currently visible obstacles,
 * the currently planned route (start to destination, possibly spanning
 * multiple floors, replanned around any obstacle blocking it — see
 * [NavigationOrchestrator]), any alerts derived from them against the static
 * building map, and whether the route is currently waiting on a floor change.
 */
data class NavigationState(
    val position: Position,
    val obstacles: List<DetectedObstacle>,
    val alerts: List<NavigationAlert>,
    val route: List<MapNode>,
    val pendingFloorChange: FloorChangeHint? = null,
)
