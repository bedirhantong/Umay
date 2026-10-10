package com.appvalence.umay.coremodel

import kotlinx.serialization.json.Json

/**
 * JSON (de)serialization for [BuildingMap] (Faz 3). This is deliberately just
 * string-in/string-out — where the resulting JSON is actually stored (a file
 * under the app's private storage, a shared asset, etc.) is a platform
 * concern that lives outside `core-model`.
 */
object BuildingMapJson {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    fun encode(map: BuildingMap): String = json.encodeToString(BuildingMap.serializer(), map)

    fun decode(text: String): BuildingMap = json.decodeFromString(BuildingMap.serializer(), text)

    /**
     * Why [map] can't be used for navigation — empty when it's fine. A map this
     * app saved itself always passes; this exists for maps arriving from outside
     * (imported from another device), where a hand-edited or truncated file can
     * decode cleanly as JSON yet still reference nodes that don't exist, which
     * would otherwise only surface later as a route that silently can't be found.
     * Typed rather than text: turning a problem into words is the UI's job
     * (and its string resources').
     */
    fun problems(map: BuildingMap): List<MapProblem> {
        val problems = mutableListOf<MapProblem>()
        if (map.id.isBlank()) problems += MapProblem.BlankName

        val allNodes = map.floors.flatMap { it.nodes }
        if (allNodes.isEmpty()) problems += MapProblem.NoNodes
        if (allNodes.map { it.id }.toSet().size != allNodes.size) problems += MapProblem.DuplicateNodeIds
        if (map.floors.map { it.floorIndex }.toSet().size != map.floors.size) problems += MapProblem.DuplicateFloors

        val danglingEdges = map.floors.sumOf { floor ->
            val floorNodeIds = floor.nodes.map { it.id }.toSet()
            floor.edges.count { it.fromId !in floorNodeIds || it.toId !in floorNodeIds }
        }
        if (danglingEdges > 0) problems += MapProblem.DanglingEdges(danglingEdges)

        val nodeFloor = map.floors.flatMap { floor -> floor.nodes.map { it.id to floor.floorIndex } }.toMap()
        val danglingTransitions = map.floorTransitions.count {
            nodeFloor[it.fromNodeId] != it.fromFloorIndex || nodeFloor[it.toNodeId] != it.toFloorIndex
        }
        if (danglingTransitions > 0) problems += MapProblem.DanglingTransitions(danglingTransitions)

        val brokenWallMaps = map.floors.count { floor -> floor.occupancy?.let { it.cellSizeMeters <= 0.0 || it.decodeCells() == null } == true }
        if (brokenWallMaps > 0) problems += MapProblem.InvalidWallMap(brokenWallMaps)

        return problems
    }
}

sealed interface MapProblem {
    data object BlankName : MapProblem
    data object NoNodes : MapProblem
    data object DuplicateNodeIds : MapProblem
    data object DuplicateFloors : MapProblem
    data class DanglingEdges(val count: Int) : MapProblem
    data class DanglingTransitions(val count: Int) : MapProblem
    data class InvalidWallMap(val floorCount: Int) : MapProblem
}
