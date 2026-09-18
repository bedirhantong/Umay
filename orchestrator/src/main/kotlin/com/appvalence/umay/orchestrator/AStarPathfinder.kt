package com.appvalence.umay.orchestrator

import com.appvalence.umay.coremodel.BuildingMap
import com.appvalence.umay.coremodel.FloorMap
import com.appvalence.umay.coremodel.MapEdge
import com.appvalence.umay.coremodel.MapNode
import kotlin.math.hypot

/**
 * A* shortest path over a node-edge graph (FR-10). Edge distance is used both
 * as the path cost and, via straight-line distance between nodes, as the
 * heuristic — admissible because it can never overestimate the true walking
 * distance along edges.
 *
 * [blockedEdges] lets a caller exclude specific edges (e.g. one an obstacle
 * is currently occupying, see [ObstacleEdgeMapper]) without mutating the
 * underlying graph; edges are treated as undirected for blocking purposes
 * (either direction of the pair blocks it).
 */
object AStarPathfinder {
    fun findPath(
        floorMap: FloorMap,
        startNodeId: String,
        goalNodeId: String,
        blockedEdges: Set<Pair<String, String>> = emptySet(),
    ): List<MapNode>? =
        search(floorMap.nodes, floorMap.edges, startNodeId, goalNodeId, blockedEdges) { a, b -> hypot(a.x - b.x, a.y - b.y) }

    /**
     * Routes across the whole building: every floor's nodes/edges plus
     * [BuildingMap.floorTransitions] (stairs/elevator crossings), treated as
     * ordinary edges weighted by their fixed cost.
     *
     * Straight-line distance is meaningless between two floors — each floor's
     * (x, y) comes from an independent PDR origin reset during survey, so
     * comparing them could make the heuristic *overestimate* and break A*'s
     * optimality guarantee. The heuristic is 0 whenever the two nodes are on
     * different floors instead (still admissible, just uninformed there,
     * which is irrelevant at building scale).
     */
    fun findPathAcrossFloors(
        buildingMap: BuildingMap,
        startNodeId: String,
        goalNodeId: String,
        blockedEdges: Set<Pair<String, String>> = emptySet(),
    ): List<MapNode>? {
        val nodes = buildingMap.floors.flatMap { it.nodes }
        val nodeFloor = buildingMap.floors.flatMap { floor -> floor.nodes.map { it.id to floor.floorIndex } }.toMap()
        val edges = buildingMap.floors.flatMap { it.edges } + buildingMap.floorTransitions.map {
            MapEdge(fromId = it.fromNodeId, toId = it.toNodeId, accessible = true, distanceMeters = it.costMeters)
        }
        return search(nodes, edges, startNodeId, goalNodeId, blockedEdges) { a, b ->
            if (nodeFloor[a.id] == nodeFloor[b.id]) hypot(a.x - b.x, a.y - b.y) else 0.0
        }
    }

    private fun search(
        nodes: List<MapNode>,
        edges: List<MapEdge>,
        startNodeId: String,
        goalNodeId: String,
        blockedEdges: Set<Pair<String, String>>,
        heuristic: (MapNode, MapNode) -> Double,
    ): List<MapNode>? {
        val nodesById = nodes.associateBy { it.id }
        val start = nodesById[startNodeId] ?: return null
        val goal = nodesById[goalNodeId] ?: return null
        if (start.id == goal.id) return listOf(start)

        val adjacency = buildAdjacency(edges, blockedEdges)

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

    private fun buildAdjacency(edges: List<MapEdge>, blockedEdges: Set<Pair<String, String>>): Map<String, List<AdjacentEdge>> {
        val adjacency = mutableMapOf<String, MutableList<AdjacentEdge>>()
        for (edge in edges) {
            if (!edge.accessible) continue
            if ((edge.fromId to edge.toId) in blockedEdges || (edge.toId to edge.fromId) in blockedEdges) continue
            adjacency.getOrPut(edge.fromId) { mutableListOf() }.add(AdjacentEdge(edge.toId, edge.distanceMeters))
            adjacency.getOrPut(edge.toId) { mutableListOf() }.add(AdjacentEdge(edge.fromId, edge.distanceMeters))
        }
        return adjacency
    }

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
