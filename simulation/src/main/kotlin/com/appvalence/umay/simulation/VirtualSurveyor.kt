package com.appvalence.umay.simulation

import com.appvalence.umay.coremodel.BuildingMap
import com.appvalence.umay.coremodel.MapNode
import com.appvalence.umay.coremodel.RadioFingerprint
import com.appvalence.umay.coremodel.RadioSample
import com.appvalence.umay.coremodel.StepEvent
import com.appvalence.umay.coremodel.TaggedWaypoint
import com.appvalence.umay.positioning.fingerprint.RadioSampler
import com.appvalence.umay.positioning.pdr.PdrIntegrator
import com.appvalence.umay.positioning.trilateration.BeaconMapBuilder
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * A building's map as the app has it, and how it lines up with the building's
 * true plan: which map place is which real one (the surveyor named them), and
 * the turn and shift that best lay the map over the plan. A map surveyed by
 * footsteps is in the survey's own drifting frame; distances and errors are
 * only comparable after laying one over the other.
 */
internal class SurveyedMap(
    val map: BuildingMap,
    /** Real place id to map node id, for every place the survey named. */
    private val mapIds: Map<String, String>,
    private val rotationRad: Double = 0.0,
    private val fromCenter: Point = Point(0.0, 0.0),
    private val toCenter: Point = Point(0.0, 0.0),
) {
    private val buildingIds: Map<String, String> = mapIds.entries.associate { (real, mapped) -> mapped to real }

    fun mapId(buildingNodeId: String): String? = mapIds[buildingNodeId]

    fun buildingId(mapNodeId: String): String? = buildingIds[mapNodeId]

    /** The map's frame back onto the real plan: the inverse of [toMap]. */
    fun toPlan(): (Double, Double) -> Pair<Double, Double> = { x, y ->
        val dx = x - toCenter.x
        val dy = y - toCenter.y
        (fromCenter.x + dx * cos(rotationRad) + dy * sin(rotationRad)) to (fromCenter.y - dx * sin(rotationRad) + dy * cos(rotationRad))
    }

    /** A point of the real plan in the map's frame. */
    /** How a direction on the plan turns on the map: a heading of h on the plan is h minus this on the map. */
    val rotationDeg: Double get() = Math.toDegrees(rotationRad)

    fun toMap(point: Point): Point {
        val dx = point.x - fromCenter.x
        val dy = point.y - fromCenter.y
        return Point(toCenter.x + dx * cos(rotationRad) - dy * sin(rotationRad), toCenter.y + dx * sin(rotationRad) + dy * cos(rotationRad))
    }

    /** How far each named place landed from where it really is, once the map is laid over the plan. */
    fun placementErrors(building: VirtualBuilding): Map<String, Double> {
        val nodes = map.floors.flatMap { it.nodes }.associateBy { it.id }
        return mapIds.mapNotNull { (real, mapped) ->
            val truth = building.nodesById[real] ?: return@mapNotNull null
            val node = nodes[mapped] ?: return@mapNotNull null
            val laid = toMap(Point(truth.x, truth.y))
            real to hypot(node.x - laid.x, node.y - laid.y)
        }.toMap()
    }

    companion object {
        /** The perfect survey: the plan itself. */
        fun ideal(map: BuildingMap): SurveyedMap = SurveyedMap(map, map.floors.flatMap { it.nodes }.associate { it.id to it.id })

        /**
         * Lines [map] up with [building] by its named places: [mapIds] says which is which,
         * and the rotation and shift are the least-squares fit of one set onto the other.
         */
        fun fitted(map: BuildingMap, building: VirtualBuilding, mapIds: Map<String, String>): SurveyedMap {
            val nodes = map.floors.flatMap { it.nodes }.associateBy { it.id }
            val pairs = mapIds.mapNotNull { (real, mapped) ->
                val truth = building.nodesById[real] ?: return@mapNotNull null
                val node = nodes[mapped] ?: return@mapNotNull null
                Point(truth.x, truth.y) to Point(node.x, node.y)
            }
            if (pairs.isEmpty()) return SurveyedMap(map, mapIds)
            val from = Point(pairs.map { it.first.x }.average(), pairs.map { it.first.y }.average())
            val to = Point(pairs.map { it.second.x }.average(), pairs.map { it.second.y }.average())
            var cross = 0.0
            var dot = 0.0
            for ((p, q) in pairs) {
                val px = p.x - from.x
                val py = p.y - from.y
                val qx = q.x - to.x
                val qy = q.y - to.y
                cross += px * qy - py * qx
                dot += px * qx + py * qy
            }
            return SurveyedMap(map, mapIds, atan2(cross, dot), from, to)
        }
    }
}

/**
 * A sighted surveyor making the map the way KURULUM.md describes for the
 * footsteps mode: walking every corridor once (down each branch and back),
 * naming each place they reach — a junction again by the same name on coming
 * back to it — while the phone counts steps with its own drift and hears the
 * beacons. The map is then built by the app's own code
 * ([BeaconMapBuilder.buildFromWaypoints]), so everything that goes wrong in a
 * real survey happens here too: the drawing drifts, a junction named twice
 * too far apart doesn't join, places land off where they are.
 */
internal object VirtualSurveyor {

    data class Survey(
        val surveyed: SurveyedMap,
        /** Real metres walked. */
        val walkedMeters: Double,
        /** Places the map has two nodes for: named again too far from the first time. */
        val unjoinedPlaces: Int,
    )

    fun survey(
        building: VirtualBuilding,
        startNodeId: String,
        seed: Int,
        walker: WalkerModel = WalkerModel(),
        phone: PhoneRadio = PhoneRadio(),
        withRadio: Boolean = true,
    ): Survey {
        require(building.floors.size == 1) { "the virtual surveyor walks one floor; ${building.name} has ${building.floors.size}" }
        val random = Random(seed)
        val scanner = VirtualScanner(building, random, phone)
        val sampler = RadioSampler()
        val pdr = PdrIntegrator()
        val gyroOffsetDeg = random.nextDouble(0.0, 360.0)
        var gyroHeadingDeg = 0.0
        var steps = 0
        var t = 1_000L
        var walked = 0.0
        val tags = ArrayList<TaggedWaypoint>()
        val radio = ArrayList<RadioSample>()
        var last = pdr.integrate(StepEvent(0.0, 0.0, t)) // where dead reckoning starts: its origin
        val trajectory = arrayListOf(last)

        fun hear(x: Double, y: Double): List<RadioFingerprint> =
            if (!withRadio) emptyList() else scanner.hear(x, y, t).map { RadioFingerprint(it.beaconId, it.rssi.toDouble()) }

        fun tag(node: MapNode) {
            // Standing still a few seconds to name it: the scanner settles meanwhile.
            var heard = emptyList<RadioFingerprint>()
            repeat(STANDING_STEPS) { t += walker.stepIntervalMs; heard = hear(node.x, node.y) }
            tags += TaggedWaypoint(node.name, last.x, last.y, 0, heard, node.type)
        }

        fun walk(from: MapNode, to: MapNode) {
            val length = hypot(to.x - from.x, to.y - from.y)
            val bearing = Math.toDegrees(atan2(to.x - from.x, to.y - from.y))
            var along = 0.0
            while (along < length) {
                val stride = walker.trueStrideMeters * (1.0 + random.gaussian() * walker.strideNoiseFraction)
                val step = minOf(stride, length - along)
                along += step
                walked += step
                val x = from.x + (to.x - from.x) * along / length
                val y = from.y + (to.y - from.y) * along / length
                val sensed = bearing - gyroOffsetDeg + walker.gyroDriftPerStepDeg * steps + random.gaussian() * walker.headingNoiseDeg
                val delta = ((sensed - gyroHeadingDeg + 540.0) % 360.0) - 180.0
                gyroHeadingDeg += delta
                t += walker.stepIntervalMs
                steps++
                last = pdr.integrate(StepEvent(walker.calibratedStrideMeters, delta, t))
                trajectory += last
                if (withRadio) {
                    val sightings = scanner.hear(x, y, t)
                    sampler.offer(last, sightings)?.let(radio::add)
                }
            }
        }

        // Down every branch and back, naming each place on reaching it.
        val neighbors = building.edges.flatMap { (a, b) -> listOf(a to b, b to a) }.groupBy({ it.first }, { it.second })
        val walkedEdges = HashSet<Set<String>>()
        fun explore(nodeId: String) {
            val node = building.nodesById.getValue(nodeId)
            for (next in neighbors[nodeId].orEmpty()) {
                if (!walkedEdges.add(setOf(nodeId, next))) continue
                val nextNode = building.nodesById.getValue(next)
                walk(node, nextNode)
                tag(nextNode)
                explore(next)
                walk(nextNode, node)
                tag(node)
            }
        }
        tag(building.nodesById.getValue(startNodeId))
        explore(startNodeId)

        val map = BeaconMapBuilder.buildFromWaypoints(
            buildingId = building.name,
            taggedWaypoints = tags,
            trajectory = trajectory,
            radioSamples = if (withRadio) mapOf(0 to radio) else emptyMap(),
        )
        // Which map node each real place became: the one with its name nearest the plan's
        // layout is decided below, by the fit; a place named twice far apart has two.
        val byName = map.floors.flatMap { it.nodes }.groupBy { it.name }
        val ids = building.nodes.mapNotNull { node -> byName[node.name]?.firstOrNull()?.let { node.id to it.id } }.toMap()
        val unjoined = byName.values.count { it.size > 1 }
        return Survey(SurveyedMap.fitted(map, building, ids), walked, unjoined)
    }

    private const val STANDING_STEPS = 4
}
