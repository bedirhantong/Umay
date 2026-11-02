package com.appvalence.umay.coremodel

import kotlin.math.hypot
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Validate persisted metric evidence before it reaches rendering or navigation. */
object SpatialValidation {
    fun valid(map: SpatialMap): Boolean {
        if (map.schemaVersion != 1 || map.coordinateFrameId.isBlank() || map.integratedFrames < 0 ||
            map.voxels.size > 30_000 || map.walls.size > 500 || map.confirmedWalls.size > 500 || map.rejectedWalls.size > 500 ||
            map.openings.size > 500 || map.openingWitnesses.size > 12_000 || map.observedRegions.size > 128) return false
        if (map.worldToFloor?.let { listOf(it.clockwiseYawDeg,it.translationX,it.translationY).any { value -> !value.isFinite() } } == true) return false
        val floor = map.floor
        if (floor != null && (!floor.worldHeight.isFinite() || floor.independentFrames < 1 ||
                    !floor.residualMeters.isFinite() || floor.residualMeters < 0)) return false
        fun point(p: MetricPoint) = p.x.isFinite() && p.y.isFinite() && kotlin.math.abs(p.x) <= 500 && kotlin.math.abs(p.y) <= 500
        fun wall(w: WallSegment) = point(w.start) && point(w.end) && hypot(w.end.x-w.start.x,w.end.y-w.start.y) >= 0.1 &&
            w.minHeight.isFinite() && w.maxHeight.isFinite() && w.maxHeight > w.minHeight &&
            w.supportingVoxels >= 1 && w.independentFrames >= 1 && w.residualMeters.isFinite() && w.residualMeters >= 0
        return map.walls.all(::wall) && map.confirmedWalls.all(::wall) && map.rejectedWalls.all(::wall) &&
            map.corners.all(::point) && map.voxels.all { point(MetricPoint(it.x,it.y)) && it.height.isFinite() && it.frames > 0 && it.lastFrame > 0 } &&
            map.openingWitnesses.all { point(it.point) && it.height.isFinite() && it.height in 0.0..3.0 && it.frames > 0 && it.lastFrame >= it.frames.toLong() } &&
            map.openings.all { point(it.start) && point(it.end) && hypot(it.end.x-it.start.x,it.end.y-it.start.y) in 0.4..2.5 &&
                it.lintelHeightMeters.isFinite() && it.lintelHeightMeters in 1.5..3.0 && it.observedClearanceHeightMeters.isFinite() &&
                it.observedClearanceHeightMeters in 0.4..it.lintelHeightMeters && it.independentFrames > 0 &&
                (it.evidence != GeometryEvidence.MULTI_FRAME || it.independentFrames >= 2) } &&
            validObservedRegions(map.observedRegions)
    }

    /** Also used before publishing generated polygons, so bounded validation cannot make drafts unsavable. */
    fun validObservedRegions(regions: List<ObservedRegion>): Boolean {
        if (regions.size > 128) return false
        var vertices = 0
        for (region in regions) {
            if (region.holes.size > 64) return false
            for (ring in listOf(region.outline) + region.holes) {
                if (ring.size !in 3..2048 || ring.any { !it.x.isFinite() || !it.y.isFinite() || abs(it.x) > 500 || abs(it.y) > 500 }) return false
                vertices += ring.size
                if (vertices > MAX_REGION_VERTICES) return false
            }
        }
        val budget = WorkBudget()
        return regions.all { validRegion(it, budget) }
    }

    private fun validRegion(region: ObservedRegion, budget: WorkBudget): Boolean {
        if (!region.areaSquareMeters.isFinite() || region.areaSquareMeters <= 0) return false
        val rings = listOf(region.outline) + region.holes
        val segments = ArrayList<Segment>()
        val areas = ArrayList<Double>()
        for ((ringIndex, ring) in rings.withIndex()) {
            if (ring.toSet().size != ring.size) return false
            val area = abs(signedArea(ring))
            if (!area.isFinite() || area <= EPSILON) return false
            areas += area
            for (index in ring.indices) {
                val a = ring[index]; val b = ring[(index + 1) % ring.size]
                if (hypot(b.x - a.x, b.y - a.y) <= EPSILON) return false
                val previous = ring[(index + ring.size - 1) % ring.size]
                // Adjacent collinear edges may continue straight, but must not double back.
                if (abs(cross(previous, a, b)) <= EPSILON &&
                    (previous.x - a.x) * (b.x - a.x) + (previous.y - a.y) * (b.y - a.y) > 0) return false
                segments += Segment(a, b, ringIndex, index, ring.size)
            }
        }
        val expectedArea = areas.first() - areas.drop(1).sum()
        if (expectedArea <= EPSILON || abs(expectedArea - region.areaSquareMeters) > max(1e-8, expectedArea * 1e-6)) return false

        // Sweep on the region's larger axis, with a global pair budget. This keeps ordinary
        // grid-derived boundaries cheap and refuses pathological files instead of unbounded O(n²).
        val extentX = segments.maxOf { it.maxX } - segments.minOf { it.minX }
        val extentY = segments.maxOf { it.maxY } - segments.minOf { it.minY }
        val sweepX = extentX >= extentY
        val sorted = segments.sortedBy { if (sweepX) it.minX else it.minY }
        for (i in sorted.indices) {
            val a = sorted[i]
            for (j in i + 1 until sorted.size) {
                val b = sorted[j]
                if ((if (sweepX) b.minX > a.maxX + EPSILON else b.minY > a.maxY + EPSILON)) break
                if (!budget.spend()) return false
                if (a.adjacent(b)) continue
                if (a.maxX + EPSILON < b.minX || b.maxX + EPSILON < a.minX ||
                    a.maxY + EPSILON < b.minY || b.maxY + EPSILON < a.minY) continue
                if (intersects(a, b)) return false
            }
        }
        // With disjoint simple boundaries, one inside point proves each entire hole is inside.
        for (hole in region.holes) if (contains(region.outline, hole.first(), budget) != true) return false
        for (i in region.holes.indices) for (j in i + 1 until region.holes.size) {
            val a = region.holes[i]; val b = region.holes[j]
            if (contains(a, b.first(), budget) != false || contains(b, a.first(), budget) != false) return false
        }
        return true
    }

    private class WorkBudget {
        private var remaining = 4_000_000
        fun spend(): Boolean = --remaining >= 0
    }

    private data class Segment(val a: MetricPoint, val b: MetricPoint, val ring: Int, val index: Int, val count: Int) {
        val minX = min(a.x, b.x); val maxX = max(a.x, b.x)
        val minY = min(a.y, b.y); val maxY = max(a.y, b.y)
        fun adjacent(other: Segment) = ring == other.ring &&
            (abs(index - other.index) == 1 || abs(index - other.index) == count - 1)
    }

    private fun signedArea(ring: List<MetricPoint>): Double {
        val origin = ring.first()
        return ring.indices.sumOf { i ->
            val a = ring[i]; val b = ring[(i + 1) % ring.size]
            (a.x - origin.x) * (b.y - origin.y) - (a.y - origin.y) * (b.x - origin.x)
        } / 2
    }

    private fun cross(a: MetricPoint, b: MetricPoint, c: MetricPoint): Double =
        (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)

    private fun onSegment(point: MetricPoint, a: MetricPoint, b: MetricPoint): Boolean =
        abs(cross(a, b, point)) <= EPSILON && point.x >= min(a.x, b.x) - EPSILON &&
            point.x <= max(a.x, b.x) + EPSILON && point.y >= min(a.y, b.y) - EPSILON && point.y <= max(a.y, b.y) + EPSILON

    private fun intersects(a: Segment, b: Segment): Boolean {
        val p = cross(a.a, a.b, b.a); val q = cross(a.a, a.b, b.b)
        val r = cross(b.a, b.b, a.a); val s = cross(b.a, b.b, a.b)
        fun opposite(x: Double, y: Double) = (x > EPSILON && y < -EPSILON) || (x < -EPSILON && y > EPSILON)
        return (opposite(p, q) && opposite(r, s)) || onSegment(b.a, a.a, a.b) || onSegment(b.b, a.a, a.b) ||
            onSegment(a.a, b.a, b.b) || onSegment(a.b, b.a, b.b)
    }

    private fun contains(ring: List<MetricPoint>, point: MetricPoint, budget: WorkBudget): Boolean? {
        var inside = false
        for (i in ring.indices) {
            if (!budget.spend()) return null
            val a = ring[i]; val b = ring[(i + 1) % ring.size]
            if ((a.y > point.y) != (b.y > point.y) &&
                point.x < a.x + (point.y - a.y) * (b.x - a.x) / (b.y - a.y)) inside = !inside
        }
        return inside
    }

    const val MAX_REGION_VERTICES = 32_768
    private const val EPSILON = 1e-9
}
