package com.appvalence.umay.positioning.replay

import com.appvalence.umay.coremodel.BleBeaconReading
import com.appvalence.umay.coremodel.FieldLogEvent
import com.appvalence.umay.coremodel.FloorChangeEvent
import com.appvalence.umay.coremodel.FloorChangePhase
import com.appvalence.umay.coremodel.GroundTruthEvent
import com.appvalence.umay.coremodel.MapNode
import com.appvalence.umay.coremodel.Position
import com.appvalence.umay.coremodel.PositionEvent
import com.appvalence.umay.coremodel.PositionSource
import com.appvalence.umay.coremodel.PositionStream
import com.appvalence.umay.coremodel.RssiEvent
import com.appvalence.umay.coremodel.SessionStartEvent
import com.appvalence.umay.coremodel.StatusEvent
import com.appvalence.umay.coremodel.StepEvent
import com.appvalence.umay.positioning.ble.BleBeaconScanner
import com.appvalence.umay.positioning.compass.CompassHint
import com.appvalence.umay.positioning.engine.FingerprintPositioningEngine
import com.appvalence.umay.positioning.engine.PositionFusionEngine
import com.appvalence.umay.positioning.fingerprint.FingerprintMatcher
import com.appvalence.umay.positioning.particle.GraphParticleFilter
import com.appvalence.umay.positioning.pdr.PdrIntegrator
import com.appvalence.umay.positioning.pdr.StepDetector
import kotlin.math.hypot
import kotlin.random.Random

/**
 * Re-runs a recorded walk (a field log, see [FieldLogEvent]) through both the
 * old pipeline (dead reckoning + fingerprint snapping, [PositionFusionEngine])
 * and the [GraphParticleFilter], and scores each against the walk's only
 * ground truth: the tester's "Buradayım" marks ([GroundTruthEvent]).
 *
 * The inputs are rebuilt exactly as the live pipelines saw them: footsteps
 * from the PDR stream's heading changes and the session's stride, and beacon
 * lists from RSSI sightings with the scanner's own staleness window. The
 * replayed old pipeline should land on the error the app logged live — when it
 * doesn't, the replay itself is off and its numbers shouldn't be trusted.
 */
object FieldLogReplay {

    data class GroundTruthComparison(
        val t: Long,
        val nodeName: String,
        /** What the app measured during the walk. */
        val liveErrorMeters: Double?,
        val legacyErrorMeters: Double?,
        /** Mean over the replay's seeds; null if any seed was on the wrong floor. */
        val particleErrorMeters: Double?,
    )

    data class Result(
        val buildingId: String,
        val steps: Int,
        val legacyCorrections: Int,
        val particleCorrections: Int,
        val comparisons: List<GroundTruthComparison>,
    ) {
        val legacyMeanError: Double? get() = comparisons.mapNotNull { it.legacyErrorMeters }.averageOrNull()
        val particleMeanError: Double? get() = comparisons.mapNotNull { it.particleErrorMeters }.averageOrNull()
    }

    fun replay(
        events: List<FieldLogEvent>,
        config: GraphParticleFilter.Config = GraphParticleFilter.Config(),
        seeds: List<Int> = listOf(1, 2, 3, 4, 5),
    ): Result {
        val start = events.filterIsInstance<SessionStartEvent>().firstOrNull()
            ?: error("A field log always starts with session_start; this one has none")
        val ordered = events.sortedBy { it.t }
        val stepLength = start.constants["stepLengthMetersInUse"] ?: StepDetector.AVERAGE_STEP_LENGTH_METERS

        val legacy = LegacyPipeline(start)
        val filters = seeds.map { GraphParticleFilter(start.map, start.startNodeId, config, Random(it)) }
        val particleCorrections = IntArray(seeds.size)

        val sightings = mutableMapOf<String, RssiEvent>()
        var previousPdrHeading = 0.0
        var steps = 0
        val comparisons = mutableListOf<GroundTruthComparison>()

        for (event in ordered) {
            when (event) {
                is PositionEvent -> if (event.stream == PositionStream.PDR) {
                    val step = StepEvent(
                        stepLengthMeters = stepLength,
                        headingDeltaDeg = signedDelta(previousPdrHeading, event.headingDeg),
                        timestampMs = event.t,
                    )
                    previousPdrHeading = event.headingDeg
                    steps++
                    legacy.onStep(step)
                    filters.forEach { it.onStep(step) }
                }

                is RssiEvent -> {
                    sightings[event.beaconId] = event
                    sightings.values.removeAll { event.t - it.t > BleBeaconScanner.STALE_THRESHOLD_MS }
                    val readings = sightings.values.map(::toReading)
                    legacy.onBeaconReadings(readings, event.t)
                    filters.forEachIndexed { i, filter ->
                        if (filter.onBeaconReadings(readings, event.t) != null) particleCorrections[i]++
                    }
                }

                // A starting direction the compass gave the live filter: the replay's get it too.
                is StatusEvent -> CompassHint.fromLogStatus(event.status)?.let { (offset, sigma) ->
                    filters.forEach { it.observeHeadingOffset(offset, sigma) }
                }

                is FloorChangeEvent -> if (event.phase != FloorChangePhase.PROMPTED) {
                    legacy.changeFloor(event.targetNodeId, event.targetFloor)
                    filters.forEach { it.changeFloor(event.targetNodeId, event.t) }
                }

                is GroundTruthEvent -> {
                    val particleErrors = filters.map { errorAgainst(it.estimate(event.t), event) }
                    comparisons += GroundTruthComparison(
                        t = event.t,
                        nodeName = event.nodeName,
                        liveErrorMeters = event.errorMeters,
                        legacyErrorMeters = legacy.current?.let { errorAgainst(it, event) },
                        particleErrorMeters = if (particleErrors.any { it == null }) null else particleErrors.filterNotNull().average(),
                    )
                }

                else -> Unit
            }
        }

        return Result(
            buildingId = start.buildingId,
            steps = steps,
            legacyCorrections = legacy.corrections,
            particleCorrections = particleCorrections.average().toInt(),
            comparisons = comparisons,
        )
    }

    /** The pre-particle-filter pipeline, step for step as NavigationSession wired it. */
    private class LegacyPipeline(start: SessionStartEvent) {
        private val nodesById = start.map.floors.flatMap { it.nodes }.associateBy { it.id }
        private val startFloor = start.map.floors.first { floor -> floor.nodes.any { it.id == start.startNodeId } }.floorIndex
        private val fingerprinted = nodesById.values.filter { it.fingerprint.isNotEmpty() }
        private val integrator = nodesById.getValue(start.startNodeId).let { PdrIntegrator(startX = it.x, startY = it.y, floor = startFloor) }

        var current: Position? = null
            private set
        var corrections = 0
            private set

        fun onStep(step: StepEvent) {
            current = integrator.integrate(step).copy(source = PositionSource.FUSED)
        }

        fun onBeaconReadings(readings: List<BleBeaconReading>, t: Long) {
            val match = FingerprintMatcher.bestMatch(readings, fingerprinted) ?: return
            // FingerprintPositioningEngine labeled every match with the *starting* floor.
            val correction = Position(
                x = match.node.x,
                y = match.node.y,
                floor = startFloor,
                headingDeg = 0.0,
                accuracyMeters = FingerprintPositioningEngine.ASSUMED_ACCURACY_METERS,
                source = PositionSource.RADIO_FINGERPRINT,
                timestampMs = t,
            )
            val fused = PositionFusionEngine.blend(current, correction)
            integrator.resetTo(fused.x, fused.y, fused.headingDeg, fused.accuracyMeters)
            current = fused
            corrections++
        }

        fun changeFloor(targetNodeId: String, targetFloor: Int) {
            val node: MapNode = nodesById[targetNodeId] ?: return
            integrator.changeFloor(targetFloor, node.x, node.y, current?.headingDeg ?: 0.0)
        }
    }

    private fun errorAgainst(estimate: Position, truth: GroundTruthEvent): Double? =
        if (estimate.floor != truth.floor) null else hypot(estimate.x - truth.nodeX, estimate.y - truth.nodeY)

    private fun toReading(event: RssiEvent) = BleBeaconReading(
        beaconId = event.beaconId,
        macAddress = "",
        deviceName = null,
        uuid = null,
        major = null,
        minor = null,
        rssi = event.rssi,
        txPowerAtOneMeter = null,
        estimatedDistanceMeters = 0.0,
        timestampMs = event.t,
    )

    private fun signedDelta(fromDeg: Double, toDeg: Double): Double = ((toDeg - fromDeg + 540.0) % 360.0) - 180.0

    private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()
}
