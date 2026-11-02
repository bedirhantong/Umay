package com.appvalence.umay.positioning.fingerprint

import com.appvalence.umay.coremodel.BleBeaconReading
import com.appvalence.umay.coremodel.MapNode
import com.appvalence.umay.coremodel.RadioFingerprint
import kotlin.math.sqrt

/**
 * Matches a live RSSI vector against the RSSI fingerprints recorded on named
 * [MapNode]s, instead of converting RSSI to a distance and trilaterating
 * (RSSI-to-meters is too noisy in real buildings for that to be trustworthy —
 * multipath, body shadowing, furniture). This is standard RF-engineering
 * practice for indoor radio positioning (radio-map / fingerprint matching).
 *
 * A beacon present on one side and absent on the other is penalized with
 * [MISSING_BEACON_RSSI] rather than excluded, so a candidate missing several
 * beacons the live reading sees (or vice versa) scores worse, as it should.
 */
object FingerprintMatcher {
    data class Match(val node: MapNode, val distance: Double)

    /** Every candidate sharing enough beacons with [live], closest signature first. */
    fun rank(live: List<BleBeaconReading>, candidates: List<MapNode>): List<Match> {
        if (live.isEmpty() || candidates.isEmpty()) return emptyList()
        val liveRssi = live.associate { it.beaconId to it.rssi.toDouble() }
        return candidates
            .filter { node ->
                val sharedBeaconIds = node.fingerprint.map { it.beaconId }.toSet().intersect(liveRssi.keys)
                sharedBeaconIds.size >= MIN_SHARED_BEACONS
            }
            .map { node -> Match(node, distanceScore(liveRssi, node.fingerprint)) }
            .sortedBy { it.distance }
    }

    fun bestMatch(live: List<BleBeaconReading>, candidates: List<MapNode>): Match? {
        val scored = rank(live, candidates).map { it.node to it.distance }

        val best = scored.getOrNull(0) ?: return null
        if (best.second > MAX_MATCH_DISTANCE) return null

        // Two candidates that look nearly as plausible as each other are exactly the
        // case where guessing does more harm than staying silent: a confident snap to
        // the wrong nearby place is indistinguishable from a correct one until the
        // user notices they're being walked the wrong way. Require the runner-up to
        // be meaningfully worse before trusting the winner — small test spaces with
        // few beacons are exactly where two waypoints look almost identical.
        val runnerUp = scored.getOrNull(1)
        if (runnerUp != null && (runnerUp.second - best.second) < AMBIGUITY_MARGIN) return null

        return Match(best.first, best.second)
    }

    private fun distanceScore(liveRssi: Map<String, Double>, fingerprint: List<RadioFingerprint>): Double {
        val fingerprintRssi = fingerprint.associate { it.beaconId to it.rssi }
        val allBeaconIds = liveRssi.keys + fingerprintRssi.keys
        val sumSquares = allBeaconIds.sumOf { beaconId ->
            val diff = (liveRssi[beaconId] ?: MISSING_BEACON_RSSI) - (fingerprintRssi[beaconId] ?: MISSING_BEACON_RSSI)
            diff * diff
        }
        return sqrt(sumSquares)
    }

    const val MIN_SHARED_BEACONS = 2

    // Real BLE RSSI typically varies ~4-6dB even standing still (multipath/fading);
    // a raw 25dB-space tolerance was loose enough that almost any two-beacon reading
    // would "match" something. Tightened to reflect actual measurement noise instead.
    const val MAX_MATCH_DISTANCE = 12.0
    const val AMBIGUITY_MARGIN = 4.0
    const val MISSING_BEACON_RSSI = -100.0
}
