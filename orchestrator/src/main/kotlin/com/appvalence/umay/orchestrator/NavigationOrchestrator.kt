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
 * - Plans (once) an A* route from [startNodeId] to [destinationNodeId] across
 *   the *whole* building via [AStarPathfinder.findPathAcrossFloors] — the
 *   route can cross floors via stairs/elevators ([BuildingMap.floorTransitions]).
 * - Speaks turn-by-turn route guidance as the user walks: a leg instruction
 *   ("X metre ilerle, sonra sağa dön") each time they reach a route node,
 *   repeated periodically while still on the same leg so a user who can't
 *   see the screen isn't left with silence, and a one-time arrival
 *   announcement. This is the actual point of the app — a blind user cannot
 *   read the on-screen route, so if this orchestrator only spoke about
 *   obstacles, it would never actually guide anyone anywhere.
 * - When the route's current leg crosses floors, progress can't be tracked by
 *   proximity (two floors' coordinates come from independent PDR origins) —
 *   [NavigationState.pendingFloorChange] surfaces this so the UI can offer a
 *   manual "kat değiştirdim" confirmation (there's no barometer-based
 *   detection anywhere in this app); confirming it resets live tracking onto
 *   the new floor, which is what actually lets progress continue.
 * - Raises CRITICAL/WARNING alerts for nearby obstacles, re-alerting on an
 *   interval (not every single frame — that would flood [feedback] with
 *   TTS requests) or immediately on a priority escalation.
 * - When an obstacle is found to be sitting on the current route's next
 *   edge on the user's current floor (via [ObstacleEdgeMapper]), blocks that
 *   edge and replans from the nearest graph node to the destination, raising
 *   an INFO alert about the reroute.
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
    private val nodeFloor: Map<String, Int> =
        map.floors.flatMap { floor -> floor.nodes.map { it.id to floor.floorIndex } }.toMap()

    private var hasPlannedRoute = false
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
    private var pendingFloorChange: FloorChangeHint? = null

    fun start(): Flow<NavigationState> =
        combine(positioning.positionUpdates(), perception.obstacleUpdates()) { position, obstacles ->
            ensureRoute()
            val floorMap = floorMapFor(position.floor)
            val alerts = deriveAlerts(position, obstacles, floorMap)
            NavigationState(
                position = position,
                obstacles = obstacles,
                alerts = alerts,
                route = currentRoute,
                pendingFloorChange = pendingFloorChange,
            )
        }.onEach { state ->
            state.alerts.forEach(feedback::emit)
        }

    private fun floorMapFor(floorIndex: Int): FloorMap? = map.floors.firstOrNull { it.floorIndex == floorIndex }

    private fun ensureRoute() {
        // The route spans the whole building, not one floor, so there's nothing to
        // re-key this on — plan once, replan only via an obstacle-triggered reroute.
        if (hasPlannedRoute) return
        hasPlannedRoute = true
        currentRoute = AStarPathfinder.findPathAcrossFloors(map, startNodeId, destinationNodeId, blockedEdges).orEmpty()
        resetRouteProgress()
    }

    private fun resetRouteProgress() {
        routeTargetIndex = 1
        lastAnnouncedLegIndex = -1
        arrivalAnnounced = false
        noRouteAnnounced = false
        pendingFloorChange = null
    }

    private fun deriveAlerts(position: Position, obstacles: List<DetectedObstacle>, floorMap: FloorMap?): List<NavigationAlert> {
        // Proximity alerts (FR-12) never depend on a loaded route/map — a person right in
        // front of the camera is dangerous whether or not we know the building's layout.
        // Rerouting (FR-11) is the only part that genuinely needs the current floor's map.
        val nodesById = floorMap?.nodes?.associateBy { it.id } ?: emptyMap()
        val alerts = mutableListOf<NavigationAlert>()

        deriveRouteGuidance(position)?.let { alerts += it }

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
            pendingFloorChange = null
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
        // that's actually ahead of them, not the one they already finished. Proximity
        // is only meaningful on the same floor — two floors' (x, y) come from
        // independent PDR origins, so a target on a different floor blocks advancement
        // until the user confirms the crossing (see pendingFloorChange below).
        while (routeTargetIndex < currentRoute.lastIndex) {
            val target = currentRoute[routeTargetIndex]
            if (nodeFloor[target.id] != position.floor) break
            if (hypot(target.x - position.x, target.y - position.y) > WAYPOINT_ARRIVAL_METERS) break
            routeTargetIndex++
            lastAnnouncedLegIndex = -1 // force an immediate announcement of the new leg
        }

        val target = currentRoute[routeTargetIndex]
        val targetFloor = nodeFloor[target.id]
        pendingFloorChange = if (targetFloor != null && targetFloor != position.floor) {
            FloorChangeHint(target.id, target.name, targetFloor)
        } else {
            null
        }

        if (routeTargetIndex == currentRoute.lastIndex && targetFloor == position.floor) {
            val distanceToDestination = hypot(target.x - position.x, target.y - position.y)
            if (distanceToDestination <= ARRIVAL_DISTANCE_METERS) {
                arrivalAnnounced = true
                return NavigationAlert(
                    id = "arrived-${UUID.randomUUID()}",
                    priority = AlertPriority.INFO,
                    message = "Hedefe ulaştın",
                    relatedObstacle = null,
                    createdAtMs = position.timestampMs,
                )
            }
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
        val previous = currentRoute[targetIndex - 1]

        if (nodeFloor[target.id] != nodeFloor[previous.id]) {
            return "${target.name}'a git, sonra merdiven veya asansörle kat değiştir"
        }

        val distanceRounded = (hypot(target.x - position.x, target.y - position.y) * 10).roundToInt() / 10.0
        if (targetIndex == currentRoute.lastIndex) {
            return "Hedefe yaklaşık $distanceRounded metre kaldı"
        }

        val next = currentRoute[targetIndex + 1]
        // A turn direction needs both bearings on the same floor as target — if the
        // *next* leg crosses floors, there's nothing meaningful to compare against.
        val turn = if (nodeFloor[next.id] == nodeFloor[target.id]) {
            classifyTurn(bearingDeg(previous, target), bearingDeg(target, next))
        } else {
            null
        }
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
        // The destination may be on a different floor than this obstacle, so replanning
        // has to consider the whole building again, not just the floor it was found on.
        val rerouted = AStarPathfinder.findPathAcrossFloors(map, nearestNode.id, destinationNodeId, blockedEdges) ?: return null
        currentRoute = rerouted
        resetRouteProgress()

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
