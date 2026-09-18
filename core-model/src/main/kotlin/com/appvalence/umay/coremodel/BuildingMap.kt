package com.appvalence.umay.coremodel

import kotlinx.serialization.Serializable

@Serializable
enum class NodeType { CORRIDOR, DOOR, STAIRS, ELEVATOR, RAMP, DESTINATION, BLE_ANCHOR, WAYPOINT }

@Serializable
data class MapNode(
    val id: String,
    val x: Double,
    val y: Double,
    val type: NodeType,
    // Human-readable label for UI (e.g. "Nokta 1", "Ana Giriş"); [id] stays the
    // stable technical key (a beacon UUID-major-minor, say) used for routing
    // joins and is rarely fit to show a person.
    val name: String = id,
    // RSSI signature captured at this node's location (only set for
    // NodeType.WAYPOINT), used to match live RSSI vectors against named
    // places instead of converting RSSI to a distance.
    val fingerprint: List<RadioFingerprint> = emptyList(),
)

@Serializable
data class MapEdge(
    val fromId: String,
    val toId: String,
    val accessible: Boolean,
    val distanceMeters: Double,
)

@Serializable
data class FloorMap(
    val floorIndex: Int,
    val nodes: List<MapNode>,
    val edges: List<MapEdge>,
)

// A stairs/elevator crossing between two nodes on different floors — the only way
// routing can move between floors, since a FloorMap's edges never span floors.
@Serializable
data class FloorTransition(
    val fromNodeId: String,
    val fromFloorIndex: Int,
    val toNodeId: String,
    val toFloorIndex: Int,
    val costMeters: Double,
)

@Serializable
data class BuildingMap(
    val id: String,
    val floors: List<FloorMap>,
    val floorTransitions: List<FloorTransition> = emptyList(),
)
