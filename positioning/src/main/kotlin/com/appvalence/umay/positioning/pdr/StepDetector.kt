package com.appvalence.umay.positioning.pdr

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.appvalence.umay.coremodel.StepEvent
import com.appvalence.umay.positioning.SensorThread
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.math.sqrt

/**
 * Detects footsteps from the accelerometer and tracks heading from a
 * gyroscope-based rotation sensor, emitting a [StepEvent] per detected step
 * (FR-2).
 *
 * This intentionally does NOT use the platform's [Sensor.TYPE_STEP_DETECTOR]:
 * on-device testing showed it delivers events unreliably on real hardware
 * (a full walk registered as a single step) — a known OEM inconsistency many
 * pedometer apps run into, which is why plenty of them roll their own
 * detector instead of trusting it. This uses hysteresis (require a rise
 * above [STEP_RISE_THRESHOLD_MPS2] in acceleration *relative to a slow-moving
 * baseline*, THEN a fall below [STEP_FALL_THRESHOLD_MPS2], before the next
 * step can register) specifically so shaking the phone — which crosses a
 * naive one-sided threshold repeatedly — doesn't get counted as steps.
 *
 * Heading comes from [Sensor.TYPE_GAME_ROTATION_VECTOR] (accelerometer +
 * gyroscope), not [Sensor.TYPE_ROTATION_VECTOR] (which also fuses the
 * magnetometer): real buildings distort magnetic north by tens of degrees
 * near rebar, steel framing, and electrical wiring, and that distortion
 * varies by exactly where in the building you're standing — the opposite of
 * what a PDR system needs, which is a *consistent* heading reference across
 * one walk. Gyro-only heading has no such spatial bias, only a slow drift
 * over time (gyro bias), which is far smaller than magnetic distortion error
 * over a single indoor walking session.
 *
 * This talks to real hardware sensors and is not unit-testable on the JVM;
 * the dead-reckoning math it feeds lives in [PdrIntegrator] instead, which is.
 *
 * [stepLengthMeters] is this person's calibrated stride (see
 * [StepLengthCalibration]); the population average is only a fallback — a
 * wrong stride scales every distance walked, which is the single largest
 * source of dead-reckoning drift.
 */
class StepDetector(context: Context, private val stepLengthMeters: Double = AVERAGE_STEP_LENGTH_METERS) {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val rotationVector: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

    fun stepEvents(): Flow<StepEvent> = callbackFlow {
        val accel = accelerometer
        val rotation = rotationVector
        if (accel == null || rotation == null) {
            // SensorManager.registerListener throws on a null Sensor — this device is
            // simply missing hardware this app's whole positioning model depends on.
            // Callers should check isAvailable(context) up front (see NavigationScreen/
            // BeaconScannerScreen's permission-style gate) rather than rely on this catch.
            close(IllegalStateException("Required motion sensors missing (accelerometer or game rotation vector)"))
            return@callbackFlow
        }

        // Everything below is touched only from SensorThread, one event at a time.
        var lastStepTimestampMs = 0L
        var currentHeadingDeg = 0f
        var lastHeadingDeg = 0f
        var runningBaseline = SensorManager.GRAVITY_EARTH.toDouble()
        var awaitingFall = false
        var lastAccelTimestampNs = 0L
        // Reused for every rotation event (~50 a second) instead of allocated per event.
        val rotationMatrix = FloatArray(9)
        val orientation = FloatArray(3)

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                        SensorManager.getOrientation(rotationMatrix, orientation)
                        val degrees = Math.toDegrees(orientation[0].toDouble()).toFloat()
                        currentHeadingDeg = if (degrees < 0f) degrees + 360f else degrees
                    }
                    Sensor.TYPE_ACCELEROMETER -> {
                        val magnitude = sqrt(
                            event.values[0] * event.values[0] +
                                event.values[1] * event.values[1] +
                                event.values[2] * event.values[2],
                        )

                        // Track the slow-moving baseline (gravity + device tilt) so a step is
                        // detected as a swing *relative to it*, independent of phone orientation.
                        // The smoothing factor is derived from real elapsed time (not a fixed
                        // per-sample constant) with a multi-second time constant, well above a
                        // walking cadence (~0.5-0.8s per step): a per-sample alpha tuned for an
                        // assumed sample rate silently breaks on any device that delivers
                        // accelerometer events faster or slower than assumed, and — critically —
                        // a baseline that adapts anywhere near stepping frequency ends up
                        // "chasing" each footfall, shrinking every subsequent swing until it no
                        // longer crosses the threshold. That is what made a whole walk register
                        // as a single step.
                        val dtSeconds = if (lastAccelTimestampNs == 0L) {
                            0.02
                        } else {
                            (event.timestamp - lastAccelTimestampNs) / 1_000_000_000.0
                        }
                        lastAccelTimestampNs = event.timestamp
                        val baselineAlpha = (dtSeconds / (BASELINE_TIME_CONSTANT_SECONDS + dtSeconds)).coerceIn(0.0, 1.0)
                        runningBaseline += baselineAlpha * (magnitude - runningBaseline)
                        val dynamicAcceleration = magnitude - runningBaseline

                        // Hysteresis: require a rise above the high threshold, THEN a fall below
                        // the low threshold, before the next step can register. A one-sided
                        // threshold triggers repeatedly on any jitter (like shaking); requiring
                        // the full swing shape is much closer to an actual footfall.
                        if (!awaitingFall && dynamicAcceleration > STEP_RISE_THRESHOLD_MPS2) {
                            awaitingFall = true
                        } else if (awaitingFall && dynamicAcceleration < STEP_FALL_THRESHOLD_MPS2) {
                            awaitingFall = false
                            val now = System.currentTimeMillis()
                            val debounced = now - lastStepTimestampMs > MIN_STEP_INTERVAL_MS
                            if (debounced) {
                                lastStepTimestampMs = now
                                val headingDelta = shortestAngleDeltaDeg(lastHeadingDeg, currentHeadingDeg)
                                lastHeadingDeg = currentHeadingDeg
                                trySend(
                                    StepEvent(
                                        stepLengthMeters = stepLengthMeters,
                                        headingDeltaDeg = headingDelta.toDouble(),
                                        timestampMs = now,
                                    ),
                                )
                            }
                        }
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        sensorManager.registerListener(listener, accel, SensorManager.SENSOR_DELAY_GAME, SensorThread.handler)
        sensorManager.registerListener(listener, rotation, SensorManager.SENSOR_DELAY_GAME, SensorThread.handler)

        awaitClose { sensorManager.unregisterListener(listener) }
    }

    private fun shortestAngleDeltaDeg(from: Float, to: Float): Float {
        var delta = (to - from) % 360f
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        return delta
    }

    companion object {
        const val MIN_STEP_INTERVAL_MS = 300L
        const val AVERAGE_STEP_LENGTH_METERS = 0.75
        const val STEP_RISE_THRESHOLD_MPS2 = 1.2
        const val STEP_FALL_THRESHOLD_MPS2 = -0.5
        const val BASELINE_TIME_CONSTANT_SECONDS = 2.0

        /** Whether this device has the hardware [StepDetector] needs — check before recording/navigating. */
        fun isAvailable(context: Context): Boolean {
            val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            return sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null &&
                sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) != null
        }
    }
}
