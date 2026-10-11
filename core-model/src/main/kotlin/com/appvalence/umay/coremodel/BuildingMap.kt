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

/** Nobody named this node (its [MapNode.name] is just its id) — like the corridor bends a camera survey adds. */
val MapNode.isUnnamed: Boolean get() = name == id

/**
 * Whether this node is a place a person would pick or be told about — not a
 * beacon's position or an unnamed bend in a corridor that only exists so the
 * graph follows the walked path. Named corridor nodes in hand-made maps stay places.
 */
val MapNode.isPlace: Boolean get() = type != NodeType.BLE_ANCHOR && !isCorridorBend

/** An unnamed corridor node: a bend a camera survey added to the graph, never something to pick or announce. */
val MapNode.isCorridorBend: Boolean get() = type == NodeType.CORRIDOR && isUnnamed

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
    // The camera-measured wall map of this floor, when it was surveyed with one
    // (null for maps recorded by footsteps alone, and every map saved before this).
    val occupancy: OccupancyRaster? = null,
    // Beacon signatures recorded every few metres along the surveyed path, not just
    // at named places — what lets positioning use every scan, not only confident
    // matches. Empty for maps surveyed before this.
    val radioMap: List<RadioSample> = emptyList(),
    // Doorways cut into [occupancy] (see MapDoors): one per DOOR node walked through.
    val doors: List<DoorOpening> = emptyList(),
    // How the compass lines up with this floor's map, measured during the survey; null
    // for maps surveyed before this, or where the compass was too inconsistent.
    val compass: CompassCalibration? = null,
)

/** The beacons heard at one spot of a floor during the survey (see [FloorMap.radioMap]). */
@Serializable
data class RadioSample(
    val x: Double,
    val y: Double,
    val fingerprint: List<RadioFingerprint>,
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
    // The surveyor's stride when this map was recorded (null for maps saved before
    // this was tracked). Every coordinate is steps × stride, so this is what would
    // let a map surveyed with an uncalibrated stride be rescaled to true meters later.
    val surveyStepLengthMeters: Double? = null,
)
