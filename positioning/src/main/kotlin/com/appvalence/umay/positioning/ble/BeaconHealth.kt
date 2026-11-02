package com.appvalence.umay.positioning.ble

import com.appvalence.umay.coremodel.BleBeaconReading
import com.appvalence.umay.coremodel.FloorMap
import com.appvalence.umay.coremodel.MapNode
import com.appvalence.umay.coremodel.isPlace
import kotlin.math.hypot

/** What a check-up scan found out about one of a building's beacons. */
data class BeaconStatus(
    val beaconId: String,
    /** Null: not heard at all during the check. */
    val lastSeenMs: Long?,
    val rssi: Int?,
    /** From the beacon's Eddystone-TLM frames, if it sends them. */
    val batteryMillivolts: Int?,
) {
    val heard: Boolean get() = lastSeenMs != null
    val lowBattery: Boolean get() = batteryMillivolts != null && batteryMillivolts < BeaconHealthTracker.LOW_BATTERY_MILLIVOLTS
}

/**
 * A check-up of a building's beacons, run by someone walking past them: which
 * of the beacons the map relies on were heard and how strongly, which report a
 * low battery, and which iBeacons of the same family were heard that the map
 * doesn't know about (added since the survey). Battery comes from Eddystone-TLM
 * frames, which carry no beacon id: they're tied to an iBeacon by its radio
 * address. Not thread-safe.
 */
class BeaconHealthTracker(expectedIds: Collection<String>) {
    private val expected = expectedIds.toSet()

    // iBeacon ids start with the UUID: the family a building's beacons share.
    private val uuids = expected.map { it.substringBeforeLast('-').substringBeforeLast('-') }.toSet()
    private val lastSeen = HashMap<String, BleBeaconReading>()
    private val unknownHeard = HashMap<String, BleBeaconReading>()
    private val idByAddress = HashMap<String, String>()
    private val batteryByAddress = HashMap<String, Int>()

    fun onReading(reading: BleBeaconReading) {
        if (reading.uuid != null) {
            if (reading.macAddress.isNotEmpty()) idByAddress[reading.macAddress] = reading.beaconId
            when {
                reading.beaconId in expected -> lastSeen[reading.beaconId] = reading
                reading.uuid in uuids -> unknownHeard[reading.beaconId] = reading
            }
        }
        val battery = reading.batteryMillivolts
        if (battery != null && reading.macAddress.isNotEmpty()) batteryByAddress[reading.macAddress] = battery
    }

    /** Every beacon the map relies on: the unheard ones first, then the weakest battery first. */
    fun statuses(): List<BeaconStatus> = expected.map { id -> statusOf(id, lastSeen[id]) }
        .sortedWith(compareBy<BeaconStatus> { it.heard }.thenBy { it.batteryMillivolts ?: Int.MAX_VALUE }.thenBy { it.beaconId })

    /** Beacons of the building's family that the map has never heard of. */
    fun unknownBeacons(): List<BeaconStatus> = unknownHeard.map { (id, reading) -> statusOf(id, reading) }.sortedBy { it.beaconId }

    private fun statusOf(id: String, reading: BleBeaconReading?): BeaconStatus {
        val address = reading?.macAddress ?: idByAddress.entries.firstOrNull { it.value == id }?.key
        return BeaconStatus(id, reading?.timestampMs, reading?.rssi, address?.let { batteryByAddress[it] })
    }

    companion object {
        /** A 3 V lithium cell (the usual beacon battery) is nearly spent below this. */
        const val LOW_BATTERY_MILLIVOLTS = 2_500
    }
}

/** A stretch of the surveyed path where positioning has little to go on, near [nearestPlace]. */
data class WeakCoverage(val x: Double, val y: Double, val beaconsHeard: Int, val nearestPlace: MapNode?)

/**
 * Where a floor needs another beacon, from the signatures recorded along its
 * surveyed path (FloorMap.radioMap): spots where fewer than [GOOD_BEACON_COUNT]
 * beacons were heard, and stretches with no usable signature at all (the survey
 * only records a sample when at least two are heard). Nearby findings merge into one.
 */
object CoverageCheck {
    const val GOOD_BEACON_COUNT = 3

    // Consecutive samples are a few metres apart; a jump this big means none could be taken between them.
    private const val GAP_METERS = 8.0
    private const val MERGE_METERS = 6.0

    /** Null when the floor has no recorded signatures (surveyed before they were recorded). */
    fun weakSpots(floor: FloorMap): List<WeakCoverage>? {
        val samples = floor.radioMap
        if (samples.isEmpty()) return null
        val findings = mutableListOf<Triple<Double, Double, Int>>()
        samples.forEach { if (it.fingerprint.size < GOOD_BEACON_COUNT) findings += Triple(it.x, it.y, it.fingerprint.size) }
        samples.zipWithNext().forEach { (a, b) ->
            if (hypot(b.x - a.x, b.y - a.y) > GAP_METERS) findings += Triple((a.x + b.x) / 2, (a.y + b.y) / 2, 0)
        }
        val places = floor.nodes.filter { it.isPlace }
        val merged = mutableListOf<Triple<Double, Double, Int>>()
        for (finding in findings.sortedBy { it.third }) {
            if (merged.none { hypot(it.first - finding.first, it.second - finding.second) < MERGE_METERS }) merged += finding
        }
        return merged.map { (x, y, heard) ->
            WeakCoverage(x, y, heard, places.minByOrNull { hypot(it.x - x, it.y - y) })
        }
    }
}
