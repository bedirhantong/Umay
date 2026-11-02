package com.appvalence.umay.positioning.engine

import com.appvalence.umay.coremodel.Position
import kotlinx.coroutines.flow.Flow

/**
 * Produces a continuous stream of fused indoor positions (RSSI fingerprint
 * corrections + PDR). Navigation uses ParticleFilterPositioningEngine; the
 * older PdrPositioningEngine + FingerprintPositioningEngine pair, combined by
 * PositionFusionEngine, remains as the baseline FieldLogReplay compares against.
 */
interface PositioningEngine {
    fun positionUpdates(): Flow<Position>
}
