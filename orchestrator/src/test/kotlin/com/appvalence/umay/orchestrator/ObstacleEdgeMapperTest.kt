package com.appvalence.umay.orchestrator

import com.appvalence.umay.coremodel.DetectedObstacle
import com.appvalence.umay.coremodel.MapEdge
import com.appvalence.umay.coremodel.MapNode
import com.appvalence.umay.coremodel.NodeType
import com.appvalence.umay.coremodel.ObstacleSensorSource
import com.appvalence.umay.coremodel.ObstacleType
import com.appvalence.umay.coremodel.Position
import com.appvalence.umay.coremodel.PositionSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ObstacleEdgeMapperTest {

    private val nodeA = MapNode("A", 0.0, 0.0, NodeType.CORRIDOR)
    private val nodeB = MapNode("B", 0.0, 10.0, NodeType.CORRIDOR) // straight ahead of a north-facing user
    private val edge = MapEdge("A", "B", accessible = true, distanceMeters = 10.0)
    private val nodesById = mapOf("A" to nodeA, "B" to nodeB)

    private fun obstacleAt(distanceMeters: Double, bearingDeg: Double) = DetectedObstacle(
        id = "o1",
        type = ObstacleType.PERSON,
        distanceMeters = distanceMeters,
        bearingDeg = bearingDeg,
        relativeVelocityMps = null,
        confidence = 0.9f,
        detectedAtMs = 0L,
        sensorSource = ObstacleSensorSource.CAMERA_ML,
    )

    private fun userAt(x: Double, y: Double, headingDeg: Double) = Position(
        x = x, y = y, floor = 0, headingDeg = headingDeg, accuracyMeters = 0.3,
        source = PositionSource.FUSED, timestampMs = 0L,
    )

    @Test
    fun `an obstacle directly ahead on the route edge is matched`() {
        val user = userAt(0.0, 0.0, headingDeg = 0.0) // facing north (+y)
        val obstacle = obstacleAt(distanceMeters = 5.0, bearingDeg = 0.0) // straight ahead -> (0, 5), on the A-B segment

        val blocked = ObstacleEdgeMapper.blockingEdge(obstacle, user, listOf(edge), nodesById)

        assertEquals(edge, blocked)
    }

    @Test
    fun `an obstacle far off to the side is not matched`() {
        val user = userAt(0.0, 0.0, headingDeg = 0.0)
        val obstacle = obstacleAt(distanceMeters = 5.0, bearingDeg = 90.0) // due east, far from the A-B segment

        val blocked = ObstacleEdgeMapper.blockingEdge(obstacle, user, listOf(edge), nodesById)

        assertNull(blocked)
    }

    @Test
    fun `a narrower corridor width rejects a near-but-not-that-near obstacle`() {
        val user = userAt(0.0, 0.0, headingDeg = 0.0)
        val obstacle = obstacleAt(distanceMeters = 5.0, bearingDeg = 30.0)

        val blocked = ObstacleEdgeMapper.blockingEdge(obstacle, user, listOf(edge), nodesById, corridorWidthMeters = 0.1)

        assertNull(blocked)
    }
}
