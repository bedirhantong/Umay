package com.appvalence.umay.positioning

import com.appvalence.umay.coremodel.Position
import com.appvalence.umay.coremodel.PositionSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlin.math.sqrt

/**
 * Complementary filter fusing sparse UWB fixes with continuous PDR updates
 * into a single `Flow<Position>` (FR-3). Every PDR update is passed through
 * as-is (it already dead-reckons from the last correction). Every UWB update
 * is blended with the current estimate by inverse-variance weighting, and the
 * result is pushed back into the PDR integrator via [pdrResettable] so it
 * keeps dead-reckoning from the corrected point instead of drifting further.
 */
class PositionFusionEngine(
    private val pdrResettable: Resettable,
) {
    private var current: Position? = null

    fun fuse(uwbUpdates: Flow<Position>, pdrUpdates: Flow<Position>): Flow<Position> =
        merge(
            uwbUpdates.map { Reading.Uwb(it) },
            pdrUpdates.map { Reading.Pdr(it) },
        ).map { reading ->
            val fused = when (reading) {
                is Reading.Pdr -> reading.position.copy(source = PositionSource.FUSED)
                is Reading.Uwb -> blend(current, reading.position).also {
                    pdrResettable.resetTo(it.x, it.y, it.headingDeg, it.accuracyMeters)
                }
            }
            current = fused
            fused
        }

    private fun blend(previous: Position?, uwb: Position): Position {
        if (previous == null) return uwb.copy(source = PositionSource.FUSED)

        val pdrVariance = previous.accuracyMeters * previous.accuracyMeters
        val uwbVariance = uwb.accuracyMeters * uwb.accuracyMeters
        val uwbWeight = pdrVariance / (pdrVariance + uwbVariance)

        return Position(
            x = previous.x * (1 - uwbWeight) + uwb.x * uwbWeight,
            y = previous.y * (1 - uwbWeight) + uwb.y * uwbWeight,
            floor = uwb.floor,
            headingDeg = previous.headingDeg,
            accuracyMeters = sqrt(1.0 / (1.0 / pdrVariance + 1.0 / uwbVariance)),
            source = PositionSource.FUSED,
            timestampMs = uwb.timestampMs,
        )
    }

    private sealed interface Reading {
        data class Uwb(val position: Position) : Reading
        data class Pdr(val position: Position) : Reading
    }
}
