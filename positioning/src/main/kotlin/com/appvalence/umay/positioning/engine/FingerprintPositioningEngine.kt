package com.appvalence.umay.positioning.engine

import com.appvalence.umay.coremodel.BleBeaconReading
import com.appvalence.umay.coremodel.MapNode
import com.appvalence.umay.coremodel.Position
import com.appvalence.umay.coremodel.PositionSource
import com.appvalence.umay.positioning.fingerprint.FingerprintMatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull

/**
 * Sparse corrective fix stream (FR-1 fallback where there's no UWB): matches
 * the live RSSI vector against named [MapNode]s' recorded fingerprints
 * instead of converting RSSI to a distance and trilaterating (see
 * [FingerprintMatcher]). Emits nothing when there's no confident match —
 * same "prefer visible not-sure over a bad guess" principle as
 * [com.appvalence.umay.positioning.ble.BleScanState]. Meant to feed
 * [PositionFusionEngine.fuse] as the correction stream alongside continuous
 * PDR updates.
 */
class FingerprintPositioningEngine(
    private val beaconReadings: Flow<List<BleBeaconReading>>,
    private val waypoints: List<MapNode>,
    private val floor: Int,
) : PositioningEngine {

    override fun positionUpdates(): Flow<Position> =
        beaconReadings.mapNotNull { readings ->
            val match = FingerprintMatcher.bestMatch(readings, waypoints) ?: return@mapNotNull null
            Position(
                x = match.node.x,
                y = match.node.y,
                floor = floor,
                headingDeg = 0.0,
                accuracyMeters = ASSUMED_ACCURACY_METERS,
                source = PositionSource.RADIO_FINGERPRINT,
                timestampMs = System.currentTimeMillis(),
            )
        }

    companion object {
        // A fingerprint match narrows you to "near this named place", not a
        // precise fix — this is deliberately coarser than UWB's ~0.3m.
        const val ASSUMED_ACCURACY_METERS = 3.0
    }
}
