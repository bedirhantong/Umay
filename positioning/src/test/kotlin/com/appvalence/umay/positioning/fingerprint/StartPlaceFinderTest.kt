package com.appvalence.umay.positioning.fingerprint

import com.appvalence.umay.coremodel.BleBeaconReading
import com.appvalence.umay.coremodel.MapNode
import com.appvalence.umay.coremodel.NodeType
import com.appvalence.umay.coremodel.RadioFingerprint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class StartPlaceFinderTest {

    private fun place(id: String, vararg rssi: Pair<String, Double>) =
        MapNode(id, 0.0, 0.0, NodeType.WAYPOINT, id, rssi.map { (beacon, value) -> RadioFingerprint(beacon, value) })

    private fun scan(vararg rssi: Pair<String, Int>) = rssi.map { (id, value) ->
        BleBeaconReading(id, "", null, "u", 1, 1, value, -59, 1.0, 0L)
    }

    private val entrance = place("giris", "b1" to -55.0, "b2" to -80.0)
    private val hall = place("hol", "b1" to -75.0, "b2" to -60.0)
    private val office = place("oda", "b3" to -60.0, "b4" to -65.0)
    private val finder = StartPlaceFinder(listOf(entrance, hall, office, MapNode("k", 1.0, 1.0, NodeType.CORRIDOR)))

    @Test
    fun `the closest signatures come first, places sharing no beacons don't come at all`() {
        // Between the entrance and the hall, nearer the entrance.
        val result = finder.update(scan("b1" to -62, "b2" to -72))

        assertEquals(listOf("giris", "hol"), result.nearbyIds)
        // Right by the entrance, the hall's signature is too different to be "near".
        assertEquals(listOf("giris"), finder.update(scan("b1" to -56, "b2" to -79)).nearbyIds)
    }

    @Test
    fun `a place is only suggested after several scans agree on it`() {
        val atEntrance = scan("b1" to -55, "b2" to -80)

        assertNull(finder.update(atEntrance).suggested)
        assertNull(finder.update(atEntrance).suggested)
        assertEquals("giris", finder.update(atEntrance).suggested?.id)
    }

    @Test
    fun `a scan that points elsewhere starts the count over`() {
        val atEntrance = scan("b1" to -55, "b2" to -80)
        finder.update(atEntrance)
        finder.update(atEntrance)

        finder.update(scan("b1" to -75, "b2" to -60))

        assertNull(finder.update(atEntrance).suggested)
    }

    @Test
    fun `a map surveyed without beacons can't find anything`() {
        assertFalse(StartPlaceFinder(listOf(MapNode("a", 0.0, 0.0, NodeType.WAYPOINT, "A"))).canFind)
    }
}
