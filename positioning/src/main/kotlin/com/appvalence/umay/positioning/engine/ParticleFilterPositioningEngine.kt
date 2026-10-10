package com.appvalence.umay.positioning.engine

import com.appvalence.umay.coremodel.BleBeaconReading
import com.appvalence.umay.coremodel.BuildingMap
import com.appvalence.umay.coremodel.MarkerSighting
import com.appvalence.umay.coremodel.Position
import com.appvalence.umay.coremodel.PositionSource
import com.appvalence.umay.coremodel.StepEvent
import com.appvalence.umay.positioning.particle.GraphParticleFilter
import com.appvalence.umay.positioning.pdr.PdrIntegrator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge

/**
 * Feeds footsteps and beacon sightings into a [GraphParticleFilter] and emits
 * its estimate after every step and every fingerprint match it applied —
 * the same cadence [PositionFusionEngine] had, so step-counting downstream
 * (NavigationOrchestrator's auto floor change) sees one position per step.
 *
 * Alongside the estimate, each [Update] carries the raw dead-reckoning
 * position from an integrator that is never corrected: field logs record it
 * as the PDR stream, and offline replay rebuilds the step sequence from its
 * heading changes, which a correction would otherwise make jump.
 */
class ParticleFilterPositioningEngine(
    private val stepEvents: Flow<StepEvent>,
    private val beaconReadings: Flow<List<BleBeaconReading>>,
    map: BuildingMap,
    startNodeId: String,
    private val config: GraphParticleFilter.Config = GraphParticleFilter.Config(),
    /** Place markers the camera sees (see GraphParticleFilter.onMarker); none without a camera. */
    private val markerSightings: Flow<MarkerSighting> = emptyFlow(),
) : PositioningEngine {

    data class Update(
        val position: Position,
        /** Set on footsteps: uncorrected dead reckoning, for the field log. */
        val rawPdr: Position? = null,
        /** Set when a fingerprint match was applied: the matched place, as a position. */
        val fingerprintFix: Position? = null,
        /** Set when a place marker was seen and applied. */
        val marker: MarkerSighting? = null,
    )

    private val filter = GraphParticleFilter(map, startNodeId, config)
    private val rawPdr = map.floors.flatMap { it.nodes }.first { it.id == startNodeId }.let { start ->
        PdrIntegrator(startX = start.x, startY = start.y, floor = filter.floor)
    }

    // merge() delivers on one collector, but changeFloor() arrives from the barometer's
    // or the button's own coroutine.
    private val lock = Any()

    override fun positionUpdates(): Flow<Position> = updates().map { it.position }

    fun updates(): Flow<Update> =
        merge(
            stepEvents.map { Input.Step(it) },
            beaconReadings.map { Input.Scan(it) },
            markerSightings.map { Input.Marker(it) },
        ).mapNotNull { input ->
            synchronized(lock) {
                when (input) {
                    is Input.Step -> Update(position = filter.onStep(input.step), rawPdr = rawPdr.integrate(input.step))
                    is Input.Marker -> filter.onMarker(input.sighting.nodeId, input.sighting.distanceMeters, input.sighting.bearingDeg, input.sighting.timestampMs)
                        ?.let { Update(position = it, marker = input.sighting) }
                    is Input.Scan -> {
                        val now = input.readings.maxOfOrNull { it.timestampMs } ?: return@mapNotNull null
                        filter.onBeaconReadings(input.readings, now)?.let { correction ->
                            Update(
                                position = correction.estimate,
                                fingerprintFix = correction.estimate.copy(
                                    x = correction.node.x,
                                    y = correction.node.y,
                                    accuracyMeters = config.fingerprintSigmaMeters,
                                    source = PositionSource.RADIO_FINGERPRINT,
                                ),
                            )
                        }
                    }
                }
            }
        }

    /** A floor change confirmed by the barometer or the user: restart at [arrivalNodeId]. */
    fun changeFloor(arrivalNodeId: String, timestampMs: Long) {
        synchronized(lock) { filter.changeFloor(arrivalNodeId, timestampMs) }
    }

    private sealed interface Input {
        data class Step(val step: StepEvent) : Input
        data class Scan(val readings: List<BleBeaconReading>) : Input
        data class Marker(val sighting: MarkerSighting) : Input
    }
}
