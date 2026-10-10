package com.appvalence.umay.positioning.fingerprint

import com.appvalence.umay.coremodel.BleBeaconReading
import com.appvalence.umay.coremodel.MapNode

/**
 * Where a person about to start a walk probably is, from the beacons heard
 * while they choose: the few places whose recorded signature is closest (put
 * first in the "Bulunduğum yer" list), and — once one place has been the clear,
 * confident match ([FingerprintMatcher.bestMatch]) for [CONFIRMATIONS_NEEDED]
 * scans in a row — that place, to suggest outright. A single lucky scan never
 * suggests anything: a wrong start sends someone the wrong way from the first step.
 *
 * Feed it every scan with [update]; not thread-safe.
 */
class StartPlaceFinder(private val places: List<MapNode>) {
    data class Result(
        /** Ids of the places to list first, most likely first. */
        val nearbyIds: List<String>,
        /** A place confident enough to ask "are you here?" about. */
        val suggested: MapNode?,
    )

    private val fingerprinted = places.filter { it.fingerprint.isNotEmpty() }
    private var candidateId: String? = null
    private var confirmations = 0

    /** Whether there's anything to match against at all (a map surveyed without beacons has nothing). */
    val canFind: Boolean get() = fingerprinted.isNotEmpty()

    fun update(readings: List<BleBeaconReading>): Result {
        val nearby = FingerprintMatcher.rank(readings, fingerprinted)
            .filter { it.distance <= NEARBY_MAX_DISTANCE }
            .take(NEARBY_COUNT)
            .map { it.node.id }

        val best = FingerprintMatcher.bestMatch(readings, fingerprinted)?.node
        if (best == null) {
            confirmations = 0
            candidateId = null
        } else if (best.id == candidateId) {
            confirmations++
        } else {
            candidateId = best.id
            confirmations = 1
        }
        val suggested = best?.takeIf { confirmations >= CONFIRMATIONS_NEEDED }
        return Result(nearby, suggested)
    }

    companion object {
        const val NEARBY_COUNT = 3

        // Looser than a confident match: ordering a list only needs "roughly here".
        const val NEARBY_MAX_DISTANCE = FingerprintMatcher.MAX_MATCH_DISTANCE * 2

        // Scans arrive a few times a second; three agreeing ones is about a second of agreement.
        const val CONFIRMATIONS_NEEDED = 3
    }
}
