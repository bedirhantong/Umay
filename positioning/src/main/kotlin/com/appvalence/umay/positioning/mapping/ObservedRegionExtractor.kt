package com.appvalence.umay.positioning.mapping

import com.appvalence.umay.coremodel.MetricPoint
import com.appvalence.umay.coremodel.ObservedRegion
import com.appvalence.umay.coremodel.OccupancyRaster
import com.appvalence.umay.coremodel.SpatialValidation
import com.appvalence.umay.coremodel.WallSegment
import kotlin.math.hypot

/** Converts only measured FREE cells into polygons. UNKNOWN space is never filled or closed. */
object ObservedRegionExtractor {
    data class Result(val regions: List<ObservedRegion>, val truncated: Boolean)
    private data class Vertex(val x: Int, val y: Int)

    fun extract(raster: OccupancyRaster?, walls: List<WallSegment>): Result {
        raster ?: return Result(emptyList(), false)
        // Bound decoding as well as polygon work; decodeCells permits much larger export rasters.
        val inputSize = raster.width.toLong() * raster.height
        if (inputSize > MAX_INPUT_CELLS) return Result(emptyList(), true)
        if (raster.width <= 0 || raster.height <= 0 || !raster.cellSizeMeters.isFinite() ||
            raster.cellSizeMeters <= 0 || !raster.originX.isFinite() || !raster.originY.isFinite() ||
            !(raster.originX + raster.width * raster.cellSizeMeters).isFinite() ||
            !(raster.originY + raster.height * raster.cellSizeMeters).isFinite()) return Result(emptyList(), false)
        val cells = raster.decodeCells() ?: return Result(emptyList(), false)
        val seen = BooleanArray(cells.size)
        // Reused for each component: no boxed queue or additional cell-membership hash set.
        val component = IntArray(cells.size)
        val regions = ArrayList<ObservedRegion>()
        var truncated = walls.size > MAX_WALL_CANDIDATES
        var outputVertices = 0

        fun at(x: Int, y: Int): Byte = if (x in 0 until raster.width && y in 0 until raster.height)
            cells[y * raster.width + x] else OccupancyRaster.UNKNOWN

        for (start in cells.indices) {
            if (seen[start] || cells[start] != OccupancyRaster.FREE) continue
            if (regions.size >= MAX_REGIONS) { truncated = true; break }
            var head = 0
            var count = 1
            component[0] = start
            seen[start] = true
            fun visit(x: Int, y: Int) {
                if (x !in 0 until raster.width || y !in 0 until raster.height) return
                val next = y * raster.width + x
                if (!seen[next] && cells[next] == OccupancyRaster.FREE) {
                    seen[next] = true
                    component[count++] = next
                }
            }
            while (head < count) {
                val index = component[head++]
                val x = index % raster.width
                val y = index / raster.width
                visit(x - 1, y); visit(x + 1, y); visit(x, y - 1); visit(x, y + 1)
            }
            if (count < MIN_REGION_CELLS) continue
            val edges = LinkedHashMap<Vertex, Vertex>()
            val incoming = HashSet<Vertex>()
            var complete = walls.size <= MAX_WALL_CANDIDATES
            var valid = true
            fun edge(from: Vertex, to: Vertex, outsideX: Int, outsideY: Int) {
                if (!valid) return
                if (edges.size >= MAX_BOUNDARY_EDGES) { valid = false; truncated = true; return }
                // Each boundary vertex must have exactly one incoming and one outgoing edge.
                // Overwriting either edge at a diagonal pinch can silently fill UNKNOWN cells.
                if (from in edges || !incoming.add(to)) { valid = false; return }
                edges[from] = to
                if (complete && (at(outsideX, outsideY) != OccupancyRaster.OCCUPIED ||
                    !wallNear(raster, walls, from, to))) complete = false
            }
            for (i in 0 until count) {
                if (!valid) break
                val x = component[i] % raster.width
                val y = component[i] / raster.width
                if (at(x, y - 1) != OccupancyRaster.FREE) edge(Vertex(x, y), Vertex(x + 1, y), x, y - 1)
                if (at(x + 1, y) != OccupancyRaster.FREE) edge(Vertex(x + 1, y), Vertex(x + 1, y + 1), x + 1, y)
                if (at(x, y + 1) != OccupancyRaster.FREE) edge(Vertex(x + 1, y + 1), Vertex(x, y + 1), x, y + 1)
                if (at(x - 1, y) != OccupancyRaster.FREE) edge(Vertex(x, y + 1), Vertex(x, y), x - 1, y)
            }
            if (!valid) continue
            val traced = trace(edges) ?: continue
            if (traced.size > MAX_HOLES + 1) { truncated = true; continue }
            val rings = traced.map(::simplify)
            if (rings.any { it.size > MAX_RING_VERTICES }) { truncated = true; continue }
            if (rings.any { it.size < 3 }) continue
            val vertices = rings.sumOf { it.size }
            if (outputVertices + vertices > SpatialValidation.MAX_REGION_VERTICES) { truncated = true; continue }
            // Directed cell edges have positive outer area and negative hole area. Verify the
            // entire component before converting coordinates, using exact integer grid areas.
            val signedAreas = rings.map(::twiceArea)
            if (signedAreas.count { it > 0 } != 1 || signedAreas.any { it == 0L } ||
                signedAreas.sum() != 2L * count) continue
            val outerIndex = signedAreas.indexOfFirst { it > 0 }
            val metricRings = rings.map { ring -> ring.map {
                MetricPoint(raster.originX + it.x * raster.cellSizeMeters, raster.originY + it.y * raster.cellSizeMeters)
            } }
            val expectedArea = count * raster.cellSizeMeters * raster.cellSizeMeters
            if (!expectedArea.isFinite() || expectedArea <= 0 || metricRings.any { ring ->
                ring.indices.any { ring[it] == ring[(it + 1) % ring.size] }
            }) continue
            // Large origins or extreme cell sizes must not collapse otherwise valid grid geometry.
            val metricArea = metricRings.sumOf(::area)
            if (!metricArea.isFinite() || kotlin.math.abs(metricArea - expectedArea) > expectedArea * 1e-8) continue
            regions += ObservedRegion(
                outline = metricRings[outerIndex],
                holes = metricRings.filterIndexed { index, _ -> index != outerIndex },
                areaSquareMeters = expectedArea,
                boundaryComplete = complete,
            )
            outputVertices += vertices
        }
        // Share persistence's topology/work limits. An unusually complex scan remains explicitly
        // unexported rather than publishing geometry that would make the whole map unsavable.
        if (!SpatialValidation.validObservedRegions(regions)) return Result(emptyList(), true)
        return Result(regions, truncated)
    }

    private fun trace(edges: Map<Vertex, Vertex>): List<List<Vertex>>? {
        if (edges.isEmpty() || edges.keys != edges.values.toSet()) return null
        val unused = edges.keys.toMutableSet()
        val rings = ArrayList<List<Vertex>>()
        while (unused.isNotEmpty()) {
            val first = unused.first()
            val ring = ArrayList<Vertex>()
            var current = first
            do {
                if (!unused.remove(current)) return null
                ring += current
                current = edges[current] ?: return null
            } while (current != first)
            rings += ring
        }
        return rings
    }

    private fun simplify(ring: List<Vertex>): List<Vertex> = ring.filterIndexed { i, current ->
        val previous = ring[(i - 1 + ring.size) % ring.size]
        val next = ring[(i + 1) % ring.size]
        (current.x - previous.x) * (next.y - current.y) != (current.y - previous.y) * (next.x - current.x)
    }

    private fun twiceArea(ring: List<Vertex>): Long = ring.indices.sumOf { i ->
        val a = ring[i]; val b = ring[(i + 1) % ring.size]
        a.x.toLong() * b.y - a.y.toLong() * b.x
    }

    private fun area(ring: List<MetricPoint>): Double {
        val origin = ring.first()
        return ring.indices.sumOf { i ->
            val a = ring[i]; val b = ring[(i + 1) % ring.size]
            (a.x - origin.x) * (b.y - origin.y) - (a.y - origin.y) * (b.x - origin.x)
        } / 2.0
    }

    private fun wallNear(raster: OccupancyRaster, walls: List<WallSegment>, a: Vertex, b: Vertex): Boolean {
        val x = raster.originX + (a.x + b.x) * raster.cellSizeMeters / 2
        val y = raster.originY + (a.y + b.y) * raster.cellSizeMeters / 2
        return walls.any { wall ->
            if (wall.independentFrames < 2) return@any false
            val dx = wall.end.x - wall.start.x; val dy = wall.end.y - wall.start.y
            val length2 = dx * dx + dy * dy
            val t = if (length2 == 0.0) 0.0 else (((x - wall.start.x) * dx + (y - wall.start.y) * dy) / length2).coerceIn(0.0, 1.0)
            hypot(x - (wall.start.x + t * dx), y - (wall.start.y + t * dy)) <= raster.cellSizeMeters * 1.5
        }
    }

    private const val MIN_REGION_CELLS = 4
    private const val MAX_INPUT_CELLS = 250_000
    private const val MAX_REGIONS = 128
    private const val MAX_RING_VERTICES = 2048
    private const val MAX_HOLES = 64
    private const val MAX_BOUNDARY_EDGES = 32_768
    private const val MAX_WALL_CANDIDATES = 500
}
