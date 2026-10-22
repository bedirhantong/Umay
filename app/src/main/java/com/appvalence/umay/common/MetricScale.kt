package com.appvalence.umay.common

import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** A 1/2/5 metric scale no longer than the requested screen length, including close zooms. */
fun metricScaleMeters(pixelsPerMeter: Double, targetPixels: Double): Double {
    require(pixelsPerMeter.isFinite() && pixelsPerMeter > 0 && targetPixels.isFinite() && targetPixels > 0)
    val desired = targetPixels / pixelsPerMeter
    val magnitude = 10.0.pow(floor(log10(desired)))
    return (listOf(1.0, 2.0, 5.0).lastOrNull { it * magnitude <= desired } ?: 1.0) * magnitude
}

fun DrawScope.drawMetricScale(viewport: FloorPlanViewport, paint: Paint, color: Color) {
    val margin = 14.dp.toPx()
    val target = minOf(80.dp.toPx(), size.width * 0.25f).coerceAtLeast(1f)
    val meters = metricScaleMeters(viewport.pixelsPerMeter, target.toDouble())
    val length = (meters * viewport.pixelsPerMeter).toFloat()
    val y = size.height - margin
    val start = Offset(margin, y); val end = Offset(margin + length, y)
    drawLine(color, start, end, 2.dp.toPx())
    drawLine(color, start, start - Offset(0f, 5.dp.toPx()), 2.dp.toPx())
    drawLine(color, end, end - Offset(0f, 5.dp.toPx()), 2.dp.toPx())
    paint.textSize = 11.dp.toPx()
    paint.color = color.toArgb()
    val number = if (meters >= 1 && meters % 1.0 < 1e-9) meters.toInt().toString() else
        java.math.BigDecimal.valueOf(meters).stripTrailingZeros().toPlainString()
    drawContext.canvas.nativeCanvas.drawText("$number m", end.x + 6.dp.toPx(), y, paint)
}
