package com.appvalence.umay.coremodel

enum class ObstacleType { PERSON, STATIC_OBJECT, OPEN_DOOR, UNKNOWN }

enum class ObstacleSensorSource { CAMERA_ML, FUSED }

/** What the camera recognized, for saying it by name ("önünde bir sandalye var"). */
enum class ObstacleKind {
    PERSON, BICYCLE, MOTORCYCLE, FIRE_HYDRANT, BENCH, DOG, SUITCASE, CHAIR, COUCH, POTTED_PLANT, BED, TABLE, TOILET, REFRIGERATOR, UNKNOWN,
}

data class DetectedObstacle(
    val id: String,
    val type: ObstacleType,
    val distanceMeters: Double,
    val bearingDeg: Double,
    val relativeVelocityMps: Double?,
    val confidence: Float,
    val detectedAtMs: Long,
    val sensorSource: ObstacleSensorSource,
    val kind: ObstacleKind = if (type == ObstacleType.PERSON) ObstacleKind.PERSON else ObstacleKind.UNKNOWN,
)
