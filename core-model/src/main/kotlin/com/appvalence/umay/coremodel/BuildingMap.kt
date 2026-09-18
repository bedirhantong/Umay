package com.appvalence.umay.coremodel

import kotlinx.serialization.Serializable

@Serializable
enum class NodeType { CORRIDOR, DOOR, STAIRS, ELEVATOR, RAMP, DESTINATION, UWB_ANCHOR, BLE_ANCHOR }

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

@Serializable
data class BuildingMap(
    val id: String,
    val floors: List<FloorMap>,
)
