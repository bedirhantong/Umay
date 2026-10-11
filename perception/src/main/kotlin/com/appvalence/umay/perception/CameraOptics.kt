package com.appvalence.umay.perception

import kotlin.math.atan
import kotlin.math.tan

/**
 * What the app assumes about a phone's main camera, in one place: how wide its
 * longer side sees. Every distance judged from an image (a person's height, a
 * marker's size) and every direction depends on it, and which side is the
 * longer one depends on how the frame is turned — the navigation frames are
 * upright (the phone held at the chest), the marker scanner's are the
 * sensor's own, on their side. Not measured per phone yet; field logs carry
 * it so it can be.
 */
object CameraOptics {
    /** A typical phone main camera's 4:3 frame, along its longer side (about 63–70° on current phones). */
    const val LONG_SIDE_FOV_DEGREES = 65.0

    /** How wide a [widthPx] × [heightPx] frame sees across its width. */
    fun fovAcrossDegrees(widthPx: Int, heightPx: Int): Double = fovOf(widthPx, heightPx)

    /** How wide a [widthPx] × [heightPx] frame sees from top to bottom. */
    fun fovDownDegrees(widthPx: Int, heightPx: Int): Double = fovOf(heightPx, widthPx)

    private fun fovOf(sidePx: Int, otherPx: Int): Double {
        if (sidePx >= otherPx || otherPx <= 0) return LONG_SIDE_FOV_DEGREES
        val halfLong = Math.toRadians(LONG_SIDE_FOV_DEGREES / 2)
        return Math.toDegrees(2 * atan(tan(halfLong) * sidePx / otherPx))
    }
}
