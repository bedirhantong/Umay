package com.appvalence.umay.positioning.mapping

import com.appvalence.umay.coremodel.*
import kotlin.math.*

/** Jamb gap hypotheses alone are never emitted as doors. Require lintel AND observed free volume. */
object OpeningDetector {
    data class Probe(val a: MetricPoint, val b: MetricPoint) {
        val width = hypot(b.x-a.x,b.y-a.y)
        val ux = (b.x-a.x)/width; val uy = (b.y-a.y)/width
    }

    fun probes(walls: List<WallSegment>): List<Probe> = buildList {
        for (i in walls.indices) for (j in i+1 until walls.size) {
            if (size >= MAX_PROBES) return@buildList
            val a=walls[i]; val b=walls[j]
            if (a.independentFrames < 3 || b.independentFrames < 3 || min(a.maxHeight,b.maxHeight) < 1.5) continue
            val ax=a.end.x-a.start.x; val ay=a.end.y-a.start.y
            val bx=b.end.x-b.start.x; val by=b.end.y-b.start.y
            val al=hypot(ax,ay); val bl=hypot(bx,by)
            if (abs(ax*by-ay*bx)/(al*bl) > sin(Math.toRadians(5.0))) continue
            val pair = listOf(a.start to b.start,a.start to b.end,a.end to b.start,a.end to b.end)
                .minBy { (p,q) -> hypot(q.x-p.x,q.y-p.y) }
            val probe=Probe(pair.first,pair.second)
            if (probe.width !in 0.65..1.4) continue
            if (abs(probe.ux*ay-probe.uy*ax)/al > sin(Math.toRadians(5.0))) continue
            // Both supporting wall spans must point away from the hypothesized opening.
            val amid=MetricPoint((a.start.x+a.end.x)/2,(a.start.y+a.end.y)/2)
            val bmid=MetricPoint((b.start.x+b.end.x)/2,(b.start.y+b.end.y)/2)
            if ((amid.x-probe.a.x)*probe.ux+(amid.y-probe.a.y)*probe.uy >= 0 ||
                (bmid.x-probe.b.x)*probe.ux+(bmid.y-probe.b.y)*probe.uy <= 0) continue
            add(probe)
        }
    }

    fun extract(walls: List<WallSegment>, voxels: List<SurfaceVoxel>, witnesses: List<OpeningClearanceWitness>): List<OpeningCandidate> =
        probes(walls).mapNotNull { p ->
            val lintel=voxels.filter { v ->
                val along=(v.x-p.a.x)*p.ux+(v.y-p.a.y)*p.uy
                val across=abs(-(v.x-p.a.x)*p.uy+(v.y-p.a.y)*p.ux)
                along in 0.1..p.width-0.1 && across <= 0.10 && v.height in 1.95..2.2 && v.frames >= 3
            }
            if (lintel.size < 3) return@mapNotNull null
            val extent=lintel.map { (it.x-p.a.x)*p.ux+(it.y-p.a.y)*p.uy }
            if (extent.max()-extent.min() < p.width*0.5) return@mapNotNull null
            var support=Int.MAX_VALUE
            for (fraction in listOf(0.25,0.5,0.75)) for (height in listOf(0.6,1.2,1.6)) {
                val x=p.a.x+fraction*p.width*p.ux; val y=p.a.y+fraction*p.width*p.uy
                val evidence=witnesses.filter { hypot(it.point.x-x,it.point.y-y) <= 0.13 && abs(it.height-height) <= 0.12 }
                    .maxOfOrNull { it.frames } ?: 0
                if (evidence < 3) return@mapNotNull null
                support=min(support,evidence)
            }
            // Objects observed within the passage contradict a positive opening classification.
            if (voxels.any { v ->
                    val along=(v.x-p.a.x)*p.ux+(v.y-p.a.y)*p.uy
                    along in 0.12..p.width-0.12 && abs(-(v.x-p.a.x)*p.uy+(v.y-p.a.y)*p.ux) <= 0.10 &&
                        v.height in 0.25..1.8 && v.frames >= 2
                }) return@mapNotNull null
            OpeningCandidate(p.a,p.b,lintel.minOf { it.height },1.6,min(support,lintel.minOf { it.frames }))
        }

    private const val MAX_PROBES = 32
}

/** Bounded witnesses at measured wall-gap planes, rather than an unbounded 3D free-space grid. */
class OpeningRayEvidence {
    data class Point(val x: Double, val y: Double, val height: Double)
    private data class Key(val x:Int,val y:Int,val h:Int)
    private val cells=LinkedHashMap<Key,OpeningClearanceWitness>()
    var full=false
        private set

    fun observe(origin: Point, hits: List<Point>, probes: List<OpeningDetector.Probe>, frame:Long) {
        val touched=HashSet<Key>()
        for (p in probes) {
            val from=-(origin.x-p.a.x)*p.uy+(origin.y-p.a.y)*p.ux
            if (abs(from)<0.2) continue
            for (hit in hits) {
                val to=-(hit.x-p.a.x)*p.uy+(hit.y-p.a.y)*p.ux
                if (from*to >= 0) continue
                val t=from/(from-to)
                val x=origin.x+(hit.x-origin.x)*t; val y=origin.y+(hit.y-origin.y)*t
                if (hypot(hit.x-x,hit.y-y)<0.3) continue
                val along=(x-p.a.x)*p.ux+(y-p.a.y)*p.uy
                val h=origin.height+(hit.height-origin.height)*t
                if (along !in 0.05..p.width-0.05 || h !in 0.4..1.8) continue
                val key=Key(floor(x/0.1).toInt(),floor(y/0.1).toInt(),floor(h/0.1).toInt())
                if (!touched.add(key)) continue
                val old=cells[key]
                if (old==null && cells.size>=MAX_WITNESSES) { full=true; continue }
                // At most one vote per independently acquired depth frame, even if many rays cross this voxel.
                if (old?.lastFrame==frame) continue
                cells[key]=OpeningClearanceWitness(MetricPoint(x,y),h,(old?.frames ?: 0)+1,frame)
            }
        }
    }
    fun snapshot()=cells.values.toList()
    companion object { const val MAX_WITNESSES=12_000 }
}
