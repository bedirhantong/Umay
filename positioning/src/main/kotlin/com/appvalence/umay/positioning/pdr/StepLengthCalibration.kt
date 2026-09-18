package com.appvalence.umay.positioning.pdr

/**
 * Personal stride from one calibration walk: the person walks a distance
 * they know (a measured corridor, 10 floor tiles of known size...) at their
 * normal pace while [StepDetector] counts steps, and stride = distance / steps.
 *
 * Stride varies a lot between people (height, cane use, pace) — a 0.75 m
 * population average can be 20-30% off for one person, and dead reckoning
 * multiplies that error into every meter walked, so this one number removes
 * more drift than any other tuning. Results outside a plausible human range
 * are rejected rather than saved, since they almost always mean missed or
 * double-counted steps, not an unusual walker.
 */
object StepLengthCalibration {
    const val MIN_DISTANCE_METERS = 5.0
    const val MIN_STEPS = 8
    const val MIN_PLAUSIBLE_STEP_METERS = 0.30
    const val MAX_PLAUSIBLE_STEP_METERS = 1.10

    sealed interface Result {
        data class Success(val stepLengthMeters: Double) : Result
        data object DistanceTooShort : Result
        data class TooFewSteps(val steps: Int) : Result
        data class Implausible(val stepLengthMeters: Double) : Result
    }

    fun compute(distanceMeters: Double, stepCount: Int): Result {
        if (distanceMeters < MIN_DISTANCE_METERS) return Result.DistanceTooShort
        if (stepCount < MIN_STEPS) return Result.TooFewSteps(stepCount)
        val stepLength = distanceMeters / stepCount
        if (stepLength !in MIN_PLAUSIBLE_STEP_METERS..MAX_PLAUSIBLE_STEP_METERS) return Result.Implausible(stepLength)
        return Result.Success(stepLength)
    }
}
