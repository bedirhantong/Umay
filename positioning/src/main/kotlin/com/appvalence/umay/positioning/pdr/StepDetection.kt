package com.appvalence.umay.positioning.pdr

import com.appvalence.umay.coremodel.StepEvent
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * The footstep logic of [StepDetector] with no Android in it: accelerometer
 * samples and headings in, one [StepEvent] out per footfall. The phone runs
 * it on live sensors; offline replay and tests run it on recorded ones, so a
 * change here can be checked against real walks without a phone.
 *
 * A step is a swing in acceleration *relative to a slow-moving baseline*
 * (gravity plus however the phone is tilted), with hysteresis: a rise above
 * [StepDetector.STEP_RISE_THRESHOLD_MPS2] and then a fall below
 * [StepDetector.STEP_FALL_THRESHOLD_MPS2] before the next can register, so
 * shaking the phone — which crosses a one-sided threshold over and over —
 * isn't counted. The baseline's smoothing comes from the real time between
 * samples with a multi-second time constant, well above a walking cadence: a
 * baseline that adapts anywhere near stepping frequency chases each footfall
 * and shrinks every later swing until none crosses the threshold (once, a
 * whole walk registered as a single step).
 */
class StepDetection(private val stepLengthMeters: Double) {
    private var lastStepTimestampMs = 0L
    private var lastHeadingDeg = 0.0
    private var headingDeg = 0.0
    private var runningBaseline = GRAVITY_EARTH
    private var awaitingFall = false
    private var lastAccelTimestampNs = 0L

    /** The phone's current heading, degrees clockwise (see [RotationVectors.azimuthDeg]). */
    fun onHeading(degrees: Double) {
        headingDeg = degrees
    }

    /**
     * One accelerometer sample (m/s²) taken at [timestampNs] (the sensor's clock,
     * for the time between samples); [nowMs] stamps a step it completes.
     */
    fun onAcceleration(x: Float, y: Float, z: Float, timestampNs: Long, nowMs: Long): StepEvent? {
        val magnitude = sqrt((x * x + y * y + z * z).toDouble())
        val dtSeconds = if (lastAccelTimestampNs == 0L) DEFAULT_SAMPLE_SECONDS else (timestampNs - lastAccelTimestampNs) / 1_000_000_000.0
        lastAccelTimestampNs = timestampNs
        val baselineAlpha = (dtSeconds / (StepDetector.BASELINE_TIME_CONSTANT_SECONDS + dtSeconds)).coerceIn(0.0, 1.0)
        runningBaseline += baselineAlpha * (magnitude - runningBaseline)
        val dynamicAcceleration = magnitude - runningBaseline

        if (!awaitingFall && dynamicAcceleration > StepDetector.STEP_RISE_THRESHOLD_MPS2) {
            awaitingFall = true
        } else if (awaitingFall && dynamicAcceleration < StepDetector.STEP_FALL_THRESHOLD_MPS2) {
            awaitingFall = false
            if (nowMs - lastStepTimestampMs > StepDetector.MIN_STEP_INTERVAL_MS) {
                lastStepTimestampMs = nowMs
                val headingDelta = shortestAngleDeltaDeg(lastHeadingDeg, headingDeg)
                lastHeadingDeg = headingDeg
                return StepEvent(stepLengthMeters = stepLengthMeters, headingDeltaDeg = headingDelta, timestampMs = nowMs)
            }
        }
        return null
    }

    private fun shortestAngleDeltaDeg(from: Double, to: Double): Double {
        var delta = (to - from) % 360.0
        if (delta > 180.0) delta -= 360.0
        if (delta < -180.0) delta += 360.0
        return delta
    }

    private companion object {
        const val GRAVITY_EARTH = 9.80665
        const val DEFAULT_SAMPLE_SECONDS = 0.02
    }
}

/** Android's rotation-vector math, without Android, so recorded sensor data can be replayed on a computer. */
object RotationVectors {
    /**
     * The azimuth of a rotation vector (x, y, z[, w]), degrees clockwise in 0..360:
     * what SensorManager.getRotationMatrixFromVector followed by getOrientation gives.
     */
    fun azimuthDeg(values: FloatArray): Double {
        val m = rotationMatrix(values)
        val degrees = Math.toDegrees(atan2(m[1], m[4]))
        return if (degrees < 0) degrees + 360.0 else degrees
    }

    /** Row-major 3×3 matrix taking device coordinates to world (east, north, up) ones, as SensorManager makes it. */
    fun rotationMatrix(values: FloatArray): DoubleArray {
        val q1 = values[0].toDouble()
        val q2 = values[1].toDouble()
        val q3 = values[2].toDouble()
        val q0 = if (values.size >= 4) values[3].toDouble() else (1 - q1 * q1 - q2 * q2 - q3 * q3).let { if (it > 0) sqrt(it) else 0.0 }
        val sqQ1 = 2 * q1 * q1
        val sqQ2 = 2 * q2 * q2
        val sqQ3 = 2 * q3 * q3
        val q1q2 = 2 * q1 * q2
        val q3q0 = 2 * q3 * q0
        val q1q3 = 2 * q1 * q3
        val q2q0 = 2 * q2 * q0
        val q2q3 = 2 * q2 * q3
        val q1q0 = 2 * q1 * q0
        return doubleArrayOf(
            1 - sqQ2 - sqQ3, q1q2 - q3q0, q1q3 + q2q0,
            q1q2 + q3q0, 1 - sqQ1 - sqQ3, q2q3 - q1q0,
            q1q3 - q2q0, q2q3 + q1q0, 1 - sqQ1 - sqQ2,
        )
    }
}
