package com.appvalence.umay.orchestrator

import com.appvalence.umay.coremodel.AlertPriority
import com.appvalence.umay.coremodel.BuildingMap
import com.appvalence.umay.coremodel.DetectedObstacle
import com.appvalence.umay.coremodel.FloorMap
import com.appvalence.umay.coremodel.GuidanceCue
import com.appvalence.umay.coremodel.MapEdge
import com.appvalence.umay.coremodel.MapNode
import com.appvalence.umay.coremodel.NavigationAlert
import com.appvalence.umay.coremodel.NodeType
import com.appvalence.umay.coremodel.ObstacleType
import com.appvalence.umay.coremodel.isUnnamed
import com.appvalence.umay.coremodel.Position
import com.appvalence.umay.feedback.FeedbackController
import com.appvalence.umay.perception.ObstacleDetector
import com.appvalence.umay.positioning.engine.PositioningEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onEach
import java.util.UUID
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

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
 * Alerts are forwarded to [feedback] as they're produced. Their wording comes
 * from [phrases], so guidance can be spoken in any language.
 */
class NavigationOrchestrator(
    private val positioning: PositioningEngine,
    private val perception: ObstacleDetector,
    private val map: BuildingMap,
    private val feedback: FeedbackController,
    private val startNodeId: String,
    private val destinationNodeId: String,
    private val phrases: GuidancePhrases,
    // The walker asked for routes without stairs (Settings): lifts and ramps only,
    // falling back to stairs — said out loud — when the building offers nothing else.
    private val avoidStairs: Boolean = false,
) {
    // Decoded once per floor that has a wall map (see WallSide, Passage).
    private val wallSides: Map<Int, WallSide> = map.floors.mapNotNull { floor -> floor.occupancy?.let { floor.floorIndex to WallSide(it) } }.toMap()
    private val passages: Map<Int, Passage> = map.floors.mapNotNull { floor -> floor.occupancy?.let { floor.floorIndex to Passage(it) } }.toMap()

    // Obstacles already said to have room beside them: once is enough.
    private val passHinted = mutableSetOf<String>()

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
    private var stairsFallbackPending = false
    private var stairsFallbackAnnounced = false
    private var pendingFloorChange: FloorChangeHint? = null

    // Steps taken while a STAIRS crossing is pending — there's no barometer, but a
    // flight of stairs reliably produces footsteps, so once enough of them have piled
    // up without the user having explicitly confirmed, it's safe to assume they made
    // it (see deriveRouteGuidance). Elevators produce no footsteps while waiting, so
    // this only ever applies to NodeType.STAIRS targets; elevators stay manual-only.
    private var pendingFloorChangeStepCount = 0
    private var lastCountedPositionTimestampMs = -1L

    // Leaving the route (see RouteMonitor): judged once per position sample, said at
    // most every OFF_ROUTE_REPEAT_MS so a walker finding their way isn't talked over.
    private val routeMonitor = RouteMonitor()
    private var lastMonitoredTimestampMs = -1L
    private var lastDeviationAlertMs = Long.MIN_VALUE / 2

    // A route planned from wherever the walker is (around an obstacle, after leaving the
    // route) may start behind them or to one side, which its first leg's wording can't
    // say. Simulated walks found that without it, "toward the lobby, 10 m" after a blocked
    // corridor sent the walker straight on into the obstacle. So: first back to where the
    // new route starts ("Geri dön."), then, there, which way it goes — both from the
    // heading they're actually walking in, known by then.
    private enum class Reorient { NONE, FIRST_LEG, TO_START }

    private var reorient = Reorient.NONE

    fun start(): Flow<NavigationState> =
        combine(
            positioning.positionUpdates(),
            // "Still nothing in view" repeated for every analyzed camera frame — by far the
            // common case — changes nothing here, but used to re-run all of this and
            // redraw the whole navigation screen several times a second. Frames that do
            // contain obstacles always differ (a fresh detectedAtMs) and still go through.
            perception.obstacleUpdates().distinctUntilChanged { old, new -> old.isEmpty() && new.isEmpty() },
        ) { position, obstacles ->
            ensureRoute()
            val floorMap = floorMapFor(position.floor)
            val alerts = deriveAlerts(position, obstacles, floorMap)
            NavigationState(
                position = position,
                obstacles = obstacles,
                alerts = alerts,
                route = currentRoute,
                pendingFloorChange = pendingFloorChange,
                nextRouteIndex = routeTargetIndex,
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
        currentRoute = planRoute(startNodeId).orEmpty()
        resetRouteProgress()
    }

    /**
     * The route from [fromNodeId] to the destination, step-free when the walker asked
     * for that and the building has one; otherwise the ordinary route, with a one-time
     * notice that it uses stairs.
     */
    private fun planRoute(fromNodeId: String): List<MapNode>? {
        if (avoidStairs) {
            AStarPathfinder.findPathAcrossFloors(map, fromNodeId, destinationNodeId, blockedEdges, avoidStairs = true)?.let { return it }
        }
        val route = AStarPathfinder.findPathAcrossFloors(map, fromNodeId, destinationNodeId, blockedEdges) ?: return null
        if (avoidStairs && !stairsFallbackAnnounced) stairsFallbackPending = true
        return route
    }

    private fun resetRouteProgress() {
        routeTargetIndex = 1
        lastAnnouncedLegIndex = -1
        arrivalAnnounced = false
        noRouteAnnounced = false
        pendingFloorChange = null
        pendingFloorChangeStepCount = 0
        lastCountedPositionTimestampMs = -1L
        routeMonitor.reset()
    }

    private fun deriveAlerts(position: Position, obstacles: List<DetectedObstacle>, floorMap: FloorMap?): List<NavigationAlert> {
        // Proximity alerts (FR-12) never depend on a loaded route/map — a person right in
        // front of the camera is dangerous whether or not we know the building's layout.
        // Rerouting (FR-11) is the only part that genuinely needs the current floor's map.
        val nodesById = floorMap?.nodes?.associateBy { it.id } ?: emptyMap()
        val alerts = mutableListOf<NavigationAlert>()

        stairsFallbackNotice(position)?.let { alerts += it }
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
                    cue = if (priority == AlertPriority.CRITICAL) GuidanceCue.OBSTACLE_CRITICAL else GuidanceCue.OBSTACLE_WARNING,
                )
            }

            if (floorMap != null) {
                rerouteIfBlocking(obstacle, position, floorMap, nodesById)?.let { alerts += it }
            }
        }
        return alerts
    }

    // Said before the first leg, so the walker knows a staircase is coming before
    // being sent toward it. Once per navigation: a later reroute that also needs the
    // stairs adds nothing new.
    private fun stairsFallbackNotice(position: Position): NavigationAlert? {
        if (!stairsFallbackPending) return null
        stairsFallbackPending = false
        stairsFallbackAnnounced = true
        return NavigationAlert(
            id = "no-step-free-route-${position.timestampMs}",
            priority = AlertPriority.INFO,
            message = phrases.noStepFreeRoute(),
            relatedObstacle = null,
            createdAtMs = position.timestampMs,
        )
    }

    private fun deriveRouteGuidance(position: Position): NavigationAlert? {
        if (currentRoute.isEmpty()) {
            pendingFloorChange = null
            if (noRouteAnnounced) return null
            noRouteAnnounced = true
            return NavigationAlert(
                id = "no-route-${UUID.randomUUID()}",
                priority = AlertPriority.WARNING,
                message = phrases.noRoute(),
                relatedObstacle = null,
                createdAtMs = position.timestampMs,
                cue = GuidanceCue.NO_ROUTE,
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
            routeMonitor.reset()
        }

        val target = currentRoute[routeTargetIndex]
        val targetFloor = nodeFloor[target.id]
        pendingFloorChange = if (targetFloor != null && targetFloor != position.floor) {
            // Only count a step once per distinct position sample — this combine()
            // block also re-runs on every obstacle-detector frame reusing the same
            // stale position, which must not inflate the count.
            if (position.timestampMs != lastCountedPositionTimestampMs) {
                lastCountedPositionTimestampMs = position.timestampMs
                pendingFloorChangeStepCount++
            }
            val autoConfirm = target.type == NodeType.STAIRS && pendingFloorChangeStepCount >= AUTO_FLOOR_CHANGE_STEP_COUNT
            FloorChangeHint(target.id, target.name, targetFloor, autoConfirm)
        } else {
            pendingFloorChangeStepCount = 0
            lastCountedPositionTimestampMs = -1L
            null
        }

        if (routeTargetIndex == currentRoute.lastIndex && targetFloor == position.floor) {
            val distanceToDestination = hypot(target.x - position.x, target.y - position.y)
            if (distanceToDestination <= ARRIVAL_DISTANCE_METERS) {
                arrivalAnnounced = true
                return NavigationAlert(
                    id = "arrived-${UUID.randomUUID()}",
                    priority = AlertPriority.INFO,
                    message = phrases.arrived(),
                    relatedObstacle = null,
                    createdAtMs = position.timestampMs,
                    cue = GuidanceCue.ARRIVED,
                )
            }
        }

        checkDeviation(position, target, targetFloor)?.let { return it }

        val routeStart = currentRoute.first()
        // At the new route's start, or already somewhere along its first stretch: either
        // way there's no going back to the start, only which way that stretch runs.
        val firstStretch = currentRoute.getOrNull(1)?.takeIf { nodeFloor[it.id] == position.floor && nodeFloor[routeStart.id] == position.floor }
        val atRouteStart = nodeFloor[routeStart.id] == position.floor && (
            hypot(routeStart.x - position.x, routeStart.y - position.y) <= REJOIN_METERS ||
                (firstStretch != null && RouteSegment(routeStart.x, routeStart.y, firstStretch.x, firstStretch.y).distanceTo(position.x, position.y) <= ON_FIRST_STRETCH_METERS)
            )
        // Back where the new route starts after being sent there: say where it goes now.
        if (reorient == Reorient.TO_START && atRouteStart) lastAnnouncedLegIndex = -1

        val now = position.timestampMs
        val isNewLeg = routeTargetIndex != lastAnnouncedLegIndex
        val isDueForRepeat = now - lastLegAnnouncedAtMs >= ROUTE_GUIDANCE_REPEAT_INTERVAL_MS
        if (!isNewLeg && !isDueForRepeat) return null

        lastAnnouncedLegIndex = routeTargetIndex
        lastLegAnnouncedAtMs = now
        val reorientTurn = if (isNewLeg) reorientationTurn(position, target, routeStart, atRouteStart) else null
        val turnAround = reorientTurn != null && abs(reorientTurn.angleDeg) >= TURN_AROUND_DEG
        return NavigationAlert(
            id = "route-guide-$routeTargetIndex-$now",
            // Stairs are a real fall risk underfoot, so that leg gets the same urgency
            // as a nearby obstacle instead of blending in with ordinary INFO guidance.
            priority = if (target.type == NodeType.STAIRS) AlertPriority.WARNING else AlertPriority.INFO,
            message = listOfNotNull(
                reorientTurn?.let { if (turnAround) phrases.turnAround() else phrases.turnNow(it) },
                legInstruction(routeTargetIndex, position),
            ).joinToString(" "),
            relatedObstacle = null,
            createdAtMs = now,
            // Only a new leg gets a cue; the periodic repeat of the same leg stays silent
            // to the touch (see GuidanceCue).
            cue = when {
                !isNewLeg -> GuidanceCue.NONE
                turnAround -> GuidanceCue.WRONG_WAY
                reorientTurn != null -> if (reorientTurn.direction == TurnDirection.RIGHT) GuidanceCue.TURN_RIGHT else GuidanceCue.TURN_LEFT
                else -> legCue(routeTargetIndex)
            },
        )
    }

    /**
     * Off the route: plan again from the nearest place on this floor. Walking the
     * wrong way along it: say so. Null when neither (or said too recently).
     */
    private fun checkDeviation(position: Position, target: MapNode, targetFloor: Int?): NavigationAlert? {
        if (targetFloor != position.floor || position.timestampMs == lastMonitoredTimestampMs) return null
        lastMonitoredTimestampMs = position.timestampMs
        val segments = currentRoute.zipWithNext()
            .filter { (a, b) -> nodeFloor[a.id] == position.floor && nodeFloor[b.id] == position.floor }
            .map { (a, b) -> RouteSegment(a.x, a.y, b.x, b.y) }
        val status = routeMonitor.update(position.x, position.y, segments, target.x, target.y)
        if (status == RouteMonitor.Status.ON_ROUTE || position.timestampMs - lastDeviationAlertMs < OFF_ROUTE_REPEAT_MS) return null
        lastDeviationAlertMs = position.timestampMs
        routeMonitor.reset()

        if (status == RouteMonitor.Status.WRONG_WAY) {
            return NavigationAlert(
                id = "wrong-way-${position.timestampMs}",
                priority = AlertPriority.WARNING,
                message = phrases.wrongWay(),
                relatedObstacle = null,
                createdAtMs = position.timestampMs,
                cue = GuidanceCue.WRONG_WAY,
            )
        }
        val nearest = map.floors.firstOrNull { it.floorIndex == position.floor }?.nodes
            ?.minByOrNull { hypot(it.x - position.x, it.y - position.y) } ?: return null
        // Already at the destination's node: nothing to replan, arrival takes it from here.
        val replanned = planRoute(nearest.id)?.takeIf { it.size >= 2 } ?: return null
        currentRoute = replanned
        resetRouteProgress()
        reorient = Reorient.FIRST_LEG
        // Not standing on the new route's first place: the first leg is getting back to it.
        if (hypot(nearest.x - position.x, nearest.y - position.y) > WAYPOINT_ARRIVAL_METERS) routeTargetIndex = 0
        return NavigationAlert(
            id = "off-route-${position.timestampMs}",
            priority = AlertPriority.WARNING,
            message = phrases.offRoute(),
            relatedObstacle = null,
            createdAtMs = position.timestampMs,
            cue = GuidanceCue.REROUTED,
        )
    }

    /**
     * The instruction for where the user is *right now*, worded exactly like
     * the spoken guidance but with a fresh distance — for a "repeat that"
     * request, where replaying the last announcement verbatim would quote a
     * distance that's already stale. Null before the first position arrives
     * (nothing planned yet).
     */
    fun currentInstruction(position: Position): String? = when {
        !hasPlannedRoute -> null
        currentRoute.isEmpty() -> phrases.noRoute()
        arrivalAnnounced -> phrases.arrived()
        currentRoute.size < 2 -> null
        else -> legInstruction(routeTargetIndex, position)
    }

    /**
     * The turn that opens a new leg of a route planned from wherever the walker was (see
     * [Reorient]): toward where the route starts while they aren't there yet, toward the
     * leg's [target] once they are. Null when no such route is pending, or they already
     * roughly face the right way.
     */
    private fun reorientationTurn(position: Position, target: MapNode, routeStart: MapNode, atRouteStart: Boolean): Turn? {
        val toward = when (reorient) {
            Reorient.NONE -> return null
            Reorient.FIRST_LEG, Reorient.TO_START -> if (routeTargetIndex == 0 || !atRouteStart) {
                reorient = Reorient.TO_START
                routeStart
            } else {
                reorient = Reorient.NONE
                target
            }
        }
        if (nodeFloor[toward.id] != position.floor) return null
        val bearing = Math.toDegrees(atan2(toward.x - position.x, toward.y - position.y))
        val delta = ((bearing - position.headingDeg + 540.0) % 360.0) - 180.0
        return if (abs(delta) > REORIENT_DEG) Turn(delta) else null
    }

    /**
     * The turn at the place this leg starts from, which the walker is at or nearly at
     * when the leg is announced: the leg begins there with a turn the instruction for
     * the previous leg ended with. Null when it starts straight on, starts the route,
     * or the three places aren't on one floor.
     */
    private fun turnAtStart(targetIndex: Int): Turn? {
        val target = currentRoute.getOrNull(targetIndex) ?: return null
        val previous = currentRoute.getOrNull(targetIndex - 1) ?: return null
        val before = currentRoute.getOrNull(targetIndex - 2) ?: return null
        val floor = nodeFloor[previous.id]
        if (nodeFloor[before.id] != floor || nodeFloor[target.id] != floor) return null
        return classifyTurn(bearingDeg(before, previous), bearingDeg(previous, target))
    }

    /** What kind of moment the start of this leg is, mirroring [legInstruction]'s wording choices. */
    private fun legCue(targetIndex: Int): GuidanceCue {
        val target = currentRoute[targetIndex]
        // Index 0: back to the route's first place after leaving it (see checkDeviation).
        val previous = currentRoute.getOrNull(targetIndex - 1) ?: return GuidanceCue.CONTINUE
        if (nodeFloor[target.id] != nodeFloor[previous.id]) return GuidanceCue.FLOOR_CHANGE
        // The turn to make right here comes before anything further along the leg.
        when (turnAtStart(targetIndex)?.direction) {
            TurnDirection.RIGHT -> return GuidanceCue.TURN_RIGHT
            TurnDirection.LEFT -> return GuidanceCue.TURN_LEFT
            null -> Unit
        }
        // A tagged hazard right ahead outranks the turn after it: the stairs come first.
        if (target.type in HAZARD_TYPES) return GuidanceCue.HAZARD_AHEAD
        val next = currentRoute.getOrNull(targetIndex + 1) ?: return GuidanceCue.CONTINUE
        if (nodeFloor[next.id] != nodeFloor[target.id]) return GuidanceCue.CONTINUE
        return when (classifyTurn(bearingDeg(previous, target), bearingDeg(target, next))?.direction) {
            TurnDirection.RIGHT -> GuidanceCue.TURN_RIGHT
            TurnDirection.LEFT -> GuidanceCue.TURN_LEFT
            null -> GuidanceCue.CONTINUE
        }
    }

    private fun legInstruction(targetIndex: Int, position: Position): String {
        val target = currentRoute[targetIndex]
        if (targetIndex == 0) {
            // Back to the route's first place, from wherever leaving the route ended up.
            val distance = hypot(target.x - position.x, target.y - position.y)
            return if (target.isUnnamed) phrases.walkAhead(distance, null) else phrases.walkToward(target.name, distance, null)
        }
        val previous = currentRoute[targetIndex - 1]
        val targetFloor = nodeFloor[target.id] ?: 0
        val previousFloor = nodeFloor[previous.id] ?: 0

        if (targetFloor != previousFloor) {
            val method = when (target.type) {
                NodeType.STAIRS -> FloorChangeMethod.STAIRS
                NodeType.ELEVATOR -> FloorChangeMethod.ELEVATOR
                else -> FloorChangeMethod.UNKNOWN
            }
            return phrases.changeFloor(method, goingUp = targetFloor > previousFloor, targetFloor = targetFloor, arrivalPlaceName = target.name)
        }

        // The next leg is announced a couple of metres before the place it starts from, so
        // a leg that starts with a turn says so first: "Sola dön." — the instruction for
        // the previous leg did end with it, but the walker has just heard a new one about
        // the turn after. Only while still near that place: a repeat further along must
        // not send them into another corner.
        val turnNow = turnAtStart(targetIndex)
            ?.takeIf { position.floor == previousFloor && hypot(previous.x - position.x, previous.y - position.y) <= TURN_NOW_RADIUS_METERS }
            ?.let(phrases::turnNow)

        // The camera only ever detects people, never static structure — a tagged hazard
        // is the only source of "there's a door/stairs/ramp right here" awareness in the
        // app, and it only exists where the survey operator explicitly tagged one.
        val hazard = phrases.hazardAhead(target.type)
        val distance = hypot(target.x - position.x, target.y - position.y)
        val instruction = if (targetIndex == currentRoute.lastIndex) {
            phrases.approachDestination(distance)
        } else {
            val next = currentRoute[targetIndex + 1]
            // A turn direction needs both bearings on the same floor as target — if the
            // *next* leg crosses floors, there's nothing meaningful to compare against.
            val turn = if (nodeFloor[next.id] == nodeFloor[target.id]) {
                classifyTurn(bearingDeg(previous, target), bearingDeg(target, next))
            } else {
                null
            }
            // A camera survey's corner nodes have no name worth saying (just an id).
            if (target.isUnnamed) phrases.walkAhead(distance, turn) else phrases.walkToward(target.name, distance, turn)
        }
        // From where the walker is, not where the leg began: halfway along, only the rest matters.
        val wall = wallSides[targetFloor]
            ?.takeIf { position.floor == targetFloor }
            ?.along(position.x, position.y, target.x, target.y)
            ?.let(phrases::followWall)
        return listOfNotNull(turnNow, hazard, instruction, wall).joinToString(" ")
    }

    // Compass bearing (0 = north/+y, 90 = east/+x, clockwise) — matches PdrIntegrator's
    // heading convention so the resulting turn direction lines up with how the phone
    // tracks its own heading.
    private fun bearingDeg(from: MapNode, to: MapNode): Double =
        (Math.toDegrees(atan2(to.x - from.x, to.y - from.y)) + 360.0) % 360.0

    // Null for "straight on": a bend smaller than TURN_ANGLE_THRESHOLD_DEG isn't worth saying.
    private fun classifyTurn(incomingBearingDeg: Double, outgoingBearingDeg: Double): Turn? {
        val turnAngle = ((outgoingBearingDeg - incomingBearingDeg + 540.0) % 360.0) - 180.0
        return if (abs(turnAngle) > TURN_ANGLE_THRESHOLD_DEG) Turn(turnAngle) else null
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
        if ((blocked.fromId to blocked.toId) in blockedEdges) return null // already accounted for

        // Room beside it: walk round it, the route stays (simulated walks: a chair in a wide
        // aisle made the walk 55% longer). Said once, which side.
        val gap = gapBeside(obstacle, position, blocked, nodesById, floorMap.floorIndex)
        if (gap != null && gap.meters >= Passage.PASSABLE_METERS) {
            if (!passHinted.add(obstacle.id)) return null
            return NavigationAlert(
                id = "pass-${obstacle.id}-${obstacle.detectedAtMs}",
                priority = AlertPriority.INFO,
                message = phrases.passObstacle(gap.side),
                relatedObstacle = obstacle,
                createdAtMs = obstacle.detectedAtMs,
            )
        }
        // People move out of the way: a route around someone standing in the corridor sends
        // the walker the long way for nothing (simulated walks: 84% longer). They're warned.
        if (obstacle.type == ObstacleType.PERSON) return null
        blockedEdges.add(blocked.fromId to blocked.toId)

        val nearestNode = nodesById.values.minByOrNull { hypot(it.x - position.x, it.y - position.y) } ?: return null
        // The destination may be on a different floor than this obstacle, so replanning
        // has to consider the whole building again, not just the floor it was found on.
        val rerouted = planRoute(nearestNode.id) ?: return null
        currentRoute = rerouted
        resetRouteProgress()
        reorient = Reorient.FIRST_LEG

        return NavigationAlert(
            id = "reroute-${blocked.fromId}-${blocked.toId}-${UUID.randomUUID()}",
            priority = AlertPriority.INFO,
            message = phrases.rerouted(),
            relatedObstacle = obstacle,
            createdAtMs = obstacle.detectedAtMs,
            cue = GuidanceCue.REROUTED,
        )
    }

    /**
     * Room beside [obstacle] standing on route edge [edge], on the side the walker would
     * pass it; null when unknown (no wall map, or its width wasn't judged).
     */
    private fun gapBeside(obstacle: DetectedObstacle, position: Position, edge: MapEdge, nodesById: Map<String, MapNode>, floorIndex: Int): Passage.Gap? {
        val width = obstacle.widthMeters ?: return null
        val passage = passages[floorIndex] ?: return null
        val from = nodesById[edge.fromId] ?: return null
        val to = nodesById[edge.toId] ?: return null
        val (x, y) = ObstacleEdgeMapper.pointOf(obstacle, position)
        // Along the edge, the way the walker is going (toward the obstacle).
        var dx = to.x - from.x
        var dy = to.y - from.y
        if (dx * (x - position.x) + dy * (y - position.y) < 0) {
            dx = -dx
            dy = -dy
        }
        return passage.widestGap(x, y, dx, dy, width)
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

    private fun obstacleMessage(priority: AlertPriority, obstacle: DetectedObstacle): String =
        phrases.obstacle(priority, obstacle.kind, distanceMeters = obstacle.distanceMeters)

    companion object {
        private val HAZARD_TYPES = setOf(NodeType.STAIRS, NodeType.DOOR, NodeType.RAMP, NodeType.ELEVATOR)

        const val CRITICAL_DISTANCE_METERS = 1.5
        const val WARNING_DISTANCE_METERS = 3.5
        const val MIN_REALERT_INTERVAL_MS = 2_500L

        const val WAYPOINT_ARRIVAL_METERS = 2.0

        // A leg is announced WAYPOINT_ARRIVAL_METERS before the place it starts from; this
        // leaves room for the estimate lagging behind.
        const val TURN_NOW_RADIUS_METERS = 3.0

        /** Facing farther than this from a new route's first leg: say which way to turn. */
        const val REORIENT_DEG = 45.0

        /** ...and this far: turn around. */
        const val TURN_AROUND_DEG = 135.0

        /** Close enough to where a new route starts to set off along it from there. */
        const val REJOIN_METERS = 1.0

        /** Close enough to a new route's first stretch to be on it already. */
        const val ON_FIRST_STRETCH_METERS = 1.5
        const val ARRIVAL_DISTANCE_METERS = 1.5
        const val TURN_ANGLE_THRESHOLD_DEG = 25.0
        const val ROUTE_GUIDANCE_REPEAT_INTERVAL_MS = 8_000L
        const val OFF_ROUTE_REPEAT_MS = 15_000L

        // A rough single flight of stairs — no real building has been measured yet, so
        // this is a starting guess (see BeaconMapBuilder.FLOOR_TRANSITION_COST_METERS,
        // similarly unset from field data) to retune once it has been.
        const val AUTO_FLOOR_CHANGE_STEP_COUNT = 14
    }
}
