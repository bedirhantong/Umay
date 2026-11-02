package com.appvalence.umay.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import com.appvalence.umay.coremodel.GeometryEvidence
import com.appvalence.umay.coremodel.MetricPoint
import com.appvalence.umay.coremodel.SpatialMap
import com.appvalence.umay.coremodel.WallSegment

/** Paths are cached in metric coordinates (x, -y); camera changes only change the draw transform. */
class SpatialMapDrawing internal constructor(
    internal val walls: Map<GeometryEvidence, Path>,
    internal val regionBoundaries: Map<Boolean, Path>,
    internal val openings: Map<GeometryEvidence, Path>,
    internal val corners: List<MetricPoint>,
)

@Composable
fun rememberSpatialMapDrawing(map: SpatialMap?): SpatialMapDrawing? = remember(map) {
    map?.let {
        SpatialMapDrawing(
            walls = map.walls.groupBy { it.evidence }.mapValues { (_, walls) -> Path().apply {
                walls.forEach { segment(it.start, it.end) }
            } },
            regionBoundaries = map.observedRegions.groupBy { it.boundaryComplete }.mapValues { (_, regions) -> Path().apply {
                regions.forEach { region -> ring(region.outline); region.holes.forEach(::ring) }
            } },
            openings = map.openings.groupBy { it.evidence }.mapValues { (_, openings) -> Path().apply {
                openings.forEach { opening -> segment(opening.start, opening.end) }
            } },
            corners = map.corners,
        )
    }
}

private fun Path.segment(a: MetricPoint, b: MetricPoint) {
    moveTo(a.x.toFloat(), -a.y.toFloat()); lineTo(b.x.toFloat(), -b.y.toFloat())
}

private fun Path.ring(points: List<MetricPoint>) {
    if (points.size < 3) return
    moveTo(points.first().x.toFloat(), -points.first().y.toFloat())
    for (i in 1 until points.size) lineTo(points[i].x.toFloat(), -points[i].y.toFloat())
    close()
}

/** Shared evidence styling across survey, navigation and map review. Unknown regions have no fill. */
fun DrawScope.drawSpatialMap(drawing: SpatialMapDrawing?, viewport: FloorPlanViewport, selectedWall: WallSegment? = null) {
    drawing ?: return
    val scale = viewport.pixelsPerMeter.toFloat()
    fun dashed(on: Float, off: Float) = PathEffect.dashPathEffect(floatArrayOf(on.dp.toPx() / scale, off.dp.toPx() / scale))
    val candidateStroke = Stroke(2.dp.toPx() / scale, pathEffect = dashed(6f, 4f))
    val multiFrameStroke = Stroke(2.5.dp.toPx() / scale, pathEffect = dashed(10f, 3f))
    val confirmedStroke = Stroke(3.dp.toPx() / scale)
    val partialBoundaryStroke = Stroke(1.5.dp.toPx() / scale, pathEffect = dashed(4f, 4f))
    val completeBoundaryStroke = Stroke(1.5.dp.toPx() / scale)
    withTransform({
        translate(viewport.anchorX.toFloat(), viewport.anchorY.toFloat())
        rotate(-viewport.headingDeg.toFloat(), pivot = androidx.compose.ui.geometry.Offset.Zero)
        scale(scale, scale, pivot = androidx.compose.ui.geometry.Offset.Zero)
        translate(-viewport.center.x.toFloat(), viewport.center.y.toFloat())
    }) {
        drawing.regionBoundaries.forEach { (complete, path) ->
            drawPath(path, if (complete) Color(0xFF1565C0) else Color(0xFF78909C),
                style = if (complete) completeBoundaryStroke else partialBoundaryStroke)
        }
        drawing.walls.forEach { (evidence, path) ->
            val color = when (evidence) {
                GeometryEvidence.OBSERVED_CANDIDATE -> Color(0xFFE65100)
                GeometryEvidence.MULTI_FRAME -> Color(0xFFAD5700)
                GeometryEvidence.OPERATOR_CONFIRMED -> Color(0xFF00695C)
            }
            val stroke = when (evidence) {
                GeometryEvidence.OBSERVED_CANDIDATE -> candidateStroke
                GeometryEvidence.MULTI_FRAME -> multiFrameStroke
                GeometryEvidence.OPERATOR_CONFIRMED -> confirmedStroke
            }
            drawPath(path, color, style = stroke)
        }
        drawing.openings.forEach { (evidence, path) ->
            drawPath(path, Color(0xFF00838F), style = if (evidence == GeometryEvidence.OPERATOR_CONFIRMED) confirmedStroke else candidateStroke)
        }
        drawing.corners.forEach { point ->
            drawCircle(Color(0xFFAD5700), 2.dp.toPx() / scale,
                androidx.compose.ui.geometry.Offset(point.x.toFloat(), -point.y.toFloat()))
        }
        selectedWall?.let { wall ->
            drawPath(Path().apply { segment(wall.start, wall.end) }, Color(0xFF9C27B0), style = Stroke(4.dp.toPx() / scale))
        }
    }
}
