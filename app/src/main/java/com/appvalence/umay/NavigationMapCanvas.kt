package com.appvalence.umay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.appvalence.umay.coremodel.DetectedObstacle
import com.appvalence.umay.coremodel.FloorMap
import com.appvalence.umay.coremodel.MapNode
import com.appvalence.umay.coremodel.Position
import kotlin.math.cos
import kotlin.math.sin

/**
 * Top-down view of a floor's node-edge graph (gray), the currently planned
 * route (blue, see [com.appvalence.umay.orchestrator.NavigationOrchestrator]),
 * the user's live position (green), and any currently visible obstacles
 * (red) placed by converting their camera-relative reading into an absolute
 * point the same way [com.appvalence.umay.orchestrator.ObstacleEdgeMapper] does.
 * Auto-fit to whichever of the map's nodes and the live position currently
 * span the widest area.
 */
@Composable
fun NavigationMapCanvas(
    floorMap: FloorMap,
    route: List<MapNode>,
    position: Position?,
    obstacles: List<DetectedObstacle>,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.fillMaxWidth().height(280.dp)) {
        if (floorMap.nodes.isEmpty()) return@Canvas

        val allX = floorMap.nodes.map { it.x } + listOfNotNull(position?.x)
        val allY = floorMap.nodes.map { it.y } + listOfNotNull(position?.y)
        val minX = allX.min()
        val maxX = allX.max()
        val minY = allY.min()
        val maxY = allY.max()
        val spanX = (maxX - minX).coerceAtLeast(1.0)
        val spanY = (maxY - minY).coerceAtLeast(1.0)
        val padding = 24f

        fun toOffset(x: Double, y: Double): Offset {
            val normalizedX = ((x - minX) / spanX).toFloat()
            val normalizedY = ((y - minY) / spanY).toFloat()
            return Offset(
                x = padding + normalizedX * (size.width - 2 * padding),
                y = size.height - padding - normalizedY * (size.height - 2 * padding),
            )
        }

        val nodesById = floorMap.nodes.associateBy { it.id }
        floorMap.edges.forEach { edge ->
            val from = nodesById[edge.fromId] ?: return@forEach
            val to = nodesById[edge.toId] ?: return@forEach
            drawLine(
                color = Color(0xFFBDBDBD),
                start = toOffset(from.x, from.y),
                end = toOffset(to.x, to.y),
                strokeWidth = 2f,
            )
        }
        floorMap.nodes.forEach { node ->
            drawCircle(color = Color(0xFF9E9E9E), radius = 6f, center = toOffset(node.x, node.y))
        }

        if (route.size >= 2) {
            val path = Path().apply {
                val first = toOffset(route.first().x, route.first().y)
                moveTo(first.x, first.y)
                route.drop(1).forEach { node ->
                    val point = toOffset(node.x, node.y)
                    lineTo(point.x, point.y)
                }
            }
            drawPath(path, color = Color(0xFF2196F3), style = Stroke(width = 6f))
        }

        if (position != null) {
            obstacles.forEach { obstacle ->
                val bearingRad = Math.toRadians(position.headingDeg + obstacle.bearingDeg)
                val obstacleX = position.x + obstacle.distanceMeters * sin(bearingRad)
                val obstacleY = position.y + obstacle.distanceMeters * cos(bearingRad)
                drawCircle(color = Color(0xFFF44336), radius = 10f, center = toOffset(obstacleX, obstacleY))
            }
            drawCircle(color = Color(0xFF4CAF50), radius = 14f, center = toOffset(position.x, position.y))
        }
    }
}
