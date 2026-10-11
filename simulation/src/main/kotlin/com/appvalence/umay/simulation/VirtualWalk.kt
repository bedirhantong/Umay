package com.appvalence.umay.simulation

import com.appvalence.umay.coremodel.BuildingMap
import com.appvalence.umay.coremodel.Position
import com.appvalence.umay.coremodel.StepEvent
import com.appvalence.umay.perception.QrMarkerReader
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
    /** Places with a printed marker on the wall (see MarkerSheet), read when the camera faces one. */
    val markerNodeIds: Set<String> = emptySet(),
    /** Fingerprints at named places too (FingerprintMatcher), not just the radio map along corridors. */
    val placeFingerprints: Boolean = true,
    /**
     * Markers read from rendered camera frames ([VirtualCamera]) by the app's own reader,
     * hung on the wall nearest each marked place, rather than seen perfectly within
     * MARKER_RANGE_METERS.
     */
    val camera: Boolean = false,
    /** The phone hands the filter its beacon list every this many steps (~1 s at normal pace). */
    val scanEverySteps: Int = 2,
)

/** A marker is read when it's this close, within the camera's view and not behind a wall. */
private const val MARKER_RANGE_METERS = 4.0
private const val CAMERA_HALF_FOV_DEG = 30.0
private const val MARKER_DISTANCE_NOISE_FRACTION = 0.1
private const val MARKER_BEARING_NOISE_DEG = 3.0

/** Beyond this a rendered marker is too small to read (see MarkerRangeTest), so no frame is rendered. */
private const val CAMERA_LOOK_METERS = 2.5

/** Particles drawn per frame in the viewer: enough to show the cloud's shape, few enough to keep the page small. */
private const val PARTICLES_PER_FRAME = 60

internal data class WalkResult(
    val errorsMeters: List<Double>,
    /** For the viewer; empty unless the walk was recorded. */
    val frames: List<WalkFrame> = emptyList(),
    val map: BuildingMap? = null,
) {
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
 * The phone in the walker's hand: turns their true movement into what the
 * app senses — a footstep with the gyro's own (offset, drifting, noisy) view
 * of the heading, the scanner's beacon list, markers the camera reads — and
 * runs the app's positioning filter on it. [random] is the world's noise and
 * is shared with the walker, so a seed replays the same walk.
 */
internal class VirtualPhone(
    private val building: VirtualBuilding,
    val map: BuildingMap,
    startNodeId: String,
    private val walker: WalkerModel,
    private val aids: Aids,
    config: GraphParticleFilter.Config,
    private val random: Random,
    filterRandom: Random,
    phone: PhoneRadio,
) {
    val filter = GraphParticleFilter(map, startNodeId, config, filterRandom)
    private val scanner = VirtualScanner(building, random, phone)
    private val camera = if (aids.camera) VirtualCamera(building, VirtualCamera.placeMarkers(building), random = Random(random.nextLong())) else null
    private val markerReader = QrMarkerReader()
    private val gyroOffsetDeg = random.nextDouble(0.0, 360.0)
    private var gyroHeadingDeg = 0.0
    private var steps = 0

    var timeMs = 1_000L
        private set

    /** Camera frames rendered, and markers read from them (see [Aids.camera]). */
    var framesRendered = 0
        private set
    var markersRead = 0
        private set
    var estimate: Position = filter.estimate(timeMs)
        private set

    /** One footstep that left the walker at ([x], [y]) facing [headingDeg] (map frame); returns what happened. */
    fun step(x: Double, y: Double, headingDeg: Double): List<String> {
        val notes = mutableListOf<String>()
        // The gyro sees the true heading change, in its own frame, plus noise and drift.
        val sensed = headingDeg - gyroOffsetDeg + walker.gyroDriftPerStepDeg * steps + random.gaussian() * walker.headingNoiseDeg
        val delta = ((sensed - gyroHeadingDeg + 540.0) % 360.0) - 180.0
        gyroHeadingDeg += delta
        timeMs += walker.stepIntervalMs
        steps++
        estimate = filter.onStep(StepEvent(walker.calibratedStrideMeters, delta, timeMs))

        if (aids.beacons) {
            // Advertisements arrive all the time; the filter looks about once a second.
            val sightings = scanner.hear(x, y, timeMs)
            if (steps % aids.scanEverySteps == 0) {
                filter.onBeaconReadings(sightings, timeMs)?.let { notes += "Parmak izi eşleşmesi: ${it.node.name}" }
                estimate = filter.estimate(timeMs)
            }
        }
        if (camera != null) {
            // Rendering is the costly part: only when a marker is close enough to have a chance.
            if (camera.markersInView(x, y, headingDeg, maxMeters = CAMERA_LOOK_METERS).isNotEmpty()) {
                framesRendered++
                for (sighting in markerReader.read(camera.render(x, y, headingDeg), camera.width, camera.height, timeMs)) {
                    markersRead++
                    val seen = filter.onMarker(sighting.nodeId, sighting.distanceMeters, sighting.bearingDeg, timeMs) ?: continue
                    estimate = seen
                    notes += "Kamera yer işaretini okudu: ${building.nodesById[sighting.nodeId]?.name}, %.1f m".format(sighting.distanceMeters)
                }
            }
            return notes
        }
        for (markerId in aids.markerNodeIds) {
            val marker = building.nodesById.getValue(markerId)
            val distance = hypot(marker.x - x, marker.y - y)
            if (distance > MARKER_RANGE_METERS) continue
            val toMarker = Math.toDegrees(atan2(marker.x - x, marker.y - y))
            val relative = ((toMarker - headingDeg + 540.0) % 360.0) - 180.0
            if (abs(relative) > CAMERA_HALF_FOV_DEG || !building.lineOfSight(x, y, marker.x, marker.y)) continue
            val seen = filter.onMarker(
                nodeId = markerId,
                distanceMeters = distance * (1.0 + random.gaussian() * MARKER_DISTANCE_NOISE_FRACTION),
                bearingDeg = relative + random.gaussian() * MARKER_BEARING_NOISE_DEG,
                timestampMs = timeMs,
            )
            if (seen != null) {
                estimate = seen
                notes += "Yer işareti okundu: ${marker.name}, %.1f m".format(distance)
            }
        }
        return notes
    }

    /** This moment as the viewer shows it. */
    fun frame(
        truth: Point,
        notes: List<String> = emptyList(),
        speech: List<String> = emptyList(),
        route: List<String>? = null,
        targetNodeId: String? = null,
    ) = WalkFrame(
        tMs = timeMs,
        floor = estimate.floor,
        truth = truth,
        estimate = Point(estimate.x, estimate.y),
        accuracyMeters = estimate.accuracyMeters,
        headingDeg = estimate.headingDeg,
        particles = filter.particlePositions(PARTICLES_PER_FRAME),
        speech = speech,
        notes = notes,
        route = route,
        targetNodeId = targetNodeId,
    )
}

/**
 * Walks [route] (place ids, each next to the previous by a corridor) through
 * [building], feeding a [GraphParticleFilter] what the phone would sense, and
 * returns how far its estimate was from the truth after every step. The walk
 * is fixed in advance: nothing the app says changes it (see
 * [simulateGuidedWalk] for a walker who follows the app).
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
    record: Boolean = false,
): WalkResult {
    val random = Random(seed)
    val map = building.surveyedMap(Random(seed + 1_000), withRadio = aids.beacons, phone = phone, placeFingerprints = aids.beacons && aids.placeFingerprints)
    val device = VirtualPhone(building, map, filterStartNodeId, walker, aids, config, random, Random(seed + 2_000), phone)

    val errors = mutableListOf<Double>()
    val frames = mutableListOf<WalkFrame>()
    val waypoints = route.map(building.nodesById::getValue)
    if (record) frames += device.frame(Point(waypoints.first().x, waypoints.first().y))

    for ((from, to) in waypoints.zipWithNext()) {
        val legLength = hypot(to.x - from.x, to.y - from.y)
        val bearing = Math.toDegrees(atan2(to.x - from.x, to.y - from.y))
        var along = 0.0
        while (along < legLength) {
            val stride = walker.trueStrideMeters * (1.0 + random.gaussian() * walker.strideNoiseFraction)
            along = minOf(legLength, along + stride)
            val x = from.x + (to.x - from.x) * along / legLength
            val y = from.y + (to.y - from.y) * along / legLength
            val notes = device.step(x, y, bearing)
            errors += hypot(device.estimate.x - x, device.estimate.y - y)
            if (record) frames += device.frame(Point(x, y), notes)
        }
    }
    return WalkResult(errors, frames, map)
}

/** The same walk with [seeds] different random draws, so one lucky run can't pass a scenario. */
internal fun simulateWalks(seeds: Int, walk: (seed: Int) -> WalkResult): List<WalkResult> = (1..seeds).map(walk)
