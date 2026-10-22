package com.appvalence.umay.positioning.mapping

import com.appvalence.umay.coremodel.*
import kotlin.math.*

/** Reconstructed-map edges need observed floor clearance; unknown cells and any wall candidate block them. */
object ObservedRoutes {
    fun restrict(map: FloorMap): FloorMap {
        val geometry = map.reconstruction ?: return map
        val raster = map.occupancy ?: return map.copy(edges = emptyList())
        val cells = raster.decodeCells() ?: return map.copy(edges = emptyList())
        if (geometry.issues.any { it == ReconstructionIssue.FLOOR_UNRESOLVED || it == ReconstructionIssue.CONFLICTING_SURFACES || it == ReconstructionIssue.FRAME_ALIGNMENT_REQUIRED })
            return map.copy(edges = emptyList())
        val nodes = map.nodes.associateBy { it.id }
        return map.copy(edges = map.edges.filter { edge ->
            val a = nodes[edge.fromId]; val b = nodes[edge.toId]
            a != null && b != null && clear(raster, cells, a, b, geometry.walls)
        })
    }

    private fun clear(r: OccupancyRaster, cells: ByteArray, a: MapNode, b: MapNode, walls: List<WallSegment>): Boolean {
        val length = hypot(b.x-a.x,b.y-a.y)
        if (!length.isFinite() || length <= 0 || length > 100) return false
        val samples = ceil(length/(r.cellSizeMeters/2)).toInt()
        val clearance = 0.4 + r.cellSizeMeters * sqrt(0.5)
        val radius = ceil(clearance/r.cellSizeMeters).toInt()
        for (i in 0..samples) {
            val t = i.toDouble()/samples
            val px = a.x+(b.x-a.x)*t; val py = a.y+(b.y-a.y)*t
            if (walls.any { wall ->
                val dx = wall.end.x-wall.start.x; val dy = wall.end.y-wall.start.y
                val squared = dx*dx+dy*dy
                val along = if (squared > 0) (((px-wall.start.x)*dx+(py-wall.start.y)*dy)/squared).coerceIn(0.0,1.0) else 0.0
                hypot(px-wall.start.x-along*dx,py-wall.start.y-along*dy) <= 0.4
            }) return false
            val column = floor((px-r.originX)/r.cellSizeMeters).toInt()
            val row = floor((a.y+(b.y-a.y)*t-r.originY)/r.cellSizeMeters).toInt()
            for (dx in -radius..radius) for (dy in -radius..radius) {
                if (hypot(dx.toDouble(),dy.toDouble())*r.cellSizeMeters > clearance) continue
                val x = column+dx; val y = row+dy
                if (x !in 0 until r.width || y !in 0 until r.height || cells[y*r.width+x] != OccupancyRaster.FREE) return false
            }
        }
        return true
    }
}
