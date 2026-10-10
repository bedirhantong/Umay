package com.appvalence.umay.positioning.trilateration

import com.appvalence.umay.coremodel.NodeType
import com.appvalence.umay.coremodel.Position
import com.appvalence.umay.coremodel.PositionSource
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
    fun `unnamed beacon nodes are named by the caller, numbered per floor`() {
        val results = listOf(
            BeaconSurveyResult("b1", floor = 2, x = 0.0, y = 0.0, observationCount = 5),
            BeaconSurveyResult("b2", floor = 2, x = 5.0, y = 0.0, observationCount = 5),
        )

        val map = BeaconMapBuilder.build("test-building", results, anchorName = { floor, number -> "F$floor-P$number" })

        assertEquals(listOf("F2-P1", "F2-P2"), map.floors.single().nodes.map { it.name })
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
    fun `buildFromWaypoints preserves a tagged hazard type instead of forcing WAYPOINT`() {
        val waypoints = listOf(
            TaggedWaypoint("Ana Giris", x = 0.0, y = 0.0, floor = 0, fingerprint = emptyList()),
            TaggedWaypoint("Merdiven", x = 1.0, y = 1.0, floor = 0, fingerprint = emptyList(), type = NodeType.STAIRS),
        )

        val map = BeaconMapBuilder.buildFromWaypoints("test-building", waypoints)

        val floor = map.floors.single()
        assertEquals(NodeType.WAYPOINT, floor.nodes.single { it.name == "Ana Giris" }.type)
        assertEquals(NodeType.STAIRS, floor.nodes.single { it.name == "Merdiven" }.type)
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

    private fun walked(vararg points: Pair<Double, Double>, floor: Int = 0) =
        points.mapIndexed { i, (x, y) -> Position(x, y, floor, 0.0, 0.1, PositionSource.MAP_MATCHED, i.toLong()) }

    @Test
    fun `a camera-surveyed walk round a corner adds the corner as an unnamed corridor node`() {
        // Tagged at the start and after turning the corner; the walk between them is an L.
        val trajectory = walked(0.0 to 0.0, 0.0 to 5.0, 0.0 to 10.0, 5.0 to 10.0, 10.0 to 10.0)
        val waypoints = listOf(
            TaggedWaypoint("Giriş", 0.0, 0.0, 0, emptyList(), trajectoryIndex = 1),
            TaggedWaypoint("Oda", 10.0, 10.0, 0, emptyList(), trajectoryIndex = 5),
        )

        val floor = BeaconMapBuilder.buildFromWaypoints("b", waypoints, trajectory = trajectory).floors.single()

        val corner = floor.nodes.single { it.type == NodeType.CORRIDOR }
        assertEquals(0.0, corner.x, 1e-9)
        assertEquals(10.0, corner.y, 1e-9)
        assertEquals(2, floor.edges.size)
        assertEquals(20.0, floor.edges.sumOf { it.distanceMeters }, 1e-9, "the edges follow the walk, not the diagonal")
    }

    @Test
    fun `waypoints without a trajectory index keep their straight edges`() {
        val trajectory = walked(0.0 to 0.0, 0.0 to 10.0, 10.0 to 10.0)
        val waypoints = listOf(
            TaggedWaypoint("Giriş", 0.0, 0.0, 0, emptyList()),
            TaggedWaypoint("Oda", 10.0, 10.0, 0, emptyList()),
        )

        val floor = BeaconMapBuilder.buildFromWaypoints("b", waypoints, trajectory = trajectory).floors.single()

        assertTrue(floor.nodes.none { it.type == NodeType.CORRIDOR })
        assertEquals(1, floor.edges.size)
    }

    private fun tag(name: String, x: Double, y: Double, floor: Int = 0, rssi: Double = -60.0, type: NodeType = NodeType.WAYPOINT) =
        TaggedWaypoint(name, x, y, floor, listOf(com.appvalence.umay.coremodel.RadioFingerprint("b1", rssi)), type)

    @Test
    fun `coming back to a place and naming it again joins the walk's branches there`() {
        // A T: walk from the entrance to the junction, down the left arm, back to the
        // junction (named again, a little off by dead reckoning), then down the right arm.
        val waypoints = listOf(
            tag("Giriş", 0.0, 0.0),
            tag("Kavşak", 0.0, 10.0, rssi = -60.0),
            tag("Sol oda", -10.0, 10.0),
            tag(" kavşak ", 0.8, 10.6, rssi = -70.0),
            tag("Sağ oda", 10.0, 10.0),
        )

        val floor = BeaconMapBuilder.buildFromWaypoints("b", waypoints).floors.single()

        assertEquals(4, floor.nodes.size)
        val junction = floor.nodes.single { it.name == "Kavşak" }
        assertEquals(0.4, junction.x, 1e-9)
        assertEquals(-65.0, junction.fingerprint.single().rssi, 1e-9)
        val junctionLinks = floor.edges.count { it.fromId == junction.id || it.toId == junction.id }
        assertEquals(3, junctionLinks)
        assertEquals(3, floor.edges.size)
    }

    @Test
    fun `two places either side of a trip to another floor are not connected`() {
        // Up the east stairs, along the upper corridor, down the west stairs.
        val waypoints = listOf(
            tag("Doğu merdiven alt", 0.0, 0.0, type = NodeType.STAIRS),
            tag("Doğu merdiven üst", 0.0, 0.0, floor = 1, type = NodeType.STAIRS),
            tag("Batı merdiven üst", 30.0, 0.0, floor = 1, type = NodeType.STAIRS),
            tag("Batı merdiven alt", 30.0, 0.0, type = NodeType.STAIRS),
        )

        val map = BeaconMapBuilder.buildFromWaypoints("b", waypoints)

        assertTrue(map.floors.single { it.floorIndex == 0 }.edges.isEmpty(), "a straight line from one staircase to the other through the ground floor")
        assertEquals(2, map.floorTransitions.size)
    }

    @Test
    fun `down the same stairs that were walked up is one crossing`() {
        val waypoints = listOf(
            tag("Merdiven alt", 0.0, 0.0, type = NodeType.STAIRS),
            tag("Merdiven üst", 0.0, 0.0, floor = 1, type = NodeType.STAIRS),
            tag("Oda", 5.0, 0.0, floor = 1),
            tag("Merdiven üst", 0.5, 0.0, floor = 1, type = NodeType.STAIRS),
            tag("Merdiven alt", 0.5, 0.0, type = NodeType.STAIRS),
        )

        val map = BeaconMapBuilder.buildFromWaypoints("b", waypoints)

        assertEquals(1, map.floorTransitions.size)
        assertEquals(2, map.floors.single { it.floorIndex == 1 }.nodes.size)
    }
}
