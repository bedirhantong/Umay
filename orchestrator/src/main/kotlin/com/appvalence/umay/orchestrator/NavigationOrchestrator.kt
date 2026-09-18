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
import com.appvalence.umay.positioning.engine.PositioningEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Combines the latest position and obstacle streams against the static
 * building map into a single [NavigationState] (FR-10/FR-11/FR-12):
 *
 * - Plans (and, on the first fix for a floor, re-plans) an A* route from
 *   [startNodeId] to [destinationNodeId] via [AStarPathfinder].
 * - Speaks turn-by-turn route guidance as the user walks: a leg instruction
 *   ("X metre ilerle, sonra sağa dön") each time they reach a route node,
 *   repeated periodically while still on the same leg so a user who can't
 *   see the screen isn't left with silence, and a one-time arrival
 *   announcement. This is the actual point of the app — a blind user cannot
 *   read the on-screen route, so if this orchestrator only spoke about
 *   obstacles, it would never actually guide anyone anywhere.
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

    // Route guidance progress: the index into currentRoute of the node the
    // user is currently walking toward. Reset whenever the route is (re)planned.
    private var routeTargetIndex = 1
    private var lastAnnouncedLegIndex = -1
    private var lastLegAnnouncedAtMs = 0L
    private var arrivalAnnounced = false
    private var noRouteAnnounced = false

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
        // Guard by floor alone, not also "route non-empty": if pathfinding genuinely
        // found no path, that's a stable fact until a reroute changes blockedEdges,
        // not something to keep retrying (and re-announcing) every position update.
        if (plannedFloorIndex == floorMap.floorIndex) return
        plannedFloorIndex = floorMap.floorIndex
        currentRoute = AStarPathfinder.findPath(floorMap, startNodeId, destinationNodeId, blockedEdges).orEmpty()
        routeTargetIndex = 1
        lastAnnouncedLegIndex = -1
        arrivalAnnounced = false
        noRouteAnnounced = false
    }

    private fun deriveAlerts(position: Position, obstacles: List<DetectedObstacle>, floorMap: FloorMap?): List<NavigationAlert> {
        // Proximity alerts (FR-12) never depend on a loaded route/map — a person right in
        // front of the camera is dangerous whether or not we know the building's layout.
        // Rerouting (FR-11) is the only part that genuinely needs a floor map.
        val nodesById = floorMap?.nodes?.associateBy { it.id } ?: emptyMap()
        val alerts = mutableListOf<NavigationAlert>()

        // Route guidance (like rerouting) genuinely needs a floor map — with none
        // loaded there's no route to have gone missing, so there's nothing to say.
        if (floorMap != null) {
            deriveRouteGuidance(position)?.let { alerts += it }
        }

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

    private fun deriveRouteGuidance(position: Position): NavigationAlert? {
        if (currentRoute.isEmpty()) {
            if (noRouteAnnounced) return null
            noRouteAnnounced = true
            return NavigationAlert(
                id = "no-route-${UUID.randomUUID()}",
                priority = AlertPriority.WARNING,
                message = "Bu iki nokta arasında bir rota bulunamadı",
                relatedObstacle = null,
                createdAtMs = position.timestampMs,
            )
        }
        if (currentRoute.size < 2 || arrivalAnnounced) return null

        // Advance toward the next route node each time the user gets close enough to
        // the one they were walking toward, so guidance always describes the leg
        // that's actually ahead of them, not the one they already finished.
        while (routeTargetIndex < currentRoute.lastIndex) {
            val target = currentRoute[routeTargetIndex]
            if (hypot(target.x - position.x, target.y - position.y) > WAYPOINT_ARRIVAL_METERS) break
            routeTargetIndex++
            lastAnnouncedLegIndex = -1 // force an immediate announcement of the new leg
        }

        val destination = currentRoute.last()
        val distanceToDestination = hypot(destination.x - position.x, destination.y - position.y)
        if (routeTargetIndex == currentRoute.lastIndex && distanceToDestination <= ARRIVAL_DISTANCE_METERS) {
            arrivalAnnounced = true
            return NavigationAlert(
                id = "arrived-${UUID.randomUUID()}",
                priority = AlertPriority.INFO,
                message = "Hedefe ulaştın",
                relatedObstacle = null,
                createdAtMs = position.timestampMs,
            )
        }

        val now = position.timestampMs
        val isNewLeg = routeTargetIndex != lastAnnouncedLegIndex
        val isDueForRepeat = now - lastLegAnnouncedAtMs >= ROUTE_GUIDANCE_REPEAT_INTERVAL_MS
        if (!isNewLeg && !isDueForRepeat) return null

        lastAnnouncedLegIndex = routeTargetIndex
        lastLegAnnouncedAtMs = now
        return NavigationAlert(
            id = "route-guide-$routeTargetIndex-$now",
            priority = AlertPriority.INFO,
            message = legInstruction(routeTargetIndex, position),
            relatedObstacle = null,
            createdAtMs = now,
        )
    }

    private fun legInstruction(targetIndex: Int, position: Position): String {
        val target = currentRoute[targetIndex]
        val distanceRounded = (hypot(target.x - position.x, target.y - position.y) * 10).roundToInt() / 10.0

        if (targetIndex == currentRoute.lastIndex) {
            return "Hedefe yaklaşık $distanceRounded metre kaldı"
        }

        val previous = currentRoute[targetIndex - 1]
        val next = currentRoute[targetIndex + 1]
        val turn = classifyTurn(bearingDeg(previous, target), bearingDeg(target, next))
        return if (turn == null) {
            "${target.name}'a doğru yaklaşık $distanceRounded metre ilerle"
        } else {
            "${target.name}'a yaklaşık $distanceRounded metre ilerle, sonra $turn"
        }
    }

    // Compass bearing (0 = north/+y, 90 = east/+x, clockwise) — matches PdrIntegrator's
    // heading convention so the resulting turn direction lines up with how the phone
    // tracks its own heading.
    private fun bearingDeg(from: MapNode, to: MapNode): Double =
        (Math.toDegrees(atan2(to.x - from.x, to.y - from.y)) + 360.0) % 360.0

    private fun classifyTurn(incomingBearingDeg: Double, outgoingBearingDeg: Double): String? {
        val turnAngle = ((outgoingBearingDeg - incomingBearingDeg + 540.0) % 360.0) - 180.0
        return when {
            turnAngle > TURN_ANGLE_THRESHOLD_DEG -> "sağa dön"
            turnAngle < -TURN_ANGLE_THRESHOLD_DEG -> "sola dön"
            else -> null
        }
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

        const val WAYPOINT_ARRIVAL_METERS = 2.0
        const val ARRIVAL_DISTANCE_METERS = 1.5
        const val TURN_ANGLE_THRESHOLD_DEG = 25.0
        const val ROUTE_GUIDANCE_REPEAT_INTERVAL_MS = 8_000L
    }
}
