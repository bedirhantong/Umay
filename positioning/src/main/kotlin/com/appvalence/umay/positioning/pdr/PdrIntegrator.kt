package com.appvalence.umay.positioning.pdr

import com.appvalence.umay.coremodel.Position
import com.appvalence.umay.coremodel.PositionSource
import com.appvalence.umay.coremodel.StepEvent
import kotlin.math.cos
import kotlin.math.sin

/**
 * Dead-reckons a 2D position by integrating [StepEvent]s (FR-2). Heading is
 * measured clockwise from north (0 = north, 90 = east). Accuracy degrades by
 * a fixed amount per step to model drift, and can be corrected via [resetTo]
 * whenever a fresh corrective fix is available (see [PositionFusionEngine]).
 */
class PdrIntegrator(
    startX: Double = 0.0,
    startY: Double = 0.0,
    startHeadingDeg: Double = 0.0,
    startAccuracyMeters: Double = DEFAULT_BASE_ACCURACY_METERS,
    private var floor: Int = 0,
    private val driftPerStepMeters: Double = DEFAULT_DRIFT_PER_STEP_METERS,
) {
    private var x = startX
    private var y = startY
    private var headingDeg = normalizeDegrees(startHeadingDeg)
    private var accuracyMeters = startAccuracyMeters

    fun resetTo(x: Double, y: Double, headingDeg: Double, accuracyMeters: Double) {
        this.x = x
        this.y = y
        this.headingDeg = normalizeDegrees(headingDeg)
        this.accuracyMeters = accuracyMeters
    }

    // Stairs/elevator crossing confirmed by the user (there's no barometer-based
    // detection): jumps straight to the arrival node's surveyed (x, y) on the new
    // floor, the same way resetTo() snaps to a fresh fix, since the old floor's
    // coordinates have nothing to do with the new one's origin.
    fun changeFloor(newFloor: Int, x: Double, y: Double, headingDeg: Double) {
        floor = newFloor
        resetTo(x, y, headingDeg, DEFAULT_BASE_ACCURACY_METERS)
    }

    fun integrate(step: StepEvent): Position {
        headingDeg = normalizeDegrees(headingDeg + step.headingDeltaDeg)
        val headingRad = Math.toRadians(headingDeg)
        x += step.stepLengthMeters * sin(headingRad)
        y += step.stepLengthMeters * cos(headingRad)
        accuracyMeters += driftPerStepMeters

        return Position(
            x = x,
            y = y,
            floor = floor,
            headingDeg = headingDeg,
            accuracyMeters = accuracyMeters,
            source = PositionSource.PEDESTRIAN_DEAD_RECKONING,
            timestampMs = step.timestampMs,
        )
    }

    private fun normalizeDegrees(deg: Double): Double = ((deg % 360.0) + 360.0) % 360.0

    companion object {
        const val DEFAULT_DRIFT_PER_STEP_METERS = 0.03
        const val DEFAULT_BASE_ACCURACY_METERS = 0.5
    }
}
