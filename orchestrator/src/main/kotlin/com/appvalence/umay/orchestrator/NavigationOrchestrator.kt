package com.appvalence.umay.orchestrator

import com.appvalence.umay.coremodel.AlertPriority
import com.appvalence.umay.coremodel.BuildingMap
import com.appvalence.umay.coremodel.DetectedObstacle
import com.appvalence.umay.coremodel.FloorMap
import com.appvalence.umay.coremodel.MapEdge
import com.appvalence.umay.coremodel.MapNode
import com.appvalence.umay.coremodel.NavigationAlert
import com.appvalence.umay.coremodel.ObstacleType
import com.appvalence.umay.coremodel.Position
import com.appvalence.umay.feedback.FeedbackController
import com.appvalence.umay.perception.ObstacleDetector
import com.appvalence.umay.positioning.PositioningEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import java.util.UUID
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Combines the latest position and obstacle streams against the static
 * building map into a single [NavigationState] (FR-10/FR-11/FR-12):
 *
 * - Plans (and, on the first fix for a floor, re-plans) an A* route from
 *   [startNodeId] to [destinationNodeId] via [AStarPathfinder].
 * - Raises CRITICAL/WARNING alerts for nearby obstacles, re-alerting on an
 *   interval (not every single frame — that would flood [feedback] with
 *   TTS requests) or immediately on a priority escalation.
 * - When an obstacle is found to be sitting on the current route's next
 *   edge (via [ObstacleEdgeMapper]), blocks that edge and replans from the
 *   nearest graph node to the destination, raising an INFO alert about the
 *   reroute.
 *
 * Alerts are forwarded to [feedback] as they're produced.
 */
class NavigationOrchestrator(
    private val positioning: PositioningEngine,
    private val perception: ObstacleDetector,
    private val map: BuildingMap,
    private val feedback: FeedbackController,
    private val startNodeId: String,
    private val destinationNodeId: String,
) {
    private var plannedFloorIndex: Int? = null
    private var currentRoute: List<MapNode> = emptyList()
    private val blockedEdges = mutableSetOf<Pair<String, String>>()
    private val lastAlertedAtMsByObstacleId = mutableMapOf<String, Long>()
    private val lastAlertedPriorityByObstacleId = mutableMapOf<String, AlertPriority>()

    fun start(): Flow<NavigationState> =
        combine(positioning.positionUpdates(), perception.obstacleUpdates()) { position, obstacles ->
            val floorMap = floorMapFor(position.floor)
            ensureRoute(floorMap)
            val alerts = deriveAlerts(position, obstacles, floorMap)
            NavigationState(position = position, obstacles = obstacles, alerts = alerts, route = currentRoute)
        }.onEach { state ->
            state.alerts.forEach(feedback::emit)
        }

    private fun floorMapFor(floorIndex: Int): FloorMap? = map.floors.firstOrNull { it.floorIndex == floorIndex }

    private fun ensureRoute(floorMap: FloorMap?) {
        if (floorMap == null) return
        if (currentRoute.isNotEmpty() && plannedFloorIndex == floorMap.floorIndex) return
        plannedFloorIndex = floorMap.floorIndex
        currentRoute = AStarPathfinder.findPath(floorMap, startNodeId, destinationNodeId, blockedEdges).orEmpty()
    }

    private fun deriveAlerts(position: Position, obstacles: List<DetectedObstacle>, floorMap: FloorMap?): List<NavigationAlert> {
        // Proximity alerts (FR-12) never depend on a loaded route/map — a person right in
        // front of the camera is dangerous whether or not we know the building's layout.
        // Rerouting (FR-11) is the only part that genuinely needs a floor map.
        val nodesById = floorMap?.nodes?.associateBy { it.id } ?: emptyMap()
        val alerts = mutableListOf<NavigationAlert>()

        for (obstacle in obstacles) {
            // Proximity alerting and rerouting are independent: an obstacle can be far
            // enough away to skip a CRITICAL/WARNING alert but still be sitting on the
            // route ahead, which should trigger a reroute before it becomes dangerous.
            val priority = priorityFor(obstacle)
            if (priority == null) {
                lastAlertedPriorityByObstacleId.remove(obstacle.id)
            } else if (shouldAlert(obstacle.id, priority, obstacle.detectedAtMs)) {
                lastAlertedAtMsByObstacleId[obstacle.id] = obstacle.detectedAtMs
                lastAlertedPriorityByObstacleId[obstacle.id] = priority
                alerts += NavigationAlert(
                    id = "obstacle-${obstacle.id}-${obstacle.detectedAtMs}",
                    priority = priority,
                    message = obstacleMessage(priority, obstacle),
                    relatedObstacle = obstacle,
                    createdAtMs = obstacle.detectedAtMs,
                )
            }

            if (floorMap != null) {
                rerouteIfBlocking(obstacle, position, floorMap, nodesById)?.let { alerts += it }
            }
        }
        return alerts
    }

    private fun shouldAlert(obstacleId: String, priority: AlertPriority, nowMs: Long): Boolean {
        val previousPriority = lastAlertedPriorityByObstacleId[obstacleId]
        if (previousPriority != priority) return true // escalation (or first sighting) always alerts immediately
        val elapsed = nowMs - (lastAlertedAtMsByObstacleId[obstacleId] ?: 0L)
        return elapsed >= MIN_REALERT_INTERVAL_MS
    }

    private fun rerouteIfBlocking(
        obstacle: DetectedObstacle,
        position: Position,
        floorMap: FloorMap,
        nodesById: Map<String, MapNode>,
    ): NavigationAlert? {
        val routeEdges = currentRouteEdges(floorMap)
        val blocked = ObstacleEdgeMapper.blockingEdge(obstacle, position, routeEdges, nodesById) ?: return null
        if (!blockedEdges.add(blocked.fromId to blocked.toId)) return null // already accounted for

        val nearestNode = nodesById.values.minByOrNull { hypot(it.x - position.x, it.y - position.y) } ?: return null
        val rerouted = AStarPathfinder.findPath(floorMap, nearestNode.id, destinationNodeId, blockedEdges) ?: return null
        currentRoute = rerouted

        return NavigationAlert(
            id = "reroute-${blocked.fromId}-${blocked.toId}-${UUID.randomUUID()}",
            priority = AlertPriority.INFO,
            message = "Rota önündeki engel nedeniyle güncellendi",
            relatedObstacle = obstacle,
            createdAtMs = obstacle.detectedAtMs,
        )
    }

    private fun currentRouteEdges(floorMap: FloorMap): List<MapEdge> {
        val routeNodeIds = currentRoute.map { it.id }.toSet()
        if (routeNodeIds.isEmpty()) return emptyList()
        return floorMap.edges.filter { it.fromId in routeNodeIds && it.toId in routeNodeIds }
    }

    private fun priorityFor(obstacle: DetectedObstacle): AlertPriority? = when {
        obstacle.distanceMeters <= CRITICAL_DISTANCE_METERS -> AlertPriority.CRITICAL
        obstacle.distanceMeters <= WARNING_DISTANCE_METERS -> AlertPriority.WARNING
        else -> null
    }

    private fun obstacleMessage(priority: AlertPriority, obstacle: DetectedObstacle): String {
        val distanceRounded = (obstacle.distanceMeters * 10).roundToInt() / 10.0
        val subject = if (obstacle.type == ObstacleType.PERSON) "Kişi" else "Engel"
        return when (priority) {
            AlertPriority.CRITICAL -> "Dur, $subject çok yakın, yaklaşık $distanceRounded metre"
            AlertPriority.WARNING -> "Dikkat, $subject yaklaşık $distanceRounded metre önünüzde"
            AlertPriority.INFO -> "$subject yaklaşık $distanceRounded metre önünüzde"
        }
    }

    companion object {
        const val CRITICAL_DISTANCE_METERS = 1.5
        const val WARNING_DISTANCE_METERS = 3.5
        const val MIN_REALERT_INTERVAL_MS = 2_500L
    }
}
