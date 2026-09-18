package com.appvalence.umay.orchestrator

import com.appvalence.umay.coremodel.DetectedObstacle
import com.appvalence.umay.coremodel.NavigationAlert
import com.appvalence.umay.coremodel.Position

/**
 * Snapshot combining the latest fused position, currently visible obstacles
 * and any alerts derived from them against the static building map.
 */
data class NavigationState(
    val position: Position,
    val obstacles: List<DetectedObstacle>,
    val alerts: List<NavigationAlert>,
)
