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
