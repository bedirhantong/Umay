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
 *
 * [autoConfirm] is true once enough footsteps have been taken on a STAIRS
 * crossing that the orchestrator infers the user has arrived without an
 * explicit tap — the UI should apply the same confirmation automatically
 * rather than wait for one. It never becomes true for an elevator crossing
 * (standing in an elevator produces no footsteps), so those stay manual-only.
 */
data class FloorChangeHint(
    val targetNodeId: String,
    val targetNodeName: String,
    val targetFloor: Int,
    val autoConfirm: Boolean = false,
)

/**
 * Snapshot combining the latest fused position, currently visible obstacles,
 * the currently planned route (start to destination, possibly spanning
 * multiple floors, replanned around any obstacle blocking it — see
 * [NavigationOrchestrator]), any alerts derived from them against the static
 * building map, and whether the route is currently waiting on a floor change.
 *
 * [nextRouteIndex] is the index into [route] of the node the user is currently
 * walking toward — everything before it is already behind them, which is what
 * lets a map draw the traveled part of the route differently from what's left.
 */
data class NavigationState(
    val position: Position,
    val obstacles: List<DetectedObstacle>,
    val alerts: List<NavigationAlert>,
    val route: List<MapNode>,
    val pendingFloorChange: FloorChangeHint? = null,
    val nextRouteIndex: Int = 1,
)
