package com.appvalence.umay.positioning.engine

import com.appvalence.umay.coremodel.BleBeaconReading
import com.appvalence.umay.coremodel.MapNode
import com.appvalence.umay.coremodel.NodeType
import com.appvalence.umay.coremodel.PositionSource
import com.appvalence.umay.coremodel.RadioFingerprint
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

class FingerprintPositioningEngineTest {

    @Test
    fun `emits a position at the matched waypoint when a confident fingerprint match is found`() = runTest {
        val waypoint = MapNode(
            id = "wp1",
            x = 12.0,
            y = 4.0,
            type = NodeType.WAYPOINT,
            name = "Ana Giris",
            fingerprint = listOf(RadioFingerprint("b1", -60.0), RadioFingerprint("b2", -75.0)),
        )
        val readings = flowOf(listOf(reading("b1", -60), reading("b2", -75)))
        val engine = FingerprintPositioningEngine(readings, listOf(waypoint), floor = 2)

        val result = engine.positionUpdates().toList()

        assertEquals(1, result.size)
        assertEquals(12.0, result[0].x)
        assertEquals(4.0, result[0].y)
        assertEquals(2, result[0].floor)
        assertEquals(PositionSource.RADIO_FINGERPRINT, result[0].source)
    }

    @Test
    fun `emits nothing when no waypoint fingerprint confidently matches`() = runTest {
        val waypoint = MapNode(
            id = "wp1",
            x = 12.0,
            y = 4.0,
            type = NodeType.WAYPOINT,
            name = "Ana Giris",
            fingerprint = listOf(RadioFingerprint("b1", -60.0), RadioFingerprint("b2", -75.0)),
        )
        val readings = flowOf(listOf(reading("b1", 0), reading("b2", 0)))
        val engine = FingerprintPositioningEngine(readings, listOf(waypoint), floor = 0)

        val result = engine.positionUpdates().toList()

        assertTrue(result.isEmpty())
    }
}
