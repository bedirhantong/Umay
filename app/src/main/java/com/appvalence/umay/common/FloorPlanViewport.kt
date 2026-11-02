package com.appvalence.umay.common

import com.appvalence.umay.coremodel.FloorMap
import com.appvalence.umay.coremodel.MetricPoint
import com.appvalence.umay.coremodel.OccupancyRaster
import com.appvalence.umay.coremodel.SpatialMap
import com.appvalence.umay.coremodel.WallSegment
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Renderer-only pixel transform, independent of Android and all sensor processing. */
data class FloorPlanViewport(
    val center: MetricPoint,
    val widthPixels: Double,
    val heightPixels: Double,
    val pixelsPerMeter: Double,
    val panX: Double = 0.0,
    val panY: Double = 0.0,
    val headingDeg: Double = 0.0,
) {
    init {
        require(pixelsPerMeter.isFinite() && pixelsPerMeter > 0)
        require(listOf(center.x, center.y, widthPixels, heightPixels, panX, panY, headingDeg).all { it.isFinite() })
        require(widthPixels >= 0 && heightPixels >= 0)
    }
    private val cosine = cos(Math.toRadians(headingDeg))
    private val sine = sin(Math.toRadians(headingDeg))
    val anchorX: Double get() = widthPixels / 2 + panX
    val anchorY: Double get() = heightPixels / 2 + panY

    fun toScreen(point: MetricPoint): Pair<Double, Double> {
        val dx = point.x - center.x; val dy = point.y - center.y
        return (anchorX + (dx * cosine - dy * sine) * pixelsPerMeter) to
            (anchorY - (dx * sine + dy * cosine) * pixelsPerMeter)
    }

    fun toWorld(xPixels: Double, yPixels: Double): MetricPoint {
        val x = (xPixels - anchorX) / pixelsPerMeter
        val y = (anchorY - yPixels) / pixelsPerMeter
        return MetricPoint(center.x + x * cosine + y * sine, center.y - x * sine + y * cosine)
    }

    /** Keep the touched physical point under the gesture centroid when zooming and panning. */
    fun transformed(centroidX: Double, centroidY: Double, zoomFactor: Double, panDeltaX: Double, panDeltaY: Double): FloorPlanViewport {
        if (listOf(centroidX, centroidY, zoomFactor, panDeltaX, panDeltaY).any { !it.isFinite() } || zoomFactor <= 0) return this
        val before = toWorld(centroidX, centroidY)
        val scaled = copy(pixelsPerMeter = (pixelsPerMeter * zoomFactor).coerceIn(0.1, 10_000.0))
        val after = scaled.toWorld(centroidX + panDeltaX, centroidY + panDeltaY)
        return scaled.copy(center = MetricPoint(center.x + before.x - after.x, center.y + before.y - after.y))
    }

    companion object {
        fun fit(bounds: FloorPlanBounds, width: Double, height: Double, padding: Double = 24.0,
                zoom: Double = 1.0, panX: Double = 0.0, panY: Double = 0.0): FloorPlanViewport {
            val scale = min((width - 2 * padding).coerceAtLeast(1.0) / (bounds.maxX - bounds.minX).coerceAtLeast(1.0),
                (height - 2 * padding).coerceAtLeast(1.0) / (bounds.maxY - bounds.minY).coerceAtLeast(1.0))
            return FloorPlanViewport(bounds.center, width, height, (scale * zoom).coerceAtLeast(0.1), panX, panY)
        }
    }
}

data class FloorPlanBounds(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double) {
    val center: MetricPoint get() = MetricPoint((minX + maxX) / 2, (minY + maxY) / 2)
    fun including(point: MetricPoint): FloorPlanBounds = of(listOf(MetricPoint(minX, minY), MetricPoint(maxX, maxY), point))!!

    companion object {
        fun of(points: Iterable<MetricPoint>): FloorPlanBounds? {
            var minX = Double.POSITIVE_INFINITY; var minY = Double.POSITIVE_INFINITY
            var maxX = Double.NEGATIVE_INFINITY; var maxY = Double.NEGATIVE_INFINITY
            for (point in points) {
                if (!point.x.isFinite() || !point.y.isFinite()) continue
                minX = minOf(minX, point.x); minY = minOf(minY, point.y)
                maxX = maxOf(maxX, point.x); maxY = maxOf(maxY, point.y)
            }
            return if (minX <= maxX && minY <= maxY) FloorPlanBounds(minX, minY, maxX, maxY) else null
        }
    }
}

/** All displayed map geometry contributes to fit; waypoints are optional. No geographic projection. */
fun floorPlanBounds(floor: FloorMap): FloorPlanBounds? = floorPlanBounds(floor.reconstruction, floor.occupancy,
    floor.nodes.map { MetricPoint(it.x, it.y) })

fun floorPlanBounds(spatial: SpatialMap?, raster: OccupancyRaster?, points: List<MetricPoint> = emptyList()): FloorPlanBounds? =
    FloorPlanBounds.of(buildList {
        addAll(points)
        raster?.let {
            add(MetricPoint(it.originX, it.originY))
            add(MetricPoint(it.originX + it.width * it.cellSizeMeters, it.originY + it.height * it.cellSizeMeters))
        }
        spatial?.let { map ->
            map.walls.forEach { add(it.start); add(it.end) }
            addAll(map.corners)
            map.openings.forEach { add(it.start); add(it.end) }
            map.observedRegions.forEach { region -> addAll(region.outline); region.holes.forEach(::addAll) }
        }
    })

/** Select the displayed segment within a pixel tolerance, independent of zoom and camera rotation. */
fun nearestWall(walls: List<WallSegment>, viewport: FloorPlanViewport, x: Double, y: Double, tolerancePixels: Double): Int? {
    var selected: Int? = null
    var closestSquared = tolerancePixels * tolerancePixels
    walls.forEachIndexed { index, wall ->
        val (ax, ay) = viewport.toScreen(wall.start); val (bx, by) = viewport.toScreen(wall.end)
        val dx = bx - ax; val dy = by - ay
        val length2 = dx * dx + dy * dy
        val t = if (length2 > 0) (((x - ax) * dx + (y - ay) * dy) / length2).coerceIn(0.0, 1.0) else 0.0
        val distance2 = (x - ax - t * dx) * (x - ax - t * dx) + (y - ay - t * dy) * (y - ay - t * dy)
        if (distance2 <= closestSquared) { selected = index; closestSquared = distance2 }
    }
    return selected
}
