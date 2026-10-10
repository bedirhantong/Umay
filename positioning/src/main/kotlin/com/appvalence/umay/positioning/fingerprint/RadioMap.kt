package com.appvalence.umay.positioning.fingerprint

import com.appvalence.umay.coremodel.BleBeaconReading
import com.appvalence.umay.coremodel.Position
import com.appvalence.umay.coremodel.RadioFingerprint
import com.appvalence.umay.coremodel.RadioSample
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max

/**
 * A floor's [RadioSample]s, for asking "what would the beacons sound like
 * here?" at any point near the surveyed path: the recorded signatures within
 * [radiusMeters], weighted by inverse distance. Samples are bucketed in a grid
 * of that size, so a lookup only looks at the nine buckets around the point.
 */
class RadioMap(samples: List<RadioSample>, private val radiusMeters: Double = DEFAULT_RADIUS_METERS) {
    private val buckets: Map<Long, List<RadioSample>> = samples.groupBy { key(bucketOf(it.x), bucketOf(it.y)) }

    /** Every beacon the survey heard on this floor. */
    val beaconIds: Set<String> = samples.flatMap { it.fingerprint }.map { it.beaconId }.toSet()

    val isEmpty: Boolean get() = buckets.isEmpty()

    /** Expected RSSI per beacon at ([x], [y]); null when no sample is close enough to say. */
    fun expectedAt(x: Double, y: Double): Map<String, Double>? {
        val column = bucketOf(x)
        val row = bucketOf(y)
        val sums = HashMap<String, Double>()
        var totalWeight = 0.0
        for (dc in -1..1) {
            for (dr in -1..1) {
                for (sample in buckets[key(column + dc, row + dr)].orEmpty()) {
                    val distance = hypot(sample.x - x, sample.y - y)
                    if (distance > radiusMeters) continue
                    // Inverse distance, but never infinite: standing on a sample is "very close".
                    val weight = 1.0 / max(distance, MIN_DISTANCE_METERS)
                    totalWeight += weight
                    // A beacon this sample didn't hear counts as heard at the floor of hearing.
                    val heard = sample.fingerprint.associate { it.beaconId to it.rssi }
                    for (beacon in beaconIds) sums[beacon] = (sums[beacon] ?: 0.0) + weight * (heard[beacon] ?: FingerprintMatcher.MISSING_BEACON_RSSI)
                }
            }
        }
        if (totalWeight == 0.0) return null
        return sums.mapValues { (_, sum) -> sum / totalWeight }
    }

    /**
     * How well [live] fits what's expected at ([x], [y]), as the mean per-beacon
     * Gaussian log-likelihood (so many beacons don't make one scan overconfident);
     * null when nothing near is known. Only beacons this floor's survey heard count.
     */
    fun logLikelihood(live: Map<String, Double>, x: Double, y: Double, sigmaDb: Double): Double? {
        val expected = expectedAt(x, y) ?: return null
        val relevant = beaconIds.filter { it in live || (expected[it] ?: FingerprintMatcher.MISSING_BEACON_RSSI) > FingerprintMatcher.MISSING_BEACON_RSSI + 1 }
        if (relevant.isEmpty()) return null
        val twoSigmaSquared = 2.0 * sigmaDb * sigmaDb
        return relevant.sumOf { beacon ->
            val diff = ((live[beacon] ?: FingerprintMatcher.MISSING_BEACON_RSSI) - (expected[beacon] ?: FingerprintMatcher.MISSING_BEACON_RSSI))
                .coerceIn(-MAX_DIFF_DB, MAX_DIFF_DB)
            -diff * diff / twoSigmaSquared
        } / relevant.size
    }

    private fun bucketOf(meters: Double): Int = floor(meters / radiusMeters).toInt()

    private fun key(column: Int, row: Int): Long = (column.toLong() shl 32) or (row.toLong() and 0xFFFFFFFFL)

    companion object {
        const val DEFAULT_RADIUS_METERS = 4.0
        private const val MIN_DISTANCE_METERS = 0.5

        // A missing beacon against a strong one shouldn't dominate everything else.
        private const val MAX_DIFF_DB = 30.0
    }
}

/**
 * Takes a [RadioSample] every [spacingMeters] along a survey walk, from the
 * beacons heard in the last [maxAgeMs] — the scanner keeps a beacon listed for
 * a while after it was last heard, and a walker has moved on by then. Keeps
 * the [maxBeacons] strongest, rounded to whole dB, so a long walk stays small.
 */
class RadioSampler(
    private val spacingMeters: Double = DEFAULT_SPACING_METERS,
    private val maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
    private val maxBeacons: Int = DEFAULT_MAX_BEACONS,
) {
    private val lastByFloor = HashMap<Int, Position>()

    /** A sample at [position], or null if it's too close to the last one on its floor or nothing was heard. */
    fun offer(position: Position, beacons: List<BleBeaconReading>): RadioSample? {
        val last = lastByFloor[position.floor]
        if (last != null && hypot(position.x - last.x, position.y - last.y) < spacingMeters) return null
        val fresh = beacons
            .filter { it.uuid != null && position.timestampMs - it.timestampMs <= maxAgeMs }
            .sortedByDescending { it.rssi }
            .take(maxBeacons)
        if (fresh.size < FingerprintMatcher.MIN_SHARED_BEACONS) return null
        lastByFloor[position.floor] = position
        return RadioSample(position.x, position.y, fresh.map { RadioFingerprint(it.beaconId, it.rssi.toDouble()) })
    }

    /** Forget where the last samples were, e.g. for a new walk. */
    fun reset() = lastByFloor.clear()

    companion object {
        const val DEFAULT_SPACING_METERS = 3.0
        const val DEFAULT_MAX_AGE_MS = 3_000L
        const val DEFAULT_MAX_BEACONS = 8
    }
}

/**
 * Keeps a building's radio maps within a share of what a map file may weigh
 * (the shared pool's document limit): while they're too big, every other
 * sample of the largest floor is dropped. Density halves; coverage stays.
 */
object RadioMapBudget {
    const val DEFAULT_MAX_ENCODED_CHARS = 200_000

    fun fit(samples: Map<Int, List<RadioSample>>, maxEncodedChars: Int = DEFAULT_MAX_ENCODED_CHARS): Map<Int, List<RadioSample>> {
        val fitted = samples.toMutableMap()
        while (fitted.values.sumOf { floor -> floor.sumOf(::estimatedChars) } > maxEncodedChars) {
            val (floor, largest) = fitted.maxByOrNull { (_, list) -> list.size } ?: break
            if (largest.size <= 1) {
                fitted.remove(floor)
            } else {
                fitted[floor] = largest.filterIndexed { i, _ -> i % 2 == 0 }
            }
        }
        return fitted
    }

    // {"x":12.34,"y":5.67,"fingerprint":[{"beaconId":"<uuid>-1-2","rssi":-61.0},…]}
    private fun estimatedChars(sample: RadioSample): Int = 50 + sample.fingerprint.sumOf { it.beaconId.length + 30 }
}
