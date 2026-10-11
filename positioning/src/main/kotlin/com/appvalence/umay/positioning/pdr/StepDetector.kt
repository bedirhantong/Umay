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

/**
 * Detects footsteps from the accelerometer and tracks heading from a
 * gyroscope-based rotation sensor, emitting a [StepEvent] per detected step
 * (FR-2). The detection itself is [StepDetection]; this only feeds it the
 * phone's sensors.
 *
 * This intentionally does NOT use the platform's [Sensor.TYPE_STEP_DETECTOR]:
 * on-device testing showed it delivers events unreliably on real hardware
 * (a full walk registered as a single step) — a known OEM inconsistency many
 * pedometer apps run into, which is why plenty of them roll their own
 * detector instead of trusting it. [StepDetection] uses hysteresis against
 * a slow-moving baseline instead, so shaking the phone isn't counted as steps.
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
 * This part talks to real hardware sensors and is not unit-testable on the
 * JVM; [StepDetection], [RotationVectors] and [PdrIntegrator] are.
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

        // Touched only from SensorThread, one event at a time.
        val detection = StepDetection(stepLengthMeters)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_GAME_ROTATION_VECTOR -> detection.onHeading(RotationVectors.headingDeg(event.values))
                    Sensor.TYPE_ACCELEROMETER -> detection
                        .onAcceleration(event.values[0], event.values[1], event.values[2], event.timestamp, System.currentTimeMillis())
                        ?.let { trySend(it) }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        sensorManager.registerListener(listener, accel, SensorManager.SENSOR_DELAY_GAME, SensorThread.handler)
        sensorManager.registerListener(listener, rotation, SensorManager.SENSOR_DELAY_GAME, SensorThread.handler)

        awaitClose { sensorManager.unregisterListener(listener) }
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
