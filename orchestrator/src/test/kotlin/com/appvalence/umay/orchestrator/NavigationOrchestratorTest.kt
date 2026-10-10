package com.appvalence.umay.orchestrator

import com.appvalence.umay.coremodel.AlertPriority
import com.appvalence.umay.coremodel.BuildingMap
import com.appvalence.umay.coremodel.DetectedObstacle
import com.appvalence.umay.coremodel.FloorMap
import com.appvalence.umay.coremodel.FloorTransition
import com.appvalence.umay.coremodel.GuidanceCue
import com.appvalence.umay.coremodel.MapEdge
import com.appvalence.umay.coremodel.MapNode
import com.appvalence.umay.coremodel.NavigationAlert
import com.appvalence.umay.coremodel.NodeType
import com.appvalence.umay.coremodel.ObstacleKind
import com.appvalence.umay.coremodel.OccupancyRaster
import com.appvalence.umay.coremodel.ObstacleSensorSource
import com.appvalence.umay.coremodel.ObstacleType
import com.appvalence.umay.coremodel.Position
import com.appvalence.umay.coremodel.PositionSource
import com.appvalence.umay.feedback.FeedbackController
import com.appvalence.umay.perception.ObstacleDetector
import com.appvalence.umay.positioning.engine.PositioningEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakePositioningEngine(private val positions: List<Position>) : PositioningEngine {
    constructor(position: Position) : this(listOf(position))
    override fun positionUpdates(): Flow<Position> = flow { positions.forEach { emit(it) } }
}

private class FakeObstacleDetector(private val sightings: List<List<DetectedObstacle>>) : ObstacleDetector {
    override fun obstacleUpdates(): Flow<List<DetectedObstacle>> = flow { sightings.forEach { emit(it) } }
}

private class RecordingFeedbackController : FeedbackController {
    val emitted = mutableListOf<NavigationAlert>()
    override fun emit(alert: NavigationAlert) {
        emitted += alert
    }
}

private fun position(x: Double, y: Double, floor: Int = 0, headingDeg: Double = 0.0, timestampMs: Long = 0L) = Position(
    x = x, y = y, floor = floor, headingDeg = headingDeg, accuracyMeters = 0.3,
    source = PositionSource.FUSED, timestampMs = timestampMs,
)

private fun personObstacle(id: String, distanceMeters: Double, bearingDeg: Double = 0.0, detectedAtMs: Long = 0L) = DetectedObstacle(
    id = id, type = ObstacleType.PERSON, distanceMeters = distanceMeters, bearingDeg = bearingDeg,
    relativeVelocityMps = null, confidence = 0.9f, detectedAtMs = detectedAtMs, sensorSource = ObstacleSensorSource.CAMERA_ML,
)

/**
 * Language-free wording: tests assert what the orchestrator *decided* (which
 * place, how far, which turn, which floor-change method) — how that's said
 * in Turkish is the app's string resources' job, tested separately.
 */
private object TokenPhrases : GuidancePhrases {
    private fun meters(distance: Double) = "%.1f".format(java.util.Locale.ROOT, distance)
    override fun walkToward(placeName: String, distanceMeters: Double, thenTurn: TurnDirection?) =
        "WALK $placeName ${meters(distanceMeters)} ${thenTurn ?: "STRAIGHT"}"
    override fun walkAhead(distanceMeters: Double, thenTurn: TurnDirection?) =
        "AHEAD ${meters(distanceMeters)} ${thenTurn ?: "STRAIGHT"}"
    override fun followWall(side: TurnDirection) = "WALL $side"
    override fun approachDestination(distanceMeters: Double) = "APPROACH ${meters(distanceMeters)}"
    override fun changeFloor(method: FloorChangeMethod, goingUp: Boolean, targetFloor: Int, arrivalPlaceName: String) =
        "CHANGE_FLOOR $method ${if (goingUp) "UP" else "DOWN"} $targetFloor $arrivalPlaceName"
    override fun hazardAhead(type: NodeType) = if (type in setOf(NodeType.STAIRS, NodeType.DOOR, NodeType.RAMP, NodeType.ELEVATOR)) "HAZARD $type" else null
    override fun arrived() = "ARRIVED"
    override fun noRoute() = "NO_ROUTE"
    override fun rerouted() = "REROUTED"
    override fun noStepFreeRoute() = "NO_STEP_FREE_ROUTE"
    override fun offRoute() = "OFF_ROUTE"
    override fun wrongWay() = "WRONG_WAY"
    override fun obstacle(priority: AlertPriority, kind: ObstacleKind, distanceMeters: Double) =
        "OBSTACLE $priority $kind ${meters(distanceMeters)}"
}

class NavigationOrchestratorTest {

    private val emptyMap = BuildingMap(id = "test-building", floors = emptyList())

    // A single-node "route" (start == destination == "A"), used by tests that only
    // care about obstacle-alerting behavior — an empty map would also be valid there,
    // but now unconditionally raises its own "no route found" alert, which would
    // pollute the exact-alert-count assertions these tests make.
    private val singleNodeMap = BuildingMap(
        id = "test-building",
        floors = listOf(FloorMap(floorIndex = 0, nodes = listOf(MapNode("A", 0.0, 0.0, NodeType.CORRIDOR)), edges = emptyList())),
    )

    @Test
    fun `combines the latest position and obstacles into a NavigationState`() = runTest {
        val position = position(1.0, 2.0, timestampMs = 1_000L)
        val orchestrator = NavigationOrchestrator(
            positioning = FakePositioningEngine(position),
            perception = FakeObstacleDetector(listOf(emptyList())),
            map = emptyMap,
            feedback = RecordingFeedbackController(),
            startNodeId = "A",
            destinationNodeId = "B",
            phrases = TokenPhrases,
        )

        val state = orchestrator.start().toList().single()

        assertEquals(position, state.position)
        assertEquals(emptyList(), state.obstacles)
        assertEquals(emptyList(), state.route)
    }

    @Test
    fun `raises a CRITICAL alert for a very close obstacle`() = runTest {
        val feedback = RecordingFeedbackController()
        val orchestrator = NavigationOrchestrator(
            positioning = FakePositioningEngine(position(0.0, 0.0)),
            perception = FakeObstacleDetector(listOf(listOf(personObstacle("p1", distanceMeters = 0.5)))),
            map = singleNodeMap,
            feedback = feedback,
            startNodeId = "A",
            destinationNodeId = "A",
            phrases = TokenPhrases,
        )

        orchestrator.start().toList()

        assertEquals(1, feedback.emitted.size)
        assertEquals(AlertPriority.CRITICAL, feedback.emitted.single().priority)
    }

    @Test
    fun `does not repeat an unchanged-priority alert within the debounce window`() = runTest {
        val feedback = RecordingFeedbackController()
        val obstacleSightings = listOf(
            listOf(personObstacle("p1", distanceMeters = 0.5, detectedAtMs = 0L)),
            listOf(personObstacle("p1", distanceMeters = 0.5, detectedAtMs = 500L)), // within the 2.5s debounce
        )
        val orchestrator = NavigationOrchestrator(
            positioning = FakePositioningEngine(position(0.0, 0.0)),
            perception = FakeObstacleDetector(obstacleSightings),
            map = singleNodeMap,
            feedback = feedback,
            startNodeId = "A",
            destinationNodeId = "A",
            phrases = TokenPhrases,
        )

        orchestrator.start().toList()

        assertEquals(1, feedback.emitted.size)
    }

    @Test
    fun `re-alerts immediately on a priority escalation even inside the debounce window`() = runTest {
        val feedback = RecordingFeedbackController()
        val obstacleSightings = listOf(
            listOf(personObstacle("p1", distanceMeters = 3.0, detectedAtMs = 0L)), // WARNING
            listOf(personObstacle("p1", distanceMeters = 0.5, detectedAtMs = 200L)), // escalates to CRITICAL
        )
        val orchestrator = NavigationOrchestrator(
            positioning = FakePositioningEngine(position(0.0, 0.0)),
            perception = FakeObstacleDetector(obstacleSightings),
            map = singleNodeMap,
            feedback = feedback,
            startNodeId = "A",
            destinationNodeId = "A",
            phrases = TokenPhrases,
        )

        orchestrator.start().toList()

        assertEquals(2, feedback.emitted.size)
        assertEquals(AlertPriority.WARNING, feedback.emitted[0].priority)
        assertEquals(AlertPriority.CRITICAL, feedback.emitted[1].priority)
    }

    @Test
    fun `reroutes around an obstacle blocking the planned route and alerts INFO`() = runTest {
        // A --- B --- C   (direct path)
        //       |     |
        //       D-----+   (detour via D if B-C is blocked)
        val nodes = listOf(
            MapNode("A", 0.0, 0.0, NodeType.CORRIDOR),
            MapNode("B", 0.0, 10.0, NodeType.CORRIDOR),
            MapNode("C", 0.0, 20.0, NodeType.DESTINATION),
            MapNode("D", 10.0, 10.0, NodeType.CORRIDOR),
        )
        val edges = listOf(
            MapEdge("A", "B", accessible = true, distanceMeters = 10.0),
            MapEdge("B", "C", accessible = true, distanceMeters = 10.0),
            MapEdge("B", "D", accessible = true, distanceMeters = 10.0),
            MapEdge("D", "C", accessible = true, distanceMeters = 14.0),
        )
        val map = BuildingMap(id = "test-building", floors = listOf(FloorMap(floorIndex = 0, nodes = nodes, edges = edges)))
        val feedback = RecordingFeedbackController()

        // User is at B, facing toward C (north, heading 0), where a person is standing
        // directly on the B-C edge — far enough away to not itself be a CRITICAL/WARNING alert.
        val userPosition = position(0.0, 10.0, headingDeg = 0.0)
        val blockingObstacle = personObstacle("p1", distanceMeters = 5.0, bearingDeg = 0.0)

        val orchestrator = NavigationOrchestrator(
            positioning = FakePositioningEngine(userPosition),
            perception = FakeObstacleDetector(listOf(listOf(blockingObstacle))),
            map = map,
            feedback = feedback,
            startNodeId = "A",
            destinationNodeId = "C",
            phrases = TokenPhrases,
        )

        val states = orchestrator.start().toList()

        // Replanning starts from the nearest node to where the user actually is (B,
        // since they're already past A) — not from the original start node.
        assertEquals(listOf("B", "D", "C"), states.last().route.map { it.id })
        assertTrue(feedback.emitted.any { it.priority == AlertPriority.INFO })
    }

    @Test
    fun `announces the first leg instruction on the first position update`() = runTest {
        val nodes = listOf(
            MapNode("A", 0.0, 0.0, NodeType.CORRIDOR, name = "Giris"),
            MapNode("B", 0.0, 10.0, NodeType.CORRIDOR, name = "Koridor"),
            MapNode("C", 0.0, 20.0, NodeType.DESTINATION, name = "Hedef"),
        )
        val edges = listOf(
            MapEdge("A", "B", accessible = true, distanceMeters = 10.0),
            MapEdge("B", "C", accessible = true, distanceMeters = 10.0),
        )
        val map = BuildingMap(id = "test-building", floors = listOf(FloorMap(0, nodes, edges)))
        val feedback = RecordingFeedbackController()
        val orchestrator = NavigationOrchestrator(
            positioning = FakePositioningEngine(position(0.0, 0.0, timestampMs = 0L)),
            perception = FakeObstacleDetector(listOf(emptyList())),
            map = map,
            feedback = feedback,
            startNodeId = "A",
            destinationNodeId = "C",
            phrases = TokenPhrases,
        )

        orchestrator.start().toList()

        val guidance = feedback.emitted.single { it.id.startsWith("route-guide-") }
        assertTrue(guidance.message.contains("Koridor"))
        assertTrue(guidance.message.contains("10"))
    }

    @Test
    fun `includes a right turn when the route bends east after heading north`() = runTest {
        val nodes = listOf(
            MapNode("A", 0.0, 0.0, NodeType.CORRIDOR, name = "A"),
            MapNode("B", 0.0, 10.0, NodeType.CORRIDOR, name = "Donus"),
            MapNode("C", 10.0, 10.0, NodeType.DESTINATION, name = "C"),
        )
        val edges = listOf(
            MapEdge("A", "B", accessible = true, distanceMeters = 10.0),
            MapEdge("B", "C", accessible = true, distanceMeters = 10.0),
        )
        val map = BuildingMap(id = "test-building", floors = listOf(FloorMap(0, nodes, edges)))
        val feedback = RecordingFeedbackController()
        val orchestrator = NavigationOrchestrator(
            positioning = FakePositioningEngine(position(0.0, 0.0)),
            perception = FakeObstacleDetector(listOf(emptyList())),
            map = map,
            feedback = feedback,
            startNodeId = "A",
            destinationNodeId = "C",
            phrases = TokenPhrases,
        )

        orchestrator.start().toList()

        val guidance = feedback.emitted.single { it.id.startsWith("route-guide-") }
        assertTrue(guidance.message.endsWith("RIGHT"), guidance.message)
    }

    @Test
    fun `an unnamed corner is announced by distance and turn, never by its id`() = runTest {
        val nodes = listOf(
            MapNode("A", 0.0, 0.0, NodeType.WAYPOINT, name = "Giris"),
            MapNode("corner-uuid", 0.0, 10.0, NodeType.CORRIDOR),
            MapNode("C", 10.0, 10.0, NodeType.WAYPOINT, name = "Oda"),
        )
        val edges = listOf(
            MapEdge("A", "corner-uuid", accessible = true, distanceMeters = 10.0),
            MapEdge("corner-uuid", "C", accessible = true, distanceMeters = 10.0),
        )
        val map = BuildingMap(id = "test-building", floors = listOf(FloorMap(0, nodes, edges)))
        val feedback = RecordingFeedbackController()
        val orchestrator = NavigationOrchestrator(
            positioning = FakePositioningEngine(position(0.0, 0.0)),
            perception = FakeObstacleDetector(listOf(emptyList())),
            map = map,
            feedback = feedback,
            startNodeId = "A",
            destinationNodeId = "C",
            phrases = TokenPhrases,
        )

        orchestrator.start().toList()

        val guidance = feedback.emitted.single { it.id.startsWith("route-guide-") }
        assertEquals("AHEAD 10.0 RIGHT", guidance.message)
    }

    @Test
    fun `a wall along one side of the leg is mentioned after the instruction`() = runTest {
        // A 10 m northward corridor with a wall 1 m to its west, surveyed with the camera.
        val width = 40
        val height = 120
        val cells = ByteArray(width * height) { i -> if (i % width in 9..10) OccupancyRaster.OCCUPIED else OccupancyRaster.FREE }
        val walls = OccupancyRaster.of(0.1, 0.0, -1.0, width, height, cells)
        val nodes = listOf(
            MapNode("A", 2.0, 0.0, NodeType.WAYPOINT, name = "Giris"),
            MapNode("B", 2.0, 10.0, NodeType.WAYPOINT, name = "Oda"),
        )
        val map = BuildingMap(id = "test-building", floors = listOf(FloorMap(0, nodes, listOf(MapEdge("A", "B", true, 10.0)), occupancy = walls)))
        val feedback = RecordingFeedbackController()
        val orchestrator = NavigationOrchestrator(
            positioning = FakePositioningEngine(position(2.0, 0.0)),
            perception = FakeObstacleDetector(listOf(emptyList())),
            map = map,
            feedback = feedback,
            startNodeId = "A",
            destinationNodeId = "B",
            phrases = TokenPhrases,
        )

        orchestrator.start().toList()

        val guidance = feedback.emitted.single { it.id.startsWith("route-guide-") }
        assertTrue(guidance.message.endsWith("WALL LEFT"), guidance.message)
    }

    // A T: the route goes north from Giris to Oda; a side corridor leads west from the junction.
    private val tMap = BuildingMap(
        id = "test-building",
        floors = listOf(
            FloorMap(
                0,
                listOf(
                    MapNode("G", 0.0, 0.0, NodeType.WAYPOINT, name = "Giris"),
                    MapNode("K", 0.0, 10.0, NodeType.WAYPOINT, name = "Kavsak"),
                    MapNode("O", 0.0, 25.0, NodeType.WAYPOINT, name = "Oda"),
                    MapNode("Y", -15.0, 10.0, NodeType.WAYPOINT, name = "Yan"),
                ),
                listOf(MapEdge("G", "K", true, 10.0), MapEdge("K", "O", true, 15.0), MapEdge("K", "Y", true, 15.0)),
            ),
        ),
    )

    private suspend fun walk(positions: List<Position>): List<com.appvalence.umay.coremodel.NavigationAlert> {
        val feedback = RecordingFeedbackController()
        NavigationOrchestrator(
            positioning = FakePositioningEngine(positions),
            perception = FakeObstacleDetector(listOf(emptyList())),
            map = tMap,
            feedback = feedback,
            startNodeId = "G",
            destinationNodeId = "O",
            phrases = TokenPhrases,
        ).start().toList()
        return feedback.emitted
    }

    @Test
    fun `a wrong turn into the side corridor gets a new route from there`() = runTest {
        val north = (0..10).map { position(0.0, it.toDouble(), timestampMs = 1_000L * it) }
        val west = (1..10).map { position(-it.toDouble(), 10.0, timestampMs = 11_000L + 1_000L * it) }

        val alerts = walk(north + west)

        val offRoute = alerts.single { it.id.startsWith("off-route-") }
        assertEquals("OFF_ROUTE", offRoute.message)
        // …followed by guidance for the new route, starting back toward the junction.
        assertTrue(alerts.any { it.createdAtMs > offRoute.createdAtMs && it.message.startsWith("WALK Kavsak") }, alerts.map { it.message }.toString())
    }

    @Test
    fun `turning back along the route is told to turn around, without replanning`() = runTest {
        val north = (0..12).map { position(0.0, it * 0.7, timestampMs = 1_000L * it) }
        val back = (1..8).map { position(0.0, 8.4 - it * 0.7, timestampMs = 13_000L + 1_000L * it) }

        val alerts = walk(north + back)

        assertEquals(1, alerts.count { it.id.startsWith("wrong-way-") })
        assertTrue(alerts.none { it.id.startsWith("off-route-") })
    }

    @Test
    fun `walking the route raises neither`() = runTest {
        val alerts = walk((0..25).map { position(if (it % 2 == 0) 0.8 else -0.8, it.toDouble(), timestampMs = 1_000L * it) })

        assertTrue(alerts.none { it.id.startsWith("off-route-") || it.id.startsWith("wrong-way-") })
    }

    @Test
    fun `advances through the route and announces arrival exactly once`() = runTest {
        val nodes = listOf(
            MapNode("A", 0.0, 0.0, NodeType.CORRIDOR, name = "A"),
            MapNode("B", 0.0, 10.0, NodeType.CORRIDOR, name = "B"),
            MapNode("C", 0.0, 20.0, NodeType.DESTINATION, name = "C"),
        )
        val edges = listOf(
            MapEdge("A", "B", accessible = true, distanceMeters = 10.0),
            MapEdge("B", "C", accessible = true, distanceMeters = 10.0),
        )
        val map = BuildingMap(id = "test-building", floors = listOf(FloorMap(0, nodes, edges)))
        val feedback = RecordingFeedbackController()
        val positions = listOf(
            position(0.0, 0.0, timestampMs = 0L),
            position(0.0, 9.0, timestampMs = 1_000L), // within waypoint-arrival radius of B
            position(0.0, 19.5, timestampMs = 2_000L), // within arrival radius of destination C
            position(0.0, 19.9, timestampMs = 3_000L), // still near C, must not re-announce
        )
        val orchestrator = NavigationOrchestrator(
            positioning = FakePositioningEngine(positions),
            perception = FakeObstacleDetector(List(positions.size) { emptyList() }),
            map = map,
            feedback = feedback,
            startNodeId = "A",
            destinationNodeId = "C",
            phrases = TokenPhrases,
        )

        orchestrator.start().toList()

        val arrivals = feedback.emitted.filter { it.id.startsWith("arrived-") }
        assertEquals(1, arrivals.size)
        assertEquals("ARRIVED", arrivals.single().message)
    }

    @Test
    fun `nextRouteIndex tracks which route node the user is walking toward`() = runTest {
        val nodes = listOf(
            MapNode("A", 0.0, 0.0, NodeType.CORRIDOR, name = "A"),
            MapNode("B", 0.0, 10.0, NodeType.CORRIDOR, name = "B"),
            MapNode("C", 0.0, 20.0, NodeType.DESTINATION, name = "C"),
        )
        val edges = listOf(
            MapEdge("A", "B", accessible = true, distanceMeters = 10.0),
            MapEdge("B", "C", accessible = true, distanceMeters = 10.0),
        )
        val map = BuildingMap(id = "test-building", floors = listOf(FloorMap(0, nodes, edges)))
        val positions = listOf(
            position(0.0, 0.0, timestampMs = 0L),
            position(0.0, 9.0, timestampMs = 1_000L), // within waypoint-arrival radius of B
        )
        val orchestrator = NavigationOrchestrator(
            positioning = FakePositioningEngine(positions),
            perception = FakeObstacleDetector(List(positions.size) { emptyList() }),
            map = map,
            feedback = RecordingFeedbackController(),
            startNodeId = "A",
            destinationNodeId = "C",
            phrases = TokenPhrases,
        )

        val states = orchestrator.start().toList()

        assertEquals(1, states.first().nextRouteIndex)
        assertEquals(2, states.last().nextRouteIndex)
    }

    @Test
    fun `repeats the current leg instruction after the guidance repeat interval elapses`() = runTest {
        val nodes = listOf(
            MapNode("A", 0.0, 0.0, NodeType.CORRIDOR, name = "A"),
            MapNode("B", 0.0, 100.0, NodeType.DESTINATION, name = "B"),
        )
        val edges = listOf(MapEdge("A", "B", accessible = true, distanceMeters = 100.0))
        val map = BuildingMap(id = "test-building", floors = listOf(FloorMap(0, nodes, edges)))
        val feedback = RecordingFeedbackController()
        val positions = listOf(
            position(0.0, 0.0, timestampMs = 0L),
            position(0.0, 0.0, timestampMs = 1_000L), // inside the 8s repeat interval, no repeat
            position(0.0, 0.0, timestampMs = 9_000L), // past the repeat interval, repeats
        )
        val orchestrator = NavigationOrchestrator(
            positioning = FakePositioningEngine(positions),
            perception = FakeObstacleDetector(List(positions.size) { emptyList() }),
            map = map,
            feedback = feedback,
            startNodeId = "A",
            destinationNodeId = "B",
            phrases = TokenPhrases,
        )

        orchestrator.start().toList()

        val guidanceMessages = feedback.emitted.filter { it.id.startsWith("route-guide-") }
        assertEquals(2, guidanceMessages.size)
    }

    @Test
    fun `announces once when no route exists between start and destination`() = runTest {
        val nodes = listOf(
            MapNode("A", 0.0, 0.0, NodeType.CORRIDOR, name = "A"),
            MapNode("B", 100.0, 100.0, NodeType.DESTINATION, name = "B"),
        )
        val map = BuildingMap(id = "test-building", floors = listOf(FloorMap(0, nodes, edges = emptyList())))
        val feedback = RecordingFeedbackController()
        val positions = listOf(position(0.0, 0.0, timestampMs = 0L), position(0.0, 0.0, timestampMs = 1_000L))
        val orchestrator = NavigationOrchestrator(
            positioning = FakePositioningEngine(positions),
            perception = FakeObstacleDetector(List(positions.size) { emptyList() }),
            map = map,
            feedback = feedback,
            startNodeId = "A",
            destinationNodeId = "B",
            phrases = TokenPhrases,
        )

        orchestrator.start().toList()

        val noRouteAlerts = feedback.emitted.filter { it.id.startsWith("no-route-") }
        assertEquals(1, noRouteAlerts.size)
    }

    // Two-floor fixture shared by the multi-floor tests below:
    // Floor 0: Giris(A) --10m-- Merdiven Alt(B)
    // Floor 1: Merdiven Ust(C) --10m-- Ofis(D)
    // B and C are connected by a FloorTransition (stairs), cost 15.
    private val floor0 = FloorMap(
        floorIndex = 0,
        nodes = listOf(
            MapNode("A", 0.0, 0.0, NodeType.CORRIDOR, name = "Giris"),
            MapNode("B", 0.0, 10.0, NodeType.CORRIDOR, name = "Merdiven Alt"),
        ),
        edges = listOf(MapEdge("A", "B", accessible = true, distanceMeters = 10.0)),
    )
    private val floor1 = FloorMap(
        floorIndex = 1,
        nodes = listOf(
            MapNode("C", 0.0, 0.0, NodeType.CORRIDOR, name = "Merdiven Ust"),
            MapNode("D", 0.0, 10.0, NodeType.DESTINATION, name = "Ofis"),
        ),
        edges = listOf(MapEdge("C", "D", accessible = true, distanceMeters = 10.0)),
    )
    private val twoFloorMap = BuildingMap(
        id = "test-building",
        floors = listOf(floor0, floor1),
        floorTransitions = listOf(FloorTransition(fromNodeId = "B", fromFloorIndex = 0, toNodeId = "C", toFloorIndex = 1, costMeters = 15.0)),
    )

    @Test
    fun `a multi-floor route crosses via a FloorTransition`() = runTest {
        val feedback = RecordingFeedbackController()
        val orchestrator = NavigationOrchestrator(
            positioning = FakePositioningEngine(position(0.0, 0.0, floor = 0, timestampMs = 0L)),
            perception = FakeObstacleDetector(listOf(emptyList())),
            map = twoFloorMap,
            feedback = feedback,
            startNodeId = "A",
            destinationNodeId = "D",
            phrases = TokenPhrases,
        )

        val states = orchestrator.start().toList()

        assertEquals(listOf("A", "B", "C", "D"), states.last().route.map { it.id })
    }

    @Test
    fun `pendingFloorChange appears on reaching the transition, and the leg instruction says to change floors`() = runTest {
        val feedback = RecordingFeedbackController()
        val positions = listOf(
            position(0.0, 0.0, floor = 0, timestampMs = 0L),
            position(0.0, 9.0, floor = 0, timestampMs = 1_000L), // within arrival radius of B
        )
        val orchestrator = NavigationOrchestrator(
            positioning = FakePositioningEngine(positions),
            perception = FakeObstacleDetector(List(positions.size) { emptyList() }),
            map = twoFloorMap,
            feedback = feedback,
            startNodeId = "A",
            destinationNodeId = "D",
            phrases = TokenPhrases,
        )

        val states = orchestrator.start().toList()

        val hint = states.last().pendingFloorChange
        assertEquals("C", hint?.targetNodeId)
        assertEquals(1, hint?.targetFloor)
        val guidance = feedback.emitted.last { it.id.startsWith("route-guide-") }
        assertTrue(guidance.message.startsWith("CHANGE_FLOOR"), guidance.message)
    }

    @Test
    fun `confirming a floor change clears pendingFloorChange and the route continues`() = runTest {
        val feedback = RecordingFeedbackController()
        val positions = listOf(
            position(0.0, 0.0, floor = 0, timestampMs = 0L),
            position(0.0, 9.0, floor = 0, timestampMs = 1_000L), // reaches B, floor change pending
            position(0.0, 0.0, floor = 1, timestampMs = 2_000L), // "kat değiştirdim": reset onto C
        )
        val orchestrator = NavigationOrchestrator(
            positioning = FakePositioningEngine(positions),
            perception = FakeObstacleDetector(List(positions.size) { emptyList() }),
            map = twoFloorMap,
            feedback = feedback,
            startNodeId = "A",
            destinationNodeId = "D",
            phrases = TokenPhrases,
        )

        val states = orchestrator.start().toList()

        assertEquals(null, states.last().pendingFloorChange)
    }

    @Test
    fun `proximity never advances across a floor the user hasn't actually confirmed`() = runTest {
        val feedback = RecordingFeedbackController()
        val positions = listOf(
            position(0.0, 0.0, floor = 0, timestampMs = 0L),
            position(0.0, 9.0, floor = 0, timestampMs = 1_000L), // reaches B, floor change pending
            // Coincidentally lands on C's (x, y) but still reports floor 0 — must not
            // count as having reached C, or a stray PDR reading could silently skip
            // the floor confirmation the user never actually gave.
            position(0.0, 0.0, floor = 0, timestampMs = 2_000L),
        )
        val orchestrator = NavigationOrchestrator(
            positioning = FakePositioningEngine(positions),
            perception = FakeObstacleDetector(List(positions.size) { emptyList() }),
            map = twoFloorMap,
            feedback = feedback,
            startNodeId = "A",
            destinationNodeId = "D",
            phrases = TokenPhrases,
        )

        val states = orchestrator.start().toList()

        assertEquals("C", states.last().pendingFloorChange?.targetNodeId)
        assertEquals(listOf("A", "B", "C", "D"), states.last().route.map { it.id })
    }

    // Same layout as twoFloorMap, but the transition's endpoints are tagged STAIRS —
    // this is what the survey operator would actually do at a stairwell.
    private val stairsMap = BuildingMap(
        id = "test-building",
        floors = listOf(
            FloorMap(
                floorIndex = 0,
                nodes = listOf(
                    MapNode("A", 0.0, 0.0, NodeType.CORRIDOR, name = "Giris"),
                    MapNode("B", 0.0, 10.0, NodeType.STAIRS, name = "Merdiven Alt"),
                ),
                edges = listOf(MapEdge("A", "B", accessible = true, distanceMeters = 10.0)),
            ),
            FloorMap(
                floorIndex = 1,
                nodes = listOf(
                    MapNode("C", 0.0, 0.0, NodeType.STAIRS, name = "Merdiven Ust"),
                    MapNode("D", 0.0, 10.0, NodeType.DESTINATION, name = "Ofis"),
                ),
                edges = listOf(MapEdge("C", "D", accessible = true, distanceMeters = 10.0)),
            ),
        ),
        floorTransitions = listOf(FloorTransition(fromNodeId = "B", fromFloorIndex = 0, toNodeId = "C", toFloorIndex = 1, costMeters = 15.0)),
    )

    @Test
    fun `avoiding stairs with no other way says so once and still routes over the stairs`() = runTest {
        val feedback = RecordingFeedbackController()
        val positions = listOf(
            position(0.0, 0.0, floor = 0, timestampMs = 0L),
            position(0.0, 1.0, floor = 0, timestampMs = 1_000L),
        )
        val orchestrator = NavigationOrchestrator(
            positioning = FakePositioningEngine(positions),
            perception = FakeObstacleDetector(List(positions.size) { emptyList() }),
            map = stairsMap,
            feedback = feedback,
            startNodeId = "A",
            destinationNodeId = "D",
            phrases = TokenPhrases,
            avoidStairs = true,
        )

        val states = orchestrator.start().toList()

        assertEquals(listOf("A", "B", "C", "D"), states.last().route.map { it.id })
        assertEquals(1, feedback.emitted.count { it.message == "NO_STEP_FREE_ROUTE" })
        // Said before the first leg, so the walker hears it before heading for the stairs.
        assertEquals("NO_STEP_FREE_ROUTE", feedback.emitted.first().message)
    }

    @Test
    fun `avoiding stairs says nothing extra when the route has none`() = runTest {
        val feedback = RecordingFeedbackController()
        val orchestrator = NavigationOrchestrator(
            positioning = FakePositioningEngine(position(0.0, 0.0, floor = 0, timestampMs = 0L)),
            perception = FakeObstacleDetector(listOf(emptyList())),
            map = twoFloorMap,
            feedback = feedback,
            startNodeId = "A",
            destinationNodeId = "D",
            phrases = TokenPhrases,
            avoidStairs = true,
        )

        orchestrator.start().toList()

        assertTrue(feedback.emitted.none { it.message == "NO_STEP_FREE_ROUTE" })
    }

    @Test
    fun `approaching a STAIRS-tagged node gets a hazard prefix and WARNING priority`() = runTest {
        val feedback = RecordingFeedbackController()
        val orchestrator = NavigationOrchestrator(
            positioning = FakePositioningEngine(position(0.0, 0.0, floor = 0, timestampMs = 0L)),
            perception = FakeObstacleDetector(listOf(emptyList())),
            map = stairsMap,
            feedback = feedback,
            startNodeId = "A",
            destinationNodeId = "D",
            phrases = TokenPhrases,
        )

        orchestrator.start().toList()

        val guidance = feedback.emitted.first { it.id.startsWith("route-guide-") }
        assertTrue(guidance.message.startsWith("HAZARD STAIRS "), guidance.message)
        assertEquals(AlertPriority.WARNING, guidance.priority)
    }

    @Test
    fun `the floor-crossing instruction for a STAIRS target names the method as merdivenden`() = runTest {
        val feedback = RecordingFeedbackController()
        val positions = listOf(
            position(0.0, 0.0, floor = 0, timestampMs = 0L),
            position(0.0, 9.0, floor = 0, timestampMs = 1_000L), // reaches B
        )
        val orchestrator = NavigationOrchestrator(
            positioning = FakePositioningEngine(positions),
            perception = FakeObstacleDetector(List(positions.size) { emptyList() }),
            map = stairsMap,
            feedback = feedback,
            startNodeId = "A",
            destinationNodeId = "D",
            phrases = TokenPhrases,
        )

        orchestrator.start().toList()

        val guidance = feedback.emitted.last { it.id.startsWith("route-guide-") }
        assertTrue(guidance.message.startsWith("CHANGE_FLOOR STAIRS UP 1 "), guidance.message)
    }

    @Test
    fun `pendingFloorChange auto-confirms after enough steps on a STAIRS crossing`() = runTest {
        val feedback = RecordingFeedbackController()
        val positions = buildList {
            add(position(0.0, 0.0, floor = 0, timestampMs = 0L))
            add(position(0.0, 9.0, floor = 0, timestampMs = 1_000L)) // reaches B, pending
            // Footsteps while climbing (still reporting floor 0 — no barometer): enough
            // of them should flip autoConfirm without an explicit tap.
            repeat(NavigationOrchestrator.AUTO_FLOOR_CHANGE_STEP_COUNT) { i ->
                add(position(0.0, 9.0, floor = 0, timestampMs = 2_000L + i * 100L))
            }
        }
        val orchestrator = NavigationOrchestrator(
            positioning = FakePositioningEngine(positions),
            perception = FakeObstacleDetector(List(positions.size) { emptyList() }),
            map = stairsMap,
            feedback = feedback,
            startNodeId = "A",
            destinationNodeId = "D",
            phrases = TokenPhrases,
        )

        val states = orchestrator.start().toList()

        assertEquals(true, states.last().pendingFloorChange?.autoConfirm)
    }

    @Test
    fun `pendingFloorChange never auto-confirms on a non-STAIRS crossing regardless of step count`() = runTest {
        val feedback = RecordingFeedbackController()
        val positions = buildList {
            add(position(0.0, 0.0, floor = 0, timestampMs = 0L))
            add(position(0.0, 9.0, floor = 0, timestampMs = 1_000L))
            repeat(30) { i -> add(position(0.0, 9.0, floor = 0, timestampMs = 2_000L + i * 100L)) }
        }
        val orchestrator = NavigationOrchestrator(
            positioning = FakePositioningEngine(positions),
            perception = FakeObstacleDetector(List(positions.size) { emptyList() }),
            map = twoFloorMap, // transition endpoints are plain CORRIDOR here, not STAIRS
            feedback = feedback,
            startNodeId = "A",
            destinationNodeId = "D",
            phrases = TokenPhrases,
        )

        val states = orchestrator.start().toList()

        assertEquals(false, states.last().pendingFloorChange?.autoConfirm)
    }

    // --- Guidance cues (what vibration reacts to) and on-demand repeat ---

    private val rightTurnMap = BuildingMap(
        id = "test-building",
        floors = listOf(
            FloorMap(
                0,
                listOf(
                    MapNode("A", 0.0, 0.0, NodeType.CORRIDOR, name = "A"),
                    MapNode("B", 0.0, 10.0, NodeType.CORRIDOR, name = "Donus"),
                    MapNode("C", 10.0, 10.0, NodeType.DESTINATION, name = "C"),
                ),
                listOf(
                    MapEdge("A", "B", accessible = true, distanceMeters = 10.0),
                    MapEdge("B", "C", accessible = true, distanceMeters = 10.0),
                ),
            ),
        ),
    )

    private fun orchestratorFor(map: BuildingMap, positions: List<Position>, feedback: FeedbackController, destination: String) =
        NavigationOrchestrator(
            positioning = FakePositioningEngine(positions),
            perception = FakeObstacleDetector(List(positions.size) { emptyList() }),
            map = map,
            feedback = feedback,
            startNodeId = "A",
            destinationNodeId = destination,
            phrases = TokenPhrases,
        )

    @Test
    fun `a new leg that ends in a right turn carries a TURN_RIGHT cue`() = runTest {
        val feedback = RecordingFeedbackController()
        orchestratorFor(rightTurnMap, listOf(position(0.0, 0.0)), feedback, "C").start().toList()

        assertEquals(GuidanceCue.TURN_RIGHT, feedback.emitted.single { it.id.startsWith("route-guide-") }.cue)
    }

    @Test
    fun `a periodic repeat of the same leg carries no cue`() = runTest {
        val feedback = RecordingFeedbackController()
        val positions = listOf(
            position(0.0, 0.0, timestampMs = 0L),
            position(0.0, 1.0, timestampMs = NavigationOrchestrator.ROUTE_GUIDANCE_REPEAT_INTERVAL_MS + 1),
        )
        orchestratorFor(rightTurnMap, positions, feedback, "C").start().toList()

        val guidance = feedback.emitted.filter { it.id.startsWith("route-guide-") }
        assertEquals(listOf(GuidanceCue.TURN_RIGHT, GuidanceCue.NONE), guidance.map { it.cue })
    }

    @Test
    fun `arrival carries an ARRIVED cue`() = runTest {
        val feedback = RecordingFeedbackController()
        val positions = listOf(position(0.0, 0.0, timestampMs = 0L), position(0.0, 9.5, timestampMs = 1L), position(9.5, 10.0, timestampMs = 2L))
        orchestratorFor(rightTurnMap, positions, feedback, "C").start().toList()

        assertEquals(GuidanceCue.ARRIVED, feedback.emitted.single { it.id.startsWith("arrived-") }.cue)
    }

    @Test
    fun `a tagged hazard ahead outranks the turn after it`() = runTest {
        val feedback = RecordingFeedbackController()
        orchestratorFor(stairsMap, listOf(position(0.0, 0.0)), feedback, "D").start().toList()

        assertEquals(GuidanceCue.HAZARD_AHEAD, feedback.emitted.first { it.id.startsWith("route-guide-") }.cue)
    }

    @Test
    fun `the leg that crosses floors carries a FLOOR_CHANGE cue`() = runTest {
        val feedback = RecordingFeedbackController()
        val positions = listOf(position(0.0, 0.0, timestampMs = 0L), position(0.0, 9.5, timestampMs = 1L))
        orchestratorFor(stairsMap, positions, feedback, "D").start().toList()

        assertEquals(GuidanceCue.FLOOR_CHANGE, feedback.emitted.filter { it.id.startsWith("route-guide-") }.last().cue)
    }

    @Test
    fun `a very close obstacle carries an OBSTACLE_CRITICAL cue`() = runTest {
        val feedback = RecordingFeedbackController()
        val orchestrator = NavigationOrchestrator(
            positioning = FakePositioningEngine(position(0.0, 0.0)),
            perception = FakeObstacleDetector(listOf(listOf(personObstacle("p1", distanceMeters = 1.0)))),
            map = singleNodeMap,
            feedback = feedback,
            startNodeId = "A",
            destinationNodeId = "A",
            phrases = TokenPhrases,
        )
        orchestrator.start().toList()

        assertEquals(GuidanceCue.OBSTACLE_CRITICAL, feedback.emitted.single { it.id.startsWith("obstacle-") }.cue)
    }

    @Test
    fun `currentInstruction rewords the current leg with a fresh distance`() = runTest {
        val orchestrator = orchestratorFor(rightTurnMap, listOf(position(0.0, 0.0)), RecordingFeedbackController(), "C")
        assertNull(orchestrator.currentInstruction(position(0.0, 0.0)), "nothing is planned before navigation starts")

        orchestrator.start().toList()

        val instruction = orchestrator.currentInstruction(position(0.0, 6.0))
        assertEquals("WALK Donus 4.0 RIGHT", instruction)
    }
}
