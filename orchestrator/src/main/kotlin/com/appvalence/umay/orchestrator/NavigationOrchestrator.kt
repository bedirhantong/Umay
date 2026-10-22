package com.appvalence.umay.orchestrator

import com.appvalence.umay.coremodel.AlertPriority
import com.appvalence.umay.coremodel.BuildingMap
import com.appvalence.umay.coremodel.FloorMap
import com.appvalence.umay.coremodel.GuidanceCue
import com.appvalence.umay.coremodel.MapNode
import com.appvalence.umay.coremodel.NavigationAlert
import com.appvalence.umay.coremodel.NodeType
import com.appvalence.umay.coremodel.isUnnamed
import com.appvalence.umay.coremodel.Position
import com.appvalence.umay.feedback.FeedbackController
import com.appvalence.umay.positioning.engine.PositioningEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import java.util.UUID
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Combines the latest position against the static building map into a single
 * [NavigationState] (FR-10/FR-11):
 *
 * - Plans (once) an A* route from [startNodeId] to [destinationNodeId] across
 *   the *whole* building via [AStarPathfinder.findPathAcrossFloors] — the
 *   route can cross floors via stairs/elevators ([BuildingMap.floorTransitions]).
 * - Speaks turn-by-turn route guidance as the user walks: a leg instruction
 *   ("X metre ilerle, sonra sağa dön") each time they reach a route node,
 *   repeated periodically while still on the same leg so a user who can't
 *   see the screen isn't left with silence, and a one-time arrival
 *   announcement. This is the actual point of the app — a blind user cannot
 *   read the on-screen route.
 * - When the route's current leg crosses floors, progress can't be tracked by
 *   proximity (two floors' coordinates come from independent PDR origins) —
 *   [NavigationState.pendingFloorChange] surfaces this so the UI can offer a
 *   manual "kat değiştirdim" confirmation (there's no barometer-based
 *   detection anywhere in this app); confirming it resets live tracking onto
 *   the new floor, which is what actually lets progress continue.
 * - When the walker leaves the route (see [RouteMonitor]) it plans again from
 *   the nearest place on their floor and says so; walking the wrong way along
 *   the route is said too.
 *
 * Alerts are forwarded to [feedback] as they're produced. Their wording comes
 * from [phrases], so guidance can be spoken in any language.
 */
class NavigationOrchestrator(
    private val positioning: PositioningEngine,
    private val map: BuildingMap,
    private val feedback: FeedbackController,
    private val startNodeId: String,
    private val destinationNodeId: String,
    private val phrases: GuidancePhrases,
    // The walker asked for routes without stairs (Settings): lifts and ramps only,
    // falling back to stairs — said out loud — when the building offers nothing else.
    private val avoidStairs: Boolean = false,
) {
    // Decoded once per floor that has a wall map (see WallSide).
    private val wallSides: Map<Int, WallSide> = map.floors.mapNotNull { floor -> floor.occupancy?.let { floor.floorIndex to WallSide(it) } }.toMap()

    private val nodeFloor: Map<String, Int> =
        map.floors.flatMap { floor -> floor.nodes.map { it.id to floor.floorIndex } }.toMap()

    private var hasPlannedRoute = false
    private var currentRoute: List<MapNode> = emptyList()

    // Route guidance progress: the index into currentRoute of the node the
    // user is currently walking toward. Reset whenever the route is (re)planned.
    private var routeTargetIndex = 1
    private var lastAnnouncedLegIndex = -1
    private var lastLegAnnouncedAtMs = 0L
    private var arrivalAnnounced = false
    private var positioningLimitedAnnounced = false
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

    // A route planned from wherever the walker is (after leaving the route) may start
    // behind them or to one side, which its first leg's wording can't say. Simulated walks
    // found that without it, "toward the lobby, 10 m" sent the walker straight on, the
    // wrong way. So: first back to where the
    // new route starts ("Geri dön."), then, there, which way it goes — both from the
    // heading they're actually walking in, known by then.
    private enum class Reorient { NONE, FIRST_LEG, TO_START }

    private var reorient = Reorient.NONE

    fun start(): Flow<NavigationState> =
        positioning.positionUpdates().map { position ->
            ensureRoute()
            NavigationState(
                position = position,
                alerts = deriveAlerts(position),
                route = currentRoute,
                pendingFloorChange = pendingFloorChange,
                nextRouteIndex = routeTargetIndex,
                upcomingFloorChange = upcomingFloorChange(),
            )
        }.onEach { state ->
            state.alerts.forEach(feedback::emit)
        }

    private fun floorMapFor(floorIndex: Int): FloorMap? = map.floors.firstOrNull { it.floorIndex == floorIndex }

    private fun ensureRoute() {
        // The route spans the whole building, not one floor, so there's nothing to
        // re-key this on — plan once, replan only after the walker leaves it.
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
            AStarPathfinder.findPathAcrossFloors(map, fromNodeId, destinationNodeId, avoidStairs = true)?.let { return it }
        }
        val route = AStarPathfinder.findPathAcrossFloors(map, fromNodeId, destinationNodeId) ?: return null
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

    private fun deriveAlerts(position: Position): List<NavigationAlert> {
        val alerts = mutableListOf<NavigationAlert>()
        stairsFallbackNotice(position)?.let { alerts += it }
        deriveRouteGuidance(position)?.let { alerts += it }
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
                    createdAtMs = position.timestampMs,
                cue = GuidanceCue.NO_ROUTE,
            )
        }
        if (floorMapFor(position.floor)?.reconstruction != null && !canGuide(position)) {
            pendingFloorChange = null
            if (positioningLimitedAnnounced) return null
            positioningLimitedAnnounced = true
            return NavigationAlert(id = "route-guide-uncertain-${position.timestampMs}", priority = AlertPriority.WARNING,
                message = phrases.positionUncertain(), createdAtMs = position.timestampMs, isGuidance = true)
        }
        if (positioningLimitedAnnounced) { positioningLimitedAnnounced = false; lastAnnouncedLegIndex = -1 }
        if (currentRoute.size < 2 || arrivalAnnounced) return null

        // Already on a floor the route only reaches further on: the floor change was
        // confirmed (the barometer, the walker's button) before guidance caught up with the
        // walker — the estimate lagging behind someone waiting at a lift, say. Pick the
        // route up where it arrives on this floor rather than sending them back down.
        if (nodeFloor[currentRoute[routeTargetIndex].id] != position.floor) {
            // The route's next crossing from here on; already being made if it's the target.
            val crossing = (maxOf(routeTargetIndex, 1)..currentRoute.lastIndex).firstOrNull { i ->
                nodeFloor[currentRoute[i].id] != nodeFloor[currentRoute[i - 1].id]
            }
            if (crossing != null && crossing > routeTargetIndex && nodeFloor[currentRoute[crossing].id] == position.floor) {
                routeTargetIndex = crossing
                lastAnnouncedLegIndex = -1
                routeMonitor.reset()
            }
        }

        // Advance toward the next route node each time the user gets close enough to
        // the one they were walking toward, so guidance always describes the leg
        // that's actually ahead of them, not the one they already finished. Proximity
        // is only meaningful on the same floor — two floors' (x, y) come from
        // independent PDR origins, so a target on a different floor blocks advancement
        // until the user confirms the crossing (see pendingFloorChange below).
        while (routeTargetIndex < currentRoute.lastIndex) {
            val target = currentRoute[routeTargetIndex]
            if (nodeFloor[target.id] != position.floor) break
            // The landing a floor change arrives at is reached by being on its floor: the
            // confirmation can come a few steps after the walker has moved off it.
            val crossingArrival = routeTargetIndex > 0 && nodeFloor[currentRoute[routeTargetIndex - 1].id] != nodeFloor[target.id]
            if (!crossingArrival && hypot(target.x - position.x, target.y - position.y) > WAYPOINT_ARRIVAL_METERS) break
            routeTargetIndex++
            lastAnnouncedLegIndex = -1 // force an immediate announcement of the new leg
            routeMonitor.reset()
        }

        val target = currentRoute[routeTargetIndex]
        val targetFloor = nodeFloor[target.id]
        pendingFloorChange = if (targetFloor != null && targetFloor != position.floor) {
            // Only count a step once per distinct position sample, so a position
            // re-emitted unchanged can't inflate the count.
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
                            createdAtMs = position.timestampMs,
                    cue = GuidanceCue.ARRIVED,
                    isGuidance = true,
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
            // Stairs are a real fall risk underfoot, so that leg gets more urgency
            // than ordinary INFO guidance.
            priority = if (target.type == NodeType.STAIRS) AlertPriority.WARNING else AlertPriority.INFO,
            message = listOfNotNull(
                reorientTurn?.let { if (turnAround) phrases.turnAround() else phrases.turnNow(it) },
                legInstruction(routeTargetIndex, position),
            ).joinToString(" "),
            createdAtMs = now,
            // Only a new leg gets a cue; the periodic repeat of the same leg stays silent
            // to the touch (see GuidanceCue).
            cue = when {
                !isNewLeg -> GuidanceCue.NONE
                turnAround -> GuidanceCue.WRONG_WAY
                reorientTurn != null -> if (reorientTurn.direction == TurnDirection.RIGHT) GuidanceCue.TURN_RIGHT else GuidanceCue.TURN_LEFT
                else -> legCue(routeTargetIndex)
            },
            isGuidance = true,
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
                    createdAtMs = position.timestampMs,
                cue = GuidanceCue.WRONG_WAY,
                isGuidance = true,
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
        floorMapFor(position.floor)?.reconstruction != null && !canGuide(position) -> phrases.positionUncertain()
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

    /** The crossing right after the leg being walked, as a hint for the barometer; null if that leg doesn't end in one. */
    private fun upcomingFloorChange(): FloorChangeHint? {
        if (pendingFloorChange != null || currentRoute.isEmpty()) return null
        val target = currentRoute.getOrNull(routeTargetIndex) ?: return null
        val arrival = currentRoute.getOrNull(routeTargetIndex + 1) ?: return null
        val arrivalFloor = nodeFloor[arrival.id] ?: return null
        if (arrivalFloor == nodeFloor[target.id]) return null
        return FloorChangeHint(arrival.id, arrival.name, arrivalFloor)
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
            return phrases.changeFloor(floorChangeMethod(target), goingUp = targetFloor > previousFloor, targetFloor = targetFloor, arrivalPlaceName = target.name)
        }

        // The next leg is announced a couple of metres before the place it starts from, so
        // a leg that starts with a turn says so first: "Sola dön." — the instruction for
        // the previous leg did end with it, but the walker has just heard a new one about
        // the turn after. Only while still near that place: a repeat further along must
        // not send them into another corner.
        val turnNow = turnAtStart(targetIndex)
            ?.takeIf { position.floor == previousFloor && hypot(previous.x - position.x, previous.y - position.y) <= TURN_NOW_RADIUS_METERS }
            ?.let(phrases::turnNow)

        // A tagged hazard is the only source of "there's a door/stairs/ramp right here"
        // awareness in the app, and it only exists where the survey operator tagged one.
        val hazard = phrases.hazardAhead(target.type)
        val distance = hypot(target.x - position.x, target.y - position.y)
        // A leg ending at a staircase or lift says what's done there, so the walker knows on
        // arriving, whether or not guidance has caught up with them by then.
        val crossingAfter = currentRoute.getOrNull(targetIndex + 1)
            ?.takeIf { nodeFloor[it.id] != targetFloor }
            ?.let { next ->
                val nextFloor = nodeFloor[next.id] ?: targetFloor
                phrases.thenChangeFloor(floorChangeMethod(next), goingUp = nextFloor > targetFloor, targetFloor = nextFloor)
            }
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
        return listOfNotNull(turnNow, hazard, instruction, crossingAfter, wall).joinToString(" ")
    }

    /** How a crossing is made, from how its arrival node was tagged during the survey. */
    private fun floorChangeMethod(arrival: MapNode): FloorChangeMethod = when (arrival.type) {
        NodeType.STAIRS -> FloorChangeMethod.STAIRS
        NodeType.ELEVATOR -> FloorChangeMethod.ELEVATOR
        else -> FloorChangeMethod.UNKNOWN
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

    companion object {
        // Admission policy, not a measured accuracy claim. Reconstructed-map guidance
        // requires uncertainty no larger than the existing arrival acceptance radius.
        fun canGuide(position: Position): Boolean = position.x.isFinite() && position.y.isFinite() &&
            position.headingDeg.isFinite() && position.accuracyMeters.isFinite() &&
            position.accuracyMeters in 0.0..ARRIVAL_DISTANCE_METERS

        private val HAZARD_TYPES = setOf(NodeType.STAIRS, NodeType.DOOR, NodeType.RAMP, NodeType.ELEVATOR)

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
