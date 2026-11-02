package com.appvalence.umay.positioning.engine

import com.appvalence.umay.coremodel.Position
import com.appvalence.umay.coremodel.PositionSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlin.math.sqrt

/**
 * Complementary filter fusing sparse corrective fixes (UWB ranging, RSSI
 * fingerprint matches, ...) with continuous PDR updates into a single
 * `Flow<Position>` (FR-3). Every PDR update is passed through as-is (it
 * already dead-reckons from the last correction). Every correction is
 * blended with the current estimate by inverse-variance weighting, and the
 * result is pushed back into the PDR integrator via [pdrResettable] so it
 * keeps dead-reckoning from the corrected point instead of drifting further.
 */
class PositionFusionEngine(
    private val pdrResettable: Resettable,
) {
    private var current: Position? = null

    fun fuse(correctionUpdates: Flow<Position>, pdrUpdates: Flow<Position>): Flow<Position> =
        merge(
            correctionUpdates.map { Reading.Correction(it) },
            pdrUpdates.map { Reading.Pdr(it) },
        ).map { reading ->
            val fused = when (reading) {
                is Reading.Pdr -> reading.position.copy(source = PositionSource.FUSED)
                is Reading.Correction -> blend(current, reading.position).also {
                    pdrResettable.resetTo(it.x, it.y, it.headingDeg, it.accuracyMeters)
                }
            }
            current = fused
            fused
        }

    private sealed interface Reading {
        data class Correction(val position: Position) : Reading
        data class Pdr(val position: Position) : Reading
    }

    companion object {
        // Shared with FieldLogReplay, which replays this pipeline synchronously.
        internal fun blend(previous: Position?, correction: Position): Position {
            if (previous == null) return correction.copy(source = PositionSource.FUSED)

            val pdrVariance = previous.accuracyMeters * previous.accuracyMeters
            val correctionVariance = correction.accuracyMeters * correction.accuracyMeters
            val correctionWeight = pdrVariance / (pdrVariance + correctionVariance)

            return Position(
                x = previous.x * (1 - correctionWeight) + correction.x * correctionWeight,
                y = previous.y * (1 - correctionWeight) + correction.y * correctionWeight,
                floor = correction.floor,
                headingDeg = previous.headingDeg,
                accuracyMeters = sqrt(1.0 / (1.0 / pdrVariance + 1.0 / correctionVariance)),
                source = PositionSource.FUSED,
                timestampMs = correction.timestampMs,
            )
        }
    }
}
