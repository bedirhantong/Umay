package com.appvalence.umay.orchestrator

import com.appvalence.umay.coremodel.BuildingMap
import com.appvalence.umay.coremodel.DetectedObstacle
import com.appvalence.umay.coremodel.NavigationAlert
import com.appvalence.umay.feedback.FeedbackController
import com.appvalence.umay.perception.ObstacleDetector
import com.appvalence.umay.positioning.PositioningEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach

/**
 * Combines the latest position and obstacle streams against the static
 * building map into a single NavigationState, and forwards derived alerts
 * to the FeedbackController. Alert derivation and local re-routing
 * (FR-11/FR-12) land in Faz 3; this is the Faz 0 wiring skeleton.
 */
class NavigationOrchestrator(
    private val positioning: PositioningEngine,
    private val perception: ObstacleDetector,
    private val map: BuildingMap,
    private val feedback: FeedbackController,
) {
    fun start(): Flow<NavigationState> =
        combine(positioning.positionUpdates(), perception.obstacleUpdates()) { position, obstacles ->
            val alerts = deriveAlerts(obstacles)
            NavigationState(position = position, obstacles = obstacles, alerts = alerts)
        }.onEach { state ->
            state.alerts.forEach(feedback::emit)
        }

    private fun deriveAlerts(obstacles: List<DetectedObstacle>): List<NavigationAlert> {
        // TODO(Faz 3): priority alert generation against the map (FR-12) and
        // local avoidance suggestions (FR-11), backed by LatencyTrace budget checks.
        return emptyList()
    }
}
