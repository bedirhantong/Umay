package com.appvalence.umay.positioning.fingerprint

import com.appvalence.umay.coremodel.BleBeaconReading
import com.appvalence.umay.coremodel.MapNode
import com.appvalence.umay.coremodel.NodeType
import com.appvalence.umay.coremodel.RadioFingerprint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private fun reading(beaconId: String, rssi: Int) = BleBeaconReading(
    beaconId = beaconId,
    macAddress = "00:00:00:00:00:00",
    deviceName = null,
    uuid = null,
    major = null,
    minor = null,
    rssi = rssi,
    txPowerAtOneMeter = null,
    estimatedDistanceMeters = -1.0,
    timestampMs = 0L,
)

private fun waypoint(name: String, fingerprint: List<RadioFingerprint>) = MapNode(
    id = name,
    x = 0.0,
    y = 0.0,
    type = NodeType.WAYPOINT,
    name = name,
    fingerprint = fingerprint,
)

class FingerprintMatcherTest {

    @Test
    fun `an exact RSSI match is chosen over a distant candidate`() {
        val live = listOf(reading("b1", -60), reading("b2", -75))
        val candidates = listOf(
            waypoint("Ana Giris", listOf(RadioFingerprint("b1", -60.0), RadioFingerprint("b2", -75.0))),
            waypoint("Uzak Oda", listOf(RadioFingerprint("b1", -90.0), RadioFingerprint("b2", -95.0))),
        )

        val match = FingerprintMatcher.bestMatch(live, candidates)

        assertEquals("Ana Giris", match?.node?.name)
    }

    @Test
    fun `a candidate with too few shared beacons is never matched`() {
        val live = listOf(reading("b1", -60))
        val candidates = listOf(waypoint("Tek Beacon", listOf(RadioFingerprint("b1", -60.0))))

        val match = FingerprintMatcher.bestMatch(live, candidates)

        assertNull(match)
    }

    @Test
    fun `a live reading with no fingerprinted candidates nearby returns no match`() {
        val live = listOf(reading("b1", -60), reading("b2", -75))
        val candidates = listOf(
            waypoint("Cok Farkli", listOf(RadioFingerprint("b1", 0.0), RadioFingerprint("b2", 0.0))),
        )

        val match = FingerprintMatcher.bestMatch(live, candidates)

        assertNull(match)
    }

    @Test
    fun `two candidates that score almost the same are too ambiguous to trust`() {
        val live = listOf(reading("b1", -60), reading("b2", -75))
        val candidates = listOf(
            waypoint("Oda A", listOf(RadioFingerprint("b1", -60.0), RadioFingerprint("b2", -75.0))),
            waypoint("Oda B", listOf(RadioFingerprint("b1", -61.0), RadioFingerprint("b2", -76.0))),
        )

        val match = FingerprintMatcher.bestMatch(live, candidates)

        assertNull(match)
    }

    @Test
    fun `no live readings or no candidates yields no match`() {
        val candidate = waypoint("Ana Giris", listOf(RadioFingerprint("b1", -60.0), RadioFingerprint("b2", -75.0)))

        assertNull(FingerprintMatcher.bestMatch(emptyList(), listOf(candidate)))
        assertNull(FingerprintMatcher.bestMatch(listOf(reading("b1", -60)), emptyList()))
    }
}
