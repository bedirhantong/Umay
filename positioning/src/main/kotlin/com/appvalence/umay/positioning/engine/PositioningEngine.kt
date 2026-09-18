package com.appvalence.umay.positioning.engine

import com.appvalence.umay.coremodel.Position
import kotlinx.coroutines.flow.Flow

/**
 * Produces a continuous stream of fused indoor positions (RSSI fingerprint
 * corrections + PDR). Implementations: PdrPositioningEngine,
 * FingerprintPositioningEngine, and PositionFusionEngine combining them.
 */
interface PositioningEngine {
    fun positionUpdates(): Flow<Position>
}
