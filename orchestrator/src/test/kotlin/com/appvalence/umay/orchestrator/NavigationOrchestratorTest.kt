package com.appvalence.umay.orchestrator

import com.appvalence.umay.coremodel.AlertPriority
import com.appvalence.umay.coremodel.BuildingMap
import com.appvalence.umay.coremodel.DetectedObstacle
import com.appvalence.umay.coremodel.FloorMap
import com.appvalence.umay.coremodel.FloorTransition
import com.appvalence.umay.coremodel.MapEdge
import com.appvalence.umay.coremodel.MapNode
import com.appvalence.umay.coremodel.NavigationAlert
import com.appvalence.umay.coremodel.NodeType
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
        )

        orchestrator.start().toList()

        val guidance = feedback.emitted.single { it.id.startsWith("route-guide-") }
        assertTrue(guidance.message.contains("sağa dön"))
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
        )

        orchestrator.start().toList()

        val arrivals = feedback.emitted.filter { it.id.startsWith("arrived-") }
        assertEquals(1, arrivals.size)
        assertEquals("Hedefe ulaştın", arrivals.single().message)
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
        )

        val states = orchestrator.start().toList()

        val hint = states.last().pendingFloorChange
        assertEquals("C", hint?.targetNodeId)
        assertEquals(1, hint?.targetFloor)
        val guidance = feedback.emitted.last { it.id.startsWith("route-guide-") }
        assertTrue(guidance.message.contains("kat değiştir"))
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
        )

        val states = orchestrator.start().toList()

        assertEquals("C", states.last().pendingFloorChange?.targetNodeId)
        assertEquals(listOf("A", "B", "C", "D"), states.last().route.map { it.id })
    }
}
