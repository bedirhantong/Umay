package com.appvalence.umay.positioning.simulation

import com.appvalence.umay.coremodel.StepEvent
import com.appvalence.umay.positioning.particle.GraphParticleFilter
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.random.Random

/**
 * How the walker and their phone behave. The defaults are a careful but
 * ordinary day: a stride a little longer than calibrated, a few degrees of
 * gyro noise and slow drift, and a phone whose gyro frame has nothing to do
 * with the map's (the filter has to learn which way is which).
 */
internal data class WalkerModel(
    /** What the filter is told (the calibrated stride). */
    val calibratedStrideMeters: Double = 0.70,
    /** What the walker actually takes. */
    val trueStrideMeters: Double = 0.74,
    val strideNoiseFraction: Double = 0.08,
    val headingNoiseDeg: Double = 3.0,
    val gyroDriftPerStepDeg: Double = 0.05,
    val stepIntervalMs: Long = 550L,
)

/** What helps the filter along the way; everything off is steps alone. */
internal data class Aids(
    val beacons: Boolean = false,
    /** Places with a printed marker on the wall (see MarkerSheet). */
    val markerNodeIds: Set<String> = emptySet(),
    /** Fingerprints at named places too (FingerprintMatcher), not just the radio map along corridors. */
    val placeFingerprints: Boolean = true,
    /** The phone hands the filter its beacon list every this many steps (~1 s at normal pace). */
    val scanEverySteps: Int = 2,
)

/** A marker is read when it's this close and within the camera's view. */
private const val MARKER_RANGE_METERS = 4.0
private const val CAMERA_HALF_FOV_DEG = 30.0
private const val MARKER_DISTANCE_NOISE_FRACTION = 0.1
private const val MARKER_BEARING_NOISE_DEG = 3.0

internal data class WalkResult(val errorsMeters: List<Double>, val trace: List<String> = emptyList()) {
    /** Steps after the first few, while the filter is still learning the heading. */
    fun settled(warmupSteps: Int = WARMUP_STEPS) = errorsMeters.drop(warmupSteps)
    val mean: Double get() = settled().average()
    val p90: Double get() = settled().sorted().let { it[(it.size * 9) / 10] }
    val max: Double get() = settled().max()
    val final: Double get() = errorsMeters.last()

    companion object {
        const val WARMUP_STEPS = 12
    }
}

/**
 * Walks [route] (place ids, each next to the previous by a corridor) through
 * [building], feeding a [GraphParticleFilter] what the phone would sense, and
 * returns how far its estimate was from the truth after every step.
 * [filterStartNodeId] is where the filter is told the walk starts; give a
 * wrong one to test recovering from a mistaken start.
 */
internal fun simulateWalk(
    building: VirtualBuilding,
    route: List<String>,
    seed: Int,
    walker: WalkerModel = WalkerModel(),
    aids: Aids = Aids(),
    filterStartNodeId: String = route.first(),
    config: GraphParticleFilter.Config = GraphParticleFilter.Config(),
    phone: PhoneRadio = PhoneRadio(),
): WalkResult {
    val random = Random(seed)
    val map = building.surveyedMap(Random(seed + 1_000), withRadio = aids.beacons, phone = phone, placeFingerprints = aids.beacons && aids.placeFingerprints)
    val filter = GraphParticleFilter(map, filterStartNodeId, config, Random(seed + 2_000))

    val scanner = VirtualScanner(building, random, phone)
    val gyroOffsetDeg = random.nextDouble(0.0, 360.0)
    var gyroHeadingDeg = 0.0
    var t = 1_000L
    var steps = 0
    val errors = mutableListOf<Double>()
    val trace = mutableListOf<String>()
    val waypoints = route.map(building.nodesById::getValue)

    for ((from, to) in waypoints.zipWithNext()) {
        val legLength = hypot(to.x - from.x, to.y - from.y)
        val bearing = Math.toDegrees(atan2(to.x - from.x, to.y - from.y))
        var along = 0.0
        while (along < legLength) {
            val stride = walker.trueStrideMeters * (1.0 + random.gaussian() * walker.strideNoiseFraction)
            along = minOf(legLength, along + stride)
            val x = from.x + (to.x - from.x) * along / legLength
            val y = from.y + (to.y - from.y) * along / legLength

            // The gyro sees the true heading change, in its own frame, plus noise and drift.
            val sensed = bearing - gyroOffsetDeg + walker.gyroDriftPerStepDeg * steps + random.gaussian() * walker.headingNoiseDeg
            val delta = ((sensed - gyroHeadingDeg + 540.0) % 360.0) - 180.0
            gyroHeadingDeg += delta
            t += walker.stepIntervalMs
            steps++
            var estimate = filter.onStep(StepEvent(walker.calibratedStrideMeters, delta, t))

            if (aids.beacons) {
                // Advertisements arrive all the time; the filter looks about once a second.
                val sightings = scanner.hear(x, y, t)
                if (steps % aids.scanEverySteps == 0) {
                    filter.onBeaconReadings(sightings, t)
                    estimate = filter.estimate(t)
                }
            }
            for (markerId in aids.markerNodeIds) {
                val marker = building.nodesById.getValue(markerId)
                val distance = hypot(marker.x - x, marker.y - y)
                if (distance > MARKER_RANGE_METERS) continue
                val toMarker = Math.toDegrees(atan2(marker.x - x, marker.y - y))
                val relative = ((toMarker - bearing + 540.0) % 360.0) - 180.0
                if (abs(relative) > CAMERA_HALF_FOV_DEG) continue
                val seen = filter.onMarker(
                    nodeId = markerId,
                    distanceMeters = distance * (1.0 + random.gaussian() * MARKER_DISTANCE_NOISE_FRACTION),
                    bearingDeg = relative + random.gaussian() * MARKER_BEARING_NOISE_DEG,
                    timestampMs = t,
                )
                if (seen != null) estimate = seen
            }
            errors += hypot(estimate.x - x, estimate.y - y)
            trace += "%d true=(%.1f,%.1f) est=(%.1f,%.1f) acc=%.1f".format(steps, x, y, estimate.x, estimate.y, estimate.accuracyMeters)
        }
    }
    return WalkResult(errors, trace)
}

/** The same walk with [seeds] different random draws, so one lucky run can't pass a scenario. */
internal fun simulateWalks(seeds: Int, walk: (seed: Int) -> WalkResult): List<WalkResult> = (1..seeds).map(walk)
