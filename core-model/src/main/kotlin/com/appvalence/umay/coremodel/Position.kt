package com.appvalence.umay.coremodel

enum class PositionSource { BLE_TRILATERATION, RADIO_FINGERPRINT, PEDESTRIAN_DEAD_RECKONING, MAP_MATCHED, FUSED }

data class Position(
    val x: Double,
    val y: Double,
    val floor: Int,
    val headingDeg: Double,
    val accuracyMeters: Double,
    val source: PositionSource,
    val timestampMs: Long,
)

data class StepEvent(
    val stepLengthMeters: Double,
    val headingDeltaDeg: Double,
    val timestampMs: Long,
)

/**
 * A raw BLE beacon sighting. [beaconId] is a stable key ("uuid-major-minor",
 * or the MAC address for non-iBeacon advertisers) so a trilateration or
 * fingerprint-matching engine can look it up against a surveyed [MapNode] of
 * type [NodeType.BLE_ANCHOR] or [NodeType.WAYPOINT] once the building's
 * beacons have been mapped to physical coordinates on-site.
 */
data class BleBeaconReading(
    val beaconId: String,
    val macAddress: String,
    val deviceName: String?,
    val uuid: String?,
    val major: Int?,
    val minor: Int?,
    val rssi: Int,
    val txPowerAtOneMeter: Int?,
    val estimatedDistanceMeters: Double,
    val timestampMs: Long,
)
