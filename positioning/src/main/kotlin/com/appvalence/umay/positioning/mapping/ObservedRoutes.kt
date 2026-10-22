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

    /** Half a walker's width plus a margin, as in [OpenSpaceLinks.CLEARANCE_METERS]: floor seen this far either side. */
    const val CLEARANCE_METERS = 0.4

    /** Of a cell's eight neighbours, how many must be seen floor for it to count as an isolated gap in it. */
    private const val SURROUNDED_BY_FLOOR = 6

    private fun clear(r: OccupancyRaster, cells: ByteArray, a: MapNode, b: MapNode, walls: List<WallSegment>): Boolean {
        val length = hypot(b.x-a.x,b.y-a.y)
        if (!length.isFinite() || length <= 0 || length > 100) return false
        val cell = r.cellSizeMeters
        val reach = ceil(CLEARANCE_METERS/cell).toInt() + 1
        // The ends stand at places, often in a doorway or against a wall, and where the surveyor stood
        // the floor under the phone was never in view: judge the stretch between them (a short edge, its middle).
        val from = min(CLEARANCE_METERS, length/2)
        val to = length - from
        val samples = if (to > from) ceil((to-from)/(cell/2)).toInt() else 0
        for (i in 0..samples) {
            val along = if (samples == 0) length/2 else from + (to-from)*i/samples
            val px = a.x+(b.x-a.x)*along/length; val py = a.y+(b.y-a.y)*along/length
            if (walls.any { wall ->
                val dx = wall.end.x-wall.start.x; val dy = wall.end.y-wall.start.y
                val squared = dx*dx+dy*dy
                val onWall = if (squared > 0) (((px-wall.start.x)*dx+(py-wall.start.y)*dy)/squared).coerceIn(0.0,1.0) else 0.0
                hypot(px-wall.start.x-onWall*dx,py-wall.start.y-onWall*dy) < CLEARANCE_METERS
            }) return false
            val column = floor((px-r.originX)/cell).toInt()
            val row = floor((py-r.originY)/cell).toInt()
            for (dx in -reach..reach) for (dy in -reach..reach) {
                val x = column+dx; val y = row+dy
                // A cell counts when its centre is within reach: a doorway of 0.8 m is 8 cells, none of them half-seen.
                val centreX = r.originX+(x+0.5)*cell; val centreY = r.originY+(y+0.5)*cell
                if (hypot(centreX-px,centreY-py) > CLEARANCE_METERS) continue
                if (!floorAt(r, cells, x, y)) return false
            }
        }
        return true
    }

    /**
     * Floor that was seen — or a single unseen cell with floor all round it: the depth camera samples
     * the ground more thinly the farther it looks, leaving isolated gaps inside floor it plainly saw.
     * Anything bigger than that, and any wall cell, still blocks.
     */
    private fun floorAt(r: OccupancyRaster, cells: ByteArray, x: Int, y: Int): Boolean {
        if (x !in 0 until r.width || y !in 0 until r.height) return false
        return when (cells[y*r.width+x]) {
            OccupancyRaster.FREE -> true
            OccupancyRaster.UNKNOWN -> {
                var free = 0
                for (dx in -1..1) for (dy in -1..1) {
                    if ((dx != 0 || dy != 0) && x+dx in 0 until r.width && y+dy in 0 until r.height && cells[(y+dy)*r.width+x+dx] == OccupancyRaster.FREE) free++
                }
                free >= SURROUNDED_BY_FLOOR
            }
            else -> false
        }
    }
}
