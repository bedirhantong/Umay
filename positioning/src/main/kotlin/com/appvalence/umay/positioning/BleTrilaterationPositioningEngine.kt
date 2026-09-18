package com.appvalence.umay.positioning

import com.appvalence.umay.coremodel.BleBeaconReading
import com.appvalence.umay.coremodel.BuildingMap
import com.appvalence.umay.coremodel.NodeType
import com.appvalence.umay.coremodel.Position
import com.appvalence.umay.coremodel.PositionSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull

/**
 * Trilaterates a live [Position] (FR-1, NFR-6 BLE fallback) from a stream of
 * BLE beacon sightings (e.g. [BleBeaconScanner.beaconUpdates]) against a
 * [BuildingMap] whose [NodeType.BLE_ANCHOR] nodes have already been surveyed
 * (see [BeaconMapBuilder]/[BeaconSurveySolver]). Emits nothing until at
 * least [MIN_ANCHORS_FOR_FIX] known anchors are visible in the same reading.
 *
 * Takes the reading [Flow] rather than a [BleBeaconScanner] instance
 * directly so it has no dependency on the concrete Android scanner — it can
 * be driven by anything shaped like beacon sightings, including a fake in
 * tests. Heading is not observable from ranging alone, so it is always 0;
 * [PositionFusionEngine] retains the PDR-derived heading instead.
 */
class BleTrilaterationPositioningEngine(
    private val beaconReadings: Flow<List<BleBeaconReading>>,
    buildingMap: BuildingMap,
    private val floor: Int,
) : PositioningEngine {
    private val anchorPositionsById: Map<String, Pair<Double, Double>> = buildingMap.floors
        .firstOrNull { it.floorIndex == floor }
        ?.nodes
        ?.filter { it.type == NodeType.BLE_ANCHOR }
        ?.associate { it.id to (it.x to it.y) }
        ?: emptyMap()

    override fun positionUpdates(): Flow<Position> = beaconReadings.mapNotNull { readings ->
        val anchorReadings = readings.mapNotNull { reading ->
            if (reading.estimatedDistanceMeters < 0) return@mapNotNull null
            val anchor = anchorPositionsById[reading.beaconId] ?: return@mapNotNull null
            Trilateration.AnchorReading(anchor.first, anchor.second, reading.estimatedDistanceMeters)
        }
        if (anchorReadings.size < MIN_ANCHORS_FOR_FIX) return@mapNotNull null
        val solution = Trilateration.solve(anchorReadings) ?: return@mapNotNull null

        Position(
            x = solution.x,
            y = solution.y,
            floor = floor,
            headingDeg = 0.0,
            accuracyMeters = ASSUMED_ACCURACY_METERS,
            source = PositionSource.BLE_TRILATERATION,
            timestampMs = System.currentTimeMillis(),
        )
    }

    companion object {
        const val MIN_ANCHORS_FOR_FIX = 3
        // NFR-2 BLE-only fallback target (1-2m); will be replaced by a per-fix
        // residual once the site's anchors are calibrated with real distances.
        const val ASSUMED_ACCURACY_METERS = 2.0
    }
}
