package com.appvalence.umay.positioning.engine

import com.appvalence.umay.coremodel.Position
import com.appvalence.umay.coremodel.PositionSource
import com.appvalence.umay.positioning.trilateration.Trilateration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Trilaterates a [Position] (FR-1) from the freshest reading per anchor seen
 * within [readingTimeoutMs], using each anchor's surveyed map coordinates.
 * Emits nothing until at least [MIN_ANCHORS_FOR_FIX] anchors have a fresh
 * reading. Heading is not observable from ranging alone, so it is always 0;
 * [PositionFusionEngine] retains the PDR-derived heading instead.
 */
class UwbPositioningEngine(
    private val rangingSource: UwbRangingSource,
    private val anchorMapPositions: Map<String, Pair<Double, Double>>,
    private val floor: Int = 0,
    private val readingTimeoutMs: Long = DEFAULT_READING_TIMEOUT_MS,
) : PositioningEngine {

    override fun positionUpdates(): Flow<Position> = flow {
        val latestReadingByAnchor = mutableMapOf<String, Pair<Long, Trilateration.AnchorReading>>()

        rangingSource.rangeReadings().collect { reading ->
            val anchorPosition = anchorMapPositions[reading.anchorId] ?: return@collect
            latestReadingByAnchor[reading.anchorId] = reading.timestampMs to
                Trilateration.AnchorReading(anchorPosition.first, anchorPosition.second, reading.distanceMeters)

            val now = reading.timestampMs
            val freshReadings = latestReadingByAnchor.values
                .filter { (timestampMs, _) -> now - timestampMs <= readingTimeoutMs }
                .map { it.second }

            if (freshReadings.size >= MIN_ANCHORS_FOR_FIX) {
                Trilateration.solve(freshReadings)?.let { solution ->
                    emit(
                        Position(
                            x = solution.x,
                            y = solution.y,
                            floor = floor,
                            headingDeg = 0.0,
                            accuracyMeters = ASSUMED_ACCURACY_METERS,
                            source = PositionSource.UWB_TRILATERATION,
                            timestampMs = now,
                        ),
                    )
                }
            }
        }
    }

    companion object {
        const val MIN_ANCHORS_FOR_FIX = 3
        const val DEFAULT_READING_TIMEOUT_MS = 2_000L
        // NFR-2 target with line of sight; will be replaced by a per-fix residual
        // once real anchor data is available to calibrate one.
        const val ASSUMED_ACCURACY_METERS = 0.3
    }
}
