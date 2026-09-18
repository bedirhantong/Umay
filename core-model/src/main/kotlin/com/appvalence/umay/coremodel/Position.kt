package com.appvalence.umay.coremodel

enum class PositionSource { UWB_TRILATERATION, BLE_TRILATERATION, RADIO_FINGERPRINT, PEDESTRIAN_DEAD_RECKONING, MAP_MATCHED, FUSED }

data class Position(
    val x: Double,
    val y: Double,
    val floor: Int,
    val headingDeg: Double,
    val accuracyMeters: Double,
    val source: PositionSource,
    val timestampMs: Long,
)

data class UwbRangeReading(
    val anchorId: String,
    val distanceMeters: Double,
    val distanceStdDevMeters: Double,
    val lineOfSight: Boolean,
    val timestampMs: Long,
)

data class StepEvent(
    val stepLengthMeters: Double,
    val headingDeltaDeg: Double,
    val timestampMs: Long,
)

/**
 * A raw BLE beacon sighting (NFR-6 fallback when no UWB anchors are available).
 * [beaconId] is a stable key ("uuid-major-minor", or the MAC address for
 * non-iBeacon advertisers) so a future trilateration engine can look it up
 * against a surveyed [MapNode] of type [NodeType.BLE_ANCHOR] once the
 * building's beacons have been mapped to physical coordinates on-site.
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
