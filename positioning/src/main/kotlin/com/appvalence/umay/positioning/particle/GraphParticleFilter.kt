package com.appvalence.umay.positioning.particle

import com.appvalence.umay.coremodel.BleBeaconReading
import com.appvalence.umay.coremodel.BuildingMap
import com.appvalence.umay.coremodel.MapNode
import com.appvalence.umay.coremodel.Position
import com.appvalence.umay.coremodel.PositionSource
import com.appvalence.umay.coremodel.StepEvent
import com.appvalence.umay.positioning.fingerprint.FingerprintMatcher
import com.appvalence.umay.positioning.fingerprint.RadioMap
import com.appvalence.umay.positioning.fingerprint.RadioSampler
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Tracks the walker as a cloud of hypotheses ("particles") that can only live
 * on the building's surveyed corridor graph — the graph-based particle filter
 * HULOP's NavCog uses for blind travelers (Ahmetovic et al., W4A 2017), with
 * the edge-bound particle shape of Navigine's GraphParticle.
 *
 * Why this beats dead reckoning + occasional snapping ([PositionFusionEngine]):
 * - Drift can't carry the estimate through a wall or out of a corridor: every
 *   particle moves *along* an edge, and walking in a direction no nearby edge
 *   goes makes a particle unlikely instead of moving it somewhere impossible.
 * - The gyro gives only *relative* heading, so the map frame's north is
 *   unknown at the start of a walk (the old pipeline assumed the walker faced
 *   exactly where the surveyor faced when recording began). Each particle
 *   carries its own heading offset, starting uniformly around the circle; the
 *   few steps it takes to leave the start node are enough to rule out
 *   directions the graph doesn't allow there. A slow random walk on that
 *   offset then absorbs gyro bias drift over the session.
 * - A fingerprint match reweights the cloud rather than teleporting it, so one
 *   wrong match no longer yanks the user onto a parallel corridor.
 *
 * All particles share one floor: a floor change is a confirmed event (barometer
 * or the user), never something the filter guesses. If the current node has no
 * edges at all (a one-node floor, say), the filter falls back to plain dead
 * reckoning in the best heading estimate it has, rather than freezing in place.
 *
 * Not thread-safe; [ParticleFilterPositioningEngine] serializes access.
 */
class GraphParticleFilter(
    private val map: BuildingMap,
    startNodeId: String,
    private val config: Config = Config(),
    private val random: Random = Random.Default,
) {
    data class Config(
        val particleCount: Int = 400,
        /** Per-step stride noise, as a fraction of the calibrated stride. */
        val stepLengthNoiseFraction: Double = 0.10,
        /** Each particle's lasting stride scale is drawn once around 1.0 with this spread. */
        val stepScaleSpread: Double = 0.05,
        /** Per-step heading noise on top of the gyro's reading. */
        val headingNoiseDeg: Double = 5.0,
        /** Per-step random walk of each particle's heading offset (gyro bias drift). */
        val headingOffsetDriftDeg: Double = 0.5,
        /**
         * How far a step's direction may stray from the edge it walks along before it
         * counts against the particle. Real walkers stay within ~10° of a corridor's
         * axis, but a surveyed edge is itself only as straight as the survey walk was.
         */
        val edgeAlignmentSigmaDeg: Double = 20.0,
        /** Likelihood of a step taken while stuck at a node with no edge in the walking direction. */
        val waitAtNodeLikelihood: Double = 0.3,
        /** Spread of a fingerprint match: "near this named place", not a precise fix. */
        val fingerprintSigmaMeters: Double = 3.0,
        /**
         * The same beacons are re-matched on every advertisement; applying each one as
         * an independent observation would make the cloud overconfident within a second.
         */
        val minFingerprintIntervalMs: Long = 1_000L,
        /** A match farther than this from every particle means the cloud is lost: re-seed at the match. */
        val reseedDistanceMeters: Double = 6.0,
        /** Fraction of the cloud replaced when re-seeding, so a single false match can't erase it. */
        val reseedFraction: Double = 0.5,
        /** RSSI spread around what the radio map expects: fading and body shadowing, after smoothing. */
        val radioSigmaDb: Double = 6.0,
        /**
         * How much one scan's fit to the radio map counts, in "beacons' worth": the fit is
         * a per-beacon mean, so this caps how sure a single scan can make the cloud.
         */
        val radioEvidenceWeight: Double = 3.0,
        /** Fit given to a particle away from every radio sample: unremarkable, neither good nor bad. */
        val radioUnknownLogLikelihood: Double = -0.7,
        /** Scans repeat the same smoothed values many times a second; one counts per this. */
        val minRadioEvidenceIntervalMs: Long = 1_000L,
        /**
         * How much better (per-beacon mean log-likelihood) the radio map must find a
         * place match than the cloud before the match is acted on. 0 = any better.
         */
        val fingerprintConsistencyMargin: Double = 0.5,
        /**
         * A beacon's reading older than this says where the walker *was*: the scanner
         * keeps a beacon's last value for a while after it stops being heard (see
         * BleBeaconScanner.STALE_THRESHOLD_MS), which while walking is metres behind.
         * The same age the survey keeps for its radio samples, so both sides compare
         * like with like.
         */
        val maxReadingAgeMs: Long = RadioSampler.DEFAULT_MAX_AGE_MS,
        /** Spread of a marker's distance judged from its size: a fixed part plus a share of the distance. */
        val markerDistanceSigmaMeters: Double = 0.4,
        val markerDistanceSigmaFraction: Double = 0.2,
        /** Spread of the direction a marker was seen in, against where it is: hand sway, lens guesses. */
        val markerBearingSigmaDeg: Double = 20.0,
        /** A marker is seen several times a second; one counts per this. */
        val minMarkerIntervalMs: Long = 700L,
        /** How far past (or short of) a junction a particle may still take the turn there. */
        val cornerSnapMeters: Double = 1.0,
    )

    /** One fingerprint match that was applied, and where the cloud ended up because of it. */
    data class Correction(val node: MapNode, val estimate: Position)

    private class Edge(val from: MapNode, val to: MapNode) {
        val length = hypot(to.x - from.x, to.y - from.y)
        val bearingDeg = normalizeDegrees(Math.toDegrees(atan2(to.x - from.x, to.y - from.y)))
    }

    private class Particle(
        var edge: Edge?,
        var along: Double,
        /** Only meaningful when [edge] is null (the degraded, graph-less mode). */
        var node: MapNode,
        var headingOffsetDeg: Double,
        var stepScale: Double,
        var logWeight: Double = 0.0,
    ) {
        val x get() = edge?.let { it.from.x + (it.to.x - it.from.x) * (along / it.length) } ?: node.x
        val y get() = edge?.let { it.from.y + (it.to.y - it.from.y) * (along / it.length) } ?: node.y

        fun copy() = Particle(edge, along, node, headingOffsetDeg, stepScale, logWeight)
    }

    private val floorOfNode: Map<String, Int> =
        map.floors.flatMap { floor -> floor.nodes.map { it.id to floor.floorIndex } }.toMap()
    private val nodesById: Map<String, MapNode> = map.floors.flatMap { it.nodes }.associateBy { it.id }

    // Directed edges leaving each node, both directions of every surveyed edge.
    private val edgesFrom: Map<String, List<Edge>> = map.floors.flatMap { floor ->
        floor.edges.flatMap { edge ->
            val from = nodesById[edge.fromId]
            val to = nodesById[edge.toId]
            if (from == null || to == null || floorOfNode[from.id] != floorOfNode[to.id]) {
                emptyList()
            } else {
                listOf(Edge(from, to), Edge(to, from)).filter { it.length > MIN_EDGE_LENGTH_METERS }
            }
        }
    }.groupBy { it.from.id }

    private val fingerprintedByFloor: Map<Int, List<MapNode>> = map.floors.associate { floor ->
        floor.floorIndex to floor.nodes.filter { it.fingerprint.isNotEmpty() }
    }

    // Signatures recorded along the surveyed path (see RadioMap); empty for older maps.
    private val radioMaps: Map<Int, RadioMap> = map.floors
        .filter { it.radioMap.isNotEmpty() }
        .associate { it.floorIndex to RadioMap(it.radioMap) }
    private var lastRadioEvidenceMs = Long.MIN_VALUE
    private var lastMarkerMs = Long.MIN_VALUE

    var floor: Int = floorOfNode[startNodeId] ?: 0
        private set

    // Gyro-frame heading accumulated from every step; a particle's map heading is this
    // plus its own offset.
    private var gyroHeadingDeg = 0.0
    private var lastFingerprintAppliedMs = Long.MIN_VALUE
    private var lastTimestampMs = 0L

    private var particles: List<Particle> = run {
        // With no edge to walk along, nothing could ever rule a heading out, so an isolated
        // start keeps the old assumption instead: the walker faces where the survey began.
        val canLearnHeading = edgesFrom[startNodeId] != null
        List(config.particleCount) {
            seedAt(nodesById.getValue(startNodeId), headingOffsetDeg = if (canLearnHeading) random.nextDouble(0.0, 360.0) else 0.0)
        }
    }

    fun onStep(step: StepEvent): Position {
        gyroHeadingDeg += step.headingDeltaDeg
        lastTimestampMs = step.timestampMs
        for (particle in particles) {
            particle.headingOffsetDeg += gaussian() * config.headingOffsetDriftDeg
            val headingDeg = gyroHeadingDeg + particle.headingOffsetDeg + gaussian() * config.headingNoiseDeg
            val distance = step.stepLengthMeters * particle.stepScale * (1.0 + gaussian() * config.stepLengthNoiseFraction)
            particle.logWeight += move(particle, normalizeDegrees(headingDeg), max(distance, 0.0))
        }
        normalizeAndMaybeResample()
        return estimate(step.timestampMs)
    }

    /**
     * Matches [readings] (those heard in the last [Config.maxReadingAgeMs]) against this
     * floor's fingerprinted places and, when the match is confident (the same rules as
     * the old pipeline, see [FingerprintMatcher]) and the radio map agrees, pulls the
     * cloud toward it. Null when nothing was applied.
     */
    fun onBeaconReadings(readings: List<BleBeaconReading>, timestampMs: Long): Correction? {
        val fresh = readings.filter { timestampMs - it.timestampMs <= config.maxReadingAgeMs }
        applyRadioEvidence(fresh, timestampMs)
        if (lastFingerprintAppliedMs != Long.MIN_VALUE &&
            timestampMs - lastFingerprintAppliedMs < config.minFingerprintIntervalMs
        ) {
            return null
        }
        val match = FingerprintMatcher.bestMatch(fresh, fingerprintedByFloor[floor].orEmpty()) ?: return null
        if (radioMapPrefersCloud(fresh, match.node)) return null
        lastFingerprintAppliedMs = timestampMs
        lastTimestampMs = timestampMs
        observeNear(match.node)
        return Correction(match.node, estimate(timestampMs))
    }

    /** A confirmed floor change: everyone restarts at the arrival node, keeping what they know about heading. */
    /**
     * A place marker seen [distanceMeters] away, [bearingDeg] right of where the camera
     * points (taken as where the walker faces). The strongest evidence there is: it says
     * which place, so it also settles the floor, and its direction settles the heading.
     * Null when ignored (an unknown marker, or too soon after the last one).
     */
    fun onMarker(nodeId: String, distanceMeters: Double, bearingDeg: Double, timestampMs: Long): Position? {
        val node = nodesById[nodeId] ?: return null
        if (lastMarkerMs != Long.MIN_VALUE && timestampMs - lastMarkerMs < config.minMarkerIntervalMs) return null
        lastMarkerMs = timestampMs
        lastTimestampMs = timestampMs
        val markerFloor = floorOfNode[nodeId] ?: floor
        if (markerFloor != floor) changeFloor(nodeId, timestampMs)

        val sigma = config.markerDistanceSigmaMeters + config.markerDistanceSigmaFraction * distanceMeters
        if (particles.minOf { kotlin.math.abs(hypot(it.x - node.x, it.y - node.y) - distanceMeters) } > 3 * sigma + config.reseedDistanceMeters) {
            // The cloud is nowhere near where the marker says: start over around it. Its
            // heading is no better than its position, so that comes from the marker too.
            particles = resampled(particles.size).map { seedFacingMarker(node, distanceMeters, bearingDeg, it.stepScale) }
        }
        for (p in particles) {
            val dx = node.x - p.x
            val dy = node.y - p.y
            val range = hypot(dx, dy)
            var logLikelihood = -0.5 * square((range - distanceMeters) / sigma)
            if (distanceMeters > MIN_BEARING_DISTANCE_METERS && range > MIN_BEARING_DISTANCE_METERS) {
                val toMarker = Math.toDegrees(atan2(dx, dy))
                val facing = gyroHeadingDeg + p.headingOffsetDeg
                logLikelihood += -0.5 * square(angleBetween(toMarker - facing, bearingDeg) / config.markerBearingSigmaDeg)
            }
            p.logWeight += logLikelihood
        }
        normalizeAndMaybeResample()
        return estimate(timestampMs)
    }

    fun changeFloor(arrivalNodeId: String, timestampMs: Long): Position {
        val arrival = nodesById.getValue(arrivalNodeId)
        floor = floorOfNode[arrivalNodeId] ?: floor
        particles = resampled(particles.size).map { seedAt(arrival, it.headingOffsetDeg, it.stepScale) }
        lastTimestampMs = timestampMs
        return estimate(timestampMs)
    }

    /**
     * Where the particles are, for offline tools (the walk viewer): at most [max] of
     * them, spread evenly over the cloud. Weights are left out — after resampling
     * they're nearly equal, and the density of points already shows the belief.
     */
    fun particlePositions(max: Int = particles.size): List<Pair<Double, Double>> {
        if (max <= 0) return emptyList()
        val stride = maxOf(1, particles.size / max)
        return particles.filterIndexed { i, _ -> i % stride == 0 }.take(max).map { it.x to it.y }
    }

    fun estimate(timestampMs: Long = lastTimestampMs): Position {
        val weights = normalizedWeights()
        var meanX = 0.0
        var meanY = 0.0
        var headingSin = 0.0
        var headingCos = 0.0
        particles.forEachIndexed { i, p ->
            meanX += weights[i] * p.x
            meanY += weights[i] * p.y
            val headingRad = Math.toRadians(gyroHeadingDeg + p.headingOffsetDeg)
            headingSin += weights[i] * sin(headingRad)
            headingCos += weights[i] * cos(headingRad)
        }
        val variance = particles.indices.sumOf { i ->
            val dx = particles[i].x - meanX
            val dy = particles[i].y - meanY
            weights[i] * (dx * dx + dy * dy)
        }
        return Position(
            x = meanX,
            y = meanY,
            floor = floor,
            headingDeg = normalizeDegrees(Math.toDegrees(atan2(headingSin, headingCos))),
            accuracyMeters = max(sqrt(variance), MIN_ACCURACY_METERS),
            source = PositionSource.MAP_MATCHED,
            timestampMs = timestampMs,
        )
    }

    /**
     * Moves [particle] [distance] meters in map heading [headingDeg] along the graph and
     * returns the log-likelihood of that step given the edge it was taken on.
     */
    private fun move(particle: Particle, headingDeg: Double, distance: Double): Double {
        val edge = particle.edge
        if (edge == null) {
            // Graph-less fallback: dead-reckon freely from the particle's last point.
            val rad = Math.toRadians(headingDeg)
            particle.node = particle.node.copy(x = particle.node.x + distance * sin(rad), y = particle.node.y + distance * cos(rad))
            return 0.0
        }

        // Standing on a node (the start, or the end of the previous step): the walker may
        // set off along any edge that leaves it, not just the one the particle sat on.
        val atNode = when {
            particle.along <= NODE_EPSILON_METERS -> edge.from
            particle.along >= edge.length - NODE_EPSILON_METERS -> edge.to
            else -> nodeTurnedAt(edge, particle.along, headingDeg)
        }
        if (atNode != null) {
            val best = bestEdgeFrom(atNode, headingDeg)
            if (best == null || angleBetween(headingDeg, best.bearingDeg) > MAX_TURN_AT_NODE_DEG) {
                // Nowhere to go this way: past a dead end, or a corner the walker hasn't
                // turned yet. A surveyed node is only roughly where the walker passed, so
                // this is mildly unlikely rather than impossible — scoring it by
                // misalignment instead would kill exactly the particles that got here a
                // little early, leaving the cloud to lag behind at every corner.
                return ln(config.waitAtNodeLikelihood)
            }
            particle.edge = best
            particle.along = 0.0
        } else if (angleBetween(headingDeg, edge.bearingDeg) > 90.0) {
            // Walking back the way it came along the same corridor.
            particle.edge = Edge(edge.to, edge.from)
            particle.along = edge.length - particle.along
        }

        val current = particle.edge!!
        val misalignment = angleBetween(headingDeg, current.bearingDeg)
        val logLikelihood = -0.5 * square(misalignment / config.edgeAlignmentSigmaDeg)

        var remaining = distance
        var walking: Edge = current
        var along = particle.along + remaining
        // Overshooting a node continues onto whichever edge leaving it best matches the
        // heading; with none ahead the particle stops at the node and waits there.
        while (along > walking.length) {
            remaining = along - walking.length
            val next = bestEdgeFrom(walking.to, headingDeg)
            if (next == null || angleBetween(headingDeg, next.bearingDeg) > MAX_TURN_AT_NODE_DEG) {
                along = walking.length
                break
            }
            walking = next
            along = remaining
        }
        particle.edge = walking
        particle.along = along
        return logLikelihood
    }

    /**
     * Mid-edge but within [Config.cornerSnapMeters] of either end, heading nowhere along
     * this edge but along one leaving that end: the walker turned there, and the
     * particle is a step off — a stride a little long, or a place surveyed a little
     * early. Without this, a tight cloud that all overshot a junction together (as
     * one does right after a marker) is wiped out by the turn, leaving only
     * whichever hypothesis happened to stop at the node.
     */
    private fun nodeTurnedAt(edge: Edge, along: Double, headingDeg: Double): MapNode? {
        if (angleBetween(headingDeg, edge.bearingDeg) <= MAX_TURN_AT_NODE_DEG) return null
        if (angleBetween(headingDeg, edge.bearingDeg + 180.0) <= MAX_TURN_AT_NODE_DEG) return null // turning back: handled below
        val end = when {
            along <= config.cornerSnapMeters -> edge.from
            edge.length - along <= config.cornerSnapMeters -> edge.to
            else -> return null
        }
        val best = bestEdgeFrom(end, headingDeg) ?: return null
        return end.takeIf { angleBetween(headingDeg, best.bearingDeg) <= MAX_TURN_AT_NODE_DEG }
    }

    private fun bestEdgeFrom(node: MapNode, headingDeg: Double): Edge? =
        edgesFrom[node.id]?.minByOrNull { angleBetween(headingDeg, it.bearingDeg) }

    /**
     * A place match judged on a few beacons at one spot is easily wrong — two beacons
     * heard out of five can look like almost anywhere — and acting on a wrong one
     * drags the walker metres away. Where the floor has a radio map, the match must
     * also explain the scan better than where the cloud already is; otherwise the
     * radio map's own evidence has it covered and the match is ignored.
     */
    private fun radioMapPrefersCloud(readings: List<BleBeaconReading>, matched: MapNode): Boolean {
        val radioMap = radioMaps[floor] ?: return false
        val live = readings.filter { it.beaconId in radioMap.beaconIds }.associate { it.beaconId to it.rssi.toDouble() }
        if (live.size < FingerprintMatcher.MIN_SHARED_BEACONS) return false
        val here = estimate()
        val atCloud = radioMap.logLikelihood(live, here.x, here.y, config.radioSigmaDb) ?: return false
        val atMatch = radioMap.logLikelihood(live, matched.x, matched.y, config.radioSigmaDb) ?: return false
        return atCloud >= atMatch - config.fingerprintConsistencyMargin
    }

    /**
     * Every scan, not just a confident match, says something: weight each particle by
     * how well the scan fits what the radio map expects where that particle is.
     */
    private fun applyRadioEvidence(readings: List<BleBeaconReading>, timestampMs: Long) {
        val radioMap = radioMaps[floor] ?: return
        if (lastRadioEvidenceMs != Long.MIN_VALUE && timestampMs - lastRadioEvidenceMs < config.minRadioEvidenceIntervalMs) return
        val live = readings.filter { it.beaconId in radioMap.beaconIds }.associate { it.beaconId to it.rssi.toDouble() }
        if (live.size < FingerprintMatcher.MIN_SHARED_BEACONS) return
        lastRadioEvidenceMs = timestampMs
        lastTimestampMs = timestampMs
        for (p in particles) {
            val fit = radioMap.logLikelihood(live, p.x, p.y, config.radioSigmaDb) ?: config.radioUnknownLogLikelihood
            p.logWeight += config.radioEvidenceWeight * fit
        }
        normalizeAndMaybeResample()
    }

    private fun observeNear(node: MapNode) {
        val closest = particles.minOf { hypot(it.x - node.x, it.y - node.y) }
        if (closest > config.reseedDistanceMeters) {
            // The cloud has lost track (or the match is wrong). Replace part of it with
            // particles at the matched place, heading knowledge kept; the likelihood below
            // then decides between them, and the next matches confirm or undo it.
            val reseedCount = (particles.size * config.reseedFraction).toInt()
            val heading = resampled(reseedCount)
            val survivors = particles.sortedByDescending { it.logWeight }.take(particles.size - reseedCount)
            val maxLogWeight = survivors.maxOfOrNull { it.logWeight } ?: 0.0
            particles = survivors + heading.map { seedAt(node, it.headingOffsetDeg, it.stepScale).also { p -> p.logWeight = maxLogWeight } }
        }
        val twoSigmaSquared = 2.0 * square(config.fingerprintSigmaMeters)
        for (p in particles) {
            p.logWeight += -(square(p.x - node.x) + square(p.y - node.y)) / twoSigmaSquared
        }
        normalizeAndMaybeResample()
    }

    private fun seedAt(node: MapNode, headingOffsetDeg: Double, stepScale: Double = 1.0 + gaussian() * config.stepScaleSpread): Particle {
        val edges = edgesFrom[node.id]
        val edge = edges?.get(random.nextInt(edges.size))
        return Particle(edge = edge, along = 0.0, node = node, headingOffsetDeg = headingOffsetDeg, stepScale = stepScale)
    }

    /**
     * A particle [distanceMeters] from the marker at [node] along one of the corridors
     * leaving it, facing so the marker is [bearingDeg] to its right — where and which
     * way the walker could be to see the marker as they did. Too close to tell a
     * direction: at the marker, heading unknown, as at the start of a walk.
     */
    private fun seedFacingMarker(node: MapNode, distanceMeters: Double, bearingDeg: Double, stepScale: Double): Particle {
        val edges = edgesFrom[node.id]
        if (edges == null || distanceMeters <= MIN_BEARING_DISTANCE_METERS) {
            return seedAt(node, headingOffsetDeg = random.nextDouble(0.0, 360.0), stepScale = stepScale)
        }
        val edge = edges[random.nextInt(edges.size)]
        val along = (distanceMeters * (1.0 + gaussian() * config.markerDistanceSigmaFraction)).coerceIn(0.0, edge.length)
        val facingDeg = edge.bearingDeg + 180.0 - bearingDeg + gaussian() * config.markerBearingSigmaDeg / 2
        return Particle(edge = edge, along = along, node = node, headingOffsetDeg = normalizeDegrees(facingDeg - gyroHeadingDeg), stepScale = stepScale)
    }

    private fun normalizedWeights(): DoubleArray {
        val maxLog = particles.maxOf { it.logWeight }
        val weights = DoubleArray(particles.size) { exp(particles[it].logWeight - maxLog) }
        val total = weights.sum()
        for (i in weights.indices) weights[i] /= total
        return weights
    }

    private fun normalizeAndMaybeResample() {
        val weights = normalizedWeights()
        val effectiveSize = 1.0 / weights.sumOf { it * it }
        if (effectiveSize < particles.size * RESAMPLE_THRESHOLD) {
            particles = resampled(particles.size, weights)
        } else {
            // Keep log weights from running off toward -infinity over a long walk.
            particles.forEachIndexed { i, p -> p.logWeight = ln(max(weights[i], Double.MIN_VALUE)) }
        }
    }

    /** Systematic resampling: [count] copies drawn in proportion to weight, with equal weight. */
    private fun resampled(count: Int, weights: DoubleArray = normalizedWeights()): List<Particle> {
        if (count <= 0) return emptyList()
        val step = 1.0 / count
        var target = random.nextDouble() * step
        var cumulative = weights[0]
        var index = 0
        return List(count) {
            while (target > cumulative && index < weights.lastIndex) {
                index++
                cumulative += weights[index]
            }
            target += step
            particles[index].copy().also { it.logWeight = 0.0 }
        }
    }

    // Box–Muller; kotlin.random has no Gaussian.
    private fun gaussian(): Double {
        val u1 = random.nextDouble().coerceAtLeast(Double.MIN_VALUE)
        val u2 = random.nextDouble()
        return sqrt(-2.0 * ln(u1)) * cos(2.0 * Math.PI * u2)
    }

    companion object {
        const val RESAMPLE_THRESHOLD = 0.5
        const val MAX_TURN_AT_NODE_DEG = 60.0
        const val MIN_ACCURACY_METERS = 0.3

        // Right next to a marker its direction says little about heading.
        private const val MIN_BEARING_DISTANCE_METERS = 0.8
        private const val MIN_EDGE_LENGTH_METERS = 0.05
        private const val NODE_EPSILON_METERS = 1e-6

        private fun normalizeDegrees(deg: Double): Double = ((deg % 360.0) + 360.0) % 360.0
        private fun angleBetween(a: Double, b: Double): Double = abs(((a - b + 540.0) % 360.0) - 180.0)
        private fun square(v: Double) = v * v
    }
}
