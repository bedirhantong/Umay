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
    /**
     * CPU-heavy on a long survey (every BLE sighting, often tens of thousands of
     * them, is matched against the trajectory) — call it off the main thread.
     */
    fun solve(trajectory: List<Position>, readings: List<BleBeaconReading>): List<BeaconSurveyResult> {
        if (trajectory.isEmpty()) return emptyList()
        // Already chronological in practice (poses are appended as steps happen), in
        // which case this sort is a single linear pass; it's what makes the binary
        // search in nearestPose valid regardless.
        val poses = trajectory.sortedBy { it.timestampMs }

        return readings
            .filter { it.uuid != null && it.estimatedDistanceMeters >= 0 }
            .mapNotNull { reading ->
                val pose = nearestPose(poses, reading.timestampMs) ?: return@mapNotNull null
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

    // Binary search over time-sorted poses: a linear scan per reading made solving
    // O(readings × poses), which on a long survey froze the app for seconds when
    // recording stopped — longer the longer the walk.
    internal fun nearestPose(sortedPoses: List<Position>, timestampMs: Long): Position? {
        if (sortedPoses.isEmpty()) return null
        val index = sortedPoses.binarySearchBy(timestampMs) { it.timestampMs }
        if (index >= 0) return sortedPoses[index]
        val insertionPoint = -index - 1
        val before = sortedPoses.getOrNull(insertionPoint - 1)
        val after = sortedPoses.getOrNull(insertionPoint)
        return when {
            before == null -> after
            after == null -> before
            // Ties go to the earlier pose, like the linear scan this replaced.
            abs(after.timestampMs - timestampMs) < abs(before.timestampMs - timestampMs) -> after
            else -> before
        }
    }

    private data class MatchedObservation(val beaconId: String, val floor: Int, val anchorReading: Trilateration.AnchorReading)

    const val MIN_OBSERVATIONS_FOR_FIX = 3
    private const val MAX_POSE_MATCH_AGE_MS = 3_000L
}
