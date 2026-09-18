package com.appvalence.umay.positioning

import com.appvalence.umay.coremodel.Position
import kotlinx.coroutines.flow.Flow

/**
 * Produces a continuous stream of fused indoor positions (UWB ranging + PDR).
 * Implementations (Faz 2): UwbPositioningEngine, PdrPositioningEngine, FusedPositioningEngine.
 */
interface PositioningEngine {
    fun positionUpdates(): Flow<Position>
}
