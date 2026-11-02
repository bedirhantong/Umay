package com.appvalence.umay.positioning.mapping

import com.appvalence.umay.coremodel.FloorMap
import com.appvalence.umay.coremodel.MapEdge
import com.appvalence.umay.coremodel.OccupancyRaster
import com.appvalence.umay.coremodel.isPlace
import java.util.PriorityQueue
import kotlin.math.floor
import kotlin.math.hypot

/**
 * Shortcuts across open space. A survey connects places in the order they were
 * walked, so two places facing each other across a lobby may only be joined by
 * the long way round the surveyor took. Where the floor's wall map shows the
 * straight line between two places — and a body's width either side of it —
 * as floor actually seen, and the walked route is much longer, they're joined
 * directly. Unseen space is never trusted.
 *
 * Applied once, when a camera survey is saved: a link removed in the editor
 * afterwards stays removed.
 */
object OpenSpaceLinks {
    const val MAX_LINK_METERS = 15.0

    /** Half a walker's width plus a margin: the line must be clear this far either side. */
    const val CLEARANCE_METERS = 0.4

    /** Only worth a new link if the walked way is this many times longer. */
    const val DETOUR_FACTOR = 1.4

    fun add(floor: FloorMap): FloorMap {
        val raster = floor.occupancy ?: return floor
        val cells = raster.decodeCells() ?: return floor
        val places = floor.nodes.filter { it.isPlace }
        val edges = floor.edges.toMutableList()
        for (i in places.indices) {
            for (j in i + 1 until places.size) {
                val a = places[i]
                val b = places[j]
                val straight = hypot(b.x - a.x, b.y - a.y)
                if (straight > MAX_LINK_METERS || straight < 1.0) continue
                if (edges.any { (it.fromId == a.id && it.toId == b.id) || (it.fromId == b.id && it.toId == a.id) }) continue
                val walked = shortestPath(edges, a.id, b.id)
                if (walked <= straight * DETOUR_FACTOR) continue
                if (!clearLine(raster, cells, a.x, a.y, b.x, b.y)) continue
                edges += MapEdge(a.id, b.id, accessible = true, distanceMeters = straight)
            }
        }
        return if (edges.size == floor.edges.size) floor else floor.copy(edges = edges)
    }

    private fun clearLine(raster: OccupancyRaster, cells: ByteArray, ax: Double, ay: Double, bx: Double, by: Double): Boolean {
        val length = hypot(bx - ax, by - ay)
        val ux = (bx - ax) / length
        val uy = (by - ay) / length
        val step = raster.cellSizeMeters / 2
        // The ends stand at places, often next to a wall or in a doorway: judge what's between.
        var along = CLEARANCE_METERS
        while (along <= length - CLEARANCE_METERS) {
            var across = -CLEARANCE_METERS
            while (across <= CLEARANCE_METERS) {
                val x = ax + ux * along - uy * across
                val y = ay + uy * along + ux * across
                if (cellAt(raster, cells, x, y) != OccupancyRaster.FREE) return false
                across += step
            }
            along += step
        }
        return true
    }

    private fun cellAt(raster: OccupancyRaster, cells: ByteArray, x: Double, y: Double): Byte {
        val column = floor((x - raster.originX) / raster.cellSizeMeters).toInt()
        val row = floor((y - raster.originY) / raster.cellSizeMeters).toInt()
        if (column !in 0 until raster.width || row !in 0 until raster.height) return OccupancyRaster.UNKNOWN
        return cells[row * raster.width + column]
    }

    /** Dijkstra over the floor's edges; infinity when not connected at all. */
    private fun shortestPath(edges: List<MapEdge>, from: String, to: String): Double {
        val neighbours = HashMap<String, MutableList<Pair<String, Double>>>()
        edges.forEach {
            neighbours.getOrPut(it.fromId) { mutableListOf() } += it.toId to it.distanceMeters
            neighbours.getOrPut(it.toId) { mutableListOf() } += it.fromId to it.distanceMeters
        }
        val best = HashMap<String, Double>()
        val queue = PriorityQueue<Pair<String, Double>>(compareBy { it.second })
        queue += from to 0.0
        best[from] = 0.0
        while (queue.isNotEmpty()) {
            val (node, cost) = queue.poll()
            if (node == to) return cost
            if (cost > (best[node] ?: Double.MAX_VALUE)) continue
            neighbours[node].orEmpty().forEach { (next, length) ->
                val candidate = cost + length
                if (candidate < (best[next] ?: Double.MAX_VALUE)) {
                    best[next] = candidate
                    queue += next to candidate
                }
            }
        }
        return Double.POSITIVE_INFINITY
    }
}
