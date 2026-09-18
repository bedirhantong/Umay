package com.appvalence.umay.survey

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.appvalence.umay.R
import com.appvalence.umay.coremodel.Position
import com.appvalence.umay.coremodel.TaggedWaypoint
import com.appvalence.umay.positioning.trilateration.BeaconSurveyResult
import kotlin.math.min

/**
 * Live top-down view of the on-site survey for the current floor: the
 * PDR-walked trajectory (blue line), every beacon position [BeaconSurveySolver]
 * has resolved so far (pink dots), every user-named [TaggedWaypoint] (orange
 * dots with labels), and the phone's last known position (green dot). All
 * inputs are already floor-filtered by the caller — this view has no opinion
 * about floors, it just draws points.
 *
 * Coordinates are auto-fit to the canvas: whatever (x, y) range the data
 * currently spans is scaled to fill the available space, so the drawing
 * stays legible whether the survey covers a small room or a long corridor.
 */
@Composable
fun SurveyMapCanvas(
    trajectory: List<Position>,
    results: List<BeaconSurveyResult>,
    waypoints: List<TaggedWaypoint> = emptyList(),
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(text = stringResource(R.string.survey_preview_title), style = MaterialTheme.typography.titleSmall)
        if (trajectory.isEmpty() && results.isEmpty() && waypoints.isEmpty()) {
            Text(
                text = stringResource(R.string.survey_preview_empty),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            return@Column
        }

        val labelPaint = remember {
            Paint().apply {
                color = android.graphics.Color.BLACK
                textSize = WAYPOINT_LABEL_TEXT_SIZE_PX
            }
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
                .padding(top = 4.dp),
        ) {
            val allX = trajectory.map { it.x } + results.map { it.x } + waypoints.map { it.x }
            val allY = trajectory.map { it.y } + results.map { it.y } + waypoints.map { it.y }
            val minX = allX.min()
            val maxX = allX.max()
            val minY = allY.min()
            val maxY = allY.max()
            val spanX = (maxX - minX).coerceAtLeast(MIN_SPAN_METERS)
            val spanY = (maxY - minY).coerceAtLeast(MIN_SPAN_METERS)
            val padding = CANVAS_PADDING_PX

            // One scale for both axes, so a square room stays square — stretching x and y
            // independently to fill the canvas distorts every corridor's proportions.
            val scale = min((size.width - 2 * padding) / spanX, (size.height - 2 * padding) / spanY)
            val centerX = (minX + maxX) / 2
            val centerY = (minY + maxY) / 2

            fun toOffset(x: Double, y: Double): Offset = Offset(
                x = (size.width / 2 + (x - centerX) * scale).toFloat(),
                // Flip y so "up" on screen matches increasing y in the survey's
                // coordinate convention (see BeaconScannerScreen's field notes).
                y = (size.height / 2 - (y - centerY) * scale).toFloat(),
            )

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

            trajectory.lastOrNull()?.let { last ->
                drawCircle(color = CURRENT_POSITION_COLOR, radius = CURRENT_POSITION_RADIUS_PX, center = toOffset(last.x, last.y))
            }
        }
        Text(
            text = stringResource(R.string.survey_preview_legend),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

private const val MIN_SPAN_METERS = 1.0
private const val CANVAS_PADDING_PX = 24f
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
