package com.appvalence.umay.orchestrator

import com.appvalence.umay.coremodel.BuildingMap
import com.appvalence.umay.coremodel.DetectedObstacle
import com.appvalence.umay.coremodel.NavigationAlert
import com.appvalence.umay.coremodel.Position
import com.appvalence.umay.coremodel.PositionSource
import com.appvalence.umay.feedback.FeedbackController
import com.appvalence.umay.perception.ObstacleDetector
import com.appvalence.umay.positioning.PositioningEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

private class FakePositioningEngine(private val position: Position) : PositioningEngine {
    override fun positionUpdates(): Flow<Position> = flowOf(position)
}

private class FakeObstacleDetector(private val obstacles: List<DetectedObstacle>) : ObstacleDetector {
    override fun obstacleUpdates(): Flow<List<DetectedObstacle>> = flowOf(obstacles)
}

private class RecordingFeedbackController : FeedbackController {
    val emitted = mutableListOf<NavigationAlert>()
    override fun emit(alert: NavigationAlert) {
        emitted += alert
    }
}

class NavigationOrchestratorTest {

    @Test
    fun `start combines the latest position and obstacles into a NavigationState`() = runTest {
        val position = Position(
            x = 1.0, y = 2.0, floor = 0, headingDeg = 90.0,
            accuracyMeters = 0.3, source = PositionSource.FUSED, timestampMs = 1_000L,
        )
        val positioning = FakePositioningEngine(position)
        val perception = FakeObstacleDetector(emptyList())
        val feedback = RecordingFeedbackController()
        val orchestrator = NavigationOrchestrator(
            positioning = positioning,
            perception = perception,
            map = BuildingMap(id = "test-building", floors = emptyList()),
            feedback = feedback,
        )

        val state = orchestrator.start().let { flow ->
            var result: NavigationState? = null
            flow.collect { result = it }
            result
        }

        assertEquals(position, state?.position)
        assertEquals(emptyList(), state?.obstacles)
        assertEquals(emptyList(), feedback.emitted)
    }
}
