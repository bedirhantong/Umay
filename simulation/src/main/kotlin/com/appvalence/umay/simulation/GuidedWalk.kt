package com.appvalence.umay.simulation

import com.appvalence.umay.coremodel.DetectedObstacle
import com.appvalence.umay.coremodel.FloorChangePhase
import com.appvalence.umay.coremodel.MapNode
import com.appvalence.umay.coremodel.NavigationAlert
import com.appvalence.umay.coremodel.NodeType
import com.appvalence.umay.coremodel.Position
import com.appvalence.umay.feedback.FeedbackController
import com.appvalence.umay.orchestrator.AStarPathfinder
import com.appvalence.umay.orchestrator.FloorChangeConfirmation
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
    /** Each floor change the walker made, by staircase or lift (the type of the place left). */
    val crossings: List<NodeType> = emptyList(),
    /** How each floor change was confirmed to positioning. */
    val floorConfirmations: List<FloorChangePhase> = emptyList(),
    /** Footsteps the app had the walker on another floor. */
    val wrongFloorSteps: Int = 0,
) {
    /** The app said "arrived" with the walker really there. */
    val reached: Boolean get() = arrived && (arrivalErrorMeters ?: Double.MAX_VALUE) <= REACHED_METERS

    /** Distance walked over the shortest way: 1.0 is perfect. */
    val detour: Double get() = walkedMeters / shortestMeters

    val meanError: Double get() = if (errorsMeters.isEmpty()) Double.NaN else errorsMeters.average()

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
 * stand-in for the camera's detector ([VirtualObstacleSensor]). [survey] is
 * the map the app guides by; by default the building's plan itself, or one
 * a [VirtualSurveyor] made, drifts and all.
 *
 * Between floors the walker climbs the stairs step by step or stands in the
 * lift, and the app learns of it the way it does on a phone
 * (FloorChangeConfirmation): from the [barometer], else from footsteps on
 * stairs, else from the walker pressing "Kat değiştirdim" on leaving the lift.
 *
 * The app's sentences take time to say ([speechCharsPerSecond], see
 * [SpokenQueue]); the walker acts on each once it's been said, and walks on
 * meanwhile, drifting a little off the corridor's centre line ([WalkerModel]).
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
    survey: SurveyedMap? = null,
    barometer: Boolean = true,
    speechCharsPerSecond: Double = SpokenQueue.TTS_CHARS_PER_SECOND,
): GuidedWalkResult = runBlocking {
    val random = Random(seed)
    // The walker's own quirks, apart from everything else's randomness.
    val quirks = Random(seed + 5_000)
    val surveyed = survey ?: SurveyedMap.ideal(
        building.surveyedMap(Random(seed + 1_000), withRadio = aids.beacons, phone = phone, placeFingerprints = aids.beacons && aids.placeFingerprints),
    )
    val map = surveyed.map
    fun mapIdOf(id: String) = surveyed.mapId(id) ?: error("the map has no place for $id")
    val device = VirtualPhone(building, surveyed, mapIdOf(appStartNodeId), walker, aids, config, random, Random(seed + 2_000), phone, Random(seed + 4_000))
    val destination = building.nodesById.getValue(destinationNodeId)
    val distanceSense = (1.0 + quirks.gaussian() * walker.distanceJudgmentSd).coerceIn(MIN_DISTANCE_SENSE, MAX_DISTANCE_SENSE)
    val follower = InstructionFollower(building, building.nodesById.getValue(startNodeId), random, distanceSense)
    val voice = SpokenQueue(speechCharsPerSecond)
    var veer = 0.0
    var listenedMs = 0L
    val shortest = AStarPathfinder.findPathAcrossFloors(building.surveyedMap(Random(0), withRadio = false), startNodeId, destinationNodeId)
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
        startNodeId = mapIdOf(appStartNodeId),
        destinationNodeId = mapIdOf(destinationNodeId),
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
    val floorChanges = FloorChangeConfirmation(hasBarometer = barometer)
    val crossings = ArrayList<NodeType>()
    val confirmations = ArrayList<FloorChangePhase>()
    var wrongFloorSteps = 0
    var altitude = building.floorOf(startNodeId) * building.floorHeightMeters
    var lastState: NavigationState? = null
    if (record) frames += device.frame(Point(follower.x, follower.y), listOf("Yola çıkıyor: ${follower.startNote}"), truthFloor = follower.floor)
    // Where the walker really is: beside the corridor's centre line by [veer].
    fun truth(): Point = follower.beside(veer)

    // How far the walker had walked at each footstep: a sentence's distances count from when its saying began.
    val walkedAt = ArrayList<Pair<Long, Double>>().apply { add(device.timeMs to 0.0) }
    fun hearSaid(byMs: Long, notes: MutableList<String>) {
        for (sentence in voice.heard(byMs)) {
            val walkedThen = walkedAt.lastOrNull { it.first <= sentence.startedAtMs }?.second ?: 0.0
            follower.hear(sentence.text, sentence.opensAlert, follower.walkedMeters - walkedThen)?.let(notes::add)
        }
    }

    fun apply(change: FloorChangeConfirmation.Confirmed?, notes: MutableList<String>) {
        change ?: return
        val mapId = surveyed.mapId(change.targetNodeId) ?: change.targetNodeId
        if (device.estimate.floor == change.targetFloor) return // already applied; the hint lingers a step
        device.changeFloor(mapId)
        confirmations += change.phase
        notes += "Kat değişimi onaylandı (${change.phase.name.lowercase()})."
    }

    // One footstep (or, standing in a lift, none) and what the app makes of it.
    suspend fun sense(stepped: Boolean, x: Double, y: Double, headingDeg: Double, floor: Int, notes: MutableList<String>): MutableList<NavigationAlert> {
        val alerts = mutableListOf<NavigationAlert>()
        if (stepped) {
            notes += device.step(x, y, headingDeg, floor)
            positions.send(device.estimate)
            val state = withTimeoutOrNull(STATE_TIMEOUT_MS) { states.receive() } ?: error("the app gave no answer to a footstep (step $steps)")
            lastState = state
            alerts += state.alerts
            apply(floorChanges.onState(state), notes)
        }
        if (barometer) apply(floorChanges.onPressure(device.pressureHpa(altitude), device.timeMs), notes)
        return alerts
    }

    while (!follower.arrived && steps < stepBudget) {
        // Whatever is said by mid-stride is acted on in it.
        val heardNotes = mutableListOf<String>()
        hearSaid(device.timeMs + walker.stepIntervalMs / 2, heardNotes)
        if (follower.arrived) {
            val stop = truth()
            arrivalError = if (follower.floor != building.floorOf(destinationNodeId)) Double.MAX_VALUE else hypot(stop.x - destination.x, stop.y - destination.y)
            break
        }
        val stride = walker.trueStrideMeters * (1.0 + random.gaussian() * walker.strideNoiseFraction)
        // Setting off, or at a wall, a door or the lift, with the phone still talking: stand and listen.
        val listen = (!follower.heardInstruction || follower.atDeadEnd(stride)) && voice.isSpeaking(device.timeMs)
        if (listen && listenedMs < MAX_LISTEN_MS) {
            device.standStill(walker.stepIntervalMs)
            listenedMs += walker.stepIntervalMs
            if (barometer) apply(floorChanges.onPressure(device.pressureHpa(altitude), device.timeMs), heardNotes)
            hearSaid(device.timeMs, heardNotes)
            if (record && heardNotes.isNotEmpty()) frames += device.frame(truth(), heardNotes, truthFloor = follower.floor)
            continue
        }
        listenedMs = 0L
        val move = follower.step(stride, route, obstacles.filter { it.blocking && it.floor == follower.floor })
        if (move.wrongTurn) wrongTurns++
        val notes = (heardNotes + move.notes).toMutableList()
        val crossing = move.crossing
        val alerts: MutableList<NavigationAlert>
        if (crossing == null) {
            steps++
            veer = (veer * VEER_PULL + quirks.gaussian() * walker.veerStepMeters).coerceIn(-walker.maxVeerMeters, walker.maxVeerMeters)
            val here = truth()
            alerts = sense(true, here.x, here.y, follower.headingDeg, follower.floor, notes)
        } else {
            // Up or down the stairs step by step, or standing in the lift; then out at the other floor.
            crossings += crossing.from.type
            val fromAltitude = altitude
            val toAltitude = building.floorOf(crossing.to.id) * building.floorHeightMeters
            alerts = mutableListOf()
            if (crossing.from.type == NodeType.ELEVATOR) {
                notes += "Asansöre bindi."
                repeat(LIFT_SECONDS * 2) { half ->
                    device.standStill(500)
                    val moving = (half - LIFT_DOORS_HALF_SECONDS).coerceIn(0, LIFT_RIDE_HALF_SECONDS).toDouble() / LIFT_RIDE_HALF_SECONDS
                    altitude = fromAltitude + (toAltitude - fromAltitude) * moving
                    alerts += sense(false, crossing.from.x, crossing.from.y, follower.headingDeg, building.floorOf(crossing.from.id), notes)
                }
            } else {
                notes += "Merdivene çıktı."
                for (k in 1..STAIR_STEPS) {
                    steps++
                    altitude = fromAltitude + (toAltitude - fromAltitude) * k / STAIR_STEPS
                    val floorNow = if (k <= STAIR_STEPS / 2) building.floorOf(crossing.from.id) else building.floorOf(crossing.to.id)
                    alerts += sense(true, crossing.from.x, crossing.from.y, follower.headingDeg, floorNow, notes)
                }
            }
            altitude = toAltitude
            follower.arriveAt(crossing.to)?.let(notes::add)
            // Off the lift without a barometer, the walker presses "Kat değiştirdim".
            if (!barometer && crossing.from.type == NodeType.ELEVATOR) apply(floorChanges.manual(), notes)
        }
        val state = lastState ?: continue
        val here = truth()
        if (obstacles.isNotEmpty() && crossing == null) {
            val seen = sensor.detect(here.x, here.y, follower.headingDeg, device.timeMs, follower.floor)
            // "Still nothing in view" after nothing is no news to the app (see NavigationOrchestrator.start).
            if (seen.isNotEmpty() || obstaclesInView) {
                detections.send(seen)
                val seenState = withTimeoutOrNull(STATE_TIMEOUT_MS) { states.receive() } ?: error("the app gave no answer to the camera (step $steps)")
                lastState = seenState
                alerts += seenState.alerts
            }
            obstaclesInView = seen.isNotEmpty()
            for (alert in alerts) {
                val obstacle = alert.relatedObstacle ?: continue
                val real = obstacles.first { it.id == obstacle.id }
                firstWarning.getOrPut(obstacle.id) { hypot(real.x - here.x, real.y - here.y) }
                if (alert.id.startsWith("reroute-")) reroutes++
            }
            for (obstacle in obstacles) {
                if (obstacle.floor != follower.floor) continue
                if (obstacle.id in contacted || hypot(obstacle.x - here.x, obstacle.y - here.y) > GuidedWalkResult.CONTACT_METERS) continue
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
            voice.say(alert, device.timeMs)
        }
        walkedAt += device.timeMs to follower.walkedMeters
        hearSaid(device.timeMs, notes)
        if (follower.arrived) {
            val stop = truth()
            arrivalError = if (follower.floor != building.floorOf(destinationNodeId)) Double.MAX_VALUE else hypot(stop.x - destination.x, stop.y - destination.y)
        }
        val current = lastState ?: state
        val mapRoute = current.route.map { it.id }
        // The walker knows real places; a map node the survey left unnamed is no place of theirs.
        route = mapRoute.map { surveyed.buildingId(it) ?: it }
        val error = device.errorTo(truth(), follower.floor)
        if (error == null) wrongFloorSteps++ else errors += error
        if (record) {
            frames += device.frame(
                truth = truth(),
                notes = notes,
                speech = speech,
                route = mapRoute.takeIf { it != recordedRoute },
                targetNodeId = current.route.getOrNull(current.nextRouteIndex)?.id,
                truthFloor = follower.floor,
            )
            recordedRoute = mapRoute
        }
    }
    collector.cancel()
    // Told "arrived" while standing still to listen.
    if (follower.arrived && arrivalError == null) {
        val stop = truth()
        arrivalError = if (follower.floor != building.floorOf(destinationNodeId)) Double.MAX_VALUE else hypot(stop.x - destination.x, stop.y - destination.y)
    }

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
        crossings = crossings,
        floorConfirmations = confirmations,
        wrongFloorSteps = wrongFloorSteps,
    )
    if (!record) return@runBlocking result
    result.copy(
        recording = WalkRecording(
            title = title.ifBlank { "${building.nodesById.getValue(startNodeId).name} → ${destination.name}" },
            subtitle = "${building.name}, rastgelelik $seed. Yürüyen binayı bilmiyor; yalnızca uygulamanın söylediklerine göre yürüyor.",
            map = map,
            frames = frames,
            summary = guidedSummary(result),
            beacons = building.beaconMarks().map { mark -> surveyed.toMap(Point(mark.x, mark.y)).let { mark.copy(x = it.x, y = it.y) } },
            markerNodeIds = aids.markerNodeIds.mapNotNull(surveyed::mapId).toSet(),
        ),
    )
}

private const val STATE_TIMEOUT_MS = 5_000L

// Each step the walker's drift off the centre line keeps this much of itself: the cane finds the wall, they correct.
private const val VEER_PULL = 0.85

// Standing at a dead end to hear the phone out, but not for ever.
private const val MAX_LISTEN_MS = 10_000L

// However poor a walker's sense of distance, it's not off by more than half, or half again.
private const val MIN_DISTANCE_SENSE = 0.5
private const val MAX_DISTANCE_SENSE = 1.5

// A flight of stairs, 3.5 m: about 20 risers, taken a step at a time over the landing.
private const val STAIR_STEPS = 20

// The lift: doors close (2 s), it travels (5 s), doors open and the walker steps out.
private const val LIFT_SECONDS = 10
private const val LIFT_DOORS_HALF_SECONDS = 4
private const val LIFT_RIDE_HALF_SECONDS = 10

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
    SummaryItem("Kat değişimi", if (result.crossings.isEmpty()) "Yok" else result.crossings.joinToString { if (it == NodeType.ELEVATOR) "asansör" else "merdiven" }),
    SummaryItem("İlk yönerge", result.firstInstructionStep?.let { "$it. adımdan sonra" } ?: "Hiç"),
)

/**
 * A blind walker new to the building, going only by what the app says:
 * - keeps to the corridor, following it round a bend;
 * - turns where told ("sonra sağa dön", "saat 2 yönüne dön") at the first
 *   junction that has such a turn, once they reckon they've walked about half
 *   the distance the instruction gave (nobody turns into the very next opening
 *   when told "about 10 metres, then right"), their reckoning off by their
 *   distance sense;
 * - turns round when told "geri dön", and stops at "Hedefe ulaştın";
 * - at a junction nobody explained, or at the start with several ways out,
 *   guesses; at a dead end, turns back.
 */
private class InstructionFollower(
    private val building: VirtualBuilding,
    start: MapNode,
    private val random: Random,
    /** The walker's metre, in real ones: told "10 metres", they reckon they've walked it after 10 / this. */
    private val distanceSense: Double = 1.0,
) {
    private val neighbors: Map<String, List<MapNode>> = building.edges
        .flatMap { (a, b) -> listOf(a to b, b to a) }
        .groupBy({ it.first }, { building.nodesById.getValue(it.second) })

    // Stairs and lifts: from a place on one floor to its other end on another.
    private val crossingsFrom: Map<String, List<MapNode>> = building.transitions
        .flatMap { (a, b) -> listOf(a to b, b to a) }
        .groupBy({ it.first }, { building.nodesById.getValue(it.second) })

    /** Heard "Merdivenle yukarı çık: Kat 1, …": the floor to go to at the next staircase or lift. */
    private var floorChangeTo: Int? = null

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

    // The last turn taken at a junction: relative degrees, and where.
    private var lastTurnDeg: Double? = null
    private var lastTurnAtMeters = 0.0

    /** Some instruction has been heard (the walker waits for the first before setting off). */
    var heardInstruction = false
        private set

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

    /**
     * The point [offset] metres right of the corridor's centre line here (left if
     * negative); none near either end, where doors and junctions are found by cane.
     */
    fun beside(offset: Double): Point {
        if (length == 0.0 || offset == 0.0) return Point(x, y)
        val taper = (minOf(along, length - along) / VEER_TAPER_METERS).coerceIn(0.0, 1.0)
        val ux = (to.x - from.x) / length
        val uy = (to.y - from.y) / length
        return Point(x + uy * offset * taper, y - ux * offset * taper)
    }
    val x: Double get() = from.x + (to.x - from.x) * along / length
    val y: Double get() = from.y + (to.y - from.y) * along / length
    val headingDeg: Double get() = if (length == 0.0) lastHeadingDeg else bearing(from, to)
    val floor: Int get() = building.floorOf(from.id)
    private var lastHeadingDeg = 0.0

    /** Up or down from [from] (a staircase or lift landing) to [to] on another floor. */
    class Crossing(val from: MapNode, val to: MapNode)

    class Move(val notes: List<String>, val wrongTurn: Boolean, val crossing: Crossing? = null)

    /** Off the stairs or lift at [arrival]: on along the only way out, or a guess among several. */
    fun arriveAt(arrival: MapNode): String? {
        val ways = neighbors[arrival.id].orEmpty()
        from = arrival
        along = 0.0
        floorChangeTo = null
        if (ways.isEmpty()) {
            arrived = true
            return "${arrival.name}: çıkacak yol yok."
        }
        to = if (ways.size == 1) ways.first() else ways[random.nextInt(ways.size)]
        return "${arrival.name} katına vardı" + if (ways.size == 1) "." else ", ${ways.size} yoldan birini seçti (${to.name})."
    }

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
            // Told to change floor, and this is the staircase or lift for it: up or down here.
            val crossTo = floorChangeTo?.let { target -> crossingsFrom[junction.id]?.firstOrNull { building.floorOf(it.id) == target } }
            if (crossTo != null) {
                lastHeadingDeg = headingDeg
                along = length
                return Move(notes, wrongTurn, Crossing(junction, crossTo))
            }
            val (next, note) = chooseAt(junction, cameFrom = from)
            note?.let(notes::add)
            val turned = normalize(bearing(junction, next) - bearing(from, junction))
            if (abs(turned) > STRAIGHT_DEG) {
                lastTurnDeg = turned
                lastTurnAtMeters = walkedMeters
            }

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

    /**
     * Whether a [stride] more would reach a place with no way on but back (a wall, a
     * door, the lift), and nothing heard says to go up or down there.
     */
    fun atDeadEnd(stride: Double): Boolean {
        if (along + stride < length) return false
        if (floorChangeTo != null && crossingsFrom[to.id] != null) return false
        return neighbors[to.id].orEmpty().none { it.id != from.id }
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

    /**
     * Takes in what the app said, a sentence (or a whole alert) at a time; [opensAlert]
     * unless it goes on from the sentence before ("Sağa dön." then "Yaklaşık 11 metre
     * ilerle, sonra sola dön."). Returns what the walker made of it, when it changed anything.
     */
    fun hear(text: String, opensAlert: Boolean = true, walkedSinceSaidMeters: Double = 0.0): String? {
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
        FLOOR_CHANGE.find(text)?.let { floorChangeTo = it.groupValues[1].toInt() }
        // "Sola dön.", "Saat 2 yönüne dön.": the turn to make at the very next junction.
        val now = when {
            text.startsWith(TURN_LEFT_NOW) -> -90.0
            text.startsWith(TURN_RIGHT_NOW) -> 90.0
            else -> CLOCK_NOW.find(text)?.groupValues?.get(1)?.toInt()?.let { hour -> normalize(hour % 12 * 30.0) }
        }
        if (now != null) {
            // Heard just after making that very turn: it's the one they made.
            val justMade = lastTurnDeg?.let { abs(normalize(it - now)) <= TURN_MATCH_DEG && walkedMeters - lastTurnAtMeters <= JUST_TURNED_METERS } == true
            if (justMade) understood = "Az önce yaptığı dönüş sandı." else turnNowDeg = now
            plannedTurnDeg = null
            plannedTurnAfterMeters = 0.0
        }
        if (WALK !in text && APPROACH !in text) return understood
        heardInstruction = true
        // A new leg forgets an earlier "turn now", unless it's what this alert opened with.
        if (now == null && opensAlert) turnNowDeg = null
        val turn = when {
            RIGHT in text -> 90.0
            LEFT in text -> -90.0
            else -> CLOCK.find(text)?.groupValues?.get(1)?.toInt()?.let { hour -> normalize(hour % 12 * 30.0) }
        }
        plannedTurnDeg = turn
        if (turn != null) {
            val meters = DISTANCE.find(text)?.groupValues?.get(1)?.toDouble() ?: 0.0
            plannedTurnAfterMeters = meters * HALF / distanceSense
            // Counted from when they heard the distance, near the sentence's start.
            turnNotBeforeMeters = walkedMeters - walkedSinceSaidMeters + plannedTurnAfterMeters
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
        private val FLOOR_CHANGE = Regex("""(?:yukarı çık|aşağı in)[:,] Kat (-?\d+)""")
        private const val HALF = 0.5
        private const val BLOCKING_HALF_WIDTH_METERS = 0.6
        private const val CANE_REACH_METERS = 0.8
        private const val TURN_MATCH_DEG = 50.0
        private const val STRAIGHT_DEG = 35.0
        private const val VEER_TAPER_METERS = 1.5
        private const val JUST_TURNED_METERS = 2.5
    }
}

private fun bearing(from: MapNode, to: MapNode): Double = Math.toDegrees(atan2(to.x - from.x, to.y - from.y))

/** Into -180..180. */
private fun normalize(deg: Double): Double = deg - 360.0 * floor((deg + 180.0) / 360.0)
