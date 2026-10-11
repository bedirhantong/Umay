package com.appvalence.umay.simulation

import com.appvalence.umay.coremodel.DetectedObstacle
import com.appvalence.umay.coremodel.ObstacleKind
import com.appvalence.umay.coremodel.ObstacleSensorSource
import com.appvalence.umay.perception.ObstacleClasses
import com.appvalence.umay.perception.ObstacleGeometry
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.tan
import kotlin.random.Random

/**
 * Something standing in the way: a chair, a person, a cleaning cart. [blocking]
 * ones fill the corridor (the walker has to turn back); the rest can be
 * stepped around once noticed.
 */
internal data class VirtualObstacle(
    val id: String,
    val kind: ObstacleKind,
    val x: Double,
    val y: Double,
    val heightMeters: Double,
    val blocking: Boolean = false,
)

/**
 * What the app's obstacle detector would report, standing in for its neural
 * network, which can't run here: an obstacle in the camera's view is found
 * with some probability (an assumption, not a measurement of the model), its
 * box is where the camera's true optics put it, and its distance and
 * direction are then judged from that box by the app's own code
 * ([ObstacleClasses.distanceMeters], [ObstacleGeometry]). So this tests what
 * the app does with a detection, and its geometry, not the detection itself.
 */
internal class VirtualObstacleSensor(
    private val building: VirtualBuilding,
    private val obstacles: List<VirtualObstacle>,
    private val random: Random,
    private val width: Int = 480,
    private val height: Int = 640,
    private val horizontalFovDeg: Double = 50.0,
    private val cameraHeightMeters: Double = 1.3,
) {
    private val focalPx = width / (2 * tan(Math.toRadians(horizontalFovDeg / 2)))

    fun detect(x: Double, y: Double, headingDeg: Double, timestampMs: Long): List<DetectedObstacle> = obstacles.mapNotNull { obstacle ->
        val distance = hypot(obstacle.x - x, obstacle.y - y)
        val relative = angle(Math.toDegrees(atan2(obstacle.x - x, obstacle.y - y)) - headingDeg)
        if (distance > MAX_RANGE_METERS || abs(relative) > horizontalFovDeg / 2 || !building.lineOfSight(x, y, obstacle.x, obstacle.y)) return@mapNotNull null
        if (random.nextDouble() > if (distance <= NEAR_METERS) NEAR_RECALL else FAR_RECALL) return@mapNotNull null
        val depth = distance * cos(Math.toRadians(relative))
        val bottom = (height / 2.0 + cameraHeightMeters * focalPx / depth).coerceAtMost(height.toDouble())
        val top = (height / 2.0 + (cameraHeightMeters - obstacle.heightMeters) * focalPx / depth).coerceAtLeast(0.0)
        if (bottom - top < MIN_BOX_PX) return@mapNotNull null
        val centerX = width / 2.0 + focalPx * tan(Math.toRadians(relative))
        val obstacleClass = ObstacleClasses.byIndex.values.first { it.kind == obstacle.kind }
        DetectedObstacle(
            id = obstacle.id,
            type = obstacleClass.type,
            distanceMeters = ObstacleClasses.distanceMeters(obstacleClass, top.toFloat(), bottom.toFloat(), height),
            bearingDeg = ObstacleGeometry.estimateBearingDegrees(centerX.toFloat(), width),
            relativeVelocityMps = null,
            confidence = 0.8f,
            detectedAtMs = timestampMs,
            sensorSource = ObstacleSensorSource.CAMERA_ML,
            kind = obstacle.kind,
        )
    }

    private fun angle(deg: Double) = ((deg % 360) + 540) % 360 - 180

    private companion object {
        const val MAX_RANGE_METERS = 8.0
        const val NEAR_METERS = 4.0
        const val NEAR_RECALL = 0.9
        const val FAR_RECALL = 0.6
        const val MIN_BOX_PX = 12.0
    }
}
