package com.appvalence.umay.coremodel

import kotlinx.serialization.Serializable

/**
 * One beacon's smoothed RSSI as part of a location's radio signature (FR: RSSI
 * fingerprinting). A [MapNode.fingerprint] is a list of these, captured at the
 * moment a place was named, and later matched against a live RSSI vector
 * instead of converting RSSI to a distance.
 */
@Serializable
data class RadioFingerprint(
    val beaconId: String,
    val rssi: Double,
)

/**
 * A place the user named while walking (survey recording), before it becomes a
 * [MapNode]. [x]/[y] come from the live PDR trajectory at the moment of
 * tagging, not manual entry. Captured in the order they were tagged, which is
 * the real walked order used to build corridor edges.
 *
 * [type] defaults to [NodeType.WAYPOINT] (an ordinary point) but the operator
 * can flag a hazard/landmark instead ([NodeType.STAIRS], [NodeType.DOOR],
 * [NodeType.ELEVATOR], [NodeType.RAMP]) — this is the only source of that
 * information in the whole app, since the camera model only ever detects
 * people, never static structure like stairs or doors.
 */
data class TaggedWaypoint(
    val name: String,
    val x: Double,
    val y: Double,
    val floor: Int,
    val fingerprint: List<RadioFingerprint>,
    val type: NodeType = NodeType.WAYPOINT,
    /**
     * How many trajectory points had been recorded when this was tagged, so the walked
     * path between two tags can be recovered (see BeaconMapBuilder's corner nodes).
     */
    val trajectoryIndex: Int? = null,
)
