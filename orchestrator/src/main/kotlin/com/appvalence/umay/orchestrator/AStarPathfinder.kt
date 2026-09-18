package com.appvalence.umay.orchestrator

import com.appvalence.umay.coremodel.FloorMap
import com.appvalence.umay.coremodel.MapNode
import kotlin.math.hypot

/**
 * A* shortest path over a single floor's node-edge graph (FR-10). Edge
 * distance is used both as the path cost and, via straight-line distance
 * between nodes, as the heuristic — admissible because it can never
 * overestimate the true walking distance along edges.
 *
 * [blockedEdges] lets a caller exclude specific edges (e.g. one an obstacle
 * is currently occupying, see [ObstacleEdgeMapper]) without mutating the
 * underlying [FloorMap]; edges are treated as undirected for blocking
 * purposes (either direction of the pair blocks it).
 */
object AStarPathfinder {
    fun findPath(
        floorMap: FloorMap,
        startNodeId: String,
        goalNodeId: String,
        blockedEdges: Set<Pair<String, String>> = emptySet(),
    ): List<MapNode>? {
        val nodesById = floorMap.nodes.associateBy { it.id }
        val start = nodesById[startNodeId] ?: return null
        val goal = nodesById[goalNodeId] ?: return null
        if (start.id == goal.id) return listOf(start)

        val adjacency = buildAdjacency(floorMap, blockedEdges)

        val openSet = mutableSetOf(start.id)
        val cameFrom = mutableMapOf<String, String>()
        val gScore = mutableMapOf(start.id to 0.0)
        val fScore = mutableMapOf(start.id to heuristic(start, goal))

        while (openSet.isNotEmpty()) {
            val currentId = openSet.minByOrNull { fScore[it] ?: Double.MAX_VALUE } ?: break
            if (currentId == goal.id) return reconstructPath(cameFrom, currentId, nodesById)
            openSet.remove(currentId)

            val currentG = gScore[currentId] ?: continue
            for (edge in adjacency[currentId].orEmpty()) {
                val tentativeG = currentG + edge.distanceMeters
                if (tentativeG < (gScore[edge.toId] ?: Double.MAX_VALUE)) {
                    cameFrom[edge.toId] = currentId
                    gScore[edge.toId] = tentativeG
                    val neighborNode = nodesById[edge.toId] ?: continue
                    fScore[edge.toId] = tentativeG + heuristic(neighborNode, goal)
                    openSet.add(edge.toId)
                }
            }
        }
        return null // goal unreachable
    }

    private data class AdjacentEdge(val toId: String, val distanceMeters: Double)

    private fun buildAdjacency(floorMap: FloorMap, blockedEdges: Set<Pair<String, String>>): Map<String, List<AdjacentEdge>> {
        val adjacency = mutableMapOf<String, MutableList<AdjacentEdge>>()
        for (edge in floorMap.edges) {
            if (!edge.accessible) continue
            if ((edge.fromId to edge.toId) in blockedEdges || (edge.toId to edge.fromId) in blockedEdges) continue
            adjacency.getOrPut(edge.fromId) { mutableListOf() }.add(AdjacentEdge(edge.toId, edge.distanceMeters))
            adjacency.getOrPut(edge.toId) { mutableListOf() }.add(AdjacentEdge(edge.fromId, edge.distanceMeters))
        }
        return adjacency
    }

    private fun heuristic(a: MapNode, b: MapNode): Double = hypot(a.x - b.x, a.y - b.y)

    private fun reconstructPath(cameFrom: Map<String, String>, endId: String, nodesById: Map<String, MapNode>): List<MapNode> {
        val idsInOrder = mutableListOf(endId)
        var current = endId
        while (cameFrom.containsKey(current)) {
            current = cameFrom.getValue(current)
            idsInOrder.add(current)
        }
        return idsInOrder.reversed().mapNotNull { nodesById[it] }
    }
}
