package com.appvalence.umay.positioning.trilateration

import com.appvalence.umay.coremodel.NodeType
import com.appvalence.umay.coremodel.TaggedWaypoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BeaconMapBuilderTest {

    @Test
    fun `creates one BLE_ANCHOR node per beacon`() {
        val results = listOf(
            BeaconSurveyResult("b1", floor = 0, x = 0.0, y = 0.0, observationCount = 5),
            BeaconSurveyResult("b2", floor = 0, x = 5.0, y = 0.0, observationCount = 5),
        )

        val map = BeaconMapBuilder.build("test-building", results)

        val floor = map.floors.single()
        assertEquals(2, floor.nodes.size)
        assertTrue(floor.nodes.all { it.type == NodeType.BLE_ANCHOR })
    }

    @Test
    fun `connects beacons within range but not beacons that are too far apart`() {
        val results = listOf(
            BeaconSurveyResult("near1", floor = 0, x = 0.0, y = 0.0, observationCount = 5),
            BeaconSurveyResult("near2", floor = 0, x = 5.0, y = 0.0, observationCount = 5),
            BeaconSurveyResult("far", floor = 0, x = 100.0, y = 0.0, observationCount = 5),
        )

        val map = BeaconMapBuilder.build("test-building", results, maxEdgeDistanceMeters = 10.0)

        val floor = map.floors.single()
        assertEquals(1, floor.edges.size)
        assertEquals(setOf("near1", "near2"), setOf(floor.edges.single().fromId, floor.edges.single().toId))
    }

    @Test
    fun `separates beacons into their own floor's FloorMap`() {
        val results = listOf(
            BeaconSurveyResult("ground", floor = 0, x = 0.0, y = 0.0, observationCount = 5),
            BeaconSurveyResult("upstairs", floor = 1, x = 0.0, y = 0.0, observationCount = 5),
        )

        val map = BeaconMapBuilder.build("test-building", results)

        assertEquals(listOf(0, 1), map.floors.map { it.floorIndex })
        assertEquals(0, map.floors.sumOf { it.edges.size }) // different floors are never connected
    }

    @Test
    fun `buildFromWaypoints connects tagged waypoints in tagging order, not proximity`() {
        val waypoints = listOf(
            TaggedWaypoint("Ana Giris", x = 0.0, y = 0.0, floor = 0, fingerprint = emptyList()),
            TaggedWaypoint("Koridor", x = 50.0, y = 0.0, floor = 0, fingerprint = emptyList()),
            TaggedWaypoint("203 Sinifi", x = 0.5, y = 0.5, floor = 0, fingerprint = emptyList()),
        )

        val map = BeaconMapBuilder.buildFromWaypoints("test-building", waypoints)

        val floor = map.floors.single()
        assertEquals(listOf("Ana Giris", "Koridor", "203 Sinifi"), floor.nodes.map { it.name })
        assertTrue(floor.nodes.all { it.type == NodeType.WAYPOINT })
        assertEquals(2, floor.edges.size)
        val ginisNode = floor.nodes[0]
        val koridorNode = floor.nodes[1]
        val sinifNode = floor.nodes[2]
        assertEquals(setOf(ginisNode.id, koridorNode.id), setOf(floor.edges[0].fromId, floor.edges[0].toId))
        assertEquals(setOf(koridorNode.id, sinifNode.id), setOf(floor.edges[1].fromId, floor.edges[1].toId))
    }

    @Test
    fun `buildFromWaypoints falls back to proximity-connected beacon anchors on a floor with no tagged waypoints`() {
        val results = listOf(
            BeaconSurveyResult("near1", floor = 0, x = 0.0, y = 0.0, observationCount = 5),
            BeaconSurveyResult("near2", floor = 0, x = 5.0, y = 0.0, observationCount = 5),
        )

        val map = BeaconMapBuilder.buildFromWaypoints("test-building", taggedWaypoints = emptyList(), beaconResults = results)

        val floor = map.floors.single()
        assertTrue(floor.nodes.all { it.type == NodeType.BLE_ANCHOR })
        assertEquals(1, floor.edges.size)
    }

    @Test
    fun `buildFromWaypoints attaches beacon anchors as unconnected nodes on a waypoint floor`() {
        val waypoints = listOf(
            TaggedWaypoint("Ana Giris", x = 0.0, y = 0.0, floor = 0, fingerprint = emptyList()),
            TaggedWaypoint("Koridor", x = 50.0, y = 0.0, floor = 0, fingerprint = emptyList()),
        )
        val results = listOf(BeaconSurveyResult("b1", floor = 0, x = 25.0, y = 0.0, observationCount = 5))

        val map = BeaconMapBuilder.buildFromWaypoints("test-building", waypoints, results)

        val floor = map.floors.single()
        assertEquals(3, floor.nodes.size)
        assertEquals(1, floor.nodes.count { it.type == NodeType.BLE_ANCHOR })
        assertEquals(1, floor.edges.size) // only the two waypoints are connected, the anchor stays unconnected
    }

    @Test
    fun `buildFromWaypoints turns a consecutive cross-floor tag into a FloorTransition`() {
        val waypoints = listOf(
            TaggedWaypoint("Merdiven Giris", x = 0.0, y = 0.0, floor = 0, fingerprint = emptyList()),
            TaggedWaypoint("Merdiven Cikis", x = 1.0, y = 1.0, floor = 1, fingerprint = emptyList()),
            TaggedWaypoint("Ofis", x = 10.0, y = 10.0, floor = 1, fingerprint = emptyList()),
        )

        val map = BeaconMapBuilder.buildFromWaypoints("test-building", waypoints)

        assertEquals(1, map.floorTransitions.size)
        val transition = map.floorTransitions.single()
        assertEquals(0, transition.fromFloorIndex)
        assertEquals(1, transition.toFloorIndex)
        val floor0Node = map.floors.single { it.floorIndex == 0 }.nodes.single()
        val floor1Nodes = map.floors.single { it.floorIndex == 1 }.nodes
        assertEquals(floor0Node.id, transition.fromNodeId)
        assertEquals(floor1Nodes.first { it.name == "Merdiven Cikis" }.id, transition.toNodeId)
        // The transition is a separate edge, not folded into either floor's own edge list.
        assertEquals(0, map.floors.single { it.floorIndex == 0 }.edges.size)
        assertEquals(1, map.floors.single { it.floorIndex == 1 }.edges.size)
    }

    @Test
    fun `buildFromWaypoints produces no transitions when every tag stays on the same floor`() {
        val waypoints = listOf(
            TaggedWaypoint("A", x = 0.0, y = 0.0, floor = 0, fingerprint = emptyList()),
            TaggedWaypoint("B", x = 1.0, y = 1.0, floor = 0, fingerprint = emptyList()),
        )

        val map = BeaconMapBuilder.buildFromWaypoints("test-building", waypoints)

        assertTrue(map.floorTransitions.isEmpty())
    }
}
