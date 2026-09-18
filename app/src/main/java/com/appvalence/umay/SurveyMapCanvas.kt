package com.appvalence.umay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.appvalence.umay.coremodel.Position
import com.appvalence.umay.positioning.BeaconSurveyResult

/**
 * Live top-down view of the on-site survey for the current floor: the
 * PDR-walked trajectory (blue line), every beacon position [BeaconSurveySolver]
 * has resolved so far (pink dots), and the phone's last known position
 * (green dot). Both inputs are already floor-filtered by the caller — this
 * view has no opinion about floors, it just draws points.
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
) {
    Column(modifier = modifier) {
        Text(text = "Harita önizleme (bu kat)", style = MaterialTheme.typography.titleSmall)
        if (trajectory.isEmpty() && results.isEmpty()) {
            Text(
                text = "Henüz veri yok — kaydı başlatıp yürümeye başla.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            return@Column
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
                .padding(top = 4.dp),
        ) {
            val allX = trajectory.map { it.x } + results.map { it.x }
            val allY = trajectory.map { it.y } + results.map { it.y }
            val minX = allX.min()
            val maxX = allX.max()
            val minY = allY.min()
            val maxY = allY.max()
            val spanX = (maxX - minX).coerceAtLeast(MIN_SPAN_METERS)
            val spanY = (maxY - minY).coerceAtLeast(MIN_SPAN_METERS)
            val padding = CANVAS_PADDING_PX

            fun toOffset(x: Double, y: Double): Offset {
                val normalizedX = ((x - minX) / spanX).toFloat()
                val normalizedY = ((y - minY) / spanY).toFloat()
                return Offset(
                    x = padding + normalizedX * (size.width - 2 * padding),
                    // Flip y so "up" on screen matches increasing y in the survey's
                    // coordinate convention (see BeaconScannerScreen's field notes).
                    y = size.height - padding - normalizedY * (size.height - 2 * padding),
                )
            }

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

            trajectory.lastOrNull()?.let { last ->
                drawCircle(color = CURRENT_POSITION_COLOR, radius = CURRENT_POSITION_RADIUS_PX, center = toOffset(last.x, last.y))
            }
        }
        Text(
            text = "🔵 yürüdüğün yol   🔴 konumlanan beacon   🟢 son konum",
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
private val TRAJECTORY_COLOR = Color(0xFF2196F3)
private val BEACON_COLOR = Color(0xFFE91E63)
private val CURRENT_POSITION_COLOR = Color(0xFF4CAF50)
