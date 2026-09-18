package com.appvalence.umay.orchestrator

import com.appvalence.umay.coremodel.AlertPriority
import com.appvalence.umay.coremodel.BuildingMap
import com.appvalence.umay.coremodel.DetectedObstacle
import com.appvalence.umay.coremodel.FloorMap
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
import com.appvalence.umay.positioning.PositioningEngine
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
            map = emptyMap,
            feedback = feedback,
            startNodeId = "A",
            destinationNodeId = "B",
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
            map = emptyMap,
            feedback = feedback,
            startNodeId = "A",
            destinationNodeId = "B",
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
            map = emptyMap,
            feedback = feedback,
            startNodeId = "A",
            destinationNodeId = "B",
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
}
