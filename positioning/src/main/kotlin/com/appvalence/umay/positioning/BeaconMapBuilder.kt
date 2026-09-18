package com.appvalence.umay.positioning

import com.appvalence.umay.coremodel.BuildingMap
import com.appvalence.umay.coremodel.FloorMap
import com.appvalence.umay.coremodel.MapEdge
import com.appvalence.umay.coremodel.MapNode
import com.appvalence.umay.coremodel.NodeType
import kotlin.math.hypot

/**
 * Turns solved beacon positions ([BeaconSurveyResult], from [BeaconSurveySolver])
 * into a routable [BuildingMap]: one [MapNode] of type [NodeType.BLE_ANCHOR] per
 * beacon, connected to every other beacon on the same floor within
 * [maxEdgeDistanceMeters] by a [MapEdge] weighted by straight-line distance.
 *
 * This is an approximation, not a substitute for a properly surveyed corridor
 * graph (doors, stairs, accessibility flags): it assumes beacons were placed
 * along walkable routes, so treating nearby beacons as directly connected is
 * a reasonable stand-in until someone authors the real topology by hand. It
 * exists so [AStarPathfinder] has *something* to route over as soon as a
 * beacon survey is done, rather than nothing at all.
 */
object BeaconMapBuilder {
    fun build(
        buildingId: String,
        results: List<BeaconSurveyResult>,
        maxEdgeDistanceMeters: Double = DEFAULT_MAX_EDGE_DISTANCE_METERS,
    ): BuildingMap {
        val floors = results
            .groupBy { it.floor }
            .map { (floorIndex, floorResults) -> buildFloor(floorIndex, floorResults, maxEdgeDistanceMeters) }
            .sortedBy { it.floorIndex }
        return BuildingMap(id = buildingId, floors = floors)
    }

    private fun buildFloor(floorIndex: Int, results: List<BeaconSurveyResult>, maxEdgeDistanceMeters: Double): FloorMap {
        val nodes = results.map { MapNode(id = it.beaconId, x = it.x, y = it.y, type = NodeType.BLE_ANCHOR) }

        val edges = mutableListOf<MapEdge>()
        for (i in nodes.indices) {
            for (j in i + 1 until nodes.size) {
                val a = nodes[i]
                val b = nodes[j]
                val distance = hypot(a.x - b.x, a.y - b.y)
                if (distance <= maxEdgeDistanceMeters) {
                    edges += MapEdge(fromId = a.id, toId = b.id, accessible = true, distanceMeters = distance)
                }
            }
        }

        return FloorMap(floorIndex = floorIndex, nodes = nodes, edges = edges)
    }

    const val DEFAULT_MAX_EDGE_DISTANCE_METERS = 20.0
}
