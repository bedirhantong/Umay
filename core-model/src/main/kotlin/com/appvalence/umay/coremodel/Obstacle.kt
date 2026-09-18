package com.appvalence.umay.coremodel

enum class ObstacleType { PERSON, STATIC_OBJECT, OPEN_DOOR, UNKNOWN }

enum class ObstacleSensorSource { CAMERA_ML, FUSED }

data class DetectedObstacle(
    val id: String,
    val type: ObstacleType,
    val distanceMeters: Double,
    val bearingDeg: Double,
    val relativeVelocityMps: Double?,
    val confidence: Float,
    val detectedAtMs: Long,
    val sensorSource: ObstacleSensorSource,
)
