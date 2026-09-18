package com.appvalence.umay.orchestrator

import com.appvalence.umay.coremodel.DetectedObstacle
import com.appvalence.umay.coremodel.MapNode
import com.appvalence.umay.coremodel.NavigationAlert
import com.appvalence.umay.coremodel.Position

/**
 * Snapshot combining the latest fused position, currently visible obstacles,
 * the currently planned route (start to destination, replanned around any
 * obstacle blocking it — see [NavigationOrchestrator]), and any alerts
 * derived from them against the static building map.
 */
data class NavigationState(
    val position: Position,
    val obstacles: List<DetectedObstacle>,
    val alerts: List<NavigationAlert>,
    val route: List<MapNode>,
)
