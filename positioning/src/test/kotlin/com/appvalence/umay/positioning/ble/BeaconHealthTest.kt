package com.appvalence.umay.positioning.ble

import com.appvalence.umay.coremodel.BleBeaconReading
import com.appvalence.umay.coremodel.FloorMap
import com.appvalence.umay.coremodel.MapNode
import com.appvalence.umay.coremodel.NodeType
import com.appvalence.umay.coremodel.RadioFingerprint
import com.appvalence.umay.coremodel.RadioSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BeaconHealthTest {

    private val uuid = "f7826da6-4fa2-4e98-8024-bc5b71e0893e"

    private fun iBeacon(minor: Int, mac: String, rssi: Int = -70, t: Long = 1_000) =
        BleBeaconReading("$uuid-1-$minor", mac, null, uuid, 1, minor, rssi, -59, 2.0, t)

    private fun tlm(mac: String, millivolts: Int) = BleBeaconReading(mac, mac, null, null, null, null, -70, null, 0.0, 1_000, millivolts)

    @Test
    fun `unheard beacons come first, and a low battery is tied to its iBeacon by address`() {
        val tracker = BeaconHealthTracker(listOf("$uuid-1-1", "$uuid-1-2", "$uuid-1-3"))

        tracker.onReading(iBeacon(1, "AA:01"))
        tracker.onReading(iBeacon(2, "AA:02"))
        tracker.onReading(tlm("AA:02", 2_300))
        tracker.onReading(tlm("AA:01", 3_000))

        val statuses = tracker.statuses()
        assertEquals(listOf("$uuid-1-3", "$uuid-1-2", "$uuid-1-1"), statuses.map { it.beaconId })
        assertFalse(statuses[0].heard)
        assertTrue(statuses[1].lowBattery)
        assertEquals(3_000, statuses[2].batteryMillivolts)
    }

    @Test
    fun `a beacon of the building's family the map doesn't know is reported`() {
        val tracker = BeaconHealthTracker(listOf("$uuid-1-1"))

        tracker.onReading(iBeacon(9, "AA:09"))
        tracker.onReading(BleBeaconReading("other-1-1", "BB:01", null, "other", 1, 1, -60, -59, 1.0, 1_000))

        assertEquals(listOf("$uuid-1-9"), tracker.unknownBeacons().map { it.beaconId })
    }

    @Test
    fun `spots with too few beacons, and stretches with none, are found near a named place`() {
        fun sample(y: Double, beacons: Int) = RadioSample(0.0, y, (1..beacons).map { RadioFingerprint("b$it", -70.0) })
        val floor = FloorMap(
            0,
            listOf(MapNode("a", 0.0, 0.0, NodeType.WAYPOINT, "Giriş"), MapNode("b", 0.0, 30.0, NodeType.WAYPOINT, "Oda")),
            emptyList(),
            radioMap = listOf(sample(0.0, 4), sample(3.0, 4), sample(6.0, 2), sample(9.0, 4), sample(24.0, 4), sample(27.0, 4)),
        )

        val spots = CoverageCheck.weakSpots(floor)!!

        assertEquals(2, spots.size)
        assertEquals(0, spots[0].beaconsHeard) // the 9 m → 24 m stretch
        assertEquals(2, spots[1].beaconsHeard)
        assertEquals("Giriş", spots[1].nearestPlace?.name)
        assertNull(CoverageCheck.weakSpots(floor.copy(radioMap = emptyList())))
    }

    @Test
    fun `only a plain TLM frame carries a battery reading`() {
        assertEquals(2_950, EddystoneTlm.batteryMillivolts(byteArrayOf(0x20, 0x00, 0x0B, 0x86.toByte())))
        assertNull(EddystoneTlm.batteryMillivolts(byteArrayOf(0x10, 0x00, 0x0B, 0x86.toByte()))) // a URL frame
        assertNull(EddystoneTlm.batteryMillivolts(byteArrayOf(0x20, 0x00, 0x00, 0x00))) // "no reading"
    }
}
