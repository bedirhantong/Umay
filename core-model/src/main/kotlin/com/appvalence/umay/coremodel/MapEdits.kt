package com.appvalence.umay.coremodel

import kotlin.math.hypot

/**
 * Corrections to a saved map, so a surveyor's slip (a typo, a wrong type, a
 * stray place, a connection through a wall) doesn't mean walking the building
 * again. Every function returns a new map and leaves an unknown id alone.
 */
object MapEdits {

    fun rename(map: BuildingMap, nodeId: String, name: String): BuildingMap {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return map
        return map.mapNode(nodeId) { it.copy(name = trimmed) }
    }

    fun setType(map: BuildingMap, nodeId: String, type: NodeType): BuildingMap = map.mapNode(nodeId) { it.copy(type = type) }

    /**
     * Removes a place. One the route passes through (two or more connections) or
     * that leads to another floor becomes an unnamed corridor bend instead: the
     * corridor it was on keeps its shape and stays walkable, it just stops being
     * something to pick or announce. Its radio fingerprint stays too, for positioning.
     */
    fun deletePlace(map: BuildingMap, nodeId: String): BuildingMap {
        val floor = map.floors.firstOrNull { floor -> floor.nodes.any { it.id == nodeId } } ?: return map
        val connections = floor.edges.count { it.fromId == nodeId || it.toId == nodeId }
        val crossesFloors = map.floorTransitions.any { it.fromNodeId == nodeId || it.toNodeId == nodeId }
        if (connections >= 2 || crossesFloors) {
            return map.mapNode(nodeId) { it.copy(type = NodeType.CORRIDOR, name = it.id) }
        }
        return map.copy(
            floors = map.floors.map { f ->
                if (f !== floor) {
                    f
                } else {
                    f.copy(nodes = f.nodes.filter { it.id != nodeId }, edges = f.edges.filter { it.fromId != nodeId && it.toId != nodeId })
                }
            },
        )
    }

    /** Removes the connection between [a] and [b], in whichever direction it was recorded. */
    fun disconnect(map: BuildingMap, a: String, b: String): BuildingMap = map.copy(
        floors = map.floors.map { floor ->
            floor.copy(edges = floor.edges.filterNot { (it.fromId == a && it.toId == b) || (it.fromId == b && it.toId == a) })
        },
    )

    /**
     * Connects two places on the same floor in a straight line, for a corridor the
     * survey walked but didn't join up (a loop closed on foot). Unchanged if they're
     * on different floors or already connected.
     */
    fun connect(map: BuildingMap, a: String, b: String): BuildingMap {
        if (a == b) return map
        val floor = map.floors.firstOrNull { floor -> floor.nodes.any { it.id == a } && floor.nodes.any { it.id == b } } ?: return map
        if (floor.edges.any { (it.fromId == a && it.toId == b) || (it.fromId == b && it.toId == a) }) return map
        val from = floor.nodes.first { it.id == a }
        val to = floor.nodes.first { it.id == b }
        val edge = MapEdge(a, b, accessible = true, distanceMeters = hypot(to.x - from.x, to.y - from.y))
        return map.copy(floors = map.floors.map { if (it === floor) it.copy(edges = it.edges + edge) else it })
    }

    /**
     * One walkable connection between two places as a person thinks of it: the
     * straight edge between them, or the chain of unnamed corridor bends a camera
     * survey recorded on the way. [edges] are all the edges it consists of.
     */
    data class PlaceLink(val fromId: String, val toId: String, val edges: List<MapEdge>, val lengthMeters: Double)

    /** Every [PlaceLink] on [floor], each once. */
    fun placeLinks(floor: FloorMap): List<PlaceLink> {
        val nodesById = floor.nodes.associateBy { it.id }
        val edgesAt = mutableMapOf<String, MutableList<MapEdge>>()
        floor.edges.forEach { edge ->
            edgesAt.getOrPut(edge.fromId) { mutableListOf() } += edge
            edgesAt.getOrPut(edge.toId) { mutableListOf() } += edge
        }
        fun isPlace(id: String) = nodesById[id]?.isCorridorBend == false
        val links = mutableListOf<PlaceLink>()
        val used = mutableSetOf<MapEdge>()
        for (start in floor.nodes.filter { isPlace(it.id) }) {
            for (first in edgesAt[start.id].orEmpty()) {
                if (first in used) continue
                // Follow bends until the next place (or a dead end, or a fork of bends).
                val chain = mutableListOf(first)
                var at = if (first.fromId == start.id) first.toId else first.fromId
                while (!isPlace(at)) {
                    val onward = edgesAt[at].orEmpty().filter { it !in chain }
                    if (onward.size != 1) break
                    val next = onward.single()
                    chain += next
                    at = if (next.fromId == at) next.toId else next.fromId
                }
                if (!isPlace(at) || at == start.id) continue
                used += chain
                links += PlaceLink(start.id, at, chain, chain.sumOf { it.distanceMeters })
            }
        }
        return links
    }

    /** Removes [link]'s edges, and the bends that are left connected to nothing. */
    fun disconnect(map: BuildingMap, link: PlaceLink): BuildingMap = map.copy(
        floors = map.floors.map { floor ->
            if (link.edges.none { it in floor.edges }) return@map floor
            val edges = floor.edges.filter { it !in link.edges }
            val connected = edges.flatMap { listOf(it.fromId, it.toId) }.toSet()
            val inTransitions = map.floorTransitions.flatMap { listOf(it.fromNodeId, it.toNodeId) }.toSet()
            floor.copy(
                edges = edges,
                nodes = floor.nodes.filter { !it.isCorridorBend || it.id in connected || it.id in inTransitions },
            )
        },
    )

    /** Forgets what the wall map says within [radiusMeters] of ([x], [y]) — a person, a cart, a reflection. */
    fun eraseWalls(map: BuildingMap, floorIndex: Int, x: Double, y: Double, radiusMeters: Double): BuildingMap {
        val floor = map.floors.firstOrNull { it.floorIndex == floorIndex } ?: return map
        val raster = floor.occupancy ?: return map
        val cells = raster.decodeCells() ?: return map
        val size = raster.cellSizeMeters
        var changed = false
        for (row in 0 until raster.height) {
            val cellY = raster.originY + (row + 0.5) * size
            for (column in 0 until raster.width) {
                val cellX = raster.originX + (column + 0.5) * size
                val index = row * raster.width + column
                if (cells[index] != OccupancyRaster.UNKNOWN && hypot(cellX - x, cellY - y) <= radiusMeters) {
                    cells[index] = OccupancyRaster.UNKNOWN
                    changed = true
                }
            }
        }
        if (!changed) return map
        val erased = OccupancyRaster.of(size, raster.originX, raster.originY, raster.width, raster.height, cells)
        return map.copy(floors = map.floors.map { if (it === floor) it.copy(occupancy = erased) else it })
    }

    fun clearWalls(map: BuildingMap, floorIndex: Int): BuildingMap =
        map.copy(floors = map.floors.map { if (it.floorIndex == floorIndex) it.copy(occupancy = null) else it })

    /**
     * Places that can't be reached from the best-connected part of the building —
     * what a removed connection (or a survey never joined up) leaves stranded.
     * Floors are joined through their transitions; beacons don't count as places.
     */
    fun unreachablePlaces(map: BuildingMap): List<MapNode> {
        val neighbours = mutableMapOf<String, MutableSet<String>>()
        fun link(a: String, b: String) {
            neighbours.getOrPut(a) { mutableSetOf() } += b
            neighbours.getOrPut(b) { mutableSetOf() } += a
        }
        map.floors.forEach { floor -> floor.edges.forEach { link(it.fromId, it.toId) } }
        map.floorTransitions.forEach { link(it.fromNodeId, it.toNodeId) }

        val places = map.floors.flatMap { it.nodes }.filter { it.isPlace }
        val placeIds = places.map { it.id }.toSet()
        val unvisited = placeIds.toMutableSet()
        var largest = emptySet<String>()
        var largestPlaces = 0
        while (unvisited.isNotEmpty()) {
            val component = mutableSetOf<String>()
            val pending = ArrayDeque(listOf(unvisited.first()))
            while (pending.isNotEmpty()) {
                val id = pending.removeFirst()
                if (!component.add(id)) continue
                neighbours[id]?.forEach { if (it !in component) pending.add(it) }
            }
            unvisited -= component
            val componentPlaces = component.count { it in placeIds }
            if (componentPlaces > largestPlaces) {
                largest = component
                largestPlaces = componentPlaces
            }
        }
        return places.filter { it.id !in largest }
    }

    private fun BuildingMap.mapNode(nodeId: String, transform: (MapNode) -> MapNode): BuildingMap = copy(
        floors = floors.map { floor ->
            if (floor.nodes.none { it.id == nodeId }) floor else floor.copy(nodes = floor.nodes.map { if (it.id == nodeId) transform(it) else it })
        },
    )
}
