package com.appvalence.umay.simulation

import com.appvalence.umay.coremodel.DetectedObstacle
import com.appvalence.umay.coremodel.MapNode
import com.appvalence.umay.coremodel.NavigationAlert
import com.appvalence.umay.coremodel.Position
import com.appvalence.umay.feedback.FeedbackController
import com.appvalence.umay.orchestrator.AStarPathfinder
import com.appvalence.umay.orchestrator.GuidancePhrases
import com.appvalence.umay.orchestrator.NavigationOrchestrator
import com.appvalence.umay.orchestrator.NavigationState
import com.appvalence.umay.perception.ObstacleDetector
import com.appvalence.umay.positioning.engine.PositioningEngine
import com.appvalence.umay.positioning.particle.GraphParticleFilter
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.random.Random

/** How a walk guided by the app ended (see [simulateGuidedWalk]). */
internal data class GuidedWalkResult(
    /** The app said "Hedefe ulaştın". */
    val arrived: Boolean,
    /** How far from the destination the walker really was when it said so. */
    val arrivalErrorMeters: Double?,
    val steps: Int,
    val walkedMeters: Double,
    /** The shortest way there along the corridors. */
    val shortestMeters: Double,
    /** Junctions where the walker left the app's route. */
    val wrongTurns: Int,
    val wrongWayAlerts: Int,
    val offRouteAlerts: Int,
    /** The step after which the app first said which way to go. */
    val firstInstructionStep: Int?,
    val errorsMeters: List<Double>,
    val recording: WalkRecording?,
    /** Camera frames rendered and markers read from them, with [Aids.camera]. */
    val framesRendered: Int = 0,
    val markersRead: Int = 0,
    /** For each obstacle the app warned about: how far the walker really was from it at the first warning. */
    val firstWarningMeters: Map<String, Double> = emptyMap(),
    /** Obstacles the walker reached (within [CONTACT_METERS]) without having been warned. */
    val unwarnedContacts: Int = 0,
    /** New routes around an obstacle. */
    val reroutes: Int = 0,
) {
    /** The app said "arrived" with the walker really there. */
    val reached: Boolean get() = arrived && (arrivalErrorMeters ?: Double.MAX_VALUE) <= REACHED_METERS

    /** Distance walked over the shortest way: 1.0 is perfect. */
    val detour: Double get() = walkedMeters / shortestMeters

    val meanError: Double get() = errorsMeters.average()

    companion object {
        /** Close enough to find the door by hand or cane. */
        const val REACHED_METERS = 3.0

        /** Close enough to walk into. */
        const val CONTACT_METERS = 0.5
    }
}

/**
 * A walk from [startNodeId] to [destinationNodeId] where nobody knows the way
 * but the app: a simulated walker ([InstructionFollower]) hears what the
 * app's own [NavigationOrchestrator] says — in the app's own Turkish
 * sentences — and does what a newcomer would with them; the simulated phone
 * ([VirtualPhone]) senses the result and runs the app's positioning on it.
 * Everything between the two is the app's real code, so this tests the app as
 * a blind user would meet it, in a building that exists only here.
 *
 * [appStartNodeId] is where the walker told the app they are; give a wrong one
 * to see a mistaken start play out. [obstacles] stand in the way, seen by a
 * stand-in for the camera's detector ([VirtualObstacleSensor]).
 */
internal fun simulateGuidedWalk(
    building: VirtualBuilding,
    startNodeId: String,
    destinationNodeId: String,
    seed: Int,
    walker: WalkerModel = WalkerModel(),
    aids: Aids = Aids(beacons = true),
    config: GraphParticleFilter.Config = GraphParticleFilter.Config(),
    phone: PhoneRadio = PhoneRadio(),
    phrases: GuidancePhrases = StringsXmlPhrases.fromAppResources(),
    appStartNodeId: String = startNodeId,
    avoidStairs: Boolean = false,
    record: Boolean = false,
    title: String = "",
    obstacles: List<VirtualObstacle> = emptyList(),
): GuidedWalkResult = runBlocking {
    val random = Random(seed)
    val map = building.surveyedMap(Random(seed + 1_000), withRadio = aids.beacons, phone = phone, placeFingerprints = aids.beacons && aids.placeFingerprints)
    val device = VirtualPhone(building, map, appStartNodeId, walker, aids, config, random, Random(seed + 2_000), phone)
    val destination = building.nodesById.getValue(destinationNodeId)
    val follower = InstructionFollower(building, building.nodesById.getValue(startNodeId), random)
    val shortest = AStarPathfinder.findPathAcrossFloors(map, startNodeId, destinationNodeId)
        ?.zipWithNext { a, b -> hypot(b.x - a.x, b.y - a.y) }?.sum()
        ?: error("no way from $startNodeId to $destinationNodeId in ${building.name}")
    val stepBudget = (shortest / walker.trueStrideMeters * 3).toInt() + 60

    // The app's navigation, fed one position per footstep, exactly as NavigationSession feeds it,
    // and the camera's obstacles whenever it sees any (or stops seeing them).
    val positions = Channel<Position>(Channel.UNLIMITED)
    val detections = Channel<List<DetectedObstacle>>(Channel.UNLIMITED)
    val sensor = VirtualObstacleSensor(building, obstacles, Random(seed + 3_000))
    val orchestrator = NavigationOrchestrator(
        positioning = object : PositioningEngine {
            override fun positionUpdates(): Flow<Position> = positions.receiveAsFlow()
        },
        perception = object : ObstacleDetector {
            override fun obstacleUpdates(): Flow<List<DetectedObstacle>> = if (obstacles.isEmpty()) flowOf(emptyList()) else detections.receiveAsFlow()
        },
        map = map,
        feedback = object : FeedbackController {
            override fun emit(alert: NavigationAlert) = Unit
        },
        startNodeId = appStartNodeId,
        destinationNodeId = destinationNodeId,
        phrases = phrases,
        avoidStairs = avoidStairs,
    )
    val states = Channel<NavigationState>(Channel.UNLIMITED)
    val collector = launch { orchestrator.start().collect { states.send(it) } }
    if (obstacles.isNotEmpty()) detections.send(emptyList())

    val errors = mutableListOf<Double>()
    val frames = mutableListOf<WalkFrame>()
    var steps = 0
    var wrongTurns = 0
    var wrongWay = 0
    var offRoute = 0
    var firstInstruction: Int? = null
    var arrivalError: Double? = null
    var route: List<String>? = null
    var recordedRoute: List<String>? = null
    val firstWarning = LinkedHashMap<String, Double>()
    val contacted = HashSet<String>()
    var unwarnedContacts = 0
    var reroutes = 0
    var obstaclesInView = false
    if (record) frames += device.frame(Point(follower.x, follower.y), listOf("Yola çıkıyor: ${follower.startNote}"))

    while (!follower.arrived && steps < stepBudget) {
        val stride = walker.trueStrideMeters * (1.0 + random.gaussian() * walker.strideNoiseFraction)
        val move = follower.step(stride, route, obstacles.filter { it.blocking })
        if (move.wrongTurn) wrongTurns++
        val notes = move.notes.toMutableList()
        notes += device.step(follower.x, follower.y, follower.headingDeg)
        steps++

        positions.send(device.estimate)
        var state = withTimeoutOrNull(STATE_TIMEOUT_MS) { states.receive() }
            ?: error("the app gave no answer to a footstep (step $steps)")
        val alerts = state.alerts.toMutableList()
        if (obstacles.isNotEmpty()) {
            val seen = sensor.detect(follower.x, follower.y, follower.headingDeg, device.timeMs)
            // "Still nothing in view" after nothing is no news to the app (see NavigationOrchestrator.start).
            if (seen.isNotEmpty() || obstaclesInView) {
                detections.send(seen)
                state = withTimeoutOrNull(STATE_TIMEOUT_MS) { states.receive() } ?: error("the app gave no answer to the camera (step $steps)")
                alerts += state.alerts
            }
            obstaclesInView = seen.isNotEmpty()
            for (alert in alerts) {
                val obstacle = alert.relatedObstacle ?: continue
                val real = obstacles.first { it.id == obstacle.id }
                firstWarning.getOrPut(obstacle.id) { hypot(real.x - follower.x, real.y - follower.y) }
                if (alert.id.startsWith("reroute-")) reroutes++
            }
            for (obstacle in obstacles) {
                if (obstacle.id in contacted || hypot(obstacle.x - follower.x, obstacle.y - follower.y) > GuidedWalkResult.CONTACT_METERS) continue
                contacted += obstacle.id
                if (obstacle.id !in firstWarning) {
                    unwarnedContacts++
                    notes += "Uyarı gelmeden ${obstacle.kind.name.lowercase()} engeline çarptı."
                }
            }
        }
        val speech = alerts.map { it.message }
        for (alert in alerts) {
            when {
                alert.id.startsWith("wrong-way-") -> wrongWay++
                alert.id.startsWith("off-route-") -> offRoute++
                alert.id.startsWith("route-guide-") && firstInstruction == null -> firstInstruction = steps
            }
            follower.hear(alert.message)?.let(notes::add)
        }
        if (follower.arrived) arrivalError = hypot(follower.x - destination.x, follower.y - destination.y)
        route = state.route.map { it.id }
        errors += hypot(device.estimate.x - follower.x, device.estimate.y - follower.y)
        if (record) {
            frames += device.frame(
                truth = Point(follower.x, follower.y),
                notes = notes,
                speech = speech,
                route = route.takeIf { it != recordedRoute },
                targetNodeId = state.route.getOrNull(state.nextRouteIndex)?.id,
            )
            recordedRoute = route
        }
    }
    collector.cancel()

    val result = GuidedWalkResult(
        arrived = follower.arrived,
        arrivalErrorMeters = arrivalError,
        steps = steps,
        walkedMeters = follower.walkedMeters,
        shortestMeters = shortest,
        wrongTurns = wrongTurns,
        wrongWayAlerts = wrongWay,
        offRouteAlerts = offRoute,
        firstInstructionStep = firstInstruction,
        errorsMeters = errors,
        recording = null,
        framesRendered = device.framesRendered,
        markersRead = device.markersRead,
        firstWarningMeters = firstWarning,
        unwarnedContacts = unwarnedContacts,
        reroutes = reroutes,
    )
    if (!record) return@runBlocking result
    result.copy(
        recording = WalkRecording(
            title = title.ifBlank { "${building.nodesById.getValue(startNodeId).name} → ${destination.name}" },
            subtitle = "${building.name}, rastgelelik $seed. Yürüyen binayı bilmiyor; yalnızca uygulamanın söylediklerine göre yürüyor.",
            map = map,
            frames = frames,
            summary = guidedSummary(result),
            beacons = building.beaconMarks(),
            markerNodeIds = aids.markerNodeIds,
        ),
    )
}

private const val STATE_TIMEOUT_MS = 5_000L

private fun guidedSummary(result: GuidedWalkResult): List<SummaryItem> = listOf(
    SummaryItem(
        "Varış",
        when {
            result.reached -> "Vardı (%.1f m)".format(result.arrivalErrorMeters)
            result.arrived -> "Yanlış yerde: %.1f m".format(result.arrivalErrorMeters)
            else -> "Varamadı"
        },
        bad = !result.reached,
    ),
    SummaryItem("Adım", result.steps.toString()),
    SummaryItem("Yürünen / en kısa", "%.0f / %.0f m".format(result.walkedMeters, result.shortestMeters), bad = result.detour > 1.5),
    SummaryItem("Yanlış dönüş", result.wrongTurns.toString(), bad = result.wrongTurns > 0),
    SummaryItem("Ters yön uyarısı", result.wrongWayAlerts.toString()),
    SummaryItem("Rotadan çıkma", result.offRouteAlerts.toString()),
    SummaryItem("Ortalama konum hatası", "%.2f m".format(result.meanError)),
    SummaryItem("İlk yönerge", result.firstInstructionStep?.let { "$it. adımdan sonra" } ?: "Hiç"),
)

/**
 * A blind walker new to the building, going only by what the app says:
 * - keeps to the corridor, following it round a bend;
 * - turns where told ("sonra sağa dön", "saat 2 yönüne dön") at the first
 *   junction that has such a turn, once they've walked about half the distance
 *   the instruction gave (nobody turns into the very next opening when told
 *   "about 10 metres, then right");
 * - turns round when told "geri dön", and stops at "Hedefe ulaştın";
 * - at a junction nobody explained, or at the start with several ways out,
 *   guesses; at a dead end, turns back.
 */
private class InstructionFollower(building: VirtualBuilding, start: MapNode, private val random: Random) {
    private val neighbors: Map<String, List<MapNode>> = building.edges
        .flatMap { (a, b) -> listOf(a to b, b to a) }
        .groupBy({ it.first }, { building.nodesById.getValue(it.second) })

    private var from: MapNode = start
    private var to: MapNode
    private var along = 0.0
    private var plannedTurnDeg: Double? = null
    private var turnNotBeforeMeters = 0.0

    // "Sola dön." opening an instruction: turn at the very next junction, and only then
    // start counting the distance to the turn the rest of the sentence ends with.
    private var turnNowDeg: Double? = null
    private var plannedTurnAfterMeters = 0.0
    private var turnAround = false

    var walkedMeters = 0.0
        private set
    var arrived = false
        private set
    val startNote: String

    init {
        val ways = neighbors[start.id].orEmpty()
        require(ways.isNotEmpty()) { "${start.id} has no corridor" }
        to = if (ways.size == 1) ways.first() else ways[random.nextInt(ways.size)]
        startNote = if (ways.size == 1) "tek yol var" else "${ways.size} yol var, birini seçti (${to.name})"
    }

    private val length: Double get() = hypot(to.x - from.x, to.y - from.y)
    val x: Double get() = from.x + (to.x - from.x) * along / length
    val y: Double get() = from.y + (to.y - from.y) * along / length
    val headingDeg: Double get() = bearing(from, to)

    class Move(val notes: List<String>, val wrongTurn: Boolean)

    /**
     * One stride along the corridors; [route] is the app's current route, to tell a wrong
     * turn. A [blockers] obstacle filling the corridor just ahead is felt with the cane,
     * and the walker turns back.
     */
    fun step(stride: Double, route: List<String>?, blockers: List<VirtualObstacle> = emptyList()): Move {
        val notes = mutableListOf<String>()
        var wrongTurn = false
        if (!turnAround && blockers.any { blocksAhead(it, stride) }) {
            turnAround = true
            plannedTurnDeg = null
            turnNowDeg = null
            notes += "Önü kapalı; bastonla engeli buldu, geri döndü."
        }
        if (turnAround) {
            val back = from
            from = to
            to = back
            along = length - along
            turnAround = false
            notes += "Geri döndü."
        }
        var remaining = stride
        while (remaining > 0) {
            if (along + remaining < length) {
                along += remaining
                walkedMeters += remaining
                break
            }
            val leftover = along + remaining - length
            walkedMeters += length - along
            val junction = to
            val (next, note) = chooseAt(junction, cameFrom = from)
            note?.let(notes::add)
            if (route != null) {
                val index = route.indexOf(junction.id)
                val expected = route.getOrNull(index + 1)
                if (index >= 0 && expected != null && expected != next.id) wrongTurn = true
            }
            from = junction
            to = next
            along = 0.0
            remaining = leftover
        }
        return Move(notes, wrongTurn)
    }

    private fun blocksAhead(obstacle: VirtualObstacle, stride: Double): Boolean {
        val ex = (to.x - from.x) / length
        val ey = (to.y - from.y) / length
        val ahead = (obstacle.x - from.x) * ex + (obstacle.y - from.y) * ey - along
        val aside = abs((obstacle.x - from.x) * -ey + (obstacle.y - from.y) * ex)
        return aside < BLOCKING_HALF_WIDTH_METERS && ahead > 0 && ahead < stride + CANE_REACH_METERS
    }

    private fun chooseAt(node: MapNode, cameFrom: MapNode): Pair<MapNode, String?> {
        val ways = neighbors[node.id].orEmpty().filter { it.id != cameFrom.id }
        if (ways.isEmpty()) return cameFrom to "${node.name}: çıkmaz, geri döndü."
        val heading = bearing(cameFrom, node)
        fun relative(way: MapNode) = normalize(bearing(node, way) - heading)

        turnNowDeg?.let { now ->
            val best = ways.minBy { abs(normalize(relative(it) - now)) }
            if (abs(normalize(relative(best) - now)) <= TURN_MATCH_DEG) {
                turnNowDeg = null
                turnNotBeforeMeters = walkedMeters + plannedTurnAfterMeters
                return best to "${node.name}: söylendiği gibi ${side(relative(best))} döndü."
            }
        }
        val planned = plannedTurnDeg
        if (turnNowDeg == null && planned != null && walkedMeters >= turnNotBeforeMeters) {
            val best = ways.minBy { abs(normalize(relative(it) - planned)) }
            if (abs(normalize(relative(best) - planned)) <= TURN_MATCH_DEG) {
                plannedTurnDeg = null
                return best to "${node.name}: söylendiği gibi ${side(relative(best))} döndü."
            }
        }
        ways.filter { abs(relative(it)) <= STRAIGHT_DEG }.minByOrNull { abs(relative(it)) }?.let { return it to null }
        if (ways.size == 1) return ways.first() to null // the corridor bends; follow it
        val guess = ways[random.nextInt(ways.size)]
        return guess to "${node.name}: kavşakta yönerge yok, tahminle ${side(relative(guess))} gitti."
    }

    /** Takes in one sentence the app said; returns what the walker made of it, when it changed anything. */
    fun hear(text: String): String? {
        if (ARRIVED in text) {
            arrived = true
            return "Vardığını duydu, durdu."
        }
        var understood: String? = null
        if (text.contains(GO_BACK, ignoreCase = true)) {
            turnAround = true
            plannedTurnDeg = null
            turnNowDeg = null
            // "Ters yöne gidiyorsun..., geri dön." is all there is; "Geri dön. Lobi yönünde
            // ... ilerle" goes on to the new route's first leg.
            if (WALK !in text) return "Geri dönmesi söylendi."
            understood = "Geri dönmesi söylendi."
        }
        if (OFF_ROUTE in text) {
            plannedTurnDeg = null
            turnNowDeg = null
            return null
        }
        if (WALK !in text && APPROACH !in text) return understood
        turnNowDeg = when {
            text.startsWith(TURN_LEFT_NOW) -> -90.0
            text.startsWith(TURN_RIGHT_NOW) -> 90.0
            else -> CLOCK_NOW.find(text)?.groupValues?.get(1)?.toInt()?.let { hour -> normalize(hour % 12 * 30.0) }
        }
        val turn = when {
            RIGHT in text -> 90.0
            LEFT in text -> -90.0
            else -> CLOCK.find(text)?.groupValues?.get(1)?.toInt()?.let { hour -> normalize(hour % 12 * 30.0) }
        }
        plannedTurnDeg = turn
        if (turn != null) {
            val meters = DISTANCE.find(text)?.groupValues?.get(1)?.toDouble() ?: 0.0
            plannedTurnAfterMeters = meters * HALF
            turnNotBeforeMeters = walkedMeters + plannedTurnAfterMeters
        } else {
            plannedTurnAfterMeters = 0.0
        }
        return understood
    }

    private fun side(relativeDeg: Double) = when {
        abs(relativeDeg) <= STRAIGHT_DEG -> "düz"
        abs(relativeDeg) >= 150 -> "geriye"
        relativeDeg > 0 -> "sağa"
        else -> "sola"
    }

    companion object {
        private const val ARRIVED = "Hedefe ulaştın"
        private const val GO_BACK = "geri dön"
        private const val OFF_ROUTE = "Rotadan çıktın"
        private const val WALK = "ilerle"
        private const val APPROACH = "Hedefe yaklaşık"
        private const val RIGHT = "sonra sağa dön"
        private const val LEFT = "sonra sola dön"
        private val CLOCK = Regex("""sonra saat (\d+) yönüne dön""")
        private val CLOCK_NOW = Regex("""^Saat (\d+) yönüne dön\.""")
        private const val TURN_LEFT_NOW = "Sola dön."
        private const val TURN_RIGHT_NOW = "Sağa dön."
        private val DISTANCE = Regex("""[Yy]aklaşık (\d+) metre""")
        private const val HALF = 0.5
        private const val BLOCKING_HALF_WIDTH_METERS = 0.6
        private const val CANE_REACH_METERS = 0.8
        private const val TURN_MATCH_DEG = 50.0
        private const val STRAIGHT_DEG = 35.0
    }
}

private fun bearing(from: MapNode, to: MapNode): Double = Math.toDegrees(atan2(to.x - from.x, to.y - from.y))

/** Into -180..180. */
private fun normalize(deg: Double): Double = deg - 360.0 * floor((deg + 180.0) / 360.0)
