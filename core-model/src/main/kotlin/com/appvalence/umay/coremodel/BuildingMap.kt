package com.appvalence.umay.coremodel

enum class NodeType { CORRIDOR, DOOR, STAIRS, ELEVATOR, RAMP, DESTINATION, UWB_ANCHOR, BLE_ANCHOR }

data class MapNode(
    val id: String,
    val x: Double,
    val y: Double,
    val type: NodeType,
)

data class MapEdge(
    val fromId: String,
    val toId: String,
    val accessible: Boolean,
    val distanceMeters: Double,
)

data class FloorMap(
    val floorIndex: Int,
    val nodes: List<MapNode>,
    val edges: List<MapEdge>,
)

data class BuildingMap(
    val id: String,
    val floors: List<FloorMap>,
)
