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
        // A raw beaconId ("uuid-major-minor") is meaningless to a person picking a
        // destination in the UI — "Nokta 1" on the floor they're standing on isn't
        // pretty either, but at least it's something a human can read and choose.
        val nodes = results.mapIndexed { index, result ->
            MapNode(
                id = result.beaconId,
                x = result.x,
                y = result.y,
                type = NodeType.BLE_ANCHOR,
                name = "Kat $floorIndex, Nokta ${index + 1}",
            )
        }

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
