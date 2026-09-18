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
 */
data class TaggedWaypoint(
    val name: String,
    val x: Double,
    val y: Double,
    val floor: Int,
    val fingerprint: List<RadioFingerprint>,
)
