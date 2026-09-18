package com.appvalence.umay.positioning

import com.appvalence.umay.coremodel.BleBeaconReading
import com.appvalence.umay.coremodel.BuildingMap
import com.appvalence.umay.coremodel.FloorMap
import com.appvalence.umay.coremodel.MapNode
import com.appvalence.umay.coremodel.NodeType
import com.appvalence.umay.coremodel.PositionSource
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BleTrilaterationPositioningEngineTest {

    private fun reading(beaconId: String, distanceMeters: Double) = BleBeaconReading(
        beaconId = beaconId, macAddress = "AA:BB", deviceName = null, uuid = "uuid", major = 1, minor = 1,
        rssi = -70, txPowerAtOneMeter = -59, estimatedDistanceMeters = distanceMeters, timestampMs = 0L,
    )

    private val buildingMap = BuildingMap(
        id = "test-building",
        floors = listOf(
            FloorMap(
                floorIndex = 0,
                nodes = listOf(
                    MapNode("anchor-1", 0.0, 0.0, NodeType.BLE_ANCHOR),
                    MapNode("anchor-2", 10.0, 0.0, NodeType.BLE_ANCHOR),
                    MapNode("anchor-3", 0.0, 10.0, NodeType.BLE_ANCHOR),
                ),
                edges = emptyList(),
            ),
        ),
    )

    @Test
    fun `emits a trilaterated position once 3 known anchors are visible`() = runTest {
        val targetX = 3.0
        val targetY = 4.0
        val readings = listOf(
            reading("anchor-1", hypot(targetX - 0.0, targetY - 0.0)),
            reading("anchor-2", hypot(targetX - 10.0, targetY - 0.0)),
            reading("anchor-3", hypot(targetX - 0.0, targetY - 10.0)),
        )
        val engine = BleTrilaterationPositioningEngine(flowOf(readings), buildingMap, floor = 0)

        val position = engine.positionUpdates().toList().single()

        assertTrue(abs(position.x - targetX) < 0.1)
        assertTrue(abs(position.y - targetY) < 0.1)
        assertEquals(PositionSource.BLE_TRILATERATION, position.source)
        assertEquals(0, position.floor)
    }

    @Test
    fun `emits nothing with fewer than 3 known anchors`() = runTest {
        val readings = listOf(reading("anchor-1", 3.0), reading("anchor-2", 4.0))
        val engine = BleTrilaterationPositioningEngine(flowOf(readings), buildingMap, floor = 0)

        assertTrue(engine.positionUpdates().toList().isEmpty())
    }

    @Test
    fun `ignores readings from beacons that are not in the building map`() = runTest {
        val readings = listOf(
            reading("anchor-1", 3.0),
            reading("anchor-2", 4.0),
            reading("unknown-beacon", 1.0), // not a surveyed anchor -> doesn't count toward the fix
        )
        val engine = BleTrilaterationPositioningEngine(flowOf(readings), buildingMap, floor = 0)

        assertTrue(engine.positionUpdates().toList().isEmpty())
    }
}
