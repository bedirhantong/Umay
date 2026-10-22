package com.appvalence.umay.positioning.mapping

import com.appvalence.umay.coremodel.*
import kotlin.math.*
import kotlin.random.Random

/** Validated depth back-projection in OpenGL camera coordinates, then rigid camera-to-world. */
data class SpatialPoint(val x: Double, val height: Double, val z: Double)

object MetricDepth {
    fun points(frame: DepthFrame, stride: Int = 3): List<SpatialPoint> {
        val count = frame.width.toLong() * frame.height
        if (stride <= 0 || frame.width <= 0 || frame.height <= 0 || count > 2_000_000 ||
            frame.depthMm.size.toLong() != count || frame.confidence?.size?.toLong()?.let { it != count } == true ||
            !frame.fx.isFinite() || !frame.fy.isFinite() || frame.fx <= 0 || frame.fy <= 0 ||
            !frame.cx.isFinite() || !frame.cy.isFinite() || !rigid(frame.cameraPose)) return emptyList()
        val m = frame.cameraPose
        return buildList {
            for (v in stride / 2 until frame.height step stride) for (u in stride / 2 until frame.width step stride) {
                val i = v * frame.width + u
                // Raw depth without confidence is not accepted in the reconstruction pipeline.
                val confidence = frame.confidence?.get(i)?.toInt()?.and(255) ?: continue
                if (confidence < DepthProjector.MIN_CONFIDENCE) continue
                val d = (frame.depthMm[i].toInt() and 65535) / 1000.0
                if (d !in DepthProjector.MIN_RANGE_METERS..DepthProjector.MAX_RANGE_METERS) continue
                val x = (u - frame.cx) * d / frame.fx
                val y = (frame.cy - v) * d / frame.fy
                val z = -d
                add(SpatialPoint(m[0]*x + m[4]*y + m[8]*z + m[12],
                    m[1]*x + m[5]*y + m[9]*z + m[13], m[2]*x + m[6]*y + m[10]*z + m[14]))
            }
        }
    }

    private fun rigid(m: FloatArray): Boolean {
        if (m.size != 16 || m.any { !it.isFinite() } || abs(m[15] - 1) > 0.001 ||
            listOf(3, 7, 11).any { abs(m[it]) > 0.001 }) return false
        for (a in 0..2) for (b in a..2) {
            val dot = (0..2).sumOf { (m[a*4+it] * m[b*4+it]).toDouble() }
            if (abs(dot - if (a == b) 1.0 else 0.0) > 0.01) return false
        }
        val determinant = m[0]*(m[5]*m[10]-m[9]*m[6]) - m[4]*(m[1]*m[10]-m[9]*m[2]) + m[8]*(m[1]*m[6]-m[5]*m[2])
        return abs(determinant - 1) < 0.01
    }
}

/**
 * Incremental bounded surface evidence. Conflicting data stays in the voxel set; no Manhattan
 * snapping, room closure or doorway invention. Call on the recorder's serial mapping dispatcher.
 */
class SpatialReconstructor(val frameId: String, val grid: OccupancyGrid = OccupancyGrid()) {
    private data class Key(val x: Int, val y: Int, val h: Int)
    private val voxels = LinkedHashMap<Key, SurfaceVoxel>()
    private var frames = 0L
    private var lastTimestamp = Long.MIN_VALUE
    private var floorCandidate: FloorSurface? = null
    private var full = false
    private var floorConflict = false
    private var frameTransform: ArToSurvey? = null
    private var alignmentRequired = false
    private val rejected = mutableListOf<WallSegment>()
    private val confirmed = mutableListOf<WallSegment>()
    private var lastWalls = emptyList<WallSegment>()
    // Worked out when the walls are (snapshot), not again for every depth frame.
    private var lastProbes = emptyList<OpeningDetector.Probe>()
    private val openingEvidence = OpeningRayEvidence()

    fun review(index: Int, accept: Boolean) {
        review(lastWalls.getOrNull(index) ?: return, accept)
    }

    fun review(observed: WallSegment, accept: Boolean) {
        val wall = lastWalls.firstOrNull { same(it, observed) } ?: return
        if (accept && confirmed.size < 500 && confirmed.none { same(it, wall) }) confirmed += wall.copy(evidence = GeometryEvidence.OPERATOR_CONFIRMED)
        else if (!accept && rejected.size < 500 && rejected.none { same(it, wall) }) rejected += wall
    }

    private fun same(a: WallSegment, b: WallSegment): Boolean =
        (hypot(a.start.x-b.start.x, a.start.y-b.start.y) < 0.15 && hypot(a.end.x-b.end.x,a.end.y-b.end.y) < 0.15) ||
        (hypot(a.start.x-b.end.x, a.start.y-b.end.y) < 0.15 && hypot(a.end.x-b.start.x,a.end.y-b.start.y) < 0.15)

    /** Floor hypotheses must have broad depth support and recur in separate depth observations. */
    private fun floor(points: List<SpatialPoint>, cameraY: Double, planes: List<Double>): FloorSurface? {
        val groups = points.filter { cameraY - it.height in 1.0..2.2 }
            .groupBy { floor(it.height / 0.05).toInt() }
        val candidate = groups.values.sortedBy { it.first().height }.firstOrNull { group ->
            group.size >= 24 && broadHorizontalSupport(group) &&
                planes.any { h -> h.isFinite() && abs(h - group.map { it.height }.average()) < 0.08 }
        } ?: return floorCandidate?.takeIf { cameraY - it.worldHeight in 1.0..2.2 }
        val height = candidate.map { it.height }.average()
        val rms = sqrt(candidate.sumOf { (it.height-height).pow(2) } / candidate.size)
        val previous = floorCandidate
        if (previous != null && previous.independentFrames >= 3) {
            // A different floor height is never silently substituted underneath existing walls.
            if (abs(previous.worldHeight-height) > 0.12) {
                floorConflict = true
                return null
            }
            // A settled floor stays at its height while ARCore's estimate wanders a few centimetres:
            // the evidence already gathered keeps counting rather than starting again from one frame.
            return previous.copy(independentFrames = previous.independentFrames + 1).also { floorCandidate = it }
        }
        return FloorSurface(height, if (previous != null && abs(previous.worldHeight-height) < 0.08) previous.independentFrames + 1 else 1, rms)
            .also { floorCandidate = it }
    }

    private fun broadHorizontalSupport(points: List<SpatialPoint>): Boolean {
        // Axis-aligned extents alone would accept a thin diagonal wall slice as a floor.
        val x = points.map { it.x }.average(); val z = points.map { it.z }.average()
        val xx = points.sumOf { (it.x-x).pow(2) } / points.size
        val zz = points.sumOf { (it.z-z).pow(2) } / points.size
        val xz = points.sumOf { (it.x-x)*(it.z-z) } / points.size
        val minorVariance = (xx+zz-sqrt((xx-zz).pow(2)+4*xz*xz))/2
        return minorVariance >= 0.02 && points.maxOf { it.x }-points.minOf { it.x } >= 0.6 &&
            points.maxOf { it.z }-points.minOf { it.z } >= 0.6
    }

    fun integrate(depth: DepthFrame, transform: ArToSurvey, cameraY: Double, planes: List<Double>): Boolean {
        if (frameTransform != null && frameTransform != transform) { alignmentRequired = true; return false }
        if (alignmentRequired || floorConflict || depth.timestampNs <= 0 || depth.timestampNs <= lastTimestamp || !cameraY.isFinite()) return false
        val points = MetricDepth.points(depth)
        if (points.isEmpty()) return false
        frameTransform = transform
        lastTimestamp = depth.timestampNs
        frames++
        val surface = floor(points, cameraY, planes)?.takeIf { it.independentFrames >= 3 } ?: return false
        val frameKeys = HashSet<Key>()
        val floorHits = ArrayList<Pair<Double, Double>>()
        val obstacleHits = ArrayList<Pair<Double, Double>>()
        val rayHits = ArrayList<OpeningRayEvidence.Point>()
        for (p in points) {
            val x = transform.x(p.x, p.z)
            val y = transform.y(p.x, p.z)
            if (!x.isFinite() || !y.isFinite() || abs(x) > 500 || abs(y) > 500) continue
            val h = p.height - surface.worldHeight
            if (abs(h) <= 0.08) floorHits += x to y
            if (h !in 0.25..2.2) continue
            // Over-door lintels are structural evidence, but they must not close the opening in 2D.
            if (h <= 1.9) obstacleHits += x to y
            rayHits += OpeningRayEvidence.Point(x, y, h)
            val key = Key(floor(x/0.1).toInt(), floor(y/0.1).toInt(), floor(h/0.15).toInt())
            if (!frameKeys.add(key)) continue
            val old = voxels[key]
            if (old == null) {
                if (voxels.size >= MAX_VOXELS) { full = true; continue }
                voxels[key] = SurfaceVoxel(x, y, h, 1, frames)
            } else {
                val n = min(old.frames, 20)
                voxels[key] = SurfaceVoxel((old.x*n+x)/(n+1), (old.y*n+y)/(n+1), (old.height*n+h)/(n+1), old.frames+1, frames)
            }
        }
        // Only floor endpoints establish floor evidence. A ray above a desk cannot certify walkability.
        grid.addSurfaceFrame(floorHits, obstacleHits)
        if (lastWalls.isNotEmpty()) {
            openingEvidence.observe(
                origin = OpeningRayEvidence.Point(
                    transform.x(depth.cameraPose[12].toDouble(), depth.cameraPose[14].toDouble()),
                    transform.y(depth.cameraPose[12].toDouble(), depth.cameraPose[14].toDouble()),
                    depth.cameraPose[13] - surface.worldHeight,
                ),
                hits = rayHits,
                probes = lastProbes,
                frame = frames,
            )
        }
        return true
    }

    fun snapshot(): SpatialMap {
        val walls = StructuralWalls.extract(voxels.values.toList()).filter { wall -> rejected.none { same(it, wall) } }
            .map { wall -> if (confirmed.any { same(it, wall) }) wall.copy(evidence = GeometryEvidence.OPERATOR_CONFIRMED) else wall }
        lastWalls = walls
        lastProbes = OpeningDetector.probes(walls)
        val issues = mutableSetOf(ReconstructionIssue.PARTIAL_COVERAGE)
        if ((floorCandidate?.independentFrames ?: 0) < 3) issues += ReconstructionIssue.FLOOR_UNRESOLVED
        if (floorConflict) { issues += ReconstructionIssue.CONFLICTING_SURFACES; issues += ReconstructionIssue.FLOOR_UNRESOLVED }
        if (alignmentRequired) issues += ReconstructionIssue.FRAME_ALIGNMENT_REQUIRED
        if (full || openingEvidence.full) issues += ReconstructionIssue.EVIDENCE_LIMIT_REACHED
        if (StructuralWalls.conflicts(walls)) issues += ReconstructionIssue.CONFLICTING_SURFACES
        val witnesses = openingEvidence.snapshot()
        val regions = ObservedRegionExtractor.extract(grid.snapshot(), walls)
        if (regions.truncated) issues += ReconstructionIssue.EVIDENCE_LIMIT_REACHED
        return SpatialMap(coordinateFrameId = frameId, worldToFloor = frameTransform?.let { MetricFrameTransform(it.rotationDeg,it.tx,it.ty) }, floor = floorCandidate, walls = walls,
            corners = StructuralWalls.corners(walls), openings = OpeningDetector.extract(walls, voxels.values.toList(), witnesses),
            openingWitnesses = witnesses, observedRegions = regions.regions, rejectedWalls = rejected.toList(), confirmedWalls = confirmed.toList(),
            voxels = voxels.values.toList(), integratedFrames = frames, issues = issues)
    }

    companion object { const val MAX_VOXELS = 30_000 }
}

/** Gravity-constrained RANSAC vertical planes, TLS refinement and continuity-based segmentation. */
object StructuralWalls {
    const val TOLERANCE = 0.07
    fun extract(evidence: List<SurfaceVoxel>): List<WallSegment> {
        // Fit wall spans from body-height evidence. Lintel points remain available to OpeningDetector,
        // but cannot bridge two jamb segments into one continuous 2D wall.
        val remaining = evidence.filter { it.frames >= 2 && it.height <= 1.9 && it.x.isFinite() && it.y.isFinite() && it.height.isFinite() }.toMutableList()
        val output = mutableListOf<WallSegment>()
        val random = Random(0)
        // Spatial sampling bounds fitting cost; full evidence remains available in persistence.
        if (remaining.size > 6_000) {
            val step = ceil(remaining.size / 6_000.0).toInt()
            val sampled = remaining.filterIndexed { i, _ -> i % step == 0 }
            remaining.clear(); remaining.addAll(sampled)
        }
        repeat(20) {
            if (remaining.size < 12) return output
            var bestCount = 0
            var bestAnchor: SurfaceVoxel? = null
            var bestNx = 0.0; var bestNy = 0.0
            repeat(96) hypothesis@ {
                val a = remaining[random.nextInt(remaining.size)]
                val b = remaining[random.nextInt(remaining.size)]
                val length = hypot(b.x-a.x, b.y-a.y)
                if (length < 0.5) return@hypothesis
                val nx = -(b.y-a.y)/length; val ny = (b.x-a.x)/length
                val count = remaining.count { abs((it.x-a.x)*nx + (it.y-a.y)*ny) <= TOLERANCE }
                if (count > bestCount) { bestCount = count; bestAnchor = a; bestNx = nx; bestNy = ny }
            }
            val anchor = bestAnchor ?: return output
            if (bestCount < 12) return output
            val best = remaining.filter { abs((it.x-anchor.x)*bestNx + (it.y-anchor.y)*bestNy) <= TOLERANCE }
            val mx = best.map { it.x }.average(); val my = best.map { it.y }.average()
            val xx = best.sumOf { (it.x-mx).pow(2) }; val yy = best.sumOf { (it.y-my).pow(2) }
            val xy = best.sumOf { (it.x-mx)*(it.y-my) }
            val angle = 0.5 * atan2(2*xy, xx-yy)
            val ux = cos(angle); val uy = sin(angle)
            val refined = best.filter { abs(-(it.x-mx)*uy + (it.y-my)*ux) <= TOLERANCE }
                .sortedBy { (it.x-mx)*ux + (it.y-my)*uy }
            val runs = mutableListOf<MutableList<SurfaceVoxel>>()
            for (p in refined) {
                val last = runs.lastOrNull()?.lastOrNull()
                if (last == null || (p.x-last.x)*ux + (p.y-last.y)*uy > 0.25) runs.add(mutableListOf())
                runs.last().add(p)
            }
            for (run in runs) {
                if (run.size < 12) continue
                val low = run.minOf { it.height }; val high = run.maxOf { it.height }
                val start = (run.first().x-mx)*ux + (run.first().y-my)*uy
                val end = (run.last().x-mx)*ux + (run.last().y-my)*uy
                if (end-start < 0.6 || high-low < 0.6) continue
                val heights = run.map { floor(it.height/0.15).toInt() }.distinct().sorted()
                if (heights.size < 5 || heights.zipWithNext().any { (a,b) -> b-a > 2 }) continue
                val rms = sqrt(run.sumOf { (-(it.x-mx)*uy + (it.y-my)*ux).pow(2) }/run.size)
                val support = run.minOf { it.frames }
                if (output.size >= 500) return output
                output += WallSegment(MetricPoint(mx+start*ux, my+start*uy), MetricPoint(mx+end*ux, my+end*uy),
                    low, high, run.size, support, rms,
                    if (support >= 3) GeometryEvidence.MULTI_FRAME else GeometryEvidence.OBSERVED_CANDIDATE)
            }
            remaining.removeAll(best.toSet())
        }
        return output
    }

    fun corners(walls: List<WallSegment>): List<MetricPoint> = buildList {
        for (i in walls.indices) for (j in i+1 until walls.size) {
            val a = walls[i]; val b = walls[j]
            if (min(a.maxHeight,b.maxHeight) - max(a.minHeight,b.minHeight) < 0.3) continue
            val ax = a.end.x-a.start.x; val ay = a.end.y-a.start.y
            val bx = b.end.x-b.start.x; val by = b.end.y-b.start.y
            val cross = ax*by-ay*bx
            if (abs(cross) < 0.5*hypot(ax,ay)*hypot(bx,by)) continue
            val dx = b.start.x-a.start.x; val dy = b.start.y-a.start.y
            val t = (dx*by-dy*bx)/cross; val u = (dx*ay-dy*ax)/cross
            val marginA = 0.12/hypot(ax,ay); val marginB = 0.12/hypot(bx,by)
            if (t !in -marginA..1+marginA || u !in -marginB..1+marginB) continue
            val p = MetricPoint(a.start.x+t*ax,a.start.y+t*ay)
            if (none { hypot(it.x-p.x,it.y-p.y) < 0.15 }) add(p)
        }
    }

    fun conflicts(walls: List<WallSegment>): Boolean {
        // Crossing interiors may be intersecting objects or drift, rather than a corner.
        return corners(walls).any { p -> walls.count { w ->
            val length = hypot(w.end.x-w.start.x,w.end.y-w.start.y)
            val t = ((p.x-w.start.x)*(w.end.x-w.start.x)+(p.y-w.start.y)*(w.end.y-w.start.y))/(length*length)
            val distance = abs((p.x-w.start.x)*(w.end.y-w.start.y)-(p.y-w.start.y)*(w.end.x-w.start.x))/length
            t in 0.15..0.85 && distance < TOLERANCE
        } >= 2 }
    }
}
