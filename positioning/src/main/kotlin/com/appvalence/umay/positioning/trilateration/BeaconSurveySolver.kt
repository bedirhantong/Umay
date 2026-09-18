package com.appvalence.umay.positioning.trilateration

import com.appvalence.umay.coremodel.BleBeaconReading
import com.appvalence.umay.coremodel.Position
import kotlin.math.abs

data class BeaconSurveyResult(
    val beaconId: String,
    val floor: Int,
    val x: Double,
    val y: Double,
    val observationCount: Int,
)

/**
 * Turns a PDR-recorded walking trajectory plus the BLE beacon sightings seen
 * along the way into an estimated (x, y) for every beacon that was observed
 * from at least [MIN_OBSERVATIONS_FOR_FIX] trajectory poses in non-degenerate
 * (non-collinear) positions — see [Trilateration]. Each reading is matched to
 * the trajectory pose closest to it in time, since dead reckoning has no
 * other way to know where the phone was when a given BLE scan result arrived;
 * that pose's [Position.floor] is what tags the reading to a floor (floor
 * changes are marked manually while recording — see the "Kat değiştir"
 * control — since there is no barometer-based floor detection yet).
 *
 * Observations are bucketed by (beaconId, floor) *before* solving, never
 * pooled across floors: PDR has no vertical axis, so time spent on a
 * staircase or in an elevator gets folded into (x, y) as ordinary walking
 * displacement, and mixing that with a different floor's geometry would
 * silently corrupt both floors' beacon positions.
 *
 * This is deliberately *not* full graph-SLAM: it does not feed range
 * residuals back into the trajectory to correct PDR drift (see the graph
 * optimization literature this is based on — PMC6264008). It is one-way,
 * PDR-seeded trilateration. For a building floor (not kilometers of
 * corridor) accumulated PDR drift should stay small enough for a usable
 * first map; joint optimization with loop closures is future work if that
 * assumption doesn't hold up in the field.
 */
object BeaconSurveySolver {
    fun solve(trajectory: List<Position>, readings: List<BleBeaconReading>): List<BeaconSurveyResult> {
        if (trajectory.isEmpty()) return emptyList()

        return readings
            .filter { it.uuid != null && it.estimatedDistanceMeters >= 0 }
            .mapNotNull { reading ->
                val pose = nearestPose(trajectory, reading.timestampMs) ?: return@mapNotNull null
                if (abs(pose.timestampMs - reading.timestampMs) > MAX_POSE_MATCH_AGE_MS) return@mapNotNull null
                MatchedObservation(reading.beaconId, pose.floor, Trilateration.AnchorReading(pose.x, pose.y, reading.estimatedDistanceMeters))
            }
            .groupBy { it.beaconId to it.floor }
            .mapNotNull { (key, matches) ->
                val (beaconId, floor) = key
                val anchorReadings = matches.map { it.anchorReading }
                if (anchorReadings.size < MIN_OBSERVATIONS_FOR_FIX) return@mapNotNull null
                val solution = Trilateration.solve(anchorReadings) ?: return@mapNotNull null
                BeaconSurveyResult(beaconId, floor, solution.x, solution.y, anchorReadings.size)
            }
            .sortedWith(compareBy({ it.floor }, { it.beaconId }))
    }

    private fun nearestPose(trajectory: List<Position>, timestampMs: Long): Position? =
        trajectory.minByOrNull { abs(it.timestampMs - timestampMs) }

    private data class MatchedObservation(val beaconId: String, val floor: Int, val anchorReading: Trilateration.AnchorReading)

    const val MIN_OBSERVATIONS_FOR_FIX = 3
    private const val MAX_POSE_MATCH_AGE_MS = 3_000L
}
