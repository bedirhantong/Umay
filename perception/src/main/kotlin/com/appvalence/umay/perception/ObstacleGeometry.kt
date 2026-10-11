package com.appvalence.umay.perception

import kotlin.math.tan

/**
 * Monocular distance/bearing estimation for a detected person (FR-6).
 *
 * Distance uses the pinhole camera model with an assumed average adult
 * height, since Faz 1 does not yet have per-device camera calibration
 * (intrinsics vary by phone). This is intentionally approximate — accuracy
 * is expected to improve once calibrated per-device intrinsics are
 * available in a later phase.
 */
object ObstacleGeometry {
    const val AVERAGE_PERSON_HEIGHT_METERS = 1.7
    // Navigation frames are upright: their height is the camera's longer side.
    const val ASSUMED_VERTICAL_FOV_DEGREES = CameraOptics.LONG_SIDE_FOV_DEGREES
    val ASSUMED_HORIZONTAL_FOV_DEGREES: Double = CameraOptics.fovAcrossDegrees(3, 4)

    fun estimateDistanceMeters(
        boundingBoxHeightPx: Float,
        imageHeightPx: Int,
        realHeightMeters: Double = AVERAGE_PERSON_HEIGHT_METERS,
        verticalFovDegrees: Double = ASSUMED_VERTICAL_FOV_DEGREES,
    ): Double {
        if (boundingBoxHeightPx <= 0f) return Double.POSITIVE_INFINITY
        val focalLengthPx = imageHeightPx / (2.0 * tan(Math.toRadians(verticalFovDegrees / 2.0)))
        return (realHeightMeters * focalLengthPx) / boundingBoxHeightPx
    }

    fun estimateBearingDegrees(
        boundingBoxCenterXPx: Float,
        imageWidthPx: Int,
        horizontalFovDegrees: Double = ASSUMED_HORIZONTAL_FOV_DEGREES,
    ): Double {
        if (imageWidthPx <= 0) return 0.0
        val normalizedOffset = (boundingBoxCenterXPx / imageWidthPx) - 0.5
        return normalizedOffset * horizontalFovDegrees
    }
}
