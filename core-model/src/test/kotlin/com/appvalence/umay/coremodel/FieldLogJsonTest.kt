package com.appvalence.umay.coremodel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FieldLogJsonTest {

    private val map = BuildingMap(
        id = "lab",
        floors = listOf(FloorMap(0, listOf(MapNode("A", 0.0, 0.0, NodeType.WAYPOINT, name = "Giriş")), emptyList())),
    )

    @Test
    fun `every event type survives a round trip`() {
        val events = listOf(
            SessionStartEvent(
                t = 1L, appVersion = "1.0", device = "Pixel", androidSdk = 35, buildingId = "lab",
                startNodeId = "A", destinationNodeId = "A", constants = mapOf("STEP" to 0.75), map = map,
            ),
            PositionEvent(2L, PositionStream.PDR, 1.0, 2.0, 0, 90.0, 0.5, "PEDESTRIAN_DEAD_RECKONING"),
            RssiEvent(3L, "beacon-1", -70),
            GuidanceEvent(4L, "route-guide-1-4", "INFO", "10 metre ilerle"),
            StatusEvent(5L, "ON_ROUTE"),
            FloorChangeEvent(6L, FloorChangePhase.CONFIRMED_AUTOMATICALLY, "S1", 1),
            GroundTruthEvent(7L, "A", "Giriş", 0, 0.0, 0.0, 3.0, 4.0, 0, 5.0),
            SessionEndEvent(8L, "stopped"),
        )

        events.forEach { event -> assertEquals(event, FieldLogJson.decode(FieldLogJson.encode(event))) }
    }

    @Test
    fun `each event encodes to a single line tagged with its type`() {
        val line = FieldLogJson.encode(RssiEvent(3L, "beacon-1", -70))

        assertFalse(line.contains('\n'))
        assertTrue(line.contains("\"type\":\"rssi\""))
    }

    @Test
    fun `the session header records the schema version`() {
        val line = FieldLogJson.encode(
            SessionStartEvent(1L, appVersion = "1.0", device = "d", androidSdk = 35, buildingId = "lab",
                startNodeId = "A", destinationNodeId = "A", constants = emptyMap(), map = map),
        )

        assertTrue(line.contains("\"schemaVersion\":${FieldLogJson.SCHEMA_VERSION}"))
    }

    @Test
    fun `ground truth measures the straight-line error on the same floor`() {
        val node = MapNode("A", 0.0, 0.0, NodeType.WAYPOINT)
        val estimate = Position(3.0, 4.0, 0, 0.0, 1.0, PositionSource.FUSED, 10L)

        assertEquals(5.0, GroundTruthEvent.of(10L, node, nodeFloor = 0, estimate = estimate).errorMeters)
    }

    @Test
    fun `ground truth leaves the error empty when the estimate is on another floor`() {
        val node = MapNode("A", 0.0, 0.0, NodeType.WAYPOINT)
        val estimate = Position(0.0, 0.0, 1, 0.0, 1.0, PositionSource.FUSED, 10L)

        val event = GroundTruthEvent.of(10L, node, nodeFloor = 0, estimate = estimate)

        assertNull(event.errorMeters)
        assertEquals(1, event.estimatedFloor)
    }
}
