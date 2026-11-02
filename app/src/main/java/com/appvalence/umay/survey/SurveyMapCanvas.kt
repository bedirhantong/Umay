package com.appvalence.umay.survey

import android.graphics.Paint
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import com.appvalence.umay.coremodel.SpatialMap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import com.appvalence.umay.R
import com.appvalence.umay.common.drawWallLayer
import com.appvalence.umay.common.rememberWallMapImage
import com.appvalence.umay.common.FloorPlanViewport
import com.appvalence.umay.common.drawSpatialMap
import com.appvalence.umay.common.drawMetricScale
import com.appvalence.umay.common.floorPlanBounds
import com.appvalence.umay.common.rememberSpatialMapDrawing
import com.appvalence.umay.common.nearestWall
import com.appvalence.umay.coremodel.MetricPoint
import com.appvalence.umay.coremodel.OccupancyRaster
import com.appvalence.umay.coremodel.Position
import com.appvalence.umay.coremodel.TaggedWaypoint
import com.appvalence.umay.positioning.trilateration.BeaconSurveyResult
import kotlin.math.sin
import kotlin.math.cos

/**
 * Live top-down view of the on-site survey for the current floor: the
 * PDR-walked trajectory (blue line), every beacon position [BeaconSurveySolver]
 * has resolved so far (pink dots), every user-named [TaggedWaypoint] (orange
 * dots with labels), and the phone's last known position (green dot). All
 * inputs are already floor-filtered by the caller — this view has no opinion
 * about floors, it just draws points.
 *
 * In a camera survey, the [wallMap] measured so far is drawn underneath —
 * walls and the floor seen between them, filling in as the surveyor walks,
 * like a robot vacuum's map — and [livePosition] is where the camera puts
 * the phone right now.
 *
 * Coordinates are auto-fit to the canvas: whatever (x, y) range the data
 * currently spans is scaled to fill the available space, so the drawing
 * stays legible whether the survey covers a small room or a long corridor.
 */
@Composable
fun SurveyMapCanvas(
    trajectory: List<Position>,
    results: List<BeaconSurveyResult>,
    modifier: Modifier = Modifier,
    waypoints: List<TaggedWaypoint> = emptyList(),
    wallMap: OccupancyRaster? = null,
    livePosition: Position? = null,
    height: Dp = 240.dp,
    spatialMap: SpatialMap? = null,
    onReviewWall: (com.appvalence.umay.coremodel.WallSegment, Boolean) -> Unit = { _, _ -> },
    floorIndex: Int = livePosition?.floor ?: trajectory.firstOrNull()?.floor ?: 0,
) {
    val wallImage by rememberWallMapImage(
        raster = wallMap,
        seenFloorColor = MaterialTheme.colorScheme.surfaceVariant,
        wallColor = MaterialTheme.colorScheme.onSurface,
    )
    val frameId = spatialMap?.coordinateFrameId
    var manualViewport by remember(floorIndex, frameId) { mutableStateOf<FloorPlanViewport?>(null) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var selectedWall by remember(floorIndex, frameId) { mutableStateOf(0) }
    val drawing = rememberSpatialMapDrawing(spatialMap)
    val bounds = remember(spatialMap, wallMap, trajectory, results, waypoints, livePosition) {
        floorPlanBounds(spatialMap, wallMap, trajectory.map { MetricPoint(it.x, it.y) } +
            results.map { MetricPoint(it.x, it.y) } + waypoints.map { MetricPoint(it.x, it.y) } +
            listOfNotNull(livePosition?.let { MetricPoint(it.x, it.y) }))
    }
    val viewport = bounds?.let {
        manualViewport?.copy(widthPixels = canvasSize.width.toDouble(), heightPixels = canvasSize.height.toDouble())
            ?: FloorPlanViewport.fit(it, canvasSize.width.toDouble(), canvasSize.height.toDouble())
    }
    val currentViewport by rememberUpdatedState(viewport)
    val currentWalls by rememberUpdatedState(spatialMap?.walls.orEmpty())
    Column(modifier = modifier) {
        Text(text = stringResource(R.string.survey_preview_title), style = MaterialTheme.typography.titleSmall)
        if (bounds == null) {
            Text(
                text = stringResource(R.string.survey_preview_empty),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            return@Column
        }
        TextButton(onClick = { manualViewport = null }) { Text(stringResource(R.string.map_fit)) }

        val labelPaint = remember {
            Paint().apply {
                color = android.graphics.Color.BLACK
                textSize = WAYPOINT_LABEL_TEXT_SIZE_PX
            }
        }
        val scalePaint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }

        // A picture for the eye; for TalkBack, what it shows in words.
        val description = pluralStringResource(R.plurals.survey_preview_description, waypoints.size, waypoints.size, if (wallMap != null) stringResource(R.string.survey_preview_with_walls) else "")
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .padding(top = 4.dp)
                .clipToBounds()
                .onSizeChanged { canvasSize = it }
                .semantics { contentDescription = description }
                .pointerInput(floorIndex, frameId) {
                    detectTransformGestures { centroid, delta, factor, _ ->
                        val active = manualViewport?.copy(widthPixels = size.width.toDouble(), heightPixels = size.height.toDouble()) ?: currentViewport
                        manualViewport = active?.transformed(centroid.x.toDouble(), centroid.y.toDouble(), factor.toDouble(), delta.x.toDouble(), delta.y.toDouble())
                    }
                }
                .pointerInput(floorIndex, frameId) {
                    detectTapGestures { tap ->
                        val view = currentViewport ?: return@detectTapGestures
                        nearestWall(currentWalls, view, tap.x.toDouble(), tap.y.toDouble(), 24.dp.toPx().toDouble())?.let { selectedWall = it }
                    }
                },
        ) {
            val walls = wallImage
            val view = viewport ?: return@Canvas
            fun toOffset(x: Double, y: Double): Offset {
                val (sx,sy) = view.toScreen(MetricPoint(x,y))
                return Offset(sx.toFloat(),sy.toFloat())
            }

            if (walls != null) {
                val topLeft = toOffset(walls.originX, walls.originY + walls.heightMeters)
                drawWallLayer(walls.seenFloor, walls, topLeft, view.pixelsPerMeter.toFloat())
                drawWallLayer(walls.walls, walls, topLeft, view.pixelsPerMeter.toFloat())
            }
            drawSpatialMap(drawing, view, spatialMap?.walls?.getOrNull(selectedWall))

            if (trajectory.size >= 2) {
                val path = Path().apply {
                    val first = toOffset(trajectory.first().x, trajectory.first().y)
                    moveTo(first.x, first.y)
                    trajectory.drop(1).forEach { pose ->
                        val point = toOffset(pose.x, pose.y)
                        lineTo(point.x, point.y)
                    }
                }
                drawPath(path, color = TRAJECTORY_COLOR, style = Stroke(width = TRAJECTORY_STROKE_WIDTH_PX))
            }

            results.forEach { result ->
                drawCircle(color = BEACON_COLOR, radius = BEACON_RADIUS_PX, center = toOffset(result.x, result.y))
            }

            waypoints.forEach { waypoint ->
                val center = toOffset(waypoint.x, waypoint.y)
                drawCircle(color = WAYPOINT_COLOR, radius = WAYPOINT_RADIUS_PX, center = center)
                drawContext.canvas.nativeCanvas.drawText(
                    waypoint.name,
                    center.x + WAYPOINT_RADIUS_PX + WAYPOINT_LABEL_OFFSET_PX,
                    center.y,
                    labelPaint,
                )
            }

            (livePosition ?: trajectory.lastOrNull())?.let { last ->
                val center = toOffset(last.x, last.y)
                val heading = Math.toRadians(last.headingDeg)
                drawLine(CURRENT_POSITION_COLOR, center,
                    center + Offset((sin(heading) * 28.dp.toPx()).toFloat(), (-cos(heading) * 28.dp.toPx()).toFloat()), 3.dp.toPx())
                drawCircle(color = CURRENT_POSITION_COLOR, radius = CURRENT_POSITION_RADIUS_PX, center = center)
            }
            drawMetricScale(view, scalePaint, Color.DarkGray)
        }
        Text(
            text = stringResource(R.string.survey_preview_legend),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
        spatialMap?.let { geometry ->
            Text(stringResource(R.string.map_geometry_legend), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.survey_geometry_status, geometry.walls.size, geometry.openings.size, geometry.observedRegions.size, geometry.integratedFrames), style = MaterialTheme.typography.bodySmall)
            if (com.appvalence.umay.coremodel.ReconstructionIssue.FLOOR_UNRESOLVED in geometry.issues) {
                Text(stringResource(R.string.survey_floor_unresolved), style = MaterialTheme.typography.bodySmall)
            }
            val index = selectedWall.coerceIn(0, (geometry.walls.size - 1).coerceAtLeast(0))
            geometry.walls.getOrNull(index)?.let { wall ->
                Text(stringResource(R.string.survey_wall_evidence, index + 1, wall.independentFrames, wall.residualMeters), style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(onClick = { selectedWall = (index + 1) % geometry.walls.size }) { Text(stringResource(R.string.survey_wall_next)) }
                    TextButton(onClick = { onReviewWall(wall, true) }) { Text(stringResource(R.string.survey_wall_confirm)) }
                    TextButton(onClick = { onReviewWall(wall, false) }) { Text(stringResource(R.string.survey_wall_reject)) }
                }
            }
        }
        if (wallMap != null) {
            Text(text = stringResource(R.string.survey_preview_legend_walls), style = MaterialTheme.typography.bodySmall)
        }
    }
}

private const val TRAJECTORY_STROKE_WIDTH_PX = 4f
private const val BEACON_RADIUS_PX = 10f
private const val CURRENT_POSITION_RADIUS_PX = 14f
private const val WAYPOINT_RADIUS_PX = 12f
private const val WAYPOINT_LABEL_OFFSET_PX = 4f
private const val WAYPOINT_LABEL_TEXT_SIZE_PX = 28f
private val TRAJECTORY_COLOR = Color(0xFF2196F3)
private val BEACON_COLOR = Color(0xFFE91E63)
private val CURRENT_POSITION_COLOR = Color(0xFF4CAF50)
private val WAYPOINT_COLOR = Color(0xFFFF9800)
