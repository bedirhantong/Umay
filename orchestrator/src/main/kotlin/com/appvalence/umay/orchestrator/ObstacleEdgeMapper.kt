package com.appvalence.umay.orchestrator

import com.appvalence.umay.coremodel.DetectedObstacle
import com.appvalence.umay.coremodel.MapEdge
import com.appvalence.umay.coremodel.MapNode
import com.appvalence.umay.coremodel.Position
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Converts a [DetectedObstacle]'s camera-relative (distance, bearing) reading
 * into an absolute map point using the user's current [Position], then finds
 * which route edge (if any) that point lies close enough to — within
 * [corridorWidthMeters] of the line segment — to be considered "blocking" it
 * (FR-11's local re-routing trigger).
 */
object ObstacleEdgeMapper {
    fun blockingEdge(
        obstacle: DetectedObstacle,
        userPosition: Position,
        candidateEdges: List<MapEdge>,
        nodesById: Map<String, MapNode>,
        corridorWidthMeters: Double = DEFAULT_CORRIDOR_WIDTH_METERS,
    ): MapEdge? {
        val absoluteBearingRad = Math.toRadians(userPosition.headingDeg + obstacle.bearingDeg)
        val obstacleX = userPosition.x + obstacle.distanceMeters * sin(absoluteBearingRad)
        val obstacleY = userPosition.y + obstacle.distanceMeters * cos(absoluteBearingRad)

        return candidateEdges
            .mapNotNull { edge ->
                val from = nodesById[edge.fromId] ?: return@mapNotNull null
                val to = nodesById[edge.toId] ?: return@mapNotNull null
                val distance = distanceToSegment(obstacleX, obstacleY, from.x, from.y, to.x, to.y)
                edge to distance
            }
            .minByOrNull { (_, distance) -> distance }
            ?.takeIf { (_, distance) -> distance <= corridorWidthMeters }
            ?.first
    }

    private fun distanceToSegment(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val abx = bx - ax
        val aby = by - ay
        val lengthSquared = abx * abx + aby * aby
        if (lengthSquared == 0.0) {
            return sqrt((px - ax) * (px - ax) + (py - ay) * (py - ay))
        }
        val t = (((px - ax) * abx) + ((py - ay) * aby)) / lengthSquared
        val clampedT = t.coerceIn(0.0, 1.0)
        val closestX = ax + clampedT * abx
        val closestY = ay + clampedT * aby
        return sqrt((px - closestX) * (px - closestX) + (py - closestY) * (py - closestY))
    }

    const val DEFAULT_CORRIDOR_WIDTH_METERS = 1.5
}
