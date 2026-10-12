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

    /**
     * How wide something [distanceMeters] away looks when its box is [boundingBoxWidthPx]
     * of a frame [imageWidthPx] wide: the same pinhole model, sideways.
     */
    fun estimateWidthMeters(
        boundingBoxWidthPx: Float,
        imageWidthPx: Int,
        distanceMeters: Double,
        horizontalFovDegrees: Double = ASSUMED_HORIZONTAL_FOV_DEGREES,
    ): Double {
        if (imageWidthPx <= 0 || boundingBoxWidthPx <= 0f || !distanceMeters.isFinite()) return 0.0
        val focalLengthPx = imageWidthPx / (2.0 * tan(Math.toRadians(horizontalFovDegrees / 2.0)))
        return boundingBoxWidthPx * distanceMeters / focalLengthPx
    }

    /**
     * [estimateWidthMeters] for a box from [leftPx] to [rightPx], or null when it runs
     * off either side of the frame: then it's wider than it looks, by how much nobody
     * knows, and "there's room beside it" mustn't be concluded from it.
     */
    fun widthIfWhollySeen(leftPx: Float, rightPx: Float, imageWidthPx: Int, distanceMeters: Double): Double? {
        val margin = imageWidthPx * FRAME_EDGE_FRACTION
        if (leftPx <= margin || rightPx >= imageWidthPx - margin) return null
        return estimateWidthMeters(rightPx - leftPx, imageWidthPx, distanceMeters)
    }

    private const val FRAME_EDGE_FRACTION = 0.01

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
